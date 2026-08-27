(ns otent.feeds-due-test
  "`:min-interval-ms` was declared per feed and read by nothing.

  These pin the two directions plus the two edges that decide whether a
  scheduler is a control or a decoration."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.feeds.core :as feeds]))

(def celestrak (feeds/by-id :celestrak))   ; 6h
(def opensky   (feeds/by-id :opensky))     ; 1m
(def aisstream (feeds/by-id :aisstream))   ; 0

(def now 1787716707921)

(deftest never-contacted-is-due
  (testing "nil admits -- a first run must look, and must not be confused
            with a satisfied interval"
    (is (true? (feeds/due? celestrak now nil)))
    (is (zero? (feeds/next-due-in-ms celestrak now nil)))))

(deftest inside-the-interval-is-not-due
  (is (false? (feeds/due? celestrak now (- now 60000))))
  (is (= (- 21600000 60000) (feeds/next-due-in-ms celestrak now (- now 60000)))))

(deftest at-the-boundary-is-due
  (testing "exactly one interval later counts as due -- >= not >, so a
            scheduler firing on the interval is never one tick short
            forever"
    (is (true? (feeds/due? celestrak now (- now 21600000))))
    (is (zero? (feeds/next-due-in-ms celestrak now (- now 21600000))))))

(deftest a-faster-feed-is-due-when-a-slower-one-is-not
  (testing "the whole point: one tick, different answers per feed.
            Derived from the intervals rather than pinned to a literal --
            the first version hard-coded two minutes, which stopped
            discriminating the day OpenSky moved from one minute to ten."
    (let [fast (:min-interval-ms opensky)
          slow (:min-interval-ms celestrak)
          last (- now (inc fast))]            ; just past the faster interval
      (is (< fast slow) "the fixture needs one feed faster than the other")
      (is (true?  (feeds/due? opensky   now last)))
      (is (false? (feeds/due? celestrak now last))))))

(deftest zero-interval-is-always-due
  (testing "a feed that declares no interval must not be silently backed
            off -- 0 and nil are different, and both admit"
    (is (true? (feeds/due? aisstream now now)))
    (is (true? (feeds/due? {:id :x} now now)))))

(deftest every-registry-entry-declares-an-interval
  (testing "a feed with no :min-interval-ms would be polled at whatever
            rate the scheduler runs at, silently"
    (doseq [f feeds/registry]
      (is (number? (:min-interval-ms f))
          (str (name (:id f)) " has no :min-interval-ms")))))

;; ── the feed that says "you already have this" with a 403 ────────────────────

(def celestrak-not-modified
  (str "GP data has not updated since your last successful\n"
       "download of GROUP=active at 2026-08-26 07:15:15 UTC.\n"
       "Data is updated once every 2 hours.\n"))

(deftest celestraks-403-is-an-observation-not-a-failure
  (testing "reading it as a failure would report a sky that had not moved as
            a sky nobody could see -- the one confusion this repository
            exists to avoid"
    (is (true? (feeds/not-modified? celestrak 403 celestrak-not-modified)))))

(deftest a-real-403-still-counts-as-not-observed
  (testing "blocked, rate-limited or banned carries a different body, and
            that one really is a failure to observe"
    (is (false? (feeds/not-modified? celestrak 403 "Forbidden")))
    (is (false? (feeds/not-modified? celestrak 403 "")))
    (is (false? (feeds/not-modified? celestrak 403 nil)))))

(deftest another-status-is-not-the-signal
  (is (false? (feeds/not-modified? celestrak 500 celestrak-not-modified)))
  (is (false? (feeds/not-modified? celestrak 429 celestrak-not-modified))))

(deftest a-feed-without-the-declaration-never-takes-this-path
  (testing "USGS has no :not-modified, so nothing it returns may be read as
            `you already have this`"
    (is (nil? (:not-modified (feeds/by-id :usgs))))
    (is (false? (feeds/not-modified? (feeds/by-id :usgs) 403 celestrak-not-modified)))))

(deftest every-feed-declares-what-it-leaves-out
  (testing "the `2.5_day` ceiling was invisible because a narrowed request
            and a quiet world produce the same rows. A scope that is not
            written down is one nobody can argue with."
    (doseq [f feeds/registry]
      (is (string? (:scope f)) (str (name (:id f)) " declares no :scope"))
      (is (< 80 (count (str (:scope f))))
          (str (name (:id f)) " :scope is too short to say what is excluded")))))

(deftest the-scope-of-the-feed-that-was-narrowed-names-the-narrowing
  (let [usgs (feeds/by-id :usgs)]
    (is (re-find #"all_day" (:url usgs)))
    (is (re-find #"2.5_day" (:scope usgs))
        "the scope has to carry the history of the ceiling that was removed,
         or the next person re-narrows it")))
