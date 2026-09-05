(ns otent.panoramax-daylight-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [otent.panoramax :as px]
            [otent.panoramax-daylight :as pxd]
            [otent.solar-elevation :as solar]))

(def bbox [139.765 35.675 139.77 35.68])

(defn- prov []
  (px/provenance {:area-id "bbox-139.765-35.675-139.77-35.68"
                  :bbox bbox
                  :retrieved-at "2026-09-01T04:00:00.000Z"
                  :input-sha256 "deadbeef"}))

(defn- obs [id datetime lon lat]
  {:observation/kind :imagery-asset
   :observation/asset-id (str "panoramax-item:" id)
   :observation/capture-time datetime
   :observation/ingested-at "2026-09-01T04:00:00.000Z"
   :observation/footprint {:type "Point" :coordinates [lon lat]}
   :observation/licence "CC-BY-SA-4.0"})

(defn- run-fixture []
  {:observations
   [(obs "day" "2026-06-10T03:00:00+00:00" 139.766 35.676)      ; 12:00 JST — daylight
    (obs "night" "2026-06-10T16:00:00+00:00" 139.767 35.677)    ; 01:00 JST — night
    (obs "badtime" "not-a-time" 139.768 35.678)]
   :counts {:fetched 3 :accepted 3 :refused 0 :returned-outside-bbox 0
            :links-next false}
   :provenance (prov)})

