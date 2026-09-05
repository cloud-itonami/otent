(ns otent.panoramax-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.panoramax :as px]))

(def ^:private bbox [139.765 35.678 139.77 35.682])

(def ^:private good-item
  {"type" "Feature"
   "id" "cf6b0e8d-3eb5-442f-8259-b7ecdb165a07"
   "bbox" [139.767360413 35.68140155 139.767360413 35.68140155]
   "links" [{"rel" "license" "href" "https://creativecommons.org/licenses/by-sa/4.0/"}
            {"rel" "self" "href" "https://api.panoramax.xyz/api/collections/abc/items/cf6b0e8d"
             "type" "application/geo+json"}]
   "assets" {"sd" {"href" "https://panoramax.openstreetmap.fr/derivates/cf/sd.jpg"}}
   "geometry" {"type" "Point" "coordinates" [139.767360413 35.68140155]}
   "properties" {"datetime" "2016-10-13T13:13:11.066709+00:00"
                 "license" "CC-BY-SA-4.0"
                 "geovisio:status" "ready"
                 "geovisio:visibility" "anyone"
                 "geovisio:rank_in_collection" 2
                 "collection" "2075995f-a707-476e-9065-4956504f66aa"
                 "view:azimuth" 198
                 "quality:horizontal_accuracy" 5.0
                 "pers:interior_orientation"
                 {"camera_model" "PULP 4G" "camera_manufacturer" "WIKO"
                  "sensor_array_dimensions" [4096 2160]}
                 ;; the raw EXIF block carries uploader identity and is
                 ;; never copied: its presence here is what the
                 ;; redaction check exists to catch
                 "exif" {"Exif.Image.DateTimeOriginal" "2016:10:13 13:13:11"
                         "Exif.Image.ImageDescription"
                         "{\"MAPLatitude\":\"35.68\",\"MAPSettingsEmail\":\"someone@example.org\"}"}}})

(defn- envelope [features]
  {"type" "FeatureCollection" "features" features "links" []})

(defn- accept [item]
  (:observation (px/item->observation-or-refusal item "2026-09-01T00:00:00Z")))

;; ── bounds ───────────────────────────────────────────────────────────

(t/deftest bbox-bounds
  (t/is (:ok? (px/check-bbox bbox)))
  (t/is (= "bbox-139.765-35.678-139.77-35.682"
           (:area-id (px/check-bbox bbox))))
  (t/is (= :panoramax/bbox-too-large (:error (px/check-bbox [139.0 35.0 139.02 35.01]))))
  (t/is (= :panoramax/bbox-inverted (:error (px/check-bbox [139.77 35.678 139.765 35.682]))))
  (t/is (= :panoramax/bbox-invalid (:error (px/check-bbox ["a" 1 2 3]))))
  (t/is (= :panoramax/bbox-invalid (:error (px/check-bbox [1 2 3])))))

;; ── privacy boundary ─────────────────────────────────────────────────

