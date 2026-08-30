(ns otent.street-test
  "Offline tests for the provider-published street-detection analysis:
  deterministic fixture, privacy rejection, unknown-label counting,
  coordinate-order refusal, provenance readback, derived-table readback."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [otent.street :as street]
            ["fs" :as fs]))

(def fixture-path
  "test/otent/fixtures/street_map_features_synthetic.json")

(defn fixture-json []
  (-> (fs/readFileSync fixture-path "utf8")
      (js/JSON.parse)
      (js->clj :keywordize-keys true)))

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

(deftest fixture-determinism-test
  (let [a (analyze-fixture)
        b (analyze-fixture)]
    (is (= a b) "same fixture bytes must analyze to the same table")
    (is (= 5 (get-in a [:counts :raw-count])))
    (is (= 2 (get-in a [:counts :observations]))
        "two in-taxonomy, valid-geometry features")
    (is (= 1 (get-in a [:counts :unknown-labels])))
    (is (= 1 (get-in a [:counts :privacy-refusals])))
    (is (= 1 (get-in a [:counts :geometry-refusals])))
    ;; visibility: nothing silently dropped — counts must sum
    (is (= 5 (+ (get-in a [:counts :observations])
                (get-in a [:counts :unknown-labels])
                (get-in a [:counts :privacy-refusals])
                (get-in a [:counts :geometry-refusals]))))))

(deftest privacy-rejection-test
  (let [a (analyze-fixture)
        r (first (filter #(= :privacy-forbidden (:refusal/reason %)) (:refusals a)))]
    (is (some? r) "the synthetic face-labelled feature is refused")
    (is (= "100000000000004" (:refusal/feature-id r)))
    (is (empty? (filter #(= "100000000000004" (:obs/source-id %)) (:observations a)))
        "refused features never become observations")
    ;; the refusal carries no coordinates and no geometry at all
    (is (not (contains? r :refusal/lon)))
    (is (not (contains? r :refusal/lat)))))

(deftest unknown-label-test
  (let [a (analyze-fixture)
        r (first (filter #(= :label-not-in-taxonomy (:refusal/reason %)) (:refusals a)))]
    (is (some? r) "an out-of-taxonomy label is refused, not dropped")
    (is (= "object--not-in-registered-taxonomy" (:refusal/object-value r)))
    (is (empty? (filter #(= "object--not-in-registered-taxonomy" (:obs/object-value %))
                        (:observations a))))
    (is (every? #(contains? % :obs/confidence) (:observations a))
        "every observation carries an explicit confidence slot")
    (is (every? #(= :unknown (:obs/confidence %)) (:observations a))
        "provider publishes no confidence → :unknown, visible")
    (is (every? #(= :unknown (:obs/spatial-uncertainty-m %)) (:observations a)))))

(deftest coordinate-order-test
  (let [a (analyze-fixture)
        r (first (filter #(= :invalid-geometry (:refusal/reason %)) (:refusals a)))]
    (is (some? r) "a [lat lon] swapped point is refused, not repaired")
    (is (= "100000000000005" (:refusal/feature-id r))
        "35.6896 lat placed in the lon slot (139.69 in the lat slot) is out of range")
    (is (empty? (filter #(= "100000000000005" (:obs/source-id %)) (:observations a))))
    (is (street/valid-point? {"type" "Point" "coordinates" [139.69171 35.68946]}))
    (is (not (street/valid-point? {"type" "Point" "coordinates" [200.0 35.0]})))
    (is (not (street/valid-point? {"type" "Point" "coordinates" [139.0 120.0]}))
        "lat out of [-90,90] is invalid, not repaired")
    (is (not (street/valid-point? {"type" "Polygon" "coordinates" [0 0]}))
        "non-Point geometry is not a pole observation")))

(deftest provenance-readback-test
  (let [p (:provenance (analyze-fixture))]
    (is (= :otent-geospatial-vision (:provenance/system-id p)))
    (is (= "mapillary" (:provenance/source-id p)))
    (is (= "map_features:fixture-synthetic" (:provenance/asset-id p)))
    (is (= :unknown (:provenance/capture-time p))
        "tile has no single capture time; observations carry their own")
    (is (str/starts-with? (:provenance/licence p) "Mapillary API Terms"))
    (is (string? (:provenance/content-hash p)) "input bytes are hashed")
    (is (= 64 (count (:provenance/content-hash p))) "a sha256 hex digest")
    (is (= "provider-published-detection" (:provenance/model-id p)))
    (is (= :unknown (:provenance/model-artifact-hash p))
        "no local artifact → no pinned hash, stated explicitly")
    (is (= "EPSG:4326 (GeoJSON lon,lat order)" (:provenance/crs p)))))

(deftest derived-table-readback-test
  (let [a (analyze-fixture)
        dt (street/derived-table a)]
    (is (= :street-feature-observations (:table dt)))
    (is (= 2 (count (:rows dt))))
    (is (every? #(contains? % :obs/source-id) (:rows dt)))
    (is (every? #(contains? % :obs/spatial-uncertainty-m) (:rows dt)))
    (is (= (:provenance/content-hash (:provenance a))
           (:provenance-hash-of-asset dt)))
    ;; round-trip: a derived row re-read gives the same observation
    (let [row (first (filter #(= "100000000000002" (:obs/source-id %)) (:rows dt)))]
      (is (some? row))
      (is (= 35.68952 (:obs/lat row))
          "the surviving advertisement observation keeps its lat in the lat slot")
      (is (= 139.69205 (:obs/lon row))))))

(deftest observation-bounds-test
  (let [obs (first (:observations (analyze-fixture)))]
    (is (str/includes? (:obs/interpretation obs) "not identity")
        "the observation carries its own epistemic boundary")
    (is (= :unknown (:obs/spatial-uncertainty-m obs)))
    (is (:obs/evidence-url obs))))
