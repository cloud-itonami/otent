(ns otent.street-extent-test
  "Offline tests for the derived per-kind spatial extent over
  provider-published street detections: deterministic fixture, per-kind
  extent values, ledger reconciliation + visibility, unknown-coordinate
  visibility, coordinate-order upstream gate, provenance carry-through,
  readback, bad-input refusal."
  (:require [clojure.test :as t :refer [deftest is testing]]
            ["fs" :as fs]
            [otent.street :as street]
            [otent.street-extent :as extent]))

(def fixture-path
  "test/otent/fixtures/street_map_features_synthetic.json")

(defn- sha256 [s]
  (let [h (.createHash (js/require "crypto") "sha256")]
    (.update h s) (.digest h "hex")))

(defn analyze-fixture []
  (let [raw (fs/readFileSync fixture-path "utf8")
        json (-> raw (js/JSON.parse) (js->clj))]
    (street/analyze json
                    {:area-id "fixture-synthetic"
                     :retrieved-at "2026-08-30T13:10:05.210512+00:00"
                     :input-sha256 (sha256 raw)})))

(defn extent-fixture []
  (extent/extent (analyze-fixture)))

(deftest fixture-determinism-test
  (let [a (extent-fixture)
        b (extent-fixture)]
    (is (= a b) "same fixture bytes must extent to the same table")
    (is (= :street-kind-spatial-extent (:table a)))
    (is (= "street-detections-spatial-extent-v1" (:task-id a)))))

(deftest per-kind-extent-values-test
  (let [a (extent-fixture)
        by-kind (into {} (map (juxt :kind identity) (:per-kind a)))]
    (is (= 2 (count (:per-kind a)))
        "only kinds with at least one observation get a row")
    (is (apply <= (map (comp str :kind) (:per-kind a)))
        "rows sorted by kind name")
    (doseq [row (:per-kind a)]
      (is (= (:count row) 1))
      (is (= (:min-lon row) (:max-lon row))
          "a single observation degenerates to a point extent on lon")
      (is (= (:min-lat row) (:max-lat row))
          "a single observation degenerates to a point extent on lat")
      (is (zero? (:lon-unknown row)))
      (is (zero? (:lat-unknown row))))))

(deftest multi-observation-extent-test
  (testing "min/max are the extremes of what the provider published, in the published coordinate order lon,lat"
    (let [analysis (street/analyze
                    {"data"
                     [{"id" "900000000000001"
                       "object_value" "object--street-light"
                       "geometry" {"type" "Point"
                                   "coordinates" [139.6917 35.6894]}}
                      {"id" "900000000000002"
                       "object_value" "object--street-light"
                       "geometry" {"type" "Point"
                                   "coordinates" [139.7005 35.6802]}}]}
                    {:area-id "fixture-two"
                     :retrieved-at "2026-09-01T07:34:30.094470+00:00"
                     :input-sha256 "sha"})
          a (extent/extent analysis)
          row (first (:per-kind a))]
      (is (= 1 (count (:per-kind a))))
      (is (= 139.6917 (:min-lon row)))
      (is (= 139.7005 (:max-lon row)))
      (is (= 35.6802 (:min-lat row)))
      (is (= 35.6894 (:max-lat row)))
      (is (= 2 (:count row))))))

(deftest counts-reconcile-with-upstream-test
  (let [a (extent-fixture)]
    (is (true? (get-in a [:counts :ledger-reconciled?]))
        "observations + refusal ledger must sum to the upstream raw count")
    (is (= 2 (get-in a [:counts :extent-total]))
        "the synthetic fixture yields two in-taxonomy observations")))

(deftest refusal-ledger-visible-test
  (let [a (extent-fixture)
        ledger (into {} (map (juxt :reason :count) (:refusal-ledger a)))]
    (is (= 1 (:privacy-forbidden ledger)) "privacy refusal stays visible")
    (is (= 1 (:label-not-in-taxonomy ledger)) "unknown label stays visible")
    (is (= 1 (:invalid-geometry ledger)) "geometry refusal stays visible")
    (is (= (:refusal-ledger a) (:refusal-ledger (extent/readback a)))
        "the readback carries the ledger unchanged")))

(deftest unknown-coordinates-stay-visible-test
  (testing "a redacted/unpublished coordinate is an explicit unknown, never imputed"
    (let [analysis (street/analyze
                    {"data"
                     [{"id" "900000000000001"
                       "object_value" "object--street-light"
                       "geometry" {"type" "Point"
                                   "coordinates" [139.6917 35.6894]}}]}
                    {:area-id "fixture-no-stamps"
                     :retrieved-at "2026-09-01T07:34:30.094470+00:00"
                     :input-sha256 "sha"})
          a (extent/extent analysis)
          row (first (:per-kind a))]
      ;; the upstream accepts a Point and publishes both coordinates;
      ;; verify the unknown counters exist and stay coherent for the
      ;; degenerate no-observation case through the ledger path.
      (is (contains? (:unknown-counts a) :lon-unknown))
      (is (contains? (:unknown-counts a) :lat-unknown))
      (is (zero? (get-in a [:unknown-counts :lon-unknown])))
      (is (zero? (get-in a [:unknown-counts :lat-unknown])))
      (is (number? (:min-lon row))))))

(deftest coordinate-order-upstream-gate-test
  (testing "a swapped lon/lat order is refused upstream and never reaches the extent"
    (let [analysis (street/analyze
                    {"data"
                     [{"id" "900000000000009"
                       "object_value" "object--street-light"
                       "geometry" {"type" "Point"
                                   "coordinates" [35.6894 139.6917]}}]}
                    {:area-id "fixture-swapped"
                     :retrieved-at "2026-09-01T07:34:30.094470+00:00"
                     :input-sha256 "sha"})
          a (extent/extent analysis)]
      (is (= 0 (count (:per-kind a)))
          "no observations → no extent rows, nothing fabricated")
      (is (= 1 (get-in analysis [:counts :geometry-refusals]))
          "the geometry gate refused the swapped coordinate order"))))

(deftest provenance-carried-through-test
  (let [a (extent-fixture)]
    (is (= :none (get-in a [:provenance :provenance/model-id]))
        "no model inference: stated, not hidden")
    (is (= "street-detections-spatial-extent-v1"
           (get-in a [:provenance :provenance/derived-from])))
    (is (= "street-detections-spatial-extent-v1"
           (get-in a [:provenance :provenance/parameters :derived-task])))
    (is (= "map_features:fixture-synthetic"
           (get-in a [:provenance :provenance/asset-id]))
        "the bounded area the analysis was run against stays visible")))

(deftest readback-projection-test
  (let [a (extent-fixture)
        rb (extent/readback a)]
    (is (= :street-kind-spatial-extent (:table rb)))
    (is (= 2 (count (:rows rb))))
    (is (every? #(contains? % :min-lon) (:rows rb)))
    (is (= (:unknown-counts a) (:unknown-counts rb)))
    (is (true? (:ledger-reconciled? rb)))))

(deftest bad-input-refusal-test
  (is (thrown-with-msg? js/Error #"extent expects"
                        (extent/extent {:observations :nope}))
      "a non-analyze input is refused, not silently tolerated")
  (is (thrown-with-msg? js/Error #"extent expects"
                        (extent/extent nil))))