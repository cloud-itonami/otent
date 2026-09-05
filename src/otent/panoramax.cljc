(ns otent.panoramax
  "Panoramax open street imagery (STAC items over the aggregate API)
  → bounded imagery-asset observations, with provenance and uncertainty.

  Source policy (otent-vision-scope.edn): :street-allow lists open-data
  street imagery providers; Panoramax is the public-interest street
  imagery federation run with IGN / OSM-FR, publishing CC-BY-SA-4.0 (and
  per-item licence links) over a STAC API. The endpoint used is
  `https://api.panoramax.xyz/api/search` (aggregate over federated
  instances) — anonymous, no credential, no bypass.

  Epistemic and privacy rules this ns enforces:

  - a picture is an **observation** at `properties.datetime`, never
    current existence; capture time and ingest time stay distinct
  - the provider blur story: the Panoramax platform (geovisio) runs
    automatic blurring of faces and plates on its public instances, but
    the item API publishes **no per-item blur-result flag** → that
    cannot be claimed per item and is carried as an explicit
    `:provider-blur-verified false` with the limitation stated, unlike a
    silent assumption. Items whose geovisio processing has not reached
    `ready`, or whose visibility is not `anyone`, are refused outright.
  - the raw EXIF `ImageDescription` block contains uploader-identifying
    metadata (e.g. a MAPSettingsEmail) → EXIF is **never** copied into
    an observation; only a curated allow-list of fields passes, and a
    redaction check refuses any observation containing an `@` or the
    known MAP metadata keys
  - the provider publishes a horizontal accuracy (95% interval, metres)
    → carried as spatial uncertainty; where absent it stays `:unknown`
  - geometry arrives GeoJSON lon/lat (EPSG:4326); a point that is only
    plausible if swapped is refused, not repaired
  - one source, one area, one PR (:run-bounds)."
  (:require [clojure.string :as str]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "panoramax")

(def api-url "https://api.panoramax.xyz/api/search")

(def terms-url "https://panoramax.fr/")

(def attribution "© Panoramax contributors (CC-BY-SA-4.0)")

(def licence
  "As published per item via the STAC `license` property and the per-item
  `license` link; recorded as-of retrieval, never re-verified every run."
  "CC-BY-SA-4.0 (per-item STAC license property)")

;; ── bounds ───────────────────────────────────────────────────────────

(def max-span 0.01)
(def max-results 100)

(defn check-bbox
  "`bbox` is [west south east north] in degrees. Refuses anything bigger
  than the run bound, non-numeric, or folded (west > east)."
  [bbox]
  (let [[w s e n] bbox]
    (cond
      (or (not (vector? bbox)) (not= 4 (count bbox))
          (some #(or (not (number? %)) (js/isNaN %)) bbox))
      {:ok? false :error :panoramax/bbox-invalid
       :detail (str "bbox must be 4 numbers [W S E N], got " (pr-str bbox))}
      :else
      (let [dw (- e w) dn (- n s)]
        (cond
          (or (<= dw 0) (<= dn 0))
          {:ok? false :error :panoramax/bbox-inverted
           :detail (str "west/south must be smaller: " (pr-str bbox))}
          (or (> dw max-span) (> dn max-span))
          {:ok? false :error :panoramax/bbox-too-large
           :detail (str "span " dw "x" dn " deg exceeds the " max-span
                        " deg run bound; one area per run")}
          :else {:ok? true
                 :area-id (str "bbox-" w "-" s "-" e "-" n)})))))

;; ── privacy boundary: curated fields only ────────────────────────────

(def exif-keys-forbidden
  "Raw EXIF blocks carry uploader identity (MAPSettingsEmail, hashes,
  device traces) and are never copied into observations — not even
  partially. Presence of any of these in an emitted observation is a
  bug, so the redaction check refuses it."
  ["exif" "ImageDescription" "MAPSettingsEmail" "MAPSettingsUploadHash"])

(defn- redacted?
  "True when the observation carries no forbidden key and no `@` anywhere
  in a string value (an email would have to ride in on one)."
  [obs]
  (letfn [(clean [v]
            (cond
              (map? v) (and (not-any? (fn [k]
                                        (some #(str/includes?
                                                (str/lower-case (name k))
                                                (str/lower-case %))
                                              exif-keys-forbidden))
                                      (keys v))
                            (every? (fn [[_ v2]] (clean v2)) v))
              (vector? v) (every? clean v)
              (string? v) (not (str/includes? v "@"))
              :else true))]
    (clean obs)))

;; ── geometry: GeoJSON lon/lat, refused not repaired ──────────────────

(defn- plausible-lonlat?
  [lonlat]
  (let [[lon lat :as c] lonlat]
    (and (vector? c) (= 2 (count c))
         (every? number? c)
         (<= -180.0 lon 180.0)
         (<= -90.0 lat 90.0))))

(defn valid-geometry?
  "The item geometry must be a GeoJSON Point already in lon/lat order. A
  point that only fits if swapped is refused as invalid geometry."
  [feature]
  (let [g (get feature "geometry")]
    (and (map? g)
         (= "Point" (get g "type"))
         (plausible-lonlat? (get g "coordinates")))))

;; ── item gate + normalization ────────────────────────────────────────

(defn item->observation-or-refusal
  "One STAC item → one imagery-asset observation, or a refusal that
  names the reason. Every refusal is counted by the caller; nothing is
  silently dropped."
  [feature retrieved-at]
  (let [props (get feature "properties" {})
        id (get feature "id")
        self (some (fn [l] (when (= "self" (get l "rel")) (get l "href")))
                   (get feature "links" []))
        licence-prop (get props "license")
        assets (get feature "assets" {})
        sd (get-in assets ["sd" "href"])
        status (get props "geovisio:status")
        visibility (get props "geovisio:visibility")
        coords (get-in feature ["geometry" "coordinates"])]
    (cond
      (str/blank? id)
      {:ok? false :error :panoramax/missing-asset-id :detail "item has no id"}

      (not= "ready" status)
      {:ok? false :error :panoramax/not-processed
       :detail (str "item " id " geovisio:status=" (pr-str status)
                    "; unprocessed imagery is not admissible")}

      (not= "anyone" visibility)
      {:ok? false :error :panoramax/not-public
       :detail (str "item " id " geovisio:visibility=" (pr-str visibility))}

      (str/blank? licence-prop)
      {:ok? false :error :panoramax/missing-licence
       :detail (str "item " id " carries no license property")}

      (str/blank? self)
      {:ok? false :error :panoramax/missing-evidence-url
       :detail (str "item " id " has no self link")}

      (str/blank? (get props "datetime"))
      {:ok? false :error :panoramax/missing-capture-time
       :detail (str "item " id " has no datetime")}

      (not (valid-geometry? feature))
      {:ok? false :error :panoramax/invalid-geometry
       :detail (str "item " id " geometry is not a plausible lon/lat Point")}

      :else
      (let [acc (get props "quality:horizontal_accuracy")
            azimuth (get props "view:azimuth")
            io (get props "pers:interior_orientation")]
        {:ok? true
         :observation
         {:observation/kind :imagery-asset
          :observation/asset-id (str source-id "-item:" id)
          :observation/source-id source-id
          ;; canonical provider URLs; no pixel is fetched or stored by
          ;; this actor (:no-raw-image-republication-without-rights)
          :observation/source-url self
          :observation/evidence-url (if (str/blank? sd) self sd)
          :observation/capture-time (get props "datetime")
          :observation/capture-time-note
          "STAC properties.datetime (UTC), as published by the provider"
          :observation/ingested-at retrieved-at
          :observation/footprint
          {:type "Point" :coordinates coords}
          :observation/crs "EPSG:4326 (lon,lat order)"
          :observation/spatial-uncertainty-m (or acc :unknown)
          :observation/spatial-uncertainty-note
          "provider quality:horizontal_accuracy in metres (95% interval); :unknown stays visible when the item omits it"
          :observation/orientation
          (if (some? azimuth)
            {:heading-deg azimuth :projection :equirectangular-panorama}
            :unknown)
          :observation/sequence-id (get props "collection")
          :observation/sequence-index (get props "geovisio:rank_in_collection")
          :observation/geometry-dimensions
          (if-some [dims (get io "sensor_array_dimensions")]
            {:width-px (first dims) :height-px (second dims)}
            :unknown)
          :observation/resolution-or-gsd :unknown
          :observation/resolution-or-gsd-note
          "provider publishes sensor dimensions only, no ground sample distance"
          :observation/sensor
          (if-some [cam (get io "camera_model")]
            {:model cam :make (get io "camera_manufacturer") :unknown false}
            :unknown)
          :observation/sensor-note
          "from pers:interior_orientation; the raw EXIF block (which also carries uploader metadata) is never copied"
          :observation/spectral-bands :unknown
          :observation/spectral-bands-note "street photograph; bands not declared by the provider"
          :observation/licence licence-prop
          :observation/licence-url terms-url
          :observation/attribution attribution
          :observation/privacy
          {:provider-blur-verified false
           :note "the Panoramax platform documents automatic blurring on its public instances, but the item API publishes no per-item blur-result flag; unprocessed (status≠ready) and non-public items are refused, and the provider's blur story is recorded as platform-level, not verified here"}
          :observation/uncertainty-note
          "one picture is an observation at capture time, not current existence"}}))))

(defn- demote-keys
  "js->clj keywordizes keys, but the provider field names contain `:`
  and `.` (geovisio:status, pers:interior_orientation) — coerce every
  key back to its string form so the published field names are the
  interface, in-process or from the CLI."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [(if (keyword? k) (name k) k) (demote-keys v)])
                           x))
    (vector? x) (mapv demote-keys x)
    (seq? x) (map demote-keys x)
    :else x))

