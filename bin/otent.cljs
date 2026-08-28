(ns otent
  "One ingest tick: fetch the public feeds, govern the rows, append them to
  the R2 Data Catalog, read back, and write a receipt.

    nbb --classpath src bin/otent.cljs tick
    nbb --classpath src bin/otent.cljs tick --feed usgs --dry-run
    nbb --classpath src bin/otent.cljs feeds
    nbb --classpath src bin/otent.cljs count --kind quake
    nbb --classpath src bin/otent.cljs coverage

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
            [otent.r2 :as r2]
            [otent.cli :as cli]
            [otent.coverage :as cov]
            [otent.deadline :as dl]
            [otent.lock :as lock]
            [otent.sanctions :as sanc]
            [otent.kotobase :as kb]
            [otent.receipt :as receipt]))

(def ACCOUNT "4da88288dc30d9ee257f319d3c33ecf0")
(def BUCKET "cloud-itonami-datalake")
(def NAMESPACE "cloud_itonami")

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

;; ---------------------------------------------------------------- args



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
  (if-let [batch (and (= :aisstream (:id feed)) (:ais-batch opts))]
    ;; The resident collector already read the socket. The batch file IS the
    ;; payload -- it is archived by its own sha256 and every row points at
    ;; it, exactly as a fetched payload would be. A stream and a poll differ
    ;; in how the bytes arrive, not in what has to be true of them.
    (js/Promise.resolve
     (let [text (fs/readFileSync batch "utf8")]
       {:ok? true :text text
        :sha (-> (crypto/createHash "sha256") (.update text) (.digest "hex"))
        :fetched-at (js/Date.now)
        :url (str "file://" batch)}))
  (let [url (url-with feed opts)]
    (-> (js/fetch url
                  #js {:signal (dl/signal)
                       ;; A feed's own headers, merged over the user-agent
                       ;; rather than replacing it. Digitraffic's terms ask
                       ;; callers to identify themselves in `Digitraffic-User`
                       ;; so a misbehaving client can be contacted rather
                       ;; than blocked, and a registry that could not carry
                       ;; that would push the requirement into a special
                       ;; case at the call site.
                       :headers (js/Object.assign
                                 #js {"user-agent"
                                      "otent/0.1 (cloud-itonami; +https://github.com/cloud-itonami/otent)"}
                                 (clj->js (or (:headers feed) {})))})
        (.then (fn [r]
                 (if-not (.-ok r)
                   ;; Some feeds answer "you already have this" with a
                   ;; non-2xx and a body that says so. CelesTrak uses 403.
                   ;; Reading that as a failure to observe would report a
                   ;; sky that had not moved as a sky nobody could see.
                   (let [nm (:not-modified feed)]
                     (if (and nm (= (:status nm) (.-status r)))
                       (.then (.text r)
                              (fn [b]
                                (if (feeds/not-modified? feed (.-status r) b)
                                  {:ok? false :error :feed/not-modified
                                   :detail (str (.-status r) " with the feed's own "
                                                "not-modified body: "
                                                (str/trim (subs b 0 (min 120 (count b)))))
                                   :url url}
                                  {:ok? false :error :feed/http-error
                                   :detail (str (.-status r) " " (.-statusText r)
                                                " from " url " -- body did not match "
                                                "this feed's not-modified signal, so "
                                                "this is a real refusal")
                                   :url url})))
                       {:ok? false :error :feed/http-error
                        :detail (str (.-status r) " " (.-statusText r) " from " url)
                        :url url}))
                   (.then (.text r)
                          (fn [t]
                            (if (str/blank? t)
                              {:ok? false :error :feed/empty-body
                               :detail (str "200 with an empty body from " url)
                               :url url}
                              {:ok? true :text t :url url
                               :sha (sha256 t)
                               :fetched-at (js/Date.now)}))))))
        (.catch (fn [e]
                  ;; A deadline and a refused connection are different
                  ;; facts. `unreachable` says the feed answered nothing;
                  ;; `timeout` says we stopped waiting, and we do not know
                  ;; what it would have said. Both are UNMEASURED, and the
                  ;; receipt has to say which, or the next reader debugs
                  ;; the wrong end of the wire.
                  (if (dl/timeout-error? e)
                    {:ok? false :error :feed/timeout
                     :detail (dl/describe (str "the feed at " url) dl/default-ms)
                     :url url}
                    {:ok? false :error :feed/unreachable
                     :detail (str (.-message e) " (" url ")")
                     :url url})))))))

