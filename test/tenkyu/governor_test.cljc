(ns tenkyu.governor-test
  "The governor, and specifically whether each rule can fire.

  A rule that has never rejected anything for the reason it names is not a
  rule. Every test here therefore asserts BOTH directions: a clean row is
  admitted, and a row broken in exactly the way the rule describes is held
  **with that rule's reason** -- not merely held."
  (:require [clojure.test :refer [deftest is testing]]
            [tenkyu.governor :as gov]))

(def now 1787700000000)                       ; 2026-08-25T18:00Z, ms

(def clean
  {:kind :quake :object-id "us7000tbu3" :observed-at 1787689416308
   :lat 37.2686 :lon 141.8893 :alt-km -35.0
   :attrs {:mag 4.7 :place "77 km E of Tomioka, Japan"}
   :source :usgs :source-url "https://earthquake.usgs.gov/..."
   :fetched-at now :payload-sha256 "abc123"})

(deftest the-clean-row-is-admitted
  ;; The control. Without it, every test below could be passing because
  ;; the governor rejects everything.
  (is (nil? (gov/check-row clean now))
      (str "the control row was held: " (pr-str (gov/check-row clean now)))))

(deftest seconds-mistaken-for-milliseconds
  ;; THE bug this rule exists for: OpenSky reports UNIX seconds, USGS
  ;; milliseconds, and 1787689416 is a perfectly good number.
  (let [h (gov/check-row (assoc clean :observed-at 1787689416) now)]
    (is (some? h) "a seconds timestamp was admitted as milliseconds")
    (is (= :timestamp-not-plausible (:reason h)))))

(deftest milliseconds-mistaken-for-seconds
  (let [h (gov/check-row (assoc clean :observed-at (* 1000 1787689416308)) now)]
    (is (some? h))
    (is (= :timestamp-not-plausible (:reason h)))))

(deftest a-timestamp-from-the-future
  (let [h (gov/check-row (assoc clean :observed-at (+ now 7200000)) now)]
    (is (some? h) "a row two hours in the future was admitted")
    (is (= :timestamp-not-plausible (:reason h))))
  (testing "but a little clock skew at the sensor is not an error"
    (is (nil? (gov/check-row (assoc clean :observed-at (+ now 60000)) now)))))

(deftest transposed-latitude-and-longitude
  ;; 141.8893 is a valid longitude and an impossible latitude.
  (let [h (gov/check-row (assoc clean :lat 141.8893 :lon 37.2686) now)]
    (is (some? h) "a latitude of 141.9 was admitted")
    (is (= :coordinates-out-of-range (:reason h))))
  (testing "the row-level rule CANNOT catch a transposition where both
            values are valid latitudes -- that is what the batch-level
            held-fraction ceiling is for, and pretending otherwise here
            would be a test that flatters the rule"
    (is (nil? (gov/check-row (assoc clean :lat 12.0 :lon 34.0) now)))))

(deftest a-row-that-cannot-say-where-it-came-from
  (doseq [k [:source :source-url :payload-sha256 :fetched-at]]
    (let [h (gov/check-row (assoc clean k nil) now)]
      (is (some? h) (str "a row with no " k " was admitted"))
      (is (= :provenance-incomplete (:reason h))
          (str k " was held for the wrong reason: " (:reason h))))))

(deftest an-attribute-that-names-a-person
  (doseq [k [:owner_name :pilot :crew_list :passenger_email :ownerName]]
    (let [h (gov/check-row (assoc-in clean [:attrs k] "x") now)]
      (is (some? h) (str k " was admitted"))
      (is (= :person-identifier (:reason h)))))
  (testing "a vehicle's own broadcast identity is NOT a person identifier --
            holding these would make the data useless while the same
            broadcast stayed public"
    (doseq [k [:icao24 :callsign :mmsi :ship_name :registration :squawk]]
      (is (nil? (gov/check-row (assoc-in clean [:attrs k] "x") now))
          (str k " was held as a person identifier")))))

(deftest the-same-observation-twice-in-one-batch
  (let [r (gov/admit [clean clean clean] now)]
    (is (= 1 (count (:admitted r))))
    (is (= 2 (count (:held r))))
    (is (= 2 (:duplicate-observation (:counts r))))))

(deftest an-observation-already-committed-by-an-earlier-tick
  (testing "at or before the watermark is held"
    (let [r (gov/admit [clean] now {:watermark-ms (:observed-at clean)})]
      (is (empty? (:admitted r)))
      (is (= :already-committed (:reason (first (:held r)))))))
  (testing "after the watermark is admitted"
    (let [r (gov/admit [clean] now {:watermark-ms (dec (:observed-at clean))})]
      (is (= 1 (count (:admitted r))))))
  (testing "NO watermark admits everything -- a first tick has no record,
            and that is not the same as a watermark of zero"
    (is (= 1 (count (:admitted (gov/admit [clean] now {})))))
    (is (= 1 (count (:admitted (gov/admit [clean] now {:watermark-ms nil})))))))

(deftest counts-are-always-present-and-add-up
  (let [rows [clean
              (assoc clean :object-id "b" :observed-at 1787689416)     ; seconds
              (assoc clean :object-id "c" :lat 999.0)                  ; bad lat
              (assoc clean :object-id "d" :source nil)]                ; no source
        r (gov/admit rows now)]
    (is (contains? r :admitted))
    (is (contains? r :held) ":held must always be present")
    (is (= 4 (:proposed (:counts r))))
    (is (= (+ (count (:admitted r)) (count (:held r))) 4)
        "every proposed row must end up in exactly one of admitted/held")
    (is (= 1 (:timestamp-not-plausible (:counts r))))
    (is (= 1 (:coordinates-out-of-range (:counts r))))
    (is (= 1 (:provenance-incomplete (:counts r))))))

(deftest an-empty-poll-is-not-a-clean-poll
  ;; This is the whole "measured nothing must not read as measured zero"
  ;; rule, at the batch level.
  (let [v (gov/commit-decision (gov/admit [] now))]
    (is (false? (:commit? v)))
    (is (= :nothing-proposed (:reason v)))))

(deftest a-mostly-held-batch-is-refused-rather-than-committed-small
  ;; The rule that caught the transposed lat/lon in the real USGS feed,
  ;; where 9 of 33 rows still passed the per-row check.
  (let [rows (concat (repeat 3 clean)
                     (map #(assoc clean :object-id (str %) :lat 999.0) (range 30)))
        v (gov/commit-decision (gov/admit rows now))]
    (is (false? (:commit? v)))
    (is (= :held-fraction-too-high (:reason v)))))

(deftest nothing-new-is-not-a-failure
  ;; A slow feed polled on schedule must not exit like a broken one.
  (let [r (gov/admit [clean] now {:watermark-ms (:observed-at clean)})
        v (gov/commit-decision r)]
    (is (false? (:commit? v)))
    (is (= :nothing-new (:reason v))
        "an entirely already-committed batch must be distinguishable from
         an entirely rejected one")))

(deftest a-healthy-batch-commits
  (let [rows (map #(assoc clean :object-id (str %)) (range 20))
        v (gov/commit-decision (gov/admit rows now))]
    (is (true? (:commit? v)))
    (is (= 20 (:rows v)))))
