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
