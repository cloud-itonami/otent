(ns scheduled
  "One scheduled cycle: ingest what is due, then prune what is past its
  horizon.

    nbb --classpath src:../../kotoba-lang/sgp4/src:../../kotoba-lang/kotobase-client/src bin/scheduled.cljs

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

  **UNMEASURED feeds are expected here -- but the set is not static.**
  `tick` exits 2 whenever a feed could not be read, which is right for a
  human and wrong for a timer: `vessel` needs a resident collector this
  repository does not run, so an unwrapped tick would exit 2 forever, and a
  job that is permanently red is one nobody can tell from a broken one.

  The exemptions are declared by name in `otent.feeds.core`, each with a
  `:clears-when`. For a feed gated on a credential, that condition is
  something this cycle can check: it looks the key up in the Keychain, and
  **a feed whose key it found is no longer exempt.** So entering the FIRMS
  key does not require also remembering to edit the exemption -- the
  exemption evaporates on its own terms, and a fire feed that then goes
  quiet is a failure rather than a standing excuse. Widening the set for a
  structural reason is still an edit with a date on it.

  **Retention runs after ingest, and on its own interval.** After, because
  deleting rows whose replacement then failed to commit is the one ordering
  that loses an observation. On its own interval because of what running it
  every cycle cost: retention takes ~3.4 minutes, launchd will not start a
  job that is still running, and so a plist asking for a cycle every 300 s
  produced one every 447 s. Every feed was then polled at ~1.5x its declared
  `:min-interval-ms` -- aircraft every 15 minutes against a declared 10 --
  and nothing was red, because `due?` was honouring the registry exactly and
  the registry was being honoured late. Measured 2026-08-27 over 173 cycles.

  The horizons retention enforces are a day at the shortest, so asking
  hourly is not a weakening of it; asking every five minutes was simply
  never what the horizons needed. `$OTENT_RETAIN_INTERVAL_MS` overrides.

  exit 0 the cycle ran · 1 something was refused · 2 could not answer"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [otent.feeds.core :as feeds]
            [otent.darkness :as dark]))

(def expected-unmeasured
  "Declared once, in `otent.feeds.core`, because `otent coverage` checks the
  same set. A second copy here would drift from that one silently."
  feeds/expected-unmeasured)

