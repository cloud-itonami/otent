(ns otent.coverage
  "What the ingest actually covers, measured from the tick ledger.

  This exists because a coverage question was answered by hand on
  2026-08-27 and the hand answer found something no code was looking for:
  **every feed was being polled at roughly 1.5x its declared interval.**
  The plist asks launchd for a cycle every 300 s, the cycle was running
  retention inline at ~3.4 minutes, launchd will not start a job that is
  still running, and so the effective cadence was 447 s. Nothing was
  broken, nothing was red, and `:min-interval-ms` -- a field this
  repository had already caught being decorative once -- was being honoured
  by the code and defeated by the schedule.

  So the declared interval is a control, and this is the instrument that
  reads it back. `feeds/due?` answers *may I poll now*; this answers *how
  often did I actually poll*, which is a different question and the one
  coverage depends on.

  Pure -- registry, parsed ledger entries and `now` in, report out. No
  clock, no file handle, no network, so a verdict is reproducible from its
  inputs.

  ## Three things it will not do

  **It will not call an unmeasured interval zero.** A feed polled once has
  no gap to measure; a feed polled never has no evidence at all. Both come
  back `:unmeasured`, which is not `:ok`, and a report in which nothing
  could be measured exits `:cannot-answer` rather than clean.

  **It will not let a missing table pass as a coverage number.** A table
  absent for a feed nobody could read is consistent; a table absent for a
  feed that has been committing rows is a finding.

  **It will not absorb a new dark feed into an exemption written for
  others.** `expected-unmeasured` is checked by name."
  (:require [clojure.string :as str]))

(def default-tolerance
  "How far past its declared interval a feed may drift before it is a
  finding.

  1.25 rather than 1.0 because a timer cannot fire on the instant and a
  feed asked 2 s late is not a defect. Not 2.0: the drift this was written
  for was 1.49x, and a tolerance that would have called that healthy would
  be a tolerance chosen to make the current state pass."
  1.25)

(def default-min-ticks
  "Below this many ledger entries there is no cadence to speak of, and the
  report says so instead of dividing small numbers."
  12)

(def default-min-reachability
  "How often a live feed has to be readable before its failures stop being
  blips. 0.8 -- a feed failing one attempt in five is not having a bad
  minute."
  0.8)

