(ns otent.street-span-test
  "Offline tests for the derived per-kind temporal span over
  provider-published street detections: deterministic fixture, ledger
  reconciliation, unknown-stamp visibility, coordinate-order upstream
  gate, readback."
  (:require [clojure.test :as t :refer [deftest is testing]]
            ["fs" :as fs]
            [otent.street :as street]
            [otent.street-span :as span]))

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

(defn span-fixture []
  (span/span (analyze-fixture)))

(deftest fixture-determinism-test
  (let [a (span-fixture)
        b (span-fixture)]
    (is (= a b) "same fixture bytes must span to the same table")
    (is (= :street-kind-temporal-span (:table a)))
    (is (= "street-detections-temporal-span-v1" (:task-id a)))))

(deftest per-kind-span-values-test
  (let [a (span-fixture)
        by-kind (into {} (map (juxt :kind identity) (:per-kind a)))]
    (is (= 2 (count (:per-kind a)))
        "only kinds with at least one observation get a row; no fabricated nil-date rows")
    (let [pole (:utility-pole by-kind)]
      (is (= 1 (:count pole)))
      (is (= "2025-04-01T00:00:00.000Z" (:earliest-first-seen-at pole)))
      (is (= "2026-08-01T00:00:00.000Z" (:latest-last-seen-at pole)))
      (is (zero? (:first-seen-unknown pole)))
      (is (zero? (:last-seen-unknown pole))))
    (let [adv (:sign-advertisement by-kind)]
      (is (= 1 (:count adv)))
      (is (= "2025-06-11T00:00:00.000Z" (:earliest-first-seen-at adv)))
      (is (= "2026-07-19T00:00:00.000Z" (:latest-last-seen-at adv))))
    (is (apply <= (map (comp str :kind) (:per-kind a)))
        "rows sorted by kind name")))

(deftest counts-reconcile-with-upstream-test
  (let [a (span-fixture)]
    (is (true? (get-in a [:counts :ledger-reconciled?]))
        "observations + refusal ledger must sum to the upstream raw count")
    (is (= 2 (get-in a [:counts :span-total]))
        "the synthetic fixture yields two in-taxonomy observations")))

(deftest refusal-ledger-visible-test
  (let [a (span-fixture)
        ledger (into {} (map (juxt :reason :count) (:refusal-ledger a)))]
    (is (= 1 (:privacy-forbidden ledger)) "privacy refusal stays visible")
    (is (= 1 (:label-not-in-taxonomy ledger)) "unknown label stays visible")
    (is (= 1 (:invalid-geometry ledger)) "geometry refusal stays visible")
    (is (= (:refusal-ledger a) (:refusal-ledger (span/readback a)))
        "the readback carries the ledger unchanged")))

(deftest unknown-stamps-stay-visible-test
  (testing "a redacted/unpublished stamp is an explicit unknown, never imputed"
    (let [analysis (street/analyze
                    {"data"
                     [{"id" "900000000000001"
                       "object_value" "object--street-light"
                       "geometry" {"type" "Point"
                                   "coordinates" [139.6917 35.6894]}}]}
                    {:area-id "fixture-no-stamps"
                     :retrieved-at "2026-09-01T07:34:30.094470+00:00"
                     :input-sha256 "sha"})
          a (span/span analysis)
          row (first (:per-kind a))]
      (is (= 1 (get-in a [:unknown-counts :first-seen-unknown])))
      (is (= 1 (get-in a [:unknown-counts :last-seen-unknown])))
      (is (nil? (:earliest-first-seen-at row))
          "no min is fabricated from a missing stamp")
      (is (nil? (:latest-last-seen-at row)))
      (is (= 1 (:first-seen-unknown row)) "the per-row count keeps it visible"))))

(deftest coordinate-order-gate-applies-upstream-test
  (testing "a swapped lon/lat point is refused upstream and never spans"
    (let [analysis (analyze-fixture)
          a (span-fixture)
          by-kind (into {} (map (juxt :kind identity) (:per-kind a)))]
      (is (= 1 (get-in analysis [:counts :geometry-refusals])))
      (is (= 1 (:count (:utility-pole by-kind)))
          "the swapped-coordinate utility pole is refused upstream, so the kind's count is the one valid observation only"))))

(deftest provenance-and-bounds-test
  (let [a (span-fixture)]
    (is (= :none (get-in a [:provenance :provenance/model-id]))
        "no local model inference: stated, not hidden")
    (is (= (get-in (analyze-fixture) [:provenance :provenance/content-hash])
           (get-in a [:provenance :provenance/content-hash]))
        "content hash carried through from the upstream analysis")
    (is (= "street-detections-temporal-span-v1"
           (get-in a [:provenance :provenance/derived-from])))
    (is (every? string? (:epistemic-bounds a))
        "the epistemic bounds ride with the table")))

(deftest readback-test
  (let [r (span/readback (span-fixture))]
    (is (= :street-kind-temporal-span (:table r)))
    (is (= [:kind :count :earliest-first-seen-at :latest-last-seen-at
            :first-seen-unknown :last-seen-unknown] (:columns r)))
    (is (= 2 (count (:rows r))))
    (is (true? (:ledger-reconciled? r)))
    (is (string? (:content-hash r)))))

(deftest bad-input-refused-test
  (is (thrown? :default (span/span {:observations "nope" :counts {}}))
      "something that is not an analyze result is refused, not spanned"))