(defn normalize-payload
  "One search response (STAC FeatureCollection) → {:ok? true
  :observations [...] :refusals [...] :counts {...}}. The envelope must
  actually be a FeatureCollection; per-item refusals are counted, never
  dropped. Only items whose point falls inside the bbox are accepted —
  the published bound is what we keep, not what the API chose to return."
  [payload {:keys [bbox retrieved-at]}]
  (let [payload (demote-keys payload)]
    (if (or (not (map? payload))
            ;; the aggregate /api/search answers {"features", "links"}
            ;; with no "type"; a plain FeatureCollection is also fine
            (and (not= "FeatureCollection" (get payload "type"))
                 (not (contains? payload "features"))))
      {:ok? false :error :panoramax/bad-envelope
       :detail (str "response is not a STAC FeatureCollection: "
                    (pr-str (get payload "type")))}
      (let [features (get payload "features" [])
          in-bbox? (fn [f]
                     (let [[lon lat] (get-in f ["geometry" "coordinates" ] [nil nil])
                           [w s e n] bbox]
                       (and (number? lon) (number? lat)
                            (<= w lon e) (<= s lat n))))
          results (map (fn [f]
                         (assoc (item->observation-or-refusal f retrieved-at)
                                :raw f
                                :in-bbox (in-bbox? f)))
                       features)
          accepted (filter #(and (:ok? %) (:in-bbox %)) results)
          refused (remove :ok? results)
          out-of-bbox (count (filter #(and (:ok? %) (not (:in-bbox %))) results))
          redacted (filter (fn [r] (redacted? (:observation r))) accepted)]
      (if (not= (count redacted) (count accepted))
        {:ok? false :error :panoramax/redaction-leak
         :detail "an accepted observation carried uploader-identifying metadata; refusing the whole run"}
        {:ok? true
         :observations (mapv :observation accepted)
         :refusals (mapv #(select-keys % [:error :detail]) refused)
         :counts {:fetched (count features)
                  :accepted (count accepted)
                  :refused (count refused)
                  :returned-outside-bbox out-of-bbox
                  :links-next (some (fn [l] (when (= "next" (get l "rel"))
                                              (get l "href")))
                                    (get payload "links" []))}})))))

;; ── provenance ───────────────────────────────────────────────────────

(defn provenance
  "One provenance block per run. `retrieved-at` is the wall-clock instant
  the provider payload was fetched (ISO-8601 string); `input-sha256` is
  the hash of the exact response bytes analyzed."
  [{:keys [area-id bbox retrieved-at input-sha256]}]
  {:provenance/system-id :otent-geospatial-vision
   :provenance/source-id source-id
   :provenance/source-url api-url
   :provenance/asset-id (str "panoramax-items:" area-id)
   :provenance/capture-time :unknown
   :provenance/capture-time-note "each observation carries its own datetime; the area has no single capture time"
   :provenance/ingested-at retrieved-at
   :provenance/licence licence
   :provenance/licence-url terms-url
   :provenance/attribution attribution
   :provenance/content-hash input-sha256
   :provenance/parameters {:area-id area-id :bbox bbox
                           :max-results max-results
                           :accept-only "STAC items, status=ready, visibility=anyone, per-item license present"}
   :provenance/derived-from :panoramax-open-data
   :provenance/run-at retrieved-at
   :provenance/crs "EPSG:4326 (lon,lat order)"})