(t/deftest privacy-redaction
  (let [obs (accept good-item)]
    ;; the raw EXIF block never reaches the observation
    (t/is (nil? (get obs :observation/exif-note-investigation)))
    (t/is (not (contains? obs :exif)))
    (t/is (not (some #(str/includes? (str %) "@") (vals obs))))
    (t/is (px/redacted? obs))
    ;; and the known-uploader key is on the forbidden list by name
    (t/is (some #{"MAPSettingsEmail"} px/exif-keys-forbidden))
    (t/is (not (px/redacted? {:observation/note "mail me at a@b.c"})))
    (t/is (not (px/redacted? {:exif {"x" "y"}})))))

(t/deftest privacy-gates
  ;; no per-item blur flag exists → the uncertainty must say so, not
  ;; claim provider blurring was verified
  (let [obs (accept good-item)]
    (t/is (false? (get-in obs [:observation/privacy :provider-blur-verified])))
    (t/is (str/includes? (get-in obs [:observation/privacy :note]) "blur")))
  (t/is (= {:ok? false :error :panoramax/not-processed}
           (-> (px/item->observation-or-refusal
                (assoc-in good-item ["properties" "geovisio:status"] "waiting-merge")
                "2026-09-01T00:00:00Z")
               (select-keys [:ok? :error]))))
  (t/is (= {:ok? false :error :panoramax/not-public}
           (-> (px/item->observation-or-refusal
                (assoc-in good-item ["properties" "geovisio:visibility"] "hidden")
                "2026-09-01T00:00:00Z")
               (select-keys [:ok? :error])))))

;; ── per-item refusals ────────────────────────────────────────────────

(t/deftest refusals
  (let [refused #(-> (px/item->observation-or-refusal % "2026-09-01T00:00:00Z")
                     (select-keys [:ok? :error]))]
    (t/is (= {:ok? false :error :panoramax/missing-asset-id}
             (refused (assoc good-item "id" nil))))
    (t/is (= {:ok? false :error :panoramax/missing-licence}
             (refused (assoc-in good-item ["properties" "license"] ""))))
    (t/is (= {:ok? false :error :panoramax/missing-evidence-url}
             (refused (assoc good-item "links" []))))
    (t/is (= {:ok? false :error :panoramax/missing-capture-time}
             (refused (assoc-in good-item ["properties" "datetime"] nil))))
    (t/is (= {:ok? false :error :panoramax/invalid-geometry}
             (refused (assoc good-item "geometry"
                             {"type" "Point" "coordinates" [999 0]}))))
    ;; a swapped (lat,lon) pair that only fits if repaired is refused
    (t/is (= {:ok? false :error :panoramax/invalid-geometry}
             (refused (assoc good-item "geometry"
                             {"type" "Point" "coordinates" [35.681 139.767]}))))))

;; ── acceptance: provenance and uncertainty fields ────────────────────

(t/deftest acceptance-fields
  (let [obs (accept good-item)]
    (t/is (= "panoramax-item:cf6b0e8d-3eb5-442f-8259-b7ecdb165a07"
             (:observation/asset-id obs)))
    (t/is (= :imagery-asset (:observation/kind obs)))
    (t/is (= "2016-10-13T13:13:11.066709+00:00" (:observation/capture-time obs)))
    (t/is (= "2026-09-01T00:00:00Z" (:observation/ingested-at obs)))
    (t/is (= "CC-BY-SA-4.0" (:observation/licence obs)))
    (t/is (str/includes? (:observation/source-url obs) "/items/"))
    ;; geometry: GeoJSON lon/lat, order preserved
    (t/is (= [139.767360413 35.68140155]
             (get-in obs [:observation/footprint :coordinates])))
    (t/is (= "EPSG:4326 (lon,lat order)" (:observation/crs obs)))
    ;; uncertainty carries the provider accuracy, and stays :unknown when absent
    (t/is (= 5.0 (:observation/spatial-uncertainty-m obs)))
    (let [no-acc (accept (assoc-in good-item ["properties" "quality:horizontal_accuracy"] nil))]
      (t/is (= :unknown (:observation/spatial-uncertainty-m no-acc))))
    (t/is (= 198 (get-in obs [:observation/orientation :heading-deg])))
    (t/is (= "PULP 4G" (get-in obs [:observation/sensor :model])))
    (t/is (= 4096 (get-in obs [:observation/geometry-dimensions :width-px])))
    (t/is (= :unknown (:observation/resolution-or-gsd obs)))))

;; ── payload normalization ────────────────────────────────────────────

(t/deftest normalize-payload
  (t/is (= :panoramax/bad-envelope (:error (px/normalize-payload {"type" "Things"} {:bbox bbox}))))
  (let [result (px/normalize-payload (envelope [good-item])
                                     {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 1 (:accepted (:counts result))))
    (t/is (= 1 (count (:observations result))))
    (t/is (empty? (:refusals result))))
  ;; an item outside the bbox is counted, never silently dropped
  (let [result (px/normalize-payload (envelope [(assoc-in good-item ["geometry" "coordinates"] [0 0])])
                                     {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 0 (:accepted (:counts result))))
    (t/is (= 1 (:returned-outside-bbox (:counts result)))))
  ;; per-item refusals survive as named reasons
  (let [result (px/normalize-payload (envelope [(assoc-in good-item ["properties" "geovisio:status"] "draft")])
                                     {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 1 (:refused (:counts result))))
    (t/is (= :panoramax/not-processed (:error (first (:refusals result))))))
  ;; the live aggregate search answers without a "type" — still valid
  (let [result (px/normalize-payload {"features" [good-item] "links" []}
                                     {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 1 (:accepted (:counts result)))))
  ;; a keyword-keyed payload (js->clj output) is demoted, not refused
  (let [result (px/normalize-payload
                {:type "FeatureCollection"
                 :features [good-item]
                 :links []}
                {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 1 (:accepted (:counts result))))))

;; ── provenance ───────────────────────────────────────────────────────

(t/deftest provenance-fields
  (let [p (px/provenance {:area-id "bbox-139.765-35.678-139.77-35.682"
                          :bbox bbox
                          :retrieved-at "2026-09-01T00:00:00Z"
                          :input-sha256 "abc123"})]
    (t/is (= :otent-geospatial-vision (:provenance/system-id p)))
    (t/is (= "panoramax" (:provenance/source-id p)))
    (t/is (= "abc123" (:provenance/content-hash p)))
    (t/is (= "abc123" (:provenance/content-hash
                       (px/provenance {:area-id "a" :bbox bbox
                                       :retrieved-at "t" :input-sha256 "abc123"}))))
    (t/is (contains? (:provenance/parameters p) :bbox))))
