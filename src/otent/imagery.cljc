(ns otent.imagery
  "Provenance and licence controls for the earth-imagery slice.

  One bounded source: NASA GIBS WMTS, layer
  `BlueMarble_ShadedRelief_Bathymetry`, EPSG:4326 / 500m tile matrix,
  one tile fetched as a bounded sample (test fixture, hashed). The
  control lives here: a licence allowlist, a provenance-completeness
  check that refuses a record missing any required field, and the
  manifest that states exactly what exists -- no more, no less."
  (:require [clojure.set :as set]))

(def licence-allowlist
  "Licences this actor may ingest imagery under. Anything else is a
  refusal, not a judgement call."
  #{:nasa-public-domain :cc0 :public-domain})

(def required-provenance-keys
  "Every provenance record must carry all of these. A record missing any
  one is not a record; it is a rumour about an image."
  #{:asset-id :source-url :capture-time :footprint :crs :resolution-gsd-m
    :sensor :bands :licence :retrieved-at :payload-sha256})

(defn licence-allowed? [licence]
  (contains? licence-allowlist licence))

(defn provenance-complete?
  "True only when every required provenance key is present and non-nil."
  [rec]
  (and (map? rec)
       (empty? (set/difference required-provenance-keys (set (keys rec))))
       (every? (fn [[_ v]] (some? v)) rec)))

(defn refusal
  "The refusal verdict for a licence: :refused with the reason, or nil
  when the licence is on the allowlist."
  [licence]
  (when-not (licence-allowed? licence)
    {:refused true
     :reason (str "licence not on allowlist: " (pr-str licence))}))

(defn manifest
  "The coverage statement: exactly what exists, derived from the
  provenance record. Nothing here may claim coverage the record does
  not back."
  [rec]
  {:what-exists
   (str "One bounded GIBS WMTS sample: layer "
        (:layer rec) ", tile matrix " (:tile-matrix rec)
        ", tile z/x/y " (:tile-zxy rec)
        ", global EPSG:4326 footprint at level 0.")
   :asset-id (:asset-id rec)
   :licence (:licence rec)
   :payload-sha256 (:payload-sha256 rec)
   :retrieved-at (:retrieved-at rec)
   :bounds-epg4326-deg [-180.0 180.0 -90.0 90.0]
   :level-0-only true})

(def sample
  "The provenance record for the bounded sample this slice captured.
  NASA Blue Marble Next Generation (shaded relief + bathymetry) is a
  work of the US government and carries no copyright restriction; the
  layer is static, so capture time is the BMNG composite epoch, not a
  dated acquisition."
  {:asset-id "BlueMarble_ShadedRelief_Bathymetry/500m/0/0/0"
   :source-url
   "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/BlueMarble_ShadedRelief_Bathymetry/default/500m/"
   :capture-time "2004"
   :capture-note
   "Blue Marble Next Generation static composite; capture time is the
  composite epoch, not a dated satellite acquisition."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 500
   :sensor "BMNG (Blue Marble Next Generation), MODIS-composite shaded relief + bathymetry"
   :bands #{:r :g :b}
   :licence :nasa-public-domain
   :tile-matrix "500m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T07:53:00Z"
   :payload-sha256
   "bcba78c5d01ba5ff545281d3acd77f7429f724f6213bec949f8298c518a963ab"})

(defn verify-sample
  "The object readback: re-derive the sample's provenance completeness
  and that its sha256 matches the fixture bytes' hash."
  [rec fixture-sha256]
  {:provenance-complete (provenance-complete? rec)
   :sha256-matches (= (:payload-sha256 rec) fixture-sha256)})
