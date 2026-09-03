(ns otent.mapillary-image-detections
  "One Mapillary **per-image detections** source: ONE `/:image_id/detections`
  request through the registered client `com-mapillary-graph-api`, metadata
  only — no pixel is ever fetched by this ns.

  Scope (`otent-vision-scope.edn`): street-allow for Mapillary under its
  current terms. The bbox metadata source (PR #20), the pixel sample
  (PR #44) and the map_features analysis pass (PR #3) exist; the
  per-image detections endpoint — the client's `detections-request`
  with `kind :image` — had no bounded ingest. This ns is it:

  - **one image, one request**: a single provider image id, a single
    `detections-request`, one HTTP GET. `paging.next` is counted and
    printed, **never followed** (:run-bounds). No bbox, no siblings.
  - **metadata only**: the field list carries no thumbnail field, so a
    pixel was never requested and never had to be defended. The pixel
    exception is the pixel sample's (PR #44), not this ns's.
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

(def source-id "mapillary-image-detections")

(def terms-url "https://mapillary.com/legal/terms/")

(def attribution "© Mapillary contributors (CC-BY-SA as published by Mapillary)")

(def licence
  "The detections payload carries no per-detection licence field; the
  licence is named where it actually lives rather than invented."
  "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)")

(defn evidence-url [image-id]
  (str "https://www.mapillary.com/app/?pKey=" image-id))

;; Fields requested from /:image_id/detections. Deliberately no
;; `image` or `thumb_*` field: this source never touches pixels.
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

;; ── subject: one provider image id ───────────────────────────────────

(defn check-image-id
  [image-id]
  (let [s (str image-id)]
    (cond
      (str/blank? s)
      {:ok? false :error :mly-imagedet/missing-image-id
       :detail "an image id is required: one subject, one request"}

      (not (re-matches #"\d+" s))
      {:ok? false :error :mly-imagedet/image-id-malformed
       :detail (str "image id must be the provider's numeric id, got " (pr-str s))}

      :else {:ok? true :image-id s})))

;; ── request (one) ────────────────────────────────────────────────────

(defn build-request
  "The exact request the registered client builds for this image id.
  No token in the result — it rides in the Authorization header at
  fetch time."
  [image-id]
  (let [{:keys [ok? error detail]} (check-image-id image-id)]
    (if-not ok?
      {:ok? false :error error :detail detail}
      {:ok? true
       :image-id (str image-id)
       :request (mi/detections-request :image image-id {:fields detection-fields})})))

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
      {:ok? false :error :mly-imagedet/missing-detection-id
       :detail "detection has no id"}

      (not (string? value))
      {:ok? false :error :mly-imagedet/missing-value
       :detail (str "detection " id " carries no string value")}

      (privacy-refusal? value)
      {:ok? false :error :mly-imagedet/privacy-value
       :detail (str "detection " id " names a person/face/plate in its taxonomy value; "
                    "it is refused before any record exists and is counted, not stored")}

      (not (plausible-lonlat? (get-in detection ["geometry" "coordinates"])))
      {:ok? false :error :mly-imagedet/invalid-geometry
       :detail (str "detection " id " geometry is not a plausible GeoJSON Point lon/lat: "
                    (pr-str (get detection "geometry")))}

      (not (number? (get detection "created_at")))
      {:ok? false :error :mly-imagedet/missing-created-at
       :detail (str "detection " id " has no numeric created_at")}

      :else {:ok? true :detection detection})))

;; ── record ───────────────────────────────────────────────────────────

(defn detection->record
  "One admissible detection → one record. Deterministic given the
  inputs: no wall-clock read here (the caller passes `retrieved-at`)."
  [image-id detection retrieved-at]
  (let [id (str (get detection "id"))
        coords (get-in detection ["geometry" "coordinates"])]
    {:observation/source source-id
     :observation/source-id (str image-id ":" id)
     :observation/asset-id (str source-id ":image:" image-id ":" id)
     :observation/image-id (str image-id)
     :observation/detection-id id
     :observation/value (get detection "value")
     :observation/lat (nth coords 1)
     :observation/lon (nth coords 0)
     :observation/created-at-ms (get detection "created_at")
     :observation/ingested-at retrieved-at
     :observation/spatial-uncertainty :unknown
     :observation/evidence-url (evidence-url image-id)
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
    {:ok? false :error :mly-imagedet/missing-licence
     :detail "record carries no licence"}

    (not= 1 (:observation/requests-made record))
    {:ok? false :error :mly-imagedet/run-bounds
     :detail "a record of this source proves exactly one request"}

    (not (and (number? (:observation/lat record)) (number? (:observation/lon record))))
    {:ok? false :error :mly-imagedet/invalid-geometry
     :detail "record carries no numeric lon/lat"}

    (not (redacted? record))
    {:ok? false :error :mly-imagedet/privacy-redaction
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
  (let [image-id (str (get payload "image-id"))
        data (get payload "data" [])
        results (map (fn [d]
                       (let [r (detection->admissible d)]
                         (if (:ok? r)
                           {:record (detection->record image-id (:detection r)
                                                       (get payload "retrieved-at"))}
                           {:refusal (select-keys r [:error :detail])})))
                     data)]
    {:records (vec (keep :record results))
     :refused-n (count (filter :refusal results))
     :raw-count (count data)
     :paging-next (boolean (get-in payload ["paging" "next"]))}))
