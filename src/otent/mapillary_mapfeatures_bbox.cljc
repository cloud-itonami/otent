(ns otent.mapillary-mapfeatures-bbox
  "One Mapillary **map-features bbox** source: ONE `/map_features`
  request through the registered client `com-mapillary-graph-api`
  (`map-features-request`), metadata only — no pixel is ever fetched
  by this ns.

  Scope (`otent-vision-scope.edn`): street-allow for Mapillary under
  its current terms. The image bbox metadata source (PR #20), the
  pixel sample (PR #44), the per-image detections pass (PR #57) and
  the per-map-feature detections pass (PR #60) exist; the client's
  `map-features-request` (`GET /map_features`) had no bounded ingest.
  This ns is it:

  - **one bbox, one request**: one 0.01-degree tile (the client's own
    limit, enforced by `bbox-within-limit?` before anything is built),
    a single `map-features-request`, one HTTP GET. `paging.next` is
    counted and printed, **never followed** (:run-bounds). No split,
    no neighbouring tile, no second request.
  - **metadata only**: the field list (`id,object_value,geometry,
    first_seen_at,last_seen_at`) carries no thumbnail field, so no
    pixel was requested and none had to be defended.
  - **a map feature is a triangulated object, not a current fact**
    (:map-feature-is-not-current-without-observation-time):
    `first_seen_at` and `last_seen_at` are carried verbatim as
    provider claims; nothing here asserts the object stands there now.
  - **privacy boundary before normalization**: `object_value` is the
    provider's taxonomy string; any value naming a person, face or
    licence plate is REFUSED before any record exists — counted,
    never stored, never an entity. Faces and plates stay outside the
    observation space.
  - **geometry refused, not repaired**: only a plausible GeoJSON Point
    lon/lat is admissible; anything else is refused and counted.
  - **token**: `MAPILLARY_ACCESS_TOKEN` from the environment only, sent
    through the client's `authorization-header` — never a URL, never a
    log line, never a record."
  (:require [clojure.string :as str]
            [com-mapillary-graph-api.core :as mi]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "mapillary-mapfeatures-bbox")

(def terms-url "https://mapillary.com/legal/terms/")

(def attribution "© Mapillary contributors (CC-BY-SA as published by Mapillary)")

(def licence
  "The map_features payload carries no per-feature licence field; the
  licence is named where it actually lives rather than invented."
  "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)")

(defn evidence-url [bbox-str]
  (str "https://www.mapillary.com/app/?focus=map&bbox=" bbox-str))

;; Fields requested from /map_features. Deliberately no `image` or
;; `thumb_*` field: this source never touches pixels. (Same list the
;; client's `map-features-request` defaults to, written here so the
;; requested fields are visible without reading the client.)
(def feature-fields ["id" "object_value" "geometry" "first_seen_at" "last_seen_at"])

;; ── the privacy boundary ─────────────────────────────────────────────

(def privacy-refusal-fragments
  "`object_value` fragments that name a person, a face or a licence
  plate. A matching feature is refused BEFORE any record is built —
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

;; ── subject: one bounded bbox (the client's 0.01-degree limit) ───────

(defn- bbox-vec->map
  "The client speaks `{:west :south :east :north}` (Mapillary's
  west,south,east,north order); the gate below speaks the plain
  `[minx,miny,maxx,maxy]` vector. The conversion happens exactly once,
  here."
  [[minx miny maxx maxy]]
  {:west minx :south miny :east maxx :north maxy})

(defn bbox-str
  "The Mapillary `west,south,east,north` string for a
  `[minx,miny,maxx,maxy]` vector — the one place the conversion from
  vector to the client's map shape happens for display too."
  [bbox-vec]
  (mi/bbox-str (bbox-vec->map (vec bbox-vec))))

(defn bbox-numbers
  "Validate `[minx,miny,maxx,maxy]` numerically, then defer to the
  registered client's `bbox-within-limit?` for the 0.01-degree rule.
  A malformed bbox is refused before any request is built."
  [bbox]
  (cond
    (or (not (sequential? bbox)) (not= 4 (count bbox)))
    {:ok? false :error :mly-mfbbox/bbox-shape
     :detail "bbox must be [minx,miny,maxx,maxy]"}

    (not (every? number? bbox))
    {:ok? false :error :mly-mfbbox/bbox-non-numeric
     :detail "bbox coordinates must be numbers"}

    (let [[minx miny maxx maxy] bbox]
      (or (< minx -180.0) (> minx 180.0) (< maxx -180.0) (> maxx 180.0)
          (< miny -90.0) (> miny 90.0) (< maxy -90.0) (> maxy 90.0)
          (> minx maxx) (> miny maxy)))
    {:ok? false :error :mly-mfbbox/bbox-out-of-range
     :detail "bbox coordinates out of range or min>max"}

    (not (mi/bbox-within-limit? (bbox-vec->map bbox)))
    {:ok? false :error :mly-mfbbox/bbox-too-large
     :detail "bbox exceeds Mapillary's 0.01 degree limit; this source is ONE tile and does not split"}

    :else {:ok? true :bbox (vec bbox) :bbox-map (bbox-vec->map bbox)}))

;; ── request (one) ────────────────────────────────────────────────────

(defn build-request
  "The exact request the registered client builds for this bbox. No
  token in the result — it rides in the Authorization header at fetch
  time."
  [bbox]
  (let [{:keys [ok? error detail bbox-map]} (bbox-numbers bbox)]
    (if-not ok?
      {:ok? false :error error :detail detail}
      {:ok? true
       :bbox (vec bbox)
       :request (mi/map-features-request bbox-map {:fields feature-fields})})))

;; ── geometry: GeoJSON Point lon/lat, refused not repaired ────────────

(defn- plausible-lonlat?
  [lonlat]
  (let [[lon lat :as c] lonlat]
    (and (vector? c) (= 2 (count c))
         (every? number? c)
         (<= -180.0 lon 180.0)
         (<= -90.0 lat 90.0))))

;; ── per-feature gates ────────────────────────────────────────────────

(defn feature->admissible
  "One feature map → `{:ok? true :feature f}` or a refusal naming the
  reason. Gates, in order: id, object_value string, privacy boundary,
  Point geometry, numeric first_seen_at."
  [feature]
  (let [id (get feature "id")
        value (get feature "object_value")]
    (cond
      (str/blank? (str id))
      {:ok? false :error :mly-mfbbox/missing-feature-id
       :detail "feature has no id"}

      (not (string? value))
      {:ok? false :error :mly-mfbbox/missing-object-value
       :detail (str "feature " id " carries no string object_value")}

      (privacy-refusal? value)
      {:ok? false :error :mly-mfbbox/privacy-value
       :detail (str "feature " id " names a person/face/plate in its object_value; "
                    "it is refused before any record exists and is counted, not stored")}

      (not (plausible-lonlat? (get-in feature ["geometry" "coordinates"])))
      {:ok? false :error :mly-mfbbox/invalid-geometry
       :detail (str "feature " id " geometry is not a plausible GeoJSON Point lon/lat: "
                    (pr-str (get feature "geometry")))}

      (not (number? (get feature "first_seen_at")))
      {:ok? false :error :mly-mfbbox/missing-first-seen-at
       :detail (str "feature " id " has no numeric first_seen_at")}

      :else {:ok? true :feature feature})))

;; ── record ───────────────────────────────────────────────────────────

(defn feature->record
  "One admissible feature → one record. Deterministic given the
  inputs: no wall-clock read here (the caller passes `retrieved-at`)."
  [bbox feature retrieved-at]
  (let [id (str (get feature "id"))
        coords (get-in feature ["geometry" "coordinates"])
        bbox-str (bbox-str (vec bbox))]
    {:observation/source source-id
     :observation/source-id (str id)
     :observation/asset-id (str source-id ":map-feature:" id)
     :observation/map-feature-id id
     :observation/value (get feature "object_value")
     :observation/lat (nth coords 1)
     :observation/lon (nth coords 0)
     :observation/first-seen-at-ms (get feature "first_seen_at")
     :observation/last-seen-at-ms (get feature "last_seen_at")
     :observation/bbox-str bbox-str
     :observation/ingested-at retrieved-at
     :observation/spatial-uncertainty :unknown
     :observation/evidence-url (evidence-url bbox-str)
     :observation/licence licence
     :observation/attribution attribution
     :observation/requests-made 1
     :observation/privacy {:provider-blur-verified false
                           :note "no pixel fetched; person/face/plate object_values refused before any record exists"}}))

(defn check-record
  "The last gate before anything leaves the process."
  [record]
  (cond
    (not (seq (:observation/licence record)))
    {:ok? false :error :mly-mfbbox/missing-licence
     :detail "record carries no licence"}

    (not= 1 (:observation/requests-made record))
    {:ok? false :error :mly-mfbbox/run-bounds
     :detail "a record of this source proves exactly one request"}

    (not (and (number? (:observation/lat record)) (number? (:observation/lon record))))
    {:ok? false :error :mly-mfbbox/invalid-geometry
     :detail "record carries no numeric lon/lat"}

    (not (redacted? record))
    {:ok? false :error :mly-mfbbox/privacy-redaction
     :detail "record carries an `@` or an exif/email-shaped key"}

    :else {:ok? true}))

;; ── the one pass over the payload ────────────────────────────────────

(defn parse-payload
  "`{\"data\" [...]}` (captured or fetched) →
  `{:records [...] :refused-n n :raw-count n :paging-next boolean}`.
  The whole payload is examined; privacy-refused features are counted
  and nothing about them is kept; `paging.next` is counted, not
  followed."
  [payload]
  (let [bbox-str (str (get payload "bbox-str"))
        data (get payload "data" [])
        results (map (fn [f]
                       (let [r (feature->admissible f)]
                         (if (:ok? r)
                           {:record (feature->record (get payload "bbox")
                                                     (:feature r)
                                                     (get payload "retrieved-at"))}
                           {:refusal (select-keys r [:error :detail])})))
                     data)]
    {:records (vec (keep :record results))
     :refused-n (count (filter :refusal results))
     :raw-count (count data)
     :paging-next (boolean (get-in payload ["paging" "next"]))}))
