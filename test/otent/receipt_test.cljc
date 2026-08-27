(ns otent.receipt-test
  "The exit code. This is the whole 'did not look' vs 'looked and found
  nothing' distinction, and it is pure, so it can be asserted directly."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.receipt :as r]))

(defn- code [& statuses]
  (r/exit-code (r/build (map #(hash-map :feed :x :status %) statuses) 0)))

(deftest unmeasured-does-not-collapse-into-its-neighbours
  (testing "everything committed"
    (is (= 0 (code :committed :committed))))
  (testing "a feed the governor refused"
    (is (= 1 (code :committed :refused))))
  (testing "a feed that was NOT READ -- the most serious fact, so it wins
            even alongside a refusal"
    (is (= 2 (code :committed :unmeasured)))
    (is (= 2 (code :refused :unmeasured))))
  (testing "a slow feed with nothing new is healthy, not a failure"
    (is (= 0 (code :nothing-new)))
    (is (= 0 (code :committed :nothing-new))))
  (testing "a tick where nothing at all was looked at is 2, not 0"
    (is (= 2 (code)))
    (is (= 2 (code :unmeasured :unmeasured)))))

(deftest the-report-says-what-was-not-read
  (let [rep (r/render (r/build [{:feed :aisstream :status :unmeasured
                                 :error :feed/needs-resident-collector
                                 :detail "no collector"}]
                               0)
                      "1970-01-01T00:00:00.000Z")]
    (is (re-find #"UNMEASURED" rep))
    (is (re-find #"NOT READ" rep)
        "the report must state plainly that a feed was not read")))

(deftest not-due-is-its-own-class-not-a-quiet-feed
  (testing "counted separately -- `nothing-new` means we asked and it had
            not changed; `not-due` means we did not ask. Collapsing them
            reports a deliberate backoff as an observation."
    (let [r (r/build [{:feed :a :status :nothing-new}
                      {:feed :b :status :not-due}
                      {:feed :c :status :not-due}]
                     0)]
      (is (= 1 (:tick/nothing-new r)))
      (is (= 2 (:tick/not-due r)))))
  (testing "a tick where every feed was inside its declared interval is a
            correct run, not a run that could not answer"
    (is (= 0 (code :not-due)))
    (is (= 0 (code :not-due :not-due))))
  (testing "backing off does not hide a feed that was never read"
    (is (= 2 (code :not-due :unmeasured))))
  (testing "nor a refusal"
    (is (= 1 (code :not-due :refused)))))

(deftest the-report-distinguishes-not-asked-from-asked-and-quiet
  (let [rep (r/render (r/build [{:feed :celestrak :status :not-due
                                 :detail "due again in 3600s. NOT asked"}]
                               0)
                      "1970-01-01T00:00:00.000Z")]
    (is (re-find #"not-due 1" rep)
        "the summary line must carry the count, or a reader cannot tell a
         backed-off tick from an idle one")
    (is (re-find #"NOT-DUE celestrak" rep))
    (is (not (re-find #"NOTHING-NEW" rep))
        "a feed that was never asked must not be rendered as one that was")))

(deftest a-feed-carries-the-time-it-took
  (testing "the cycle overran the timer's period and the receipt could not
            say which feed did it. A cycle total says there is a problem; a
            per-feed number says where."
    (let [rep (r/build [{:feed :opensky :status :committed :table "otent_aircraft"
                            :appended 7000 :elapsed-ms 91000}
                           {:feed :usgs :status :nothing-new :table "otent_quake"
                            :detail "nothing new" :elapsed-ms 1400}] 1787824000000)
          out (r/render rep "2026-08-27T10:00:00Z")]
      (is (re-find #"\[91s\]" out))
      (is (re-find #"\[1s\]" out)
          "printed for every status -- a feed that spent ninety seconds
           discovering it had nothing new is as much of a finding as one
           that spent it committing"))))

(deftest a-receipt-without-timings-still-renders
  (testing "every ledger entry written before 2026-08-27 has no
            :elapsed-ms, and reading one back must not produce the string
            `[NaNs]` where a number would be"
    (let [out (r/render (r/build [{:feed :usgs :status :committed
                                   :table "otent_quake" :appended 3}]
                                 1787824000000)
                        "2026-08-27T10:00:00Z")]
      (is (not (re-find #"\[" out)))
      (is (re-find #"COMMITTED usgs" out)))))
