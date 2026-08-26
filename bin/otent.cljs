(ns otent
  "One ingest tick: fetch the public feeds, govern the rows, append them to
  the R2 Data Catalog, read back, and write a receipt.

    nbb --classpath src bin/otent.cljs tick
    nbb --classpath src bin/otent.cljs tick --feed usgs --dry-run
    nbb --classpath src bin/otent.cljs feeds
    nbb --classpath src bin/otent.cljs count --kind quake

  ## Exit codes

  | | |
  |---|---|
  | 0 | every feed that COULD be read was read, governed and committed |
  | 1 | something was refused: a governor verdict, a schema drift, a readback that disagreed |
  | 2 | **could not answer** -- no catalog token, a feed unreachable, a table unreadable |

  2 is the one that matters. A run that skipped four of five feeds for want
  of a credential has not observed a quiet planet; it has not looked. It
  must not exit like a run that looked and found little.

  ## What is written, and what is not

  Rows go to `otent_<kind>` in the `cloud_itonami` namespace of the
  `cloud-itonami-datalake` bucket. **The Iceberg tables are a projection,
  not the source of truth**: the raw payload of every fetch is content-
  addressed and its sha256 travels on every row, so the tables can be
  dropped and rebuilt from the payloads. The moment that stops being true
  they have become a premise, and this workspace does not put a premise
  behind a single vendor's SQL endpoint (superproject ADR-2608039000)."
  (:require [cljs.reader]
            ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            ["child_process" :as cp]
            [clojure.string :as str]
            [otent.feeds.core :as feeds]
            [otent.feeds.parse :as parse]
            [otent.governor :as gov]
            [otent.observation :as obs]
            [otent.receipt :as receipt]))

(def ACCOUNT "4da88288dc30d9ee257f319d3c33ecf0")
(def BUCKET "cloud-itonami-datalake")
(def NAMESPACE "cloud_itonami")

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

;; ---------------------------------------------------------------- args

