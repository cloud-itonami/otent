(ns otent.mapillary-mapfeature-detections
  "One Mapillary **per-map-feature detections** source: ONE
  `/:map_feature_id/detections` request through the registered client
  `com-mapillary-graph-api`, metadata only — no pixel is ever fetched
  by this ns.

  Scope (`otent-vision-scope.edn`): street-allow for Mapillary under its
  current terms. The bbox metadata source (PR #20), the pixel sample
  (PR #44), the map_features analysis pass (PR #3) and the per-IMAGE
  detections pass (PR #57) exist; the per-MAP-FEATURE detections
  endpoint — the client's `detections-request` with `kind :map-feature`
  — had no bounded ingest. This ns is it:

  - **one map feature, one request**: a single provider map-feature id,
    a single `detections-request`, one HTTP GET. `paging.next` is
    counted and printed, **never followed** (:run-bounds). No bbox, no
    neighbours, no second feature.
  - **metadata only**: the field list carries no thumbnail field, so a
    pixel was never requested and never had to be defended. The pixel
    exception is the pixel sample's (PR #44), not this ns's.
  - **a map feature is a triangulated object, not a current fact**
    (:map-feature-is-not-current-without-observation-time): every
    detection's `created_at` and the feature's own `object_value` are
    carried verbatim; nothing here claims the pole stands there now.
  - **privacy boundary before normalization**: detection `value` is the
    provider's own taxonomy string. Any value naming a person, face or
    licence plate is REFUSED (counted, never stored, never becomes an
    entity) even though the requested fields carry no imagery. Faces
    and plates stay outside the observation space; no person/vehicle
    tracking or re-identification is possible from a stored record.
  - **geometry refused, not repaired**: only a plausible GeoJSON Point
    lon/lat is admissible; anything else is refused and counted.
  - **token**: `MAPILLARY_ACCESS_TOKEN` from the environment only, sent
    through the client's `authorization-header` — never a URL, never a
    log line, never a record."
  (:require [clojure.string :as str]
            [com-mapillary-graph-api.core :as mi]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "mapillary-mapfeature-detections")

(def terms-url "https://mapillary.com/legal/terms/")

(def attribution "© Mapillary contributors (CC-BY-SA as published by Mapillary)")

(def licence
  "The detections payload carries no per-detection licence field; the
  licence is named where it actually lives rather than invented."
  "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)")

(defn evidence-url [map-feature-id]
  (str "https://www.mapillary.com/app/?focus=map&mapFeature=" map-feature-id))

;; Fields requested from /:map_feature_id/detections. Deliberately no
;; `image` or `thumb_*` field: this source never touches pixels. (Same
;; list the client's `detections-request` defaults to, written here so
;; the requested fields are visible without reading the client.)
(def detection-fields ["id" "value" "geometry" "created_at"])

;; ── the privacy boundary ─────────────────────────────────────────────

(def privacy-refusal-fragments
  "Detection `value` fragments that name a person, a face or a licence
  plate. A matching detection is refused BEFORE any record is built —
  it cannot become an entity, a track or an identity."
  ["human" "person" "pedestrian" "face" "licence-plate" "license-plate" "plate"])

(defn privacy-refusal? [value]
  (let [v (str/lower-case (str value))]
    (boolean (some #(str/includes? v %) privacy-refusal-fragments))))

(defn- clean-value? [v]
  (cond
    (string? v) (not (str/includes? v "@"))
    (map? v) (every? (fn [[k v2]]
                       (and (not-any? #(str/includes? (str/lower-case (str k)) %)
                                      ["exif" "email"])
                            (clean-value? v2)))
                     v)
    (vector? v) (every? clean-value? v)
    :else true))

(defn redacted?
  "Same last-line check the other street sources run: any record
  carrying an `@` in a string, or an exif/email-shaped key, is a bug
  that must refuse rather than ship."
  [record]
  (clean-value? record))

;; ── subject: one provider map-feature id ─────────────────────────────

(defn check-map-feature-id
  [map-feature-id]
  (let [s (str map-feature-id)]
    (cond
      (str/blank? s)
      {:ok? false :error :mly-mfdet/missing-map-feature-id
       :detail "a map feature id is required: one subject, one request"}

      (not (re-matches #"\d+" s))
      {:ok? false :error :mly-mfdet/map-feature-id-malformed
       :detail (str "map feature id must be the provider's numeric id, got " (pr-str s))}

      :else {:ok? true :map-feature-id s})))

;; ── request (one) ────────────────────────────────────────────────────

(defn build-request
  "The exact request the registered client builds for this map feature
  id. No token in the result — it rides in the Authorization header at
  fetch time."
  [map-feature-id]
  (let [{:keys [ok? error detail]} (check-map-feature-id map-feature-id)]
    (if-not ok?
      {:ok? false :error error :detail detail}
      {:ok? true
       :map-feature-id (str map-feature-id)
       :request (mi/detections-request :map-feature map-feature-id
                                       {:fields detection-fields})})))

;; ── geometry: GeoJSON Point lon/lat, refused not repaired ────────────

(defn- plausible-lonlat?
  [lonlat]
  (let [[lon lat :as c] lonlat]
    (and (vector? c) (= 2 (count c))
         (every? number? c)
         (<= -180.0 lon 180.0)
         (<= -90.0 lat 90.0))))

;; ── per-detection gates ──────────────────────────────────────────────

(defn detection->admissible
  "One detection map → `{:ok? true :detection d}` or a refusal naming
  the reason. Gates, in order: id, value string, privacy boundary,
  Point geometry, numeric created_at."
  [detection]
  (let [id (get detection "id")
        value (get detection "value")]
    (cond
      (str/blank? (str id))
      {:ok? false :error :mly-mfdet/missing-detection-id
       :detail "detection has no id"}

      (not (string? value))
      {:ok? false :error :mly-mfdet/missing-value
       :detail (str "detection " id " carries no string value")}

      (privacy-refusal? value)
      {:ok? false :error :mly-mfdet/privacy-value
       :detail (str "detection " id " names a person/face/plate in its taxonomy value; "
                    "it is refused before any record exists and is counted, not stored")}

      (not (plausible-lonlat? (get-in detection ["geometry" "coordinates"])))
      {:ok? false :error :mly-mfdet/invalid-geometry
       :detail (str "detection " id " geometry is not a plausible GeoJSON Point lon/lat: "
                    (pr-str (get detection "geometry")))}

      (not (number? (get detection "created_at")))
      {:ok? false :error :mly-mfdet/missing-created-at
       :detail (str "detection " id " has no numeric created_at")}

      :else {:ok? true :detection detection})))

;; ── record ───────────────────────────────────────────────────────────

(defn detection->record
  "One admissible detection → one record. Deterministic given the
  inputs: no wall-clock read here (the caller passes `retrieved-at`)."
  [map-feature-id detection retrieved-at]
  (let [id (str (get detection "id"))
        coords (get-in detection ["geometry" "coordinates"])]
    {:observation/source source-id
     :observation/source-id (str map-feature-id ":" id)
     :observation/asset-id (str source-id ":map-feature:" map-feature-id ":" id)
     :observation/map-feature-id (str map-feature-id)
     :observation/detection-id id
     :observation/value (get detection "value")
     :observation/lat (nth coords 1)
     :observation/lon (nth coords 0)
     :observation/created-at-ms (get detection "created_at")
     :observation/ingested-at retrieved-at
     :observation/spatial-uncertainty :unknown
     :observation/evidence-url (evidence-url map-feature-id)
     :observation/licence licence
     :observation/attribution attribution
     :observation/requests-made 1
     :observation/privacy {:provider-blur-verified false
                           :note "no pixel fetched; person/face/plate detections refused before any record exists"}}))

(defn check-record
  "The last gate before anything leaves the process."
  [record]
  (cond
    (not (seq (:observation/licence record)))
    {:ok? false :error :mly-mfdet/missing-licence
     :detail "record carries no licence"}

    (not= 1 (:observation/requests-made record))
    {:ok? false :error :mly-mfdet/run-bounds
     :detail "a record of this source proves exactly one request"}

    (not (and (number? (:observation/lat record)) (number? (:observation/lon record))))
    {:ok? false :error :mly-mfdet/invalid-geometry
     :detail "record carries no numeric lon/lat"}

    (not (redacted? record))
    {:ok? false :error :mly-mfdet/privacy-redaction
     :detail "record carries an `@` or an exif/email-shaped key"}

    :else {:ok? true}))

;; ── the one pass over the payload ────────────────────────────────────

(defn parse-payload
  "`{\"data\" [...]}` (captured or fetched) →
  `{:records [...] :refused-n n :raw-count n :paging-next boolean}`.
  The whole payload is examined; privacy-refused detections are counted
  and nothing about them is kept; `paging.next` is counted, not
  followed."
  [payload]
  (let [map-feature-id (str (get payload "map-feature-id"))
        data (get payload "data" [])
        results (map (fn [d]
                       (let [r (detection->admissible d)]
                         (if (:ok? r)
                           {:record (detection->record map-feature-id (:detection r)
                                                       (get payload "retrieved-at"))}
                           {:refusal (select-keys r [:error :detail])})))
                     data)]
    {:records (vec (keep :record results))
     :refused-n (count (filter :refusal results))
     :raw-count (count data)
     :paging-next (boolean (get-in payload ["paging" "next"]))}))
