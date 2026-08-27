(ns otent.coverage-test
  "The instrument that reads back a declared interval.

  Every test here is written against the shape of the real defect: a
  cadence that is honoured by the code and defeated by the schedule, and
  therefore invisible to a check that only asks whether the tick succeeded."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.coverage :as cov]))

(def registry
  [{:id :usgs :kind :quake :access :open :min-interval-ms 300000}
   {:id :opensky :kind :aircraft :access :open :min-interval-ms 600000}
   {:id :firms :kind :fire :access :free-key :min-interval-ms 3600000}])

(defn- entries
  "n ticks `gap` apart, in which `feeds` were contacted and `dark` were not."
  [n gap {:keys [contacted dark]}]
  (for [i (range n)]
    {:tick/at (+ 1787800000000 (* i gap))
     :tick/results (concat (for [f contacted] {:feed f :status :committed})
                           (for [f dark] {:feed f :status :unmeasured}))}))

(deftest a-cadence-on-time-is-ok
  (let [r (cov/report {:registry registry
                       :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms]})
                       :now 0
                       :tables {:quake 10 :aircraft 0 :fire :absent}
                       :expected-unmeasured #{"firms"}})
        usgs (first (filter #(= :usgs (:id %)) (:feeds r)))]
    (is (= 300000 (:measured-ms usgs)))
    (is (= :ok (:status usgs)))
    (is (= :ok (:verdict r)))
    (is (zero? (cov/exit-code r)))))

(deftest the-drift-that-started-this
  (testing "a feed polled at 1.5x its declared interval is a finding, even
            though every tick succeeded and `due?` was honoured exactly"
    (let [r (cov/report {:registry registry
                         :entries (entries 40 903000 {:contacted [:opensky] :dark [:firms]})
                         :now 0
                         :tables {:quake 10 :aircraft 5 :fire :absent}
                         :expected-unmeasured #{"firms"}})
          os (first (filter #(= :opensky (:id %)) (:feeds r)))]
      (is (= :drift (:status os)))
      (is (< 1.4 (:ratio os) 1.6))
      (is (= :drift (:verdict r)))
      (is (= 1 (cov/exit-code r)))
      (is (re-find #"declared interval is a floor" (:detail os))))))

(deftest a-tolerance-that-would-pass-the-defect-is-not-a-tolerance
  (is (< cov/default-tolerance 1.49)
      "1.49x is the drift this was written for; a tolerance at or above it
       would have been chosen to make the broken state pass"))

(deftest one-poll-is-not-a-cadence
  (let [r (cov/report {:registry registry
                       :entries (entries 20 300000 {:contacted [] :dark [:firms]})
                       :now 0 :tables {:quake 1 :aircraft 1 :fire :absent}
                       :expected-unmeasured #{"firms"}})
        usgs (first (filter #(= :usgs (:id %)) (:feeds r)))]
    (is (= :unmeasured (:status usgs)))
    (is (nil? (:measured-ms usgs)) "never contacted must not read as an interval of 0")
    (is (re-find #"never contacted" (:detail usgs)))))

(deftest too-few-ticks-refuses-rather-than-dividing-small-numbers
  (let [r (cov/report {:registry registry
                       :entries (entries 3 300000 {:contacted [:usgs] :dark [:firms]})
                       :now 0 :tables {:quake 1 :aircraft 1 :fire :absent}
                       :expected-unmeasured #{"firms"}})]
    (is (= :cannot-answer (:verdict r)))
    (is (= 2 (cov/exit-code r)) "could not answer is 2, never 0 and never 1")))

(deftest unread-tables-are-not-a-clean-run
  (let [r (cov/report {:registry registry
                       :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms]})
                       :now 0 :tables nil
                       :expected-unmeasured #{"firms"}})]
    (is (= :tables-unmeasured (:verdict r)))
    (is (= 2 (cov/exit-code r)))
    (is (some #(re-find #"UNMEASURED" %) (cov/render r)))))

(deftest a-third-feed-going-dark-is-not-absorbed
  (let [r (cov/report {:registry registry
                       :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms :opensky]})
                       :now 0 :tables {:quake 1 :aircraft 1 :fire :absent}
                       :expected-unmeasured #{"firms"}})]
    (is (= ["opensky"] (:unexpected-dark r)))
    (is (= :dark (:verdict r)))
    (is (= 1 (cov/exit-code r)))))

(deftest a-missing-table-under-a-live-feed-is-a-finding-and-under-a-dark-one-is-not
  (testing "fire is absent because nothing could ever read it -- consistent"
    (let [r (cov/report {:registry registry
                         :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms]})
                         :now 0 :tables {:quake 5 :aircraft :absent :fire :absent}
                         :expected-unmeasured #{"firms"}})]
      (is (empty? (:table-findings r))
          "aircraft was never polled in this ledger either, so its absent
           table is consistent too")))
  (testing "a table that vanished under a feed that HAS been committing is not"
    (let [r (cov/report {:registry registry
                         :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms]})
                         :now 0 :tables {:quake :absent :aircraft :absent :fire :absent}
                         :expected-unmeasured #{"firms"}})]
      (is (= [[:quake :absent]] (:table-findings r)))
      (is (= :table-missing (:verdict r)))
      (is (= 1 (cov/exit-code r))))))

(deftest not-due-is-not-evidence-of-polling
  (testing "a feed we chose not to ask must not count as contact, or a
            backoff would look like a cadence"
    (let [es (for [i (range 40)]
               {:tick/at (+ 1787800000000 (* i 300000))
                :tick/results [{:feed :opensky
                                :status (if (zero? (mod i 4)) :committed :not-due)}]})
          r (cov/report {:registry registry :entries es :now 0
                         :tables {:quake 1 :aircraft 1 :fire :absent}
                         :expected-unmeasured #{"firms"}})
          os (first (filter #(= :opensky (:id %)) (:feeds r)))]
      (is (= 1200000 (:measured-ms os))
          "four ticks between commits is a 20-minute cadence, not a 5-minute one")
      (is (= :drift (:status os))))))

(deftest the-median-does-not-let-one-outage-restate-the-cadence
  (let [ats (concat (range 0 (* 20 300000) 300000)
                    [(+ (* 20 300000) 9000000)])
        es (for [a ats] {:tick/at (+ 1787800000000 a)
                         :tick/results [{:feed :usgs :status :committed}]})
        r (cov/report {:registry registry :entries es :now 0
                       :tables {:quake 1 :aircraft 1 :fire :absent}
                       :expected-unmeasured #{"firms"}})
        usgs (first (filter #(= :usgs (:id %)) (:feeds r)))]
    (is (= 300000 (:measured-ms usgs)))
    (is (= :ok (:status usgs)) "one 2.5-hour gap is an outage, not a cadence")))

(deftest one-blip-is-not-darkness
  (testing "the first version of dark-now was `unmeasured at any point`,
            which reported a feed that failed 3 polls in 90 as being as dark
            as one that has never been readable -- and would have made this
            command permanently red, hence permanently ignorable"
    (let [es (for [i (range 40)]
               {:tick/at (+ 1787800000000 (* i 300000))
                :tick/results [{:feed :usgs :status (if (= i 7) :unmeasured :committed)}
                               {:feed :firms :status :unmeasured}]})
          r (cov/report {:registry registry :entries es :now 0
                         :tables {:quake 1 :aircraft 1 :fire :absent}
                         :expected-unmeasured #{"firms"}})
          usgs (first (filter #(= :usgs (:id %)) (:feeds r)))]
      (is (= ["firms"] (:dark r)))
      (is (empty? (:unexpected-dark r)))
      (is (= 1 (:unmeasured usgs)))
      (is (< 0.95 (:reachability usgs) 1.0))
      (is (empty? (:flaky r)) "one in forty is a blip, not a pattern")
      (is (= :ok (:verdict r))))))

(deftest a-pattern-of-failure-is-a-finding
  (testing "half of a feed's attempts unreadable is not a blip.

            The feed here is one whose declared interval is far longer than
            the tick, so the failures do NOT also show up as drift -- which
            they normally would, because a feed that fails every other
            attempt is by definition contacted half as often as it is
            asked. Separating them here is what makes this test about
            reachability rather than about cadence."
    (let [es (for [i (range 40)]
               {:tick/at (+ 1787800000000 (* i 300000))
                :tick/results [{:feed :usgs :status :committed}
                               {:feed :firms :status (if (zero? (mod i 2))
                                                       :unmeasured :committed)}]})
          r (cov/report {:registry registry :entries es :now 0
                         :tables {:quake 1 :aircraft 1 :fire 3}
                         :expected-unmeasured #{"firms"}})
          firms (first (filter #(= :firms (:id %)) (:feeds r)))]
      (is (= [:firms] (:flaky r)))
      (is (< 0.45 (:reachability firms) 0.55)
          "counted from its first successful contact, not from the top of
           the ledger -- so the denominator is one tick short of half")
      (is (= :ok (:status firms)) "600s of contact gap against a 3600s declared
                                   interval is not drift")
      (is (= :flaky (:verdict r)))
      (is (= 1 (cov/exit-code r)))
      (is (some #(re-find #"reachable 5\d%" %)
                (map #(nth % 2) (:findings r)))))))

(deftest a-window-lets-a-repair-be-seen-and-names-itself
  (testing "a 30-hour median cannot show a cadence fixed an hour ago, so an
            instrument without a window cannot show a schedule being repaired"
    (let [old (for [i (range 100)]                        ; 30h of 450s ticks
                {:tick/at (+ 1787700000000 (* i 450000))
                 :tick/results [{:feed :usgs :status :committed}]})
          new (for [i (range 20)]                         ; the last 1.6h, fixed
                {:tick/at (+ 1787745000000 (* i 300000))
                 :tick/results [{:feed :usgs :status :committed}]})
          now (+ 1787745000000 (* 20 300000))
          all (concat old new)
          whole (cov/report {:registry registry :entries all :now now
                             :tables {:quake 1 :aircraft 1 :fire 1}
                             :expected-unmeasured #{"firms"}})
          recent (cov/report {:registry registry :entries all :now now
                              :window-ms 7200000
                              :tables {:quake 1 :aircraft 1 :fire 1}
                              :expected-unmeasured #{"firms"}})
          f #(first (filter (fn [x] (= :usgs (:id x))) (:feeds %)))]
      (is (= :drift (:status (f whole))) "the whole ledger still carries the old cadence")
      (is (= :ok (:status (f recent))) "the window shows the repair")
      (is (some #(re-find #"window=2.0h" %) (cov/render recent))
          "and the window is named, so a reader can see which question was asked")
      (is (some #(re-find #"window=the whole ledger" %) (cov/render whole))))))

(deftest a-window-too-narrow-to-measure-refuses
  (let [es (for [i (range 100)]
             {:tick/at (+ 1787700000000 (* i 450000))
              :tick/results [{:feed :usgs :status :committed}]})
        now (+ 1787700000000 (* 100 450000))
        r (cov/report {:registry registry :entries es :now now :window-ms 900000
                       :tables {:quake 1 :aircraft 1 :fire 1}
                       :expected-unmeasured #{"firms"}})]
    (is (= :cannot-answer (:verdict r)))
    (is (= 2 (cov/exit-code r)) "a window that holds three ticks answers nothing")))

(deftest durations-that-are-not-durations-are-nil
  (is (= 10800000 (cov/parse-window "3h")))
  (is (= 2700000 (cov/parse-window "45m")))
  (is (= 90000 (cov/parse-window "90s")))
  (is (= 5000 (cov/parse-window "5000")))
  (is (nil? (cov/parse-window "soon")))
  (is (nil? (cov/parse-window ""))))

(deftest a-feed-that-just-came-online-is-not-flaky
  (testing "firms went live on 2026-08-27 after a free key was entered, and
            counting the whole ledger called it `reachable 1% of the time`
            -- true about the history and false about the feed. Every newly
            enabled feed would read as broken for a day."
    (let [es (concat
              ;; 30 ticks before the key existed
              (for [i (range 30)]
                {:tick/at (+ 1787800000000 (* i 300000))
                 :tick/results [{:feed :usgs :status :committed}
                                {:feed :firms :status :unmeasured}]})
              ;; 20 ticks after, all fine
              (for [i (range 20)]
                {:tick/at (+ 1787800000000 (* (+ 30 i) 300000))
                 :tick/results [{:feed :usgs :status :committed}
                                {:feed :firms :status :committed}]}))
          r (cov/report {:registry registry :entries es :now 0
                         :tables {:quake 1 :aircraft 1 :fire 1}
                         :expected-unmeasured #{"firms"}})
          firms (first (filter #(= :firms (:id %)) (:feeds r)))]
      (is (= 1 (:reachability firms))
          "since it started working, it has always worked")
      (is (zero? (:unmeasured firms))
          "the runs before it was enabled were not failures")
      (is (empty? (:flaky r)))
      (is (= :ok (:verdict r))))))

(deftest a-feed-that-never-worked-has-no-reachability-to-report
  (let [es (for [i (range 40)]
             {:tick/at (+ 1787800000000 (* i 300000))
              :tick/results [{:feed :usgs :status :committed}
                             {:feed :firms :status :unmeasured}]})
        r (cov/report {:registry registry :entries es :now 0
                       :tables {:quake 1 :aircraft 1 :fire :absent}
                       :expected-unmeasured #{"firms"}})
        firms (first (filter #(= :firms (:id %)) (:feeds r)))]
    (is (nil? (:reachability firms))
        "no denominator rather than a denominator of everything")
    (is (= :unmeasured (:status firms)))
    (is (empty? (:flaky r)) "never having run is what :unmeasured already says")))

(deftest a-long-kind-name-does-not-run-into-its-row-count
  (testing "`vessel-static` is 13 characters and the column was 10, so the
            rendered line read `vessel-static2070` -- one token, with no
            way to tell where the name ends and the number begins"
    (let [r (cov/report {:registry registry
                         :entries (entries 40 300000 {:contacted [:usgs] :dark [:firms]})
                         :now 0
                         :tables {:quake 213 :vessel-static 2070 :vessel-risk 23173}
                         :expected-unmeasured #{"firms"}})
          out (cov/render r)]
      (is (some #(re-find #"vessel-static\s+2070" %) out))
      (is (some #(re-find #"vessel-risk\s+23173" %) out))
      (is (not-any? #(re-find #"vessel-static\d" %) out)))))
