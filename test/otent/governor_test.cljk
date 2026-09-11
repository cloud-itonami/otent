(ns otent.governor-test
  "The governor, and specifically whether each rule can fire.

  A rule that has never rejected anything for the reason it names is not a
  rule. Every test here therefore asserts BOTH directions: a clean row is
  admitted, and a row broken in exactly the way the rule describes is held
  **with that rule's reason** -- not merely held."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.governor :as gov]
            [otent.observation :as obs]))

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

(deftest already-committed-rows-do-not-spend-the-ceiling
  ;; Measured 2026-08-26 against the live USGS feed: a poll two and a half
  ;; hours after the last one returned 46 quakes, 42 of them already in the
  ;; table and 4 genuinely new. The ceiling counted the 42 and refused the
  ;; batch at 91%, so four new earthquakes were dropped for arriving next
  ;; to forty-two old ones. Scheduling the tick would have made this the
  ;; normal outcome for every slow feed.
  (let [old-rows (map #(assoc clean :object-id (str "old" %)
                              :observed-at (- now 3600000))
                      (range 42))
        new-rows (map #(assoc clean :object-id (str "new" %)
                              :observed-at (- now 30000))
                      (range 4))
        r (gov/admit (concat old-rows new-rows) now
                     {:watermark-ms (- now 60000)})
        v (gov/commit-decision r)]
    (testing "the 42 are held, and held for being already committed"
      (is (= 42 (:already-committed (:counts r)))))
    (testing "and the four new ones are committed anyway"
      (is (true? (:commit? v))
          (str "refused a healthy repeated poll: " (pr-str v)))
      (is (= 4 (:rows v))))))

(deftest the-ceiling-still-catches-a-broken-parser-behind-a-repeated-poll
  ;; The other direction, and the one that matters: dedup no longer spends
  ;; the ceiling, so the ceiling has to still fire on the rows that a poll
  ;; could actually have contributed. Without this, the fix above would
  ;; have quietly disabled the rule whenever a feed was polled twice.
  (let [old-rows (map #(assoc clean :object-id (str "old" %)
                              :observed-at (- now 3600000))
                      (range 42))
        bad-rows (map #(assoc clean :object-id (str "bad" %)
                              :observed-at (- now 30000) :lat 999.0)
                      (range 9))
        ok-rows  (map #(assoc clean :object-id (str "ok" %)
                              :observed-at (- now 30000))
                      (range 3))
        r (gov/admit (concat old-rows bad-rows ok-rows) now
                     {:watermark-ms (- now 60000)})
        v (gov/commit-decision r)]
    (testing "9 of the 12 contributable rows are bad -- 75%, over the ceiling"
      (is (false? (:commit? v)))
      (is (= :held-fraction-too-high (:reason v))))
    (testing "and the message counts the contributable rows, not the payload,
              so a reader is not told 51 of 54 and left to guess"
      (is (re-find #"9 of 12" (:detail v))
          (str "the detail still counts already-committed rows: " (:detail v)))
      (is (re-find #"further 42 were already committed" (:detail v))))))

(deftest the-known-kinds-live-in-exactly-one-place
  (testing "the governor held a second inline copy of this set, and the two
            agreed only because nobody had added a kind since they were
            written. Adding one meant editing both, and the governor was the
            copy that fails SILENTLY -- every row of the new kind held as
            :unknown-kind, which reads like a parser fault rather than a set
            nobody updated."
    (is (contains? obs/kinds :vessel-static))
    (doseq [k obs/kinds]
      (let [row (obs/observation {:kind k :object-id "x" :observed-at 1787800000000
                                  :attrs {} :source "s"
                                  :source-url "https://example.test"
                                  :fetched-at 1787800000000
                                  :payload-sha256 "abc"})
            v (gov/check-row row 1787800001000)]
        (is (not= :unknown-kind (:reason v))
            (str "kind " k " is in obs/kinds and the governor rejects it"))))))