(deftest task-identity
  (is (= "panoramax-capture-daylight-v1" pxd/task-id))
  (is (= "panoramax" pxd/source-id))
  (is (= #{:daylight :civil-twilight :night} pxd/label-taxonomy)))

(deftest solar-model-known-instant
  (testing "Tokyo solar noon on the June solstice is well above the horizon"
    (let [t (solar/parse-utc-datetime "2026-06-21T03:00:00+00:00")
          elev (solar/solar-elevation-deg t 139.767 35.677)]
      (is (:ok? t))
      (is (> elev 70)))
    (testing "and local solar midnight is below the −6° twilight floor"
      (let [t (solar/parse-utc-datetime "2026-06-21T16:00:00+00:00")
            elev (solar/solar-elevation-deg t 139.767 35.677)]
        (is (< elev -20))
        (is (> elev -40))))))

(deftest solar-model-parse-refuses-foreign-formats
  (is (not (:ok? (solar/parse-utc-datetime "2026-06-10T03:00:00Z"))))
  (is (not (:ok? (solar/parse-utc-datetime "2026-06-10 03:00:00"))))
  (is (not (:ok? (solar/parse-utc-datetime nil)))))

(deftest classify-thresholds
  (is (= :daylight (solar/classify 0.1)))
  (is (= :civil-twilight (solar/classify 0.0)))
  (is (= :civil-twilight (solar/classify -6.0)))
  (is (= :night (solar/classify -6.1))))

(deftest table-determinism
  (let [a (pxd/daylight-table (run-fixture))
        b (pxd/daylight-table (run-fixture))]
    (is (= a b) "same inputs, same table — the model is deterministic")
    (is (= {:daylight 1 :civil-twilight 0 :night 1} (:table/classes a)))))

(deftest unknown-stays-visible
  (let [table (pxd/daylight-table (run-fixture))]
    (is (= 1 (:capture-unknown (:table/photos table))))
    (is (= 2 (:classified (:table/photos table))))
    (is (= 3 (:accepted (:table/photos table))))))

(deftest all-unknown-table
  (let [table (pxd/daylight-table
               {:observations [(obs "x" "garbage" 139.766 35.676)]
                :counts {:fetched 1 :accepted 1 :refused 0
                         :returned-outside-bbox 0 :links-next false}
                :provenance (prov)})]
    (is (= {:daylight 0 :civil-twilight 0 :night 0} (:table/classes table)))
    (is (= 1 (:capture-unknown (:table/photos table))))))

(deftest geometry-unknown-counted
  (let [table (pxd/daylight-table
               {:observations [(assoc (obs "g" "2026-06-10T03:00:00+00:00" 139.766 35.676)
                                      :observation/footprint :bad)]
                :counts {:fetched 1 :accepted 1 :refused 0
                         :returned-outside-bbox 0 :links-next false}
                :provenance (prov)})]
    (is (= 1 (:geometry-unknown (:table/photos table))))))

(deftest model-pinned-in-table-and-provenance
  (let [fixture (run-fixture)
        table (pxd/daylight-table fixture)
        dprov (pxd/provenance (:provenance fixture)
                              {:run-at "2026-09-01T04:00:01.000Z"
                               :artifact-hash "abc123"})]
    (is (= "otent-solar-elevation-noaa-lowprec" (:model-id (:table/model table))))
    (is (= 0.0 (get-in table [:table/model :parameters :daylight-threshold-deg])))
    (is (= -6.0 (get-in table [:table/model :parameters :civil-twilight-floor-deg])))
    (is (= 2.0 (get-in table [:table/model :parameters
                              :elevation-uncertainty-deg])
            (get-in dprov [:provenance/model-parameters :elevation-uncertainty-deg])))
    (is (= "abc123" (:provenance/model-artifact-hash dprov)))
    (is (= "otent-solar-elevation-noaa-lowprec" (:provenance/model-id dprov)))))

(deftest artifact-hash-unknown-stated-not-hidden
  (let [dprov (pxd/provenance (prov) {:run-at "2026-09-01T04:00:01.000Z"
                                      :artifact-hash :unknown})]
    (is (= :unknown (:provenance/model-artifact-hash dprov)))))

(deftest provenance-readback
  (let [fixture (run-fixture)
        dprov (pxd/provenance (:provenance fixture)
                              {:run-at "2026-09-01T04:00:01.000Z"
                               :artifact-hash "abc123"})]
    (is (= :otent-geospatial-vision (:provenance/system-id dprov)))
    (is (= "panoramax" (:provenance/source-id dprov)))
    (is (= "deadbeef" (:provenance/content-hash dprov)))
    (is (true? (contains? dprov :provenance/label-taxonomy)))
    (is (= "CC-BY-SA-4.0 (per-item STAC license property)" (:provenance/licence dprov)))))

(deftest derived-table-readback
  (testing "the derived table round-trips through JSON without changing values"
    (let [table (pxd/daylight-table (run-fixture))
          rt (js->clj (js/JSON.parse (js/JSON.stringify (clj->js table)))
                      :keywordize-keys true)]
      ;; clj->js drops keyword namespaces, so the round-tripped keys are
      ;; the demoted forms — the VALUES must survive intact
      (is (= (get-in table [:table/classes :daylight]) (get-in rt [:classes :daylight])))
      (is (= (get-in table [:table/photos :capture-unknown])
             (get-in rt [:photos :capture-unknown])))
      (is (= (get-in table [:table/model :model-id])
             (get-in rt [:model :model-id])))
      (is (= (get-in table [:table/run-counts :fetched])
             (get-in rt [:run-counts :fetched])))
      (is (nil? (some #(re-find #"nil|NaN" (str %)) (js->clj (js/JSON.stringify (clj->js table)))))))))

(deftest privacy-asserted-and-absent
  (testing "the derived provenance states the privacy boundary; no face/plate/person entity exists"
    (let [fixture (run-fixture)
          table (pxd/daylight-table fixture)
          dprov (pxd/provenance (:provenance fixture)
                                {:run-at "2026-09-01T04:00:01.000Z" :artifact-hash "abc123"})]
      (is (re-find #"no face, plate, person or vehicle" (:provenance/privacy-note dprov)))
      (is (not (contains? table :table/person)))
      (is (nil? (some #(re-find #"(?i)face|plate|pedestrian" (str %))
                      (tree-seq coll? seq table)))))))

(deftest epistemic-boundary-present
  (is (re-find #"not scene lighting|not current existence"
               (:table/epistemic-boundary (pxd/daylight-table (run-fixture))))))
