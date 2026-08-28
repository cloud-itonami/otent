(ns otent.watchlist-test
  (:require [clojure.test :refer [deftest is testing]]
            [otent.watchlist :as w]))

(def risk [{:imo "9260823" :mmsi "273123456" :risk "mare.shadow;sanction"}
           {:imo "9513139" :risk "sanction"}
           {:mmsi "636099999" :risk "mare.shadow"}
           {:risk "mare.detained"}])

(deftest both-keys-are-kept
  (testing "754 of 23,191 risk records carry neither identifier, and the ones
            with only an MMSI are exactly the vessels whose identity is most
            obscured -- the population this list is for"
    (let [wl (w/build risk)]
      (is (= #{"273123456" "636099999"} (:mmsi wl)))
      (is (= #{"9260823" "9513139"} (:imo wl))))))

(deftest a-position-report-is-matched-on-mmsi
  (let [wl (w/build risk)]
    (is (w/watched? {:mmsi "273123456"} wl))
    (is (w/watched? {:mmsi 636099999} wl) "numeric MMSI from the wire")
    (is (not (w/watched? {:mmsi "999999999"} wl)))
    (is (not (w/watched? {} wl)) "no MMSI is not a match")))

(deftest an-imo-only-entry-does-not-match-a-position-report
  (testing "a PositionReport carries no IMO number. Treating the IMO set as
            matchable would silently never fire, and a filter that never
            fires looks exactly like a quiet ocean."
    (let [wl (w/build risk)]
      (is (not (w/watched? {:mmsi "9513139"} wl))
          "an IMO used as an MMSI must not match"))))

(deftest an-empty-watchlist-is-refused-rather-than-run
  (testing "a collector sitting on a global stream recording nothing is
            indistinguishable, in the table afterwards, from an ocean with
            nothing on it"
    (is (w/empty-list? (w/build [])))
    (is (w/empty-list? (w/build [{:imo "9260823"}])) "IMOs alone cannot match")
    (is (not (w/empty-list? (w/build risk))))))
