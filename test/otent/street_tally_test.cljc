(ns otent.street-tally-test
  "Offline tests for the derived per-kind tally over provider-published
  street detections: deterministic fixture, ledger reconciliation,
  unknown-count visibility, readback."
  (:require [clojure.test :as t :refer [deftest is testing]]
            ["fs" :as fs]
            [otent.street :as street]
            [otent.street-tally :as tally]))

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

(defn tally-fixture []
  (tally/tally (analyze-fixture)))

(deftest fixture-determinism-test
  (let [a (tally-fixture)
        b (tally-fixture)]
    (is (= a b) "same fixture bytes must tally to the same table")
    (is (= :street-kind-tally (:table a)))
    (is (= "street-detections-kind-tally-v1" (:task-id a)))))

(deftest every-taxonomy-kind-present-test
  (let [a (tally-fixture)]
    (is (= (count street/taxonomy) (count (:per-kind a)))
        "every registered taxonomy kind appears, zeros included")
    (is (= (sort (map name street/taxonomy))
           (sort (map #(:object-value %) (:per-kind a))))
        "tally rows are keyed by the registered taxonomy, not by what happened to appear")
    (is (= (sort (map #(name (:kind %)) (:per-kind a)))
           (sort (map #(name (:kind %)) (:per-kind a))))
        "deterministic ordering")
    (is (apply <= (map #(name (:kind %)) (:per-kind a)))
        "rows sorted by kind name")))

(deftest counts-reconcile-with-upstream-test
  (let [a (tally-fixture)]
    (is (true? (get-in a [:counts :ledger-reconciled?]))
        "observations + refusal ledger must sum to the upstream raw count")
    (is (= 2 (get-in a [:counts :tally-total]))
        "the synthetic fixture yields two in-taxonomy observations")
    (let [pole-row (first (filter #(= :utility-pole (:kind %)) (:per-kind a)))]
      (is (= 1 (:count pole-row)) "the fixture's utility pole is counted")
      (is (= "object--support--utility-pole" (:object-value pole-row))))
    (is (every? zero? (map :count (filter #(zero? (:count %)) (:per-kind a))))
        "zero rows are explicit zeros, not nil")))

(deftest refusal-ledger-visible-test
  (let [a (tally-fixture)
        ledger (into {} (map (juxt :reason :count) (:refusal-ledger a)))]
    (is (= 1 (:privacy-forbidden ledger)) "privacy refusal stays visible")
    (is (= 1 (:label-not-in-taxonomy ledger)) "unknown label stays visible")
    (is (= 1 (:invalid-geometry ledger)) "geometry refusal stays visible")
    (is (= (:refusal-ledger a) (:refusal-ledger (tally/readback a)))
        "the readback carries the ledger unchanged")))

(deftest unknown-counts-test
  (let [a (tally-fixture)]
    (is (= 2 (get-in a [:unknown-counts :confidence-unknown]))
        "the provider publishes no confidence → counted unknown, not imputed")
    (is (= 2 (get-in a [:unknown-counts :spatial-uncertainty-unknown])))))

(deftest provenance-and-bounds-test
  (let [a (tally-fixture)]
    (is (= :none (get-in a [:provenance :provenance/model-id]))
        "no local model inference: stated, not hidden")
    (is (= (get-in (analyze-fixture) [:provenance :provenance/content-hash])
           (get-in a [:provenance :provenance/content-hash]))
        "content hash carried through from the upstream analysis")
    (is (every? string? (:epistemic-bounds a))
        "the epistemic bounds ride with the table")))

(deftest readback-test
  (let [r (tally/readback (tally-fixture))]
    (is (= :street-kind-tally (:table r)))
    (is (= [:kind :object-value :count] (:columns r)))
    (is (= (count street/taxonomy) (count (:rows r))))
    (is (true? (:ledger-reconciled? r)))
    (is (string? (:content-hash r)))))

(deftest bad-input-refused-test
  (is (thrown? :default (tally/tally {:observations "nope" :counts {}}))
      "something that is not an analyze result is refused, not tallied"))