(defn archive-payload!
  "Put the fetched bytes in the bucket, keyed by their own sha256.

  ## Why this is not optional

  The README has said since day one that the Iceberg tables are a
  projection and not the source of truth, because \"the raw payload of
  every fetch is content-addressed and its sha256 travels on every row, so
  the tables can be dropped and rebuilt from the payloads.\"

  That was false. The payload was fetched, hashed, parsed and dropped on
  the floor; only the hash survived, on the rows. **You cannot rebuild
  anything from a hash.** The tables were the only copy, which makes them
  a premise, which is the one thing ADR-2608039000 says a distributed
  path must not put behind a single vendor.

  It also made retention impossible to do honestly: deleting a row would
  have destroyed the only record of an observation, so the table could
  only ever grow.

  ## Content-addressed, so a repeat costs one ranged GET

  The key is the sha256 of the UNCOMPRESSED bytes -- the same hash that
  travels on every row, so a row points at its payload without a second
  identifier. The stored object is gzipped, which is a transport detail
  and does not change the identity.

  A payload already present is not written again. That is not only an
  optimisation: PUT-ing it twice would be two objects with the same
  content and different write times, and the second one would be the
  answer to \"when did we first see this?\"."
  [{:keys [text sha]}]
  (let [key (str "otent/payload/" sha ".json.gz")]
    (-> (r2/head key)
        (.then (fn [h]
                 (cond
                   (:present? h) (js/Promise.resolve {:ok? true :key key :already? true})
                   ;; A probe that could not run is not a probe that found
                   ;; nothing. Write, rather than assume either way.
                   :else (r2/put! key (r2/gzip (js/Buffer.from text "utf8"))
                                  "application/gzip")))))))

;; ---------------------------------------------------------------- parse

