(ns otent.darkness-test
  (:require [clojure.test :refer [deftest is testing]]
            [otent.darkness :as d]))

(deftest one-timeout-is-not-a-failure
  (testing "supplying the FIRMS key correctly removed its exemption, and
            twenty minutes later a burst of local load made two fetches
            exceed their deadline and the cycle REFUSED. True, and the
            start of the failure `expected-unmeasured` exists to prevent:
            a job that goes red for a transient reason teaches its reader
            that red means nothing."
    (let [s (d/advance {} [:usgs :opensky :firms] [:opensky])
          v (d/verdict {:streaks s :exempt #{}})]
      (is (= {:usgs 0 :opensky 1 :firms 0} s))
      (is (false? (:refuse? v)))
      (is (re-find #"opensky 1/3" (:detail v))
          "and the rising count is visible while it is still recoverable"))))

(deftest three-in-a-row-is
  (let [s (-> (d/advance {} [:usgs :opensky] [:opensky])
              (d/advance [:usgs :opensky] [:opensky])
              (d/advance [:usgs :opensky] [:opensky]))
        v (d/verdict {:streaks s :exempt #{}})]
    (is (= 3 (:opensky s)))
    (is (true? (:refuse? v)))
    (is (= [:opensky] (:over v)))
    (is (re-find #"Not a blip" (:detail v)))))

(deftest a-feed-that-recovers-is-back-to-zero
  (testing "without the reset every feed eventually accumulates its way to
            the threshold, and the refusal fires for one that has been
            healthy for a week"
    (let [s (-> (d/advance {} [:opensky] [:opensky])
                (d/advance [:opensky] [:opensky])
                (d/advance [:opensky] []))]
      (is (zero? (:opensky s)))
      (is (false? (:refuse? (d/verdict {:streaks s :exempt #{}})))))))

(deftest a-declared-exemption-never-refuses-however-long-it-lasts
  (let [s (reduce (fn [acc _] (d/advance acc [:aisstream] [:aisstream])) {} (range 500))
        v (d/verdict {:streaks s :exempt #{:aisstream}})]
    (is (= 500 (:aisstream s)) "the streak is still counted, and still visible")
    (is (false? (:refuse? v)))
    (is (nil? (:detail v))
        "a documented state is not news, and must not crowd out the feeds
         whose silence IS news")))

(deftest a-feed-that-was-not-asked-keeps-its-streak
  (testing "not-due and unmeasured are different: a feed inside its
            interval was not asked, so its streak must neither grow nor
            reset -- resetting would let a broken feed launder its record
            by being skipped"
    (let [s (d/advance {:firms 2} [:usgs] [])]
      (is (nil? (:firms s)) "not in this cycle's result at all")
      (is (= 2 (get (merge {:firms 2} s) :firms))
          "so a caller merging over the previous state keeps the 2"))))