(defn parse-args [argv]
  (loop [a (seq argv) out {:flags #{} :opts {}}]
    (if-not a
      out
      (let [x (first a)]
        (cond
          (#{"--dry-run" "--create" "--verbose"} x)
          (recur (next a) (update out :flags conj (subs x 2)))

          (str/starts-with? x "--")
          (recur (nnext a) (assoc-in out [:opts (keyword (subs x 2))] (second a)))

          (nil? (:cmd out)) (recur (next a) (assoc out :cmd x))
          :else (recur (next a) out))))))

;; ---------------------------------------------------------------- fetch

(defn- url-with [feed opts]
  (let [params (merge (:default-params feed)
                      (when-let [k (:credential-env feed)]
                        (when-let [v (some-> (aget js/process.env k) str/trim not-empty)]
                          ;; FIRMS takes the key as a PATH segment, not a query
                          ;; parameter. Handled at the call site below.
                          {"__key" v}))
                      (:params opts))
        qs (->> (dissoc params "__key")
                (map (fn [[k v]] (str (js/encodeURIComponent k) "="
                                      (js/encodeURIComponent v))))
                (str/join "&"))]
    (cond
      (= :firms (:id feed))
      (str (:url feed) "/" (get params "__key") "/"
           (get params "source") "/" (get params "area") "/" (get params "day_range"))
      (str/blank? qs) (:url feed)
      :else (str (:url feed) "?" qs))))

(defn fetch-text
  "GET the feed. Returns `{:ok? true :text ... :sha ... :fetched-at ...}` or
  a refusal that names the status -- never an empty string that a parser
  would turn into zero rows."
  [feed opts]
  (let [url (url-with feed opts)]
    (-> (js/fetch url
                  #js {:headers #js {"user-agent"
                                     "otent/0.1 (cloud-itonami; +https://github.com/cloud-itonami/otent)"}})
        (.then (fn [r]
                 (if-not (.-ok r)
                   {:ok? false :error :feed/http-error
                    :detail (str (.-status r) " " (.-statusText r) " from " url)
                    :url url}
                   (.then (.text r)
                          (fn [t]
                            (if (str/blank? t)
                              {:ok? false :error :feed/empty-body
                               :detail (str "200 with an empty body from " url)
                               :url url}
                              {:ok? true :text t :url url
                               :sha (sha256 t)
                               :fetched-at (js/Date.now)}))))))
        (.catch (fn [e] {:ok? false :error :feed/unreachable
                         :detail (str (.-message e) " (" url ")")
                         :url url})))))

;; ---------------------------------------------------------------- parse

(defn parse-payload [feed {:keys [text url sha fetched-at]}]
  (case (:id feed)
    :celestrak (parse/celestrak text feed url fetched-at sha)
    :usgs (parse/usgs (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :opensky (parse/opensky (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :firms (parse/firms text feed url fetched-at sha)
    {:ok [] :failed [{:error :feed/no-parser
                      :detail (str "no parser for " (:id feed))}]}))

;; ---------------------------------------------------------------- write

(defn- write-ndjson! [rows]
  (let [dir (path/join (or (aget js/process.env "TMPDIR") "/tmp") "otent")
        _ (fs/mkdirSync dir #js {:recursive true})
        f (path/join dir (str "batch-" (js/Date.now) "-" (rand-int 100000) ".ndjson"))]
    (fs/writeFileSync
     f (str (str/join "\n" (map #(js/JSON.stringify (clj->js (obs/->row %))) rows)) "\n"))
    f))

(defn- run-writer [args]
  (let [r (cp/spawnSync "python3"
                        (clj->js (concat [(path/join (js/process.cwd) "scripts" "iceberg_append.py")]
                                         args))
                        #js {:stdio #js ["ignore" "pipe" "inherit"]
                             :env js/process.env})]
    {:code (.-status r)
     :out (str/trim (str (.-stdout r)))}))

(defn table-count
  "Row count from the catalog. `nil` means the count could not be taken --
  which is not zero, and callers must not treat it as zero."
  [table]
  (let [{:keys [code out]} (run-writer ["--account" ACCOUNT "--bucket" BUCKET
                                        "--namespace" NAMESPACE "--table" table
                                        "--count"])]
    (when (zero? code) (js/parseInt out 10))))

(defn commit!
  "Append the admitted rows and verify by reading back.

  The readback is a separate catalog call from the write, and the check is
  on the DELTA: a table's absolute count says nothing about whether this
  batch landed."
  [table rows create?]
  (let [before (table-count table)
        f (write-ndjson! rows)
        {:keys [code]} (run-writer (cond-> ["--account" ACCOUNT "--bucket" BUCKET
                                            "--namespace" NAMESPACE "--table" table
                                            "--ndjson" f]
                                     create? (conj "--create")))]
    (cond
      (= 2 code) {:ok? false :error :commit/could-not-answer
                  :detail "the writer could not reach the catalog"}
      (not (zero? code)) {:ok? false :error :commit/refused
                          :detail (str "iceberg_append.py exited " code)}
      :else
      (let [after (table-count table)]
        (cond
          (nil? after)
          {:ok? false :error :commit/readback-unavailable
           :detail (str "appended, but " table " could not be read back -- "
                        "UNVERIFIED, not verified")}

          (nil? before)
          {:ok? true :appended (count rows) :after after
           :note (str "table did not exist before this run; delta not checkable, "
                      "count after = " after)}

          (not= (- after before) (count rows))
          {:ok? false :error :commit/count-mismatch
           :detail (str "wrote " (count rows) " rows but the table grew by "
                        (- after before) " (" before " -> " after
                        "). Another writer may be appending concurrently, or "
                        "the commit was partial.")}

          :else {:ok? true :appended (count rows) :before before :after after})))))

(defn watermarks
  "The newest `observed-at` each feed has committed, read back from the
  ledger.

  From the ledger rather than from the tables: the answer was already
  written down by the tick that committed the rows, and re-deriving it
  would mean a `max(observed_at) GROUP BY object_id` over seven thousand
  aircraft rows per minute to learn something free.

  A feed absent from the ledger gets nil, NOT 0 -- `nil` admits everything
  (correct for a first run) and 0 would too, but only by accident, and the
  day someone changes the comparison the two stop being the same."
  []
  (let [f (path/join (js/process.cwd) "ledger" "tick.ledger.edn")]
    (if-not (fs/existsSync f)
      {}
      (->> (str/split-lines (fs/readFileSync f "utf8"))
           (remove str/blank?)
           (map cljs.reader/read-string)
           (mapcat :tick/results)
           ;; :nothing-new too, not only :committed. A feed that has gone
           ;; quiet still tells us the sha it last served, and without this
           ;; the sha short-circuit could never arm itself once the
           ;; timestamp watermark started answering first.
           (filter #(#{:committed :nothing-new} (:status %)))
           (reduce (fn [acc r]
                     (cond-> acc
                       (:max-observed-at r)
                       (update-in [(:feed r) :max-observed-at] (fnil max 0) (:max-observed-at r))
                       (:payload-sha256 r)
                       (assoc-in [(:feed r) :payload-sha256] (:payload-sha256 r))))
                   {})))))

(defn append-receipt!
  "Append one EDN map per line to `ledger/tick.ledger.edn`.

  Append-only on purpose: this is a measurement series, not a document, and
  the superproject's rule that documents carry only their current state
  exempts measurement and event streams for exactly this reason -- the
  value of a tick log is the sequence."
  [r]
  (let [dir (path/join (js/process.cwd) "ledger")
        f (path/join dir "tick.ledger.edn")]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/appendFileSync f (str (pr-str r) "\n"))
    f))

;; ---------------------------------------------------------------- tick

(defn tick-feed [feed {:keys [flags opts] :as args} watermark]
  (let [dry? (contains? flags "dry-run")]
    (-> (fetch-text feed opts)
        (.then
         (fn [f]
           (if-not (:ok? f)
             {:feed (:id feed) :status :unmeasured
              :error (:error f) :detail (:detail f)}
             ;; Exact short-circuit: a byte-identical payload cannot contain
             ;; anything new, whatever the timestamps say. This is what
             ;; catches polling faster than a feed republishes, and unlike
             ;; the timestamp watermark below it has no false negatives.
             (if (and (:payload-sha256 watermark)
                      (= (:payload-sha256 watermark) (:sha f)))
               {:feed (:id feed) :status :nothing-new
                :table (obs/table-name (:kind feed))
                :payload-sha256 (:sha f)
                :detail (str "the payload is byte-identical to the one committed "
                             "last tick (sha256 " (subs (:sha f) 0 12) "...)")}
               (let [{:keys [ok failed]} (parse-payload feed f)
                   result (gov/admit ok (js/Date.now)
                                     {:watermark-ms (:max-observed-at watermark)})
                   verdict (gov/commit-decision result)
                   table (obs/table-name (:kind feed))]
               (log (str "  " (name (:id feed)) ": " (count ok) " parsed, "
                         (count failed) " unparsable, "
                         (count (:admitted result)) " admitted, "
                         (count (:held result)) " held"))
               (doseq [[reason n] (dissoc (:counts result) :proposed :admitted :held)]
                 (log (str "      held " n " x " (name reason))))
               (cond
                 ;; "nothing new since the last tick" is the healthy
                 ;; outcome of polling a slow feed, not a refusal.
                 (= :nothing-new (:reason verdict))
                 {:feed (:id feed) :status :nothing-new :table table
                  :payload-sha256 (:sha f)
                  :detail (:detail verdict) :counts (:counts result)}

                 (not (:commit? verdict))
                 {:feed (:id feed) :status :refused :table table
                  :error (:reason verdict) :detail (:detail verdict)
                  :counts (:counts result)}

                 dry?
                 {:feed (:id feed) :status :dry-run :table table
                  :would-append (count (:admitted result))
                  :counts (:counts result)
                  :parse-failures (count failed)}

                 :else
                 (let [c (commit! table (:admitted result)
                                  (contains? flags "create"))]
                   (merge {:feed (:id feed) :table table
                           :counts (:counts result)
                           :parse-failures (count failed)}
                          (if (:ok? c)
                            {:status :committed :appended (:appended c)
                             :rows-before (:before c) :rows-after (:after c)
                             ;; The watermark the NEXT tick will read.
                             :max-observed-at (reduce max 0 (map :observed-at (:admitted result)))
                             :payload-sha256 (:sha f)
                             :note (:note c)}
                            {:status :refused :error (:error c) :detail (:detail c)})))))))))
        (.catch (fn [e] {:feed (:id feed) :status :unmeasured
                         :error :tick/threw :detail (str (.-message e))})))))

(defn selected-feeds [opts]
  (if-let [only (:feed opts)]
    (let [ids (set (map keyword (str/split only #",")))]
      (filter #(contains? ids (:id %)) feeds/registry))
    feeds/registry))

(defn runnable?
  "Can this feed be read at all right now? Returns nil if yes, or the reason."
  [feed]
  (case (:access feed)
    :open nil
    :free-key (when-not (some-> (aget js/process.env (:credential-env feed))
                                str/trim not-empty)
                {:error :feed/no-credential
                 :detail (str "$" (:credential-env feed) " is not set: this feed "
                              "is UNMEASURED, which is not the same as empty")})
    :stream {:error :feed/needs-resident-collector
             :detail (str (:label feed) " is a WebSocket subscription. The message "
                          "parser is implemented and tested; the resident "
                          "collector is not part of this repository. UNMEASURED.")}
    {:error :feed/unknown-access :detail (pr-str (:access feed))}))

(defn tick [args]
  (let [fs* (selected-feeds (:opts args))
        _ (when (empty? fs*)
            (log "no feed matched --feed" (:feed (:opts args)))
            (js/process.exit 2))
        skipped (for [f fs* :let [r (runnable? f)] :when r]
                  (merge {:feed (:id f) :status :unmeasured} r))
        runnable (remove #(runnable? %) fs*)]
    (log (str "otent tick: " (count runnable) " runnable, "
              (count skipped) " unmeasured, of " (count fs*) " feeds"))
    (doseq [s skipped]
      (log (str "  " (name (:feed s)) ": UNMEASURED -- " (:detail s))))
    (let [wm (watermarks)]
      (doseq [[k v] wm]
        (log (str "  watermark " (name k)
                  " max-observed-at=" (:max-observed-at v)
                  " sha=" (some-> (:payload-sha256 v) (subs 0 12)))))
      (-> (js/Promise.all (clj->js (map #(tick-feed % args (get wm (:id %))) runnable)))
        (.then (fn [rs]
                 (let [results (concat (js->clj rs :keywordize-keys true) skipped)
                       now (js/Date.now)
                       r (receipt/build results now)]
                   (append-receipt! r)
                   (println (receipt/render r (.toISOString (js/Date. now))))
                   (js/process.exit (receipt/exit-code r)))))))))

;; ---------------------------------------------------------------- main

(defn user-args
  "The arguments after the script path.

  Not `(drop 2 argv)`: nbb's argv carries a variable number of leading
  entries before the script, and dropping a fixed count reads the script
  path as the command -- which lands in the `usage` branch and looks like
  the user typed nothing."
  []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [i a] (when (str/ends-with? a "otent.cljs") i)) argv))]
    (if i (drop (inc i) argv) (drop 2 argv))))

(defn -main [& _]
  (let [{:keys [cmd opts] :as args} (parse-args (user-args))]
    (case cmd
      "feeds" (doseq [l (feeds/describe)] (println l))
      "count" (let [k (keyword (or (:kind opts) "quake"))
                    t (obs/table-name k)
                    n (table-count t)]
                (if (nil? n)
                  (do (println t "UNREADABLE -- not zero") (js/process.exit 2))
                  (println t n)))
      "tick" (tick args)
      (do (println "usage: otent.cljs <tick|feeds|count> [--feed a,b] [--dry-run] [--create]")
          (js/process.exit 2)))))

(-main)