(defn parse-payload [feed {:keys [text url sha fetched-at]}]
  (case (:id feed)
    :celestrak (parse/celestrak text feed url fetched-at sha)
    :usgs (parse/usgs (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :opensky (parse/opensky (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :digitraffic (parse/digitraffic (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :digitraffic-static (parse/digitraffic-static (js->clj (js/JSON.parse text)) feed url fetched-at sha)
    :opensanctions-maritime (parse/opensanctions-maritime text feed url fetched-at sha)
    :opensanctions-ownership (parse/opensanctions-ownership text feed url fetched-at sha)
    :opensanctions-organizations (parse/opensanctions-organizations text feed url fetched-at sha)
    :aisstream (parse/aisstream-batch text feed url fetched-at sha)
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

(defn- run-writer!
  "Run the Iceberg writer and RESOLVE, rather than blocking until it exits.

  This was `cp/spawnSync` until 2026-08-28, and that single call produced
  three separate symptoms that each looked like their own bug:

  1. **Healthy feeds timed out.** `tick` runs the feeds under
     `js/Promise.all` and `AbortSignal.timeout` is a wall clock, so while one
     feed sat inside `python3` every other feed's deadline kept counting
     against a server that was never asked. `firms` reported
     `did not answer within 60s` on three consecutive cycles while the same
     request answered in 2.3 s from the shell.
  2. **The per-feed timings could not attribute cost.** `opensky` reported
     175 s on a poll whose fetch had already given up at 60 s. The missing
     115 s was this process, frozen in a sibling's commit.
  3. **The cycle overran the timer.** launchd will not start a job that is
     still running, so every commit that blocked the loop stretched the whole
     schedule and every feed's effective interval with it.

  One cause, three bug reports. The commits still SERIALISE -- python is
  still one process at a time -- but the event loop stays free, so a fetch
  elsewhere can make progress and a deadline measures the remote again."
  [args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (cp/spawn "python3"
                          (clj->js (concat [(path/join (js/process.cwd) "scripts" "iceberg_append.py")]
                                           args))
                          #js {:stdio #js ["ignore" "pipe" "inherit"]
                               :env js/process.env})
           out (atom "")]
       (.on (.-stdout proc) "data" (fn [d] (swap! out str (.toString d))))
       ;; A process killed by a signal exits with `code` nil. `-1` keeps it
       ;; out of the `zero?` and `= 2` branches rather than letting nil be
       ;; compared and quietly fall through to the success path.
       (.on proc "close" (fn [code] (resolve {:code (or code -1) :out (str/trim @out)})))
       (.on proc "error" (fn [e] (resolve {:code -1 :out (str (.-message e))})))))))

(defn table-status
  "Ask the catalog about one table and keep the three answers apart.

  `{:status :rows}` where status is `:rows` with a number, `:absent`
  (asked, and the table is not there) or `:unreadable` (could not ask).
  The counter used to return 2 for both of the last two, so
  `otent count --kind fire` said UNREADABLE about a table whose absence is
  the most certain fact in the system -- nothing has ever been able to read
  that feed, so the table cannot exist. `could not look` and `looked, and
  it is not there` are different claims and must not share an exit code."
  [table]
  (-> (run-writer! ["--account" ACCOUNT "--bucket" BUCKET
                    "--namespace" NAMESPACE "--table" table
                    "--count"])
      (.then (fn [{:keys [code out]}]
               (case code
                 0 {:status :rows :rows (js/parseInt out 10)}
                 3 {:status :absent}
                 {:status :unreadable})))))

(defn table-count
  "Row count from the catalog. `nil` means the count could not be taken --
  which is not zero, and callers must not treat it as zero."
  [table]
  ;; `(.then p :rows)` looks right and is not. A ClojureScript keyword is a
  ;; function to Clojure and an opaque object to `Promise.prototype.then`,
  ;; which handed back the whole status map. The delta check then computed
  ;; `NaN` and REFUSED two commits that had actually landed -- caught only by
  ;; running it against the live catalog, because nothing in the suite
  ;; exercises `commit!` against a real table.
  (.then (table-status table) (fn [m] (:rows m))))

(def ^:private table-locks
  "One chain per table. See `otent.lock` for why the accident that
  `spawnSync` was holding had to be replaced deliberately."
  (lock/make))

(defn commit!
  "Append the admitted rows and verify by reading back. Resolves a result map.

  The readback is a separate catalog call from the write, and the check is
  on the DELTA: a table's absolute count says nothing about whether this
  batch landed.

  Promise-returning since 2026-08-28. The three calls inside still happen in
  order -- count, write, count -- because each needs the one before it. What
  changed is that the event loop is free between them, so a sibling feed's
  fetch is not frozen for the duration and its deadline still measures the
  remote rather than this process."
  [table rows create?]
  (lock/with-lock table-locks table
    (fn []
      (-> (table-count table)
      (.then
       (fn [before]
         (let [f (write-ndjson! rows)]
           (-> (run-writer! (cond-> ["--account" ACCOUNT "--bucket" BUCKET
                                     "--namespace" NAMESPACE "--table" table
                                     "--ndjson" f]
                              create? (conj "--create")))
               (.then
                (fn [{:keys [code]}]
                  (cond
                    (= 2 code)
                    {:ok? false :error :commit/could-not-answer
                     :detail "the writer could not reach the catalog"}

                    (not (zero? code))
                    {:ok? false :error :commit/refused
                     :detail (str "iceberg_append.py exited " code)}

                    :else
                    (-> (table-count table)
                        (.then
                         (fn [after]
                           (cond
                             (nil? after)
                             {:ok? false :error :commit/readback-unavailable
                              :detail (str "appended, but " table " could not be read back -- "
                                           "UNVERIFIED, not verified")}

                             (nil? before)
                             {:ok? true :appended (count rows) :after after
                              :note (str "table did not exist before this run; delta not checkable, "
                                         "count after = " after)}

                             ;; The table grew by LESS than we wrote. Rows
                             ;; are missing however you count it.
                             (< (- after before) (count rows))
                             {:ok? false :error :commit/count-mismatch
                              :detail (str "wrote " (count rows) " rows but the table grew by "
                                           (- after before) " (" before " -> " after
                                           "). The commit was partial.")}

                             ;; The table grew by MORE. Another writer
                             ;; appended between our two counts -- which is
                             ;; now possible on purpose: the AIS collector
                             ;; and the `digitraffic` feed both write
                             ;; `otent_vessel` from separate PROCESSES, and
                             ;; `otent.lock` only orders writers inside one.
                             ;;
                             ;; Refusing here would report two commits that
                             ;; both succeeded as a fault. But the delta no
                             ;; longer proves OUR rows landed, so the result
                             ;; says which check answered rather than
                             ;; quietly weakening the strong one.
                             (> (- after before) (count rows))
                             {:ok? true :appended (count rows)
                              :before before :after after
                              :verification :presence-only
                              :note (str "the table grew by " (- after before)
                                         " while this commit wrote " (count rows)
                                         " -- another writer appended concurrently, so this"
                                         " is verified by growth rather than by delta")}

                             :else {:ok? true :appended (count rows)
                                    :before before :after after
                                    :verification :delta})))))))))))))))

(defn ledger-file
  "Where the tick ledger lives.

  **Not in the checkout.** `$OTENT_LEDGER_DIR`, or `~/.gftd/otent`.

  It was `./ledger/tick.ledger.edn`, tracked in git, which was fine while a
  person ran the tick by hand and wrong the moment launchd started running
  it every ten minutes: a scheduled job that appends to a tracked file
  leaves the shared checkout permanently dirty, and CLAUDE.md records what
  that costs -- every other session's `main` sync then tries to preserve
  somebody's WIP, and stashes pile up. The detector tick next door holds the
  same invariant for the same reason: state lives under `~/.gftd/`, never in
  a checkout.

  The committed `ledger/` in git stays as the record of the hand-run era. It
  is no longer written to."
  []
  (let [dir (or (some-> (aget js/process.env "OTENT_LEDGER_DIR") str/trim not-empty)
                (path/join (or (aget js/process.env "HOME") ".") ".gftd" "otent"))]
    (path/join dir "tick.ledger.edn")))

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
  (let [f (ledger-file)]
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

(defn last-contacts
  "When each feed was last CONTACTED, from the same ledger.

  `:tick/at` rather than any timestamp inside the data: the question is how
  long ago we asked, not how fresh the answer was. A feed that has gone
  quiet for a week was still contacted a minute ago, and backing off on the
  data's own timestamps would poll the quietest feeds hardest.

  Only statuses that mean the feed was actually reached count. `:unmeasured`
  does not -- a feed skipped for want of a credential was never asked, so
  there is nothing to back off from, and treating it as contact would make
  a missing key look like a satisfied interval."
  []
  (let [f (ledger-file)]
    (if-not (fs/existsSync f)
      {}
      (->> (str/split-lines (fs/readFileSync f "utf8"))
           (remove str/blank?)
           (map cljs.reader/read-string)
           (mapcat (fn [r] (for [x (:tick/results r)] (assoc x :at (:tick/at r)))))
           (filter #(#{:committed :nothing-new :refused :dry-run} (:status %)))
           (reduce (fn [acc x]
                     (update acc (:feed x) (fnil max 0) (:at x)))
                   {})))))

(defn append-receipt!
  "Append one EDN map per line to `ledger/tick.ledger.edn`.

  Append-only on purpose: this is a measurement series, not a document, and
  the superproject's rule that documents carry only their current state
  exempts measurement and event streams for exactly this reason -- the
  value of a tick log is the sequence."
  [r]
  (let [f (ledger-file)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/appendFileSync f (str (pr-str r) "\n"))
    f))

(defn ledger-entries
  "Every receipt in the ledger, oldest first. `nil` when there is no ledger
  at all -- which is not an empty history."
  []
  (let [f (ledger-file)]
    (when (fs/existsSync f)
      (->> (str/split-lines (fs/readFileSync f "utf8"))
           (remove str/blank?)
           (map cljs.reader/read-string)
           (sort-by :tick/at)
           vec))))

;; ---------------------------------------------------------------- tick

(defn tick-feed
  "One feed, start to finish, with the wall time it took.

  The elapsed number is here because the cycle went over the timer's period
  again after retention was moved off the critical path, and nothing in the
  receipt could say which feed was responsible. A cycle total tells you
  there is a problem; a per-feed number tells you where. Guessing from the
  feed list is how you end up tuning the one that was already fast.

  ## What this number is NOT

  **It is not the cost this feed caused.** `tick` runs the feeds under
  `js/Promise.all`, and the Iceberg commit is `cp/spawnSync`, which blocks
  the entire Node event loop. So while one feed is committing, every other
  feed's clock keeps running and none of them make progress.

  Measured 2026-08-27: `opensky` reported 175 s on a poll whose fetch had
  already given up at its 60-second deadline. The missing 115 s was not
  OpenSky. It was this process, frozen inside a sibling's `python3`.

  So the numbers are wall-clock-from-start, they do not add up to the
  cycle, and the largest one names the feed that finished last rather than
  the feed that cost most. That is still worth having -- it is how the
  serialised-commit shape was found at all -- but reading it as an
  attribution would send the next person to optimise the wrong feed.

  Making it an attribution means not blocking the loop: an async spawn, or
  one commit process the feeds hand batches to."
  [feed {:keys [flags opts] :as args} watermark]
  (let [dry? (contains? flags "dry-run")
        started (js/Date.now)
        stamp (fn [r] (assoc r :elapsed-ms (- (js/Date.now) started)))]
    (-> (fetch-text feed opts)
        (.then
         (fn [f]
           (if-not (:ok? f)
             (if (= :feed/not-modified (:error f))
               ;; The feed was reached and said nothing has changed. That is
               ;; an observation, and it must not be counted with the feeds
               ;; nobody could read.
               {:feed (:id feed) :status :nothing-new
                :table (obs/table-name (:kind feed))
                :detail (:detail f)}
               {:feed (:id feed) :status :unmeasured
                :error (:error f) :detail (:detail f)})
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
                 ;; The payload goes to the bucket BEFORE the rows go to the
                 ;; table, and a failure to store it refuses the commit.
                 ;;
                 ;; Order matters, and so does the refusal. Rows whose payload
                 ;; was never stored are rows that cannot be rebuilt or
                 ;; audited: the sha256 on them points at nothing. Committing
                 ;; them anyway would put the table back to being the only
                 ;; copy -- silently, and only for the ticks where the write
                 ;; happened to fail, which is the worst of both.
                 (-> (archive-payload! f)
                     (.then
                      (fn [a]
                        (if-not (:ok? a)
                          {:feed (:id feed) :status :refused :table table
                           :error :payload/not-archived
                           :counts (:counts result)
                           :detail (str "the payload was not stored (" (name (:error a))
                                        ": " (:detail a) "), so these " (count (:admitted result))
                                        " rows would have been unrebuildable. Nothing was appended.")}
                          ;; `commit!` resolves rather than blocking, so the
                          ;; result is threaded rather than bound. Returning a
                          ;; promise from inside a `.then` chains it.
                          (-> (commit! table (:admitted result)
                                       (contains? flags "create"))
                              (.then
                               (fn [c]
                                 (merge {:feed (:id feed) :table table
                                         :counts (:counts result)
                                         :parse-failures (count failed)}
                                        (if (:ok? c)
                                          {:status :committed :appended (:appended c)
                                           :rows-before (:before c) :rows-after (:after c)
                                           ;; The watermark the NEXT tick will read.
                                           :max-observed-at (reduce max 0 (map :observed-at (:admitted result)))
                                           :payload-sha256 (:sha f)
                                           :payload-key (:key a)
                                           :payload-already-stored (boolean (:already? a))
                                           :note (:note c)}
                                          {:status :refused :error (:error c) :detail (:detail c)})))))))))))))))
        (.catch (fn [e] {:feed (:id feed) :status :unmeasured
                         :error :tick/threw :detail (str (.-message e))}))
        (.then stamp))))

(defn selected-feeds [opts]
  (if-let [only (:feed opts)]
    (let [ids (set (map keyword (str/split only #",")))]
      (filter #(contains? ids (:id %)) feeds/registry))
    feeds/registry))

(defn runnable?
  "Can this feed be read at all right now? Returns nil if yes, or the reason.

  A `:stream` feed is unrunnable UNLESS a batch file is supplied: the
  resident collector holds the socket and hands its flush here, so from this
  process's point of view the stream became a payload. Without one the
  answer is still `needs-resident-collector`, which is the honest state on
  any machine where the collector is not running."
  [feed opts]
  (if (and (= :stream (:access feed)) (:ais-batch opts))
    nil
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
    {:error :feed/unknown-access :detail (pr-str (:access feed))})))

(defn tick [args]
  (let [fs* (selected-feeds (:opts args))
        _ (when (empty? fs*)
            (log "no feed matched --feed" (:feed (:opts args)))
            (js/process.exit 2))
        skipped (for [f fs* :let [r (runnable? f (:opts args))] :when r]
                  (merge {:feed (:id f) :status :unmeasured} r))
        reachable (remove #(runnable? % (:opts args)) fs*)
        force? (contains? (:flags args) "force")
        now0 (js/Date.now)
        contacts (last-contacts)
        ;; `:min-interval-ms` has been declared per feed in the registry
        ;; since it was written, and until now nothing read it. Without
        ;; this the tick polls at whatever rate it is invoked at, which is
        ;; how a scheduler turns a 6-hourly element set into 96 identical
        ;; payloads a day, every one of them held as a duplicate.
        due-fn (fn [f] (or force?
                           (feeds/due? f now0 (get contacts (:id f)))))
        not-due (for [f reachable :when (not (due-fn f))]
                  {:feed (:id f) :status :not-due
                   :table (obs/table-name (:kind f))
                   :detail (str "last contacted "
                                (Math/round (/ (- now0 (get contacts (:id f))) 1000))
                                "s ago; this feed declares a minimum interval of "
                                (Math/round (/ (:min-interval-ms f) 1000))
                                "s, so it is due again in "
                                (Math/round (/ (feeds/next-due-in-ms f now0 (get contacts (:id f))) 1000))
                                "s. NOT asked -- this is not an observation.")})
        runnable (filter due-fn reachable)]
    (log (str "otent tick: " (count runnable) " runnable, "
              (count not-due) " not due, "
              (count skipped) " unmeasured, of " (count fs*) " feeds"))
    (doseq [s skipped]
      (log (str "  " (name (:feed s)) ": UNMEASURED -- " (:detail s))))
    (doseq [s not-due]
      (log (str "  " (name (:feed s)) ": not due -- " (:detail s))))
    (let [wm (watermarks)]
      (doseq [[k v] wm]
        (log (str "  watermark " (name k)
                  " max-observed-at=" (:max-observed-at v)
                  " sha=" (some-> (:payload-sha256 v) (subs 0 12)))))
      (-> (js/Promise.all (clj->js (map #(tick-feed % args (get wm (:id %))) runnable)))
        (.then (fn [rs]
                 (let [results (concat (js->clj rs :keywordize-keys true)
                                       not-due skipped)
                       now (js/Date.now)
                       r (receipt/build results now)]
                   (append-receipt! r)
                   (println (receipt/render r (.toISOString (js/Date. now))))
                   ;; The catalog goes to kotobase.net AFTER the receipt is
                   ;; on disk. The ledger is the record; this plane is a
                   ;; projection of it, so a publish that fails costs
                   ;; queryability and not history -- and it is reported
                   ;; rather than swallowed, because a catalog that has
                   ;; silently stopped being written looks exactly like a
                   ;; workspace where nothing happened.
                   (-> (if (contains? (:flags args) "no-publish")
                         (js/Promise.resolve {:ok? true :skipped? true})
                         (kb/publish-tick! r))
                       (.then (fn [p]
                                (println
                                 (cond
                                   (:skipped? p) "  catalog: not published (--no-publish)"
                                   (:ok? p) (str "  catalog -> kotobase.net/" kb/db-name
                                                 "  " (:entities p) " entities, graph "
                                                 (some-> (:graph p) (subs 0 20)) "...")
                                   :else (str "  catalog NOT published [" (name (:error p)) "] "
                                              (:detail p))))
                                (js/process.exit
                                 (if (:ok? p)
                                   (receipt/exit-code r)
                                   (max 1 (receipt/exit-code r))))))))))))))

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

(def archive-began-ms
  "When `archive-payload!` started storing payloads.

  Rows observed before this were written when nothing kept the bytes, so no
  payload can ever be found for them and `iceberg_retain.py` would refuse
  forever on a fixed set of old rows. The waiver is passed explicitly rather
  than defaulted inside the Python, so widening it is an edit here with a
  date attached, not a flag someone reaches for.

  2026-08-26T07:24:00Z, the first tick that archived."
  1787729040000)

(defn retain
  "Delete observations past their horizon, per kind.

  Each table is a separate run so one refusal does not stop the others: a
  missing payload behind aircraft rows says nothing about quakes."
  [args]
  (let [dry? (contains? (:flags args) "dry-run")
        now (js/Date.now)
        kinds (if-let [k (:kind (:opts args))]
                (map keyword (str/split k #","))
                (map :kind feeds/registry))
        all-tables (distinct (map obs/table-name kinds))
        ;; A table the tick has never created cannot be retained, and the R2
        ;; catalog answers "not found or forbidden" with one message -- so
        ;; absence and denial are indistinguishable from the error alone.
        ;; Rather than guess, ask whether it is readable, and treat
        ;; unreadable as SKIPPED with the reason named. `fire` and `vessel`
        ;; are UNMEASURED by design (no key, no resident collector), and a
        ;; job that exits 2 on every run for a known reason is one a
        ;; scheduler cannot tell from a broken one.
        ;; `table-count` resolves now, so the readability probe is one
        ;; `Promise.all` instead of a map comprehension. Retention runs alone
        ;; after the tick, so blocking here never cost anything -- but the
        ;; function it calls is shared, and a shared function that is async
        ;; for one caller is async for all of them.
        _ nil]
    (-> (js/Promise.all (clj->js (map table-count all-tables)))
        (.then
         (fn [counts]
           (let [readable (zipmap all-tables (js->clj counts))
                 tables (filterv #(some? (readable %)) all-tables)
                 skipped (remove #(some? (readable %)) all-tables)
                 results
                 (doall
         (for [t tables]
           (let [r (cp/spawnSync
                    "python3"
                    (clj->js (cond-> [(path/join (js/process.cwd) "scripts" "iceberg_retain.py")
                                      "--table" t
                                      "--now-ms" (str now)
                                      "--pre-archive-ms" (str archive-began-ms)]
                               dry? (conj "--dry-run")))
                    #js {:stdio #js ["ignore" "pipe" "inherit"] :env js/process.env})]
                      {:table t :code (.-status r) :out (str/trim (str (.-stdout r)))})))]
             (doseq [t skipped]
               (println (str "SKIPPED " t "  not readable: the tick reports this feed "
                             "UNMEASURED, so there is nothing to retain -- which is "
                             "not the same as having retained nothing")))
             (doseq [{:keys [table code out]} results]
               (println (str (case code 0 "OK      " 1 "REFUSED " "UNKNOWN ") table "  " out)))
             (let [refused (filter #(= 1 (:code %)) results)
                   unknown (remove #(#{0 1} (:code %)) results)]
               (println (str "retain: " (count results) " table(s) examined, "
                             (count skipped) " skipped as unreadable, "
                             (count refused) " refused, " (count unknown) " could not answer"))
               ;; The evidence floor. Skipping a known-absent table is fine;
               ;; skipping EVERY table is what a catalog outage looks like,
               ;; and it must not exit like a clean run.
               (when (empty? tables)
                 (println "REFUSING to report a pass: no table was readable at all, "
                          "which is what an outage looks like from here")
                 (js/process.exit 2))
               ;; 2 wins over 1 for the same reason it does in the tick
               ;; receipt: `could not answer` changes what a reader should
               ;; conclude more than `answered no` does.
               (js/process.exit (cond (seq unknown) 2 (seq refused) 1 :else 0)))))))))

(defn- run-reader!
  "Read one table as NDJSON. Resolves `{:code :rows}`; `:rows` is nil when
  the table could not be read, which is not an empty table."
  [table columns]
  (js/Promise.
   (fn [resolve _]
     (let [proc (cp/spawn "python3"
                          (clj->js [(path/join (js/process.cwd) "scripts" "iceberg_read.py")
                                    "--account" ACCOUNT "--bucket" BUCKET
                                    "--namespace" NAMESPACE "--table" table
                                    "--columns" (str/join "," columns)])
                          #js {:stdio #js ["ignore" "pipe" "inherit"]
                               :env js/process.env})
           buf (atom "")]
       (.on (.-stdout proc) "data" (fn [d] (swap! buf str (.toString d))))
       (.on proc "close"
            (fn [code]
              (resolve {:code (or code -1)
                        :rows (when (zero? (or code -1))
                                (mapv #(js->clj (js/JSON.parse %))
                                      (remove str/blank? (str/split-lines @buf))))})))
       (.on proc "error" (fn [_] (resolve {:code -1 :rows nil})))))))

(defn- attrs-of [row]
  (js->clj (js/JSON.parse (or (get row "attrs_json") "{}"))))

(defn- latest-per-object
  "One row per object, newest `observed_at`. The identity tables are snapshot
  SERIES -- a vessel appears once per poll -- so joining without this counts
  the same ship as many times as it has been seen."
  [rows]
  (vals (reduce (fn [acc r]
                  (let [k (get r "object_id")
                        prev (get acc k)]
                    (if (or (nil? prev)
                            (> (js/parseInt (get r "observed_at") 10)
                               (js/parseInt (get prev "observed_at") 10)))
                      (assoc acc k r) acc)))
                {} rows)))

(defn sanctions
  "Who, of the ships currently in coverage, is on a list -- and who is behind
  them.

    otent sanctions

  Four tables, one join, inside the catalog. This existed as an ad-hoc script
  run by hand six times before it was a command, and two of those runs were
  wrong in ways nobody could see afterwards: one joined OFAC alone (20 of 60
  vessels) and one left the `IMO` prefix on and got zero rows, which reads
  exactly like a clean fleet."
  [_args]
  (-> (js/Promise.all
       (clj->js [(run-reader! "otent_vessel_static" ["object_id" "observed_at" "attrs_json"])
                 (run-reader! "otent_vessel_risk" ["attrs_json"])
                 (run-reader! "otent_ownership_link" ["attrs_json"])
                 (run-reader! "otent_org_identity" ["object_id" "attrs_json"])]))
      (.then
       (fn [[vs rk ow og]]
         (let [vessels (some->> (:rows vs) latest-per-object
                                (mapv (fn [r]
                                        (let [a (attrs-of r)]
                                          {:name (get a "ship_name")
                                           :imo (some-> (get a "imo") str)
                                           :mmsi (get r "object_id")}))))
               risk (some->> (:rows rk)
                             (mapv (fn [r] (let [a (attrs-of r)]
                                             {:imo (get a "imo") :mmsi (get a "mmsi")
                                              :risk (get a "risk")}))))
               own (some->> (:rows ow)
                            (mapv (fn [r] (let [a (attrs-of r)]
                                            {:asset-imo (get a "asset_imo")
                                             :org-id (get a "org_id")
                                             :org-name (get a "org_name")
                                             :org-jurisdiction (get a "org_jurisdiction")
                                             :role (get a "role")}))))
               orgs (some->> (:rows og)
                             (mapv (fn [r] (let [a (attrs-of r)]
                                             {:id (get r "object_id")
                                              :org-name (get a "org_name")
                                              :imo-company-no (get a "imo_company_no")}))))
               rpt (sanc/report {:vessels vessels :risk risk :ownership own :orgs orgs})]
           (doseq [l (sanc/render rpt)] (println l))
           (js/process.exit (sanc/exit-code rpt)))))))

(defn coverage
  "What the ingest actually covers, measured rather than declared.

    otent coverage

  Reads the tick ledger for cadence and the catalog for row counts, and
  refuses to print a clean line when either could not be measured. It
  exists because the drift it looks for -- every feed polled at ~1.5x its
  declared interval, because the cycle ran longer than the timer's period
  -- was invisible to every check in this repository: the tick was green,
  `due?` was honouring `:min-interval-ms` exactly, and the only symptom was
  a number nobody was computing."
  [{:keys [opts]}]
  (let [entries (ledger-entries)
        window (cov/parse-window (:window opts))]
    (when (and (:window opts) (nil? window))
      (println "REFUSING: --window" (pr-str (:window opts))
               "is not a duration. Use 3h, 45m, 90s or a number of milliseconds.")
      (js/process.exit 2))
    (when (nil? entries)
      (println "REFUSING to report coverage: there is no ledger at" (ledger-file))
      (js/process.exit 2))
    (let [tables (when (r2/token)
                   (into {} (for [k (distinct (map :kind feeds/registry))]
                              [k (let [{:keys [status rows]} (table-status (obs/table-name k))]
                                   (if (= :rows status) rows status))])))
          rpt (cov/report {:registry feeds/registry
                           :entries entries
                           :now (js/Date.now)
                           :tables tables
                           :window-ms window
                           :expected-unmeasured (set (keys feeds/expected-unmeasured))})]
      (doseq [l (cov/render rpt)] (println l))
      (js/process.exit (cov/exit-code rpt)))))

(defn -main [& _]
  (let [parsed (cli/parse-args (user-args))
        _ (when (:error parsed)
            (binding [*print-fn* *print-err-fn*] (println (:detail parsed)))
            (js/process.exit 2))
        {:keys [cmd opts] :as args} parsed]
    (case cmd
      "feeds" (doseq [l (feeds/describe)] (println l))
      "count" (let [k (keyword (or (:kind opts) "quake"))
                    t (obs/table-name k)]
                (-> (table-status t)
                    (.then (fn [{:keys [status rows]}]
                             (case status
                               :rows (println t rows)
                               :absent (do (println t "ABSENT -- the catalog was asked and does not have this table")
                                           (js/process.exit 3))
                               (do (println t "UNREADABLE -- not zero, and not absent either")
                                   (js/process.exit 2)))))))
      "tick" (tick args)
      "retain" (retain args)
      "coverage" (coverage args)
      "sanctions" (sanctions args)
      (do (println "usage: otent.cljs <tick|retain|feeds|count|coverage|sanctions> [--feed a,b] [--kind a,b] [--window 3h] [--dry-run] [--create] [--force]")
          (js/process.exit 2)))))

(-main)