(def contacted-statuses
  "Statuses that mean the feed was actually reached.

  `:not-due` is excluded because it means we chose not to ask, and
  `:unmeasured` because we could not -- counting either as contact would
  turn a backoff or a missing credential into evidence of polling."
  #{:committed :nothing-new :refused :dry-run})

(defn parse-window
  "`3h` / `45m` / `90s` / a bare number of milliseconds -> ms, or nil.

  Needed because the median this reports is over the WHOLE ledger, and a
  fix to the cadence cannot show up in a 30-hour median for most of a day.
  Without a window, an instrument that measures a schedule cannot show the
  schedule being repaired -- which would make it useless at exactly the
  moment it matters.

  The window is named in the output. A window is a legitimate question
  (`what is the cadence now`) and an illegitimate one (`which window makes
  this look fine`), and the difference is whether the reader can see which
  was asked."
  [s]
  (when (and (string? s) (seq s))
    (let [n (js/parseFloat s)]
      (when-not (js/isNaN n)
        (condp #(str/ends-with? %2 %1) s
          "h" (* n 3600000)
          "m" (* n 60000)
          "s" (* n 1000)
          n)))))

(defn- median [xs]
  (when (seq xs)
    (let [s (vec (sort xs))
          n (count s)]
      (if (odd? n)
        (nth s (quot n 2))
        (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2)))))

(defn poll-times
  "When each feed was actually contacted, newest last."
  [entries]
  (reduce (fn [acc e]
            (reduce (fn [a r]
                      (if (contacted-statuses (:status r))
                        (update a (:feed r) (fnil conj []) (:tick/at e))
                        a))
                    acc
                    (:tick/results e)))
          {}
          (sort-by :tick/at entries)))

(defn unmeasured-counts
  "How many times each feed came back unmeasured, and how many times it was
  reached. A feed that failed three polls in ninety is not dark; it is
  reachable 97% of the time, and that number is coverage.

  **Counted from a feed's first successful contact onward.** Before that it
  was not failing -- it was not running, for a reason already recorded as
  an exemption. Measured 2026-08-27: `firms` went live at 09:07 after a
  free key was entered, and counting the whole ledger reported it
  `reachable 1% of the time (179 of 180 attempts could not be read)` --
  true about the history and false about the feed. Every newly enabled feed
  would read as broken for a day, and a report that is red for a bad reason
  is one nobody reads for the good ones.

  The statistic this keeps is `since it started working, how often does it
  work`. A feed that has never worked has no reachability at all, which is
  what `:unmeasured` already says."
  [entries]
  (let [ordered (sort-by :tick/at entries)
        ;; When each feed was first actually reached. Feeds absent from
        ;; this map have never been reached, and get no denominator rather
        ;; than a denominator of everything.
        first-contact (reduce (fn [acc e]
                                (reduce (fn [a r]
                                          (if (and (contacted-statuses (:status r))
                                                   (not (contains? a (:feed r))))
                                            (assoc a (:feed r) (:tick/at e))
                                            a))
                                        acc (:tick/results e)))
                              {}
                              ordered)]
    (reduce (fn [acc e]
              (reduce (fn [a r]
                        (let [since (get first-contact (:feed r))]
                          (if (or (nil? since) (< (:tick/at e) since))
                            a
                            (cond-> a
                              (= :unmeasured (:status r))
                              (update-in [(:feed r) :unmeasured] (fnil inc 0))
                              (contacted-statuses (:status r))
                              (update-in [(:feed r) :reached] (fnil inc 0))))))
                      acc
                      (:tick/results e)))
            {}
            ordered)))

(defn dark-now
  "Which feeds are unmeasured **as of the last tick that mentioned them**.

  Not `unmeasured at any point in the ledger`. The first version of this
  was, and it reported the two feeds that had blipped once each in thirty
  hours as being as dark as the two that have never been readable at all --
  which would have made `otent coverage` permanently red and therefore
  permanently ignorable, the exact failure mode the scheduler's declared
  set was written to avoid."
  [entries]
  (let [by-feed (reduce (fn [acc e]
                          (reduce (fn [a r] (assoc a (:feed r) (:status r)))
                                  acc (:tick/results e)))
                        {}
                        (sort-by :tick/at entries))]
    (set (for [[f st] by-feed :when (= :unmeasured st)] (name f)))))

(defn measure-feed
  "Declared interval against the measured one, for one feed.

  `:measured-ms` is the MEDIAN gap, not the mean: one 40-minute outage
  should not restate a healthy cadence, and one very fast run should not
  hide a slow one."
  [feed times reach tolerance]
  (let [declared (or (:min-interval-ms feed) 0)
        tried (+ (:reached reach 0) (:unmeasured reach 0))
        reachability (when (pos? tried) (/ (:reached reach 0) tried))
        gaps (when (> (count times) 1) (map - (rest times) times))
        measured (median gaps)]
    (cond
      (nil? measured)
      {:id (:id feed) :kind (:kind feed) :access (:access feed)
       :declared-ms declared :measured-ms nil :polls (count times)
       :unmeasured (:unmeasured reach 0) :reachability reachability
       :status :unmeasured
       :detail (if (zero? (count times))
                 "never contacted in this ledger -- no evidence, which is not a cadence of zero"
                 "contacted once; a single contact has no gap to measure")}

      (zero? declared)
      {:id (:id feed) :kind (:kind feed) :access (:access feed)
       :declared-ms declared :measured-ms measured :polls (count times)
       :unmeasured (:unmeasured reach 0) :reachability reachability
       :status :no-declared-interval
       :detail "this feed declares no minimum interval, so there is nothing to compare against"}

      :else
      (let [ratio (/ measured declared)]
        {:id (:id feed) :kind (:kind feed) :access (:access feed)
         :declared-ms declared :measured-ms measured :polls (count times)
         :unmeasured (:unmeasured reach 0) :reachability reachability
         :ratio ratio
         :status (if (> ratio tolerance) :drift :ok)
         :detail (when (> ratio tolerance)
                   (str "polled every " (Math/round (/ measured 1000.0)) "s against a declared "
                        (Math/round (/ declared 1000.0)) "s -- "
                        (.toFixed ratio 2) "x. The declared interval is a floor the"
                        " schedule is not reaching, so coverage is lower than the"
                        " registry says it is."))}))))

(defn report
  "The whole picture: cadence per feed, plus whichever feeds went dark.

  `tables` is `{kind row-count-or-keyword}` -- a number, `:absent` for a
  table the catalog does not have, or `:unreadable` for one that could not
  be asked. `nil` means tables were not measured at all, and that is
  reported as such rather than omitted."
  [{:keys [registry entries now tables expected-unmeasured tolerance min-ticks
           min-reachability window-ms]
    :or {tolerance default-tolerance min-ticks default-min-ticks
         min-reachability default-min-reachability
         expected-unmeasured #{}}}]
  (let [;; The window cuts by tick time, not by count: `the last three
        ;; hours` and `the last thirty ticks` answer differently the moment
        ;; the cadence is the thing in question, and it is.
        cutoff (when (and window-ms now) (- now window-ms))
        entries (if cutoff (filterv #(>= (:tick/at %) cutoff) entries) (vec entries))
        ats (sort (map :tick/at entries))
        times (poll-times entries)
        reach (unmeasured-counts entries)
        feeds (mapv #(measure-feed % (get times (:id %) []) (get reach (:id %) {}) tolerance)
                    registry)
        dark (dark-now entries)
        unexpected-dark (sort (remove (set expected-unmeasured) dark))
        ;; A table is missing for a feed that HAS been committing rows.
        ;; That, not a missing table as such, is the finding: the fire and
        ;; vessel tables are absent because nothing could read those feeds.
        live-kinds (set (for [f feeds :when (pos? (:polls f))] (:kind f)))
        table-findings (when tables
                         (sort-by first
                                  (for [[kind v] tables
                                        :when (and (live-kinds kind) (not (number? v)))]
                                    [kind v])))
        drifting (filterv #(= :drift (:status %)) feeds)
        ;; A feed reachable less than this often is not dark and is not
        ;; healthy either. Named separately so a blip is information and a
        ;; pattern is a finding.
        flaky (filterv #(and (:reachability %)
                             (pos? (:polls %))
                             (< (:reachability %) min-reachability))
                       feeds)
        measurable? (and (>= (count entries) min-ticks)
                         (some #(number? (:measured-ms %)) feeds))]
    {:ticks (count entries)
     :window-ms window-ms
     :span-ms (when (> (count ats) 1) (- (last ats) (first ats)))
     :first-at (first ats)
     :last-at (last ats)
     :now now
     :feeds feeds
     :tables tables
     :dark (sort dark)
     :flaky (mapv :id flaky)
     :unexpected-dark unexpected-dark
     :table-findings table-findings
     :verdict (cond
                (not measurable?)                :cannot-answer
                (nil? tables)                    :tables-unmeasured
                (seq unexpected-dark)            :dark
                (seq table-findings)             :table-missing
                (seq drifting)                   :drift
                (seq flaky)                      :flaky
                :else                            :ok)
     :findings (concat (map #(vector :drift (:id %) (:detail %)) drifting)
                       (map #(vector :flaky (:id %)
                                     (str "reachable " (.toFixed (* 100 (:reachability %)) 0)
                                          "% of the time (" (:unmeasured %) " of "
                                          (+ (:polls %) (:unmeasured %))
                                          " attempts could not be read)"))
                            flaky)
                       (map #(vector :dark (symbol %)
                                     "went unmeasured and is not in the declared set")
                            unexpected-dark)
                       (map (fn [[k v]]
                              [:table-missing k
                               (str "this feed has been committing rows but its table came"
                                    " back " (name v))])
                            table-findings))}))

(defn exit-code
  "0 clean · 1 a finding · 2 could not answer.

  Two beats one for the same reason it does everywhere else here: `I could
  not look` changes what a reader should do more than `I looked and found
  something` does."
  [{:keys [verdict]}]
  (case verdict
    :ok 0
    (:cannot-answer :tables-unmeasured) 2
    1))

(defn- ms->human [ms]
  (cond
    (nil? ms) "--"
    (< ms 90000) (str (Math/round (/ ms 1000.0)) "s")
    (< ms 5400000) (str (.toFixed (/ ms 60000.0) 1) "m")
    :else (str (.toFixed (/ ms 3600000.0) 1) "h")))

(defn render
  "The report as lines. Every feed appears, including the ones that could
  not be measured -- a coverage report that lists only what worked is the
  shape this repository exists to avoid."
  [{:keys [ticks span-ms feeds tables dark unexpected-dark findings verdict window-ms]}]
  (concat
   [(str "otent coverage  ticks=" ticks "  history=" (ms->human span-ms)
         (if window-ms
           (str "  window=" (ms->human window-ms) " (the whole ledger is longer)")
           "  window=the whole ledger"))
    ""
    (str (str/join "  " ["feed      " "kind     " "polls" "declared" "measured" "ratio " "reach"]))]
   (for [f feeds]
     (str (str/join "  " [(.padEnd (name (:id f)) 10)
                          (.padEnd (name (:kind f)) 9)
                          (.padStart (str (:polls f)) 5)
                          (.padStart (ms->human (:declared-ms f)) 8)
                          (.padStart (ms->human (:measured-ms f)) 8)
                          (.padStart (if (:ratio f) (str (.toFixed (:ratio f) 2) "x") "--") 6)
                          (.padStart (if (:reachability f)
                                       (str (.toFixed (* 100 (:reachability f)) 0) "%")
                                       "--") 5)])
          (when (not= :ok (:status f)) (str "  " (str/upper-case (name (:status f)))))))
   [""]
   (if (nil? tables)
     ["tables UNMEASURED -- no $CF_CATALOG_TOKEN. Not zero rows, and not a clean run."]
     (for [[kind v] (sort-by first tables)]
       (str "table  " (.padEnd (name kind) 10)
            (if (number? v) v (str (str/upper-case (name v)) " -- not zero")))))
   [""]
   (if (seq dark)
     [(str "unmeasured feeds: " (str/join "," dark)
           (if (seq unexpected-dark)
             (str "  <- UNDECLARED: " (str/join "," unexpected-dark))
             "  (all declared)"))]
     ["unmeasured feeds: none"])
   (when (seq findings)
     (cons "" (for [[k id detail] findings]
                (str (str/upper-case (name k)) " " (name id) "  " detail))))
   [""
    (case verdict
      :ok "coverage OK"
      :cannot-answer (str "REFUSING to report coverage: " ticks
                          " ledger entries is too few to measure a cadence from"
                          (when window-ms " in this window"))
      :tables-unmeasured "REFUSING to report coverage: the tables were not read"
      (str "coverage " (str/upper-case (name verdict))))]))
