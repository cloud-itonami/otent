(ns scheduled
  "One scheduled cycle: ingest what is due, then prune what is past its
  horizon.

    nbb --classpath src:../../kotoba-lang/sgp4/src bin/scheduled.cljs

  Run by launchd every five minutes. Five minutes is not the poll rate --
  each feed's `:min-interval-ms` decides that, and a feed inside its
  interval comes back NOT-DUE without being asked. The timer only has to be
  faster than the fastest feed wants; the registry does the rest.

  ## Why a wrapper rather than launchd calling `tick` directly

  Three things have to happen that a plist cannot express.

  **The credential comes from the Keychain**, targeted by name -- never by
  enumerating the store. Absent, this exits 2 and says so, rather than
  running a tick that reports every feed UNMEASURED and looks like a quiet
  planet.

  **UNMEASURED feeds are expected here.** `tick` exits 2 whenever a feed
  could not be read, which is right for a human running it and wrong for a
  timer: `fire` needs a FIRMS key nobody has entered and `vessel` needs a
  resident collector this repository does not run, so an unwrapped tick
  would exit 2 on every single run forever. A job that is permanently red is
  one nobody can tell from a broken one. So the expected set is declared
  here, by name, and only a feed going unmeasured OUTSIDE that set is a
  failure. Widening the set is an edit with a date on it.

  **Retention runs after ingest, not beside it.** Deleting rows the tick has
  just written is fine -- they are past the horizon or they are not -- but
  doing it first would delete rows whose replacement then failed to commit.

  exit 0 the cycle ran · 1 something was refused · 2 could not answer"
  (:require ["child_process" :as cp]
            ["path" :as path]
            [clojure.string :as str]))

(def expected-unmeasured
  "Feeds that cannot be read today, with the reason and what would clear it.

  Declared, dated, and checked against what the tick actually reports -- so
  a THIRD feed going dark is a failure rather than being absorbed into an
  exemption written for two others."
  {"firms"     {:since "2026-08-26"
                :why "$FIRMS_MAP_KEY is not set; NASA FIRMS needs a free key"
                :clears-when "the key is entered on this machine"}
   "aisstream" {:since "2026-08-26"
                :why "AIS is a WebSocket subscription and the resident collector is not in this repository"
                :clears-when "a collector runs somewhere and writes vessel rows"}})

(defn- log [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " xs))))

(defn- keychain-token
  "The one credential this needs, fetched by its exact identifiers.

  Never an enumeration of the store: a dump would expose unrelated
  credentials' metadata and raise a prompt per item."
  []
  (let [r (cp/spawnSync "security"
                        #js ["find-generic-password" "-s" "gftd.cf" "-a" "API_TOKEN" "-w"]
                        #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str/trim (str (.-stdout r))))))

(defn- run [token & args]
  (let [r (cp/spawnSync "nbb"
                        (clj->js (concat ["--classpath"
                                          (str "src:" (path/join ".." ".." "kotoba-lang" "sgp4" "src"))
                                          (path/join "bin" "otent.cljs")]
                                         args))
                        #js {:encoding "utf8"
                             :env (js/Object.assign #js {} js/process.env
                                                    #js {"CF_CATALOG_TOKEN" token})})]
    {:code (.-status r) :out (str (.-stdout r)) :err (str (.-stderr r))}))

(defn- unmeasured-feeds
  "Which feeds the tick reported UNMEASURED, from its own output."
  [out]
  (set (keep #(second (re-find #"^\s+UNMEASURED (\S+)" %)) (str/split-lines out))))

(defn -main []
  (let [token (keychain-token)]
    (when-not token
      (log "REFUSED: the Keychain has no gftd.cf/API_TOKEN. Nothing ran, which"
           "is not the same as running and finding nothing.")
      (js/process.exit 2))

    (let [t (run token "tick")
          unmeasured (unmeasured-feeds (:out t))
          unexpected (remove (set (keys expected-unmeasured)) unmeasured)]
      (print (:out t))
      (log "tick exit" (:code t)
           "| unmeasured:" (if (seq unmeasured) (str/join "," (sort unmeasured)) "none")
           "| unexpected:" (if (seq unexpected) (str/join "," (sort unexpected)) "none"))

      (when (seq unexpected)
        (log "REFUSED:" (str/join "," (sort unexpected))
             "went unmeasured and is not in the declared set"
             (str/join "," (sort (keys expected-unmeasured))))
        (js/process.exit 2))

      ;; The tick's own exit 2 is expected here whenever the declared feeds
      ;; are the only unmeasured ones; 1 is not -- that is a governor
      ;; refusal or a commit that failed, and it must reach the caller.
      (let [tick-bad? (= 1 (:code t))
            r (run token "retain")]
        (print (:out r))
        (log "retain exit" (:code r))
        (js/process.exit
         (cond (or (= 2 (:code r))) 2
               (or tick-bad? (= 1 (:code r))) 1
               :else 0))))))

(-main)