(defn- log [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " xs))))

(defn- keychain
  "One credential, fetched by its exact service and account.

  Never an enumeration of the store: a dump would expose unrelated
  credentials' metadata and raise a prompt per item. Each call names the
  one item it wants, and a miss is nil rather than a search."
  [service account]
  (let [r (cp/spawnSync "security"
                        #js ["find-generic-password" "-s" service "-a" account "-w"]
                        #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str/trim (str (.-stdout r))))))

(def credentials
  "Which Keychain item backs which feed's environment variable.

  The catalog token is not here: without it nothing can run at all, so it
  is fetched separately and its absence stops the cycle. These are
  per-feed, and a missing one costs exactly one feed."
  [{:env "FIRMS_MAP_KEY" :service "firms.nasa" :account "MAP_KEY" :feed "firms"}])

(def default-retain-interval-ms
  "How often retention is worth running.

  An hour, against horizons whose shortest is a day. Running it every cycle
  was not stricter -- the horizons do not move -- it just held the timer
  open long enough to slow every feed down."
  3600000)

(defn- retain-interval-ms []
  (or (some-> (aget js/process.env "OTENT_RETAIN_INTERVAL_MS")
              str/trim not-empty js/parseInt)
      default-retain-interval-ms))

(defn- timer-interval-ms
  "What the plist asks launchd for. Read so the cycle can notice when it is
  taking longer than its own period, which is the failure that started
  this: nothing is red, and every declared interval is quietly stretched."
  []
  (or (some-> (aget js/process.env "OTENT_TIMER_INTERVAL_MS")
              str/trim not-empty js/parseInt)
      300000))

(defn- state-file []
  (let [dir (or (some-> (aget js/process.env "OTENT_LEDGER_DIR") str/trim not-empty)
                (path/join (or (aget js/process.env "HOME") ".") ".gftd" "otent"))]
    (path/join dir "retain.state.edn")))

(defn- read-state
  "The cycle's own small state: when retention last ran, and how many
  consecutive cycles each feed has been unreadable.

  Its own file rather than the tick ledger, because the ledger is the
  record of what was OBSERVED and neither of these is an observation."
  []
  (let [f (state-file)]
    (if-not (fs/existsSync f)
      {}
      (or (try (js->clj (js/JSON.parse (fs/readFileSync f "utf8")) :keywordize-keys false)
               (catch :default _ nil))
          {}))))

(defn- write-state! [m]
  (let [f (state-file)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/writeFileSync f (js/JSON.stringify (clj->js m)))))

(defn- last-retain-ms [state]
  (some-> (get state "last-at") js/parseInt))

(defn- run [env & args]
  (let [r (cp/spawnSync "nbb"
                        (clj->js (concat ["--classpath"
                                          ;; Every library `bin/otent.cljs`
                                          ;; can reach for. A parser added
                                          ;; without its dependency here
                                          ;; loads fine by hand and fails
                                          ;; only under launchd, in a log
                                          ;; nobody reads.
                                          (str "src:"
                                               (path/join ".." ".." "kotoba-lang" "sgp4" "src") ":"
                                               (path/join ".." ".." "kotoba-lang" "kotobase-client" "src") ":"
                                               (path/join ".." ".." "kotoba-lang" "org-ietf-csv" "src"))
                                          (path/join "bin" "otent.cljs")]
                                         args))
                        #js {:encoding "utf8"
                             :env (js/Object.assign #js {} js/process.env env)})]
    {:code (.-status r) :out (str (.-stdout r)) :err (str (.-stderr r))}))

(defn- unmeasured-feeds
  "Which feeds the tick reported UNMEASURED, from its own output."
  [out]
  (set (keep #(second (re-find #"^\s+UNMEASURED (\S+)" %)) (str/split-lines out))))

(defn -main []
  (let [now-start (js/Date.now)
        token (keychain "gftd.cf" "API_TOKEN")
        ;; Per-feed credentials, each fetched by name. A feed whose key is
        ;; present is EXPECTED TO RUN: if it then reports unmeasured, that
        ;; is a failure rather than the standing exemption, because the
        ;; exemption's own `:clears-when` has been met.
        supplied (into {} (keep (fn [c]
                                  (when-let [v (keychain (:service c) (:account c))]
                                    [(:env c) v]))
                                credentials))
        env (js/Object.assign #js {"CF_CATALOG_TOKEN" token} (clj->js supplied))
        expected (remove (set (for [c credentials :when (supplied (:env c))] (:feed c)))
                         (keys expected-unmeasured))]
    (when-not token
      (log "REFUSED: the Keychain has no gftd.cf/API_TOKEN. Nothing ran, which"
           "is not the same as running and finding nothing.")
      (js/process.exit 2))

    (when (seq supplied)
      (log "credentials supplied for:"
           (str/join "," (sort (for [c credentials :when (supplied (:env c))] (:feed c))))
           "-- these feeds are no longer exempt from being unmeasured"))

    (let [state (read-state)
          t (run env "tick")
          unmeasured (unmeasured-feeds (:out t))
          ;; Every feed the tick reported on, so a feed that ANSWERED has
          ;; its streak reset rather than merely not incremented.
          asked (set (keep #(second (re-find #"^\s+(?:COMMITTED|NOTHING-NEW|REFUSED|DRY-RUN|UNMEASURED)\s+(\S+)" %))
                           (str/split-lines (:out t))))
          streaks (merge (get state "dark" {})
                         (dark/advance (get state "dark" {}) asked unmeasured))
          v (dark/verdict {:streaks streaks :exempt (set expected)})]
      (print (:out t))
      (log "tick exit" (:code t)
           "| unmeasured:" (if (seq unmeasured) (str/join "," (sort unmeasured)) "none")
           "| streaks:" (if-let [d (:detail v)] d "none"))
      (write-state! (assoc state "dark" streaks))

      (when (:refuse? v)
        (log "REFUSED:" (:detail v))
        (js/process.exit 2))

      ;; The tick's own exit 2 is expected here whenever the declared feeds
      ;; are the only unmeasured ones; 1 is not -- that is a governor
      ;; refusal or a commit that failed, and it must reach the caller.
      (let [tick-bad? (= 1 (:code t))
            now (js/Date.now)
            last (last-retain-ms state)
            since (when last (- now last))
            due? (or (nil? last) (>= since (retain-interval-ms)))
            r (when due? (run env "retain"))]
        (if due?
          (do (print (:out r))
              (write-state! (assoc (read-state) "last-at" now))
              (log "retain exit" (:code r)))
          ;; NOT "retain exit 0". Skipping retention is not a clean run of
          ;; it, and a line that reads the same either way would make this
          ;; change invisible the day the interval is set too long and rows
          ;; start living past their horizon.
          (log "retain NOT-DUE  last ran" (Math/round (/ since 1000)) "s ago;"
               "interval is" (Math/round (/ (retain-interval-ms) 1000)) "s, so it is due in"
               (Math/round (/ (- (retain-interval-ms) since) 1000)) "s."
               "NOT run -- this is not a claim that nothing needed pruning."))

        (let [elapsed (- (js/Date.now) now-start)]
          (log "cycle took" (Math/round (/ elapsed 1000)) "s")
          (when (> elapsed (timer-interval-ms))
            ;; The whole reason this file changed. launchd will not start a
            ;; job that is still running, so a cycle longer than the period
            ;; silently divides every feed's real poll rate -- and the tick
            ;; stays green throughout, because `due?` is answering honestly
            ;; about a clock it is being asked on too rarely.
            (log "WARNING: the cycle took longer than the timer's period of"
                 (Math/round (/ (timer-interval-ms) 1000)) "s."
                 "launchd will skip fires, so every feed's effective interval"
                 "is longer than the registry declares. Run `otent coverage`"
                 "to measure by how much.")))

        (js/process.exit
         (cond (= 2 (:code r)) 2
               (or tick-bad? (= 1 (:code r))) 1
               :else 0))))))

(-main)
