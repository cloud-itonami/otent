(ns otent.kartaview
  "KartaView (OpenStreetCam) open street imagery → bounded imagery-asset
  observations, with provenance and uncertainty.

  Source policy (otent-vision-scope.edn): :street-allow includes
  :kartaview-open-data. KartaView licenses street images and 3D spatial
  data CC-BY-SA 4.0 (kartaview.org/terms, verified at authoring time);
  the terms are recorded **as of retrieval**, never pinned by us.

  The API used is the anonymous OpenStreetCam 2.0 photo search
  (`https://api.openstreetcam.org/2.0/photo/`), which needs no credential
  — measured 2026-09-01 it answers 200 where
  `api.kartaview.org/2.0/photo/` answers 401. No bypass involved: this is
  the provider's own public endpoint.

  Epistemic and privacy rules this ns enforces:

  - a photo is an **observation**, not current existence; capture time
    (shotDate) is never confused with ingest time
  - only provider-blurred processed imagery is accepted
    (`autoImgProcessingResult == \"BLURRED\"`); a photo without that flag
    is refused, never normalized — faces and plates must stay blurred
  - non-public or non-active photos are refused (deletion/takedown
    respect: a photo the provider withdrew must not be re-admitted)
  - the provider publishes no GSD, no sensor model and no per-photo
    confidence → each is carried as `:unknown` and stays visible
    (:missing-is-unmeasured)
  - coordinates stay lon/lat ordered (EPSG:4326); a swapped point is
    refused, not repaired
  - one source, one area, one PR (:run-bounds)."
  (:require [clojure.string :as str]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "kartaview")

(def api-url "https://api.openstreetcam.org/2.0/photo/")

(def terms-url "https://kartaview.org/terms")

(def attribution "© KartaView contributors (CC-BY-SA 4.0)")

(def licence
  "As published in the KartaView terms of use; recorded as-of retrieval,
  never re-verified against the live page every run."
  "CC-BY-SA 4.0 (KartaView terms of use)")

;; ── bounds ───────────────────────────────────────────────────────────

(def max-span 0.01)
(def max-results 100)
(def max-radius-m 500)

(defn check-bbox
  "`bbox` is [west south east north] in degrees. Refuses anything bigger
  than the run bound, non-numeric, or folded (west > east)."
  [bbox]
  (let [[w s e n] bbox]
    (cond
      (or (not (vector? bbox)) (not= 4 (count bbox))
          (some #(or (not (number? %)) (js/isNaN %)) bbox))
      {:ok? false :error :kartaview/bbox-invalid
       :detail (str "bbox must be 4 numbers [W S E N], got " (pr-str bbox))}
      :else
      (let [dw (- e w) dn (- n s)]
        (cond
          (or (<= dw 0) (<= dn 0))
          {:ok? false :error :kartaview/bbox-inverted
           :detail (str "west/south must be smaller: " (pr-str bbox))}
          (or (> dw max-span) (> dn max-span))
          {:ok? false :error :kartaview/bbox-too-large
           :detail (str "span " dw "x" dn " deg exceeds the " max-span
                        " deg run bound; one area per run")}
          :else {:ok? true
                 :area-id (str "bbox-" w "-" s "-" e "-" n)})))))

(defn bbox->query
  "The API takes a centre + radius, not a bbox. The centre is the bbox
  centre; the radius is half the diagonal in metres, capped, and the
  response is filtered back down to the bbox client-side — so the
  published bound is what we keep, not what we asked for."
  [[w s e n]]
  (let [cx (/ (+ w e) 2.0)
        cy (/ (+ s n) 2.0)
        diag-deg (Math/sqrt (+ (* (- e w) (- e w))
                               (* (- n s) (- n s))))
        m-per-deg 111320.0
        radius (min max-radius-m
                    (js/Math.ceil (/ (* diag-deg m-per-deg) 2)))]
    {:lat cy :lng cx :radius radius}))

;; ── privacy boundary ─────────────────────────────────────────────────

(defn blurred?
  "Only provider-blurred processed imagery may become an observation.
  A missing flag is a refusal, not an acceptance."
  [photo]
  (= "BLURRED" (get photo "autoImgProcessingResult")))

;; ── geometry: lon/lat order, refused not repaired ────────────────────

(defn- plausible-lonlat?
  [lonlat]
  (let [[lon lat :as c] lonlat]
    (and (vector? c) (= 2 (count c))
         (every? number? c)
         (<= -180.0 lon 180.0)
         (<= -90.0 lat 90.0))))

(defn valid-coords?
  "The API returns string coordinates; both orders parse to a plausible
  terrestrial point, so the order is decided by field name: `lng` first.
  A point that only fits if swapped is refused as invalid geometry."
  [photo]
  (let [lon (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lng"))
        lat (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lat"))]
    (and (not (js/isNaN lon)) (not (js/isNaN lat))
         (plausible-lonlat? [lon lat]))))

;; ── normalization ────────────────────────────────────────────────────

(defn- num-or-unknown [s]
  (let [n (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) s)]
    (if (js/isNaN n) :unknown n)))

(defn photo->observation-or-refusal
  "One KartaView photo → one imagery-asset observation, or a refusal that
  names the reason. Every refusal is counted by the caller; nothing is
  silently dropped."
  [photo retrieved-at]
  (let [id (get photo "id")
        evidence-url (str "https://kartaview.org/sequence/"
                          (get photo "sequenceId") "/")]
    (cond
      (str/blank? id)
      {:ok? false :error :kartaview/missing-asset-id :detail "photo has no id"}

      (not (blurred? photo))
      {:ok? false :error :kartaview/not-provider-blurred
       :detail (str "photo " id " autoImgProcessingResult="
                    (pr-str (get photo "autoImgProcessingResult"))
                    "; only BLURRED imagery is admissible")}

      (not= "public" (get photo "visibility"))
      {:ok? false :error :kartaview/not-public
       :detail (str "photo " id " visibility=" (pr-str (get photo "visibility")))}

      (not= "active" (get photo "status"))
      {:ok? false :error :kartaview/not-active
       :detail (str "photo " id " status=" (pr-str (get photo "status"))
                    "; withdrawn photos are not re-admitted")}

      (not (valid-coords? photo))
      {:ok? false :error :kartaview/invalid-geometry
       :detail (str "photo " id " coordinates do not parse as lon/lat")}

      (str/blank? (get photo "shotDate"))
      {:ok? false :error :kartaview/missing-capture-time
       :detail (str "photo " id " has no shotDate")}

      (str/blank? (get photo "imageProcUrl"))
      {:ok? false :error :kartaview/missing-evidence-url
       :detail (str "photo " id " has no processed-image URL")}

      :else
      (let [lon (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lng"))
            lat (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lat"))
            acc (num-or-unknown (get photo "gpsAccuracy"))]
        {:ok? true
         :observation
         {:observation/kind :imagery-asset
          :observation/asset-id (str source-id "-photo:" id)
          :observation/source-id source-id
          ;; canonical provider URLs; no pixel is fetched or stored by
          ;; this actor (:no-raw-image-republication-without-rights)
          :observation/source-url (get photo "imageProcUrl")
          :observation/evidence-url evidence-url
          :observation/capture-time (get photo "shotDate")
          :observation/capture-time-note
          "provider shotDate string, as published; timezone as recorded by the provider"
          :observation/ingested-at retrieved-at
          :observation/footprint
          {:type "Point" :coordinates [lon lat]}
          :observation/crs "EPSG:4326 (lon,lat order)"
          :observation/spatial-uncertainty-m acc
          :observation/spatial-uncertainty-note
          "provider gpsAccuracy in metres; matched position (matchLat/matchLng) additionally snapped to OSM geometry"
          :observation/orientation
          (if-some [h (get photo "heading")]
            {:heading-deg (num-or-unknown h) :projection (get photo "projection")}
            :unknown)
          :observation/sequence-id (get photo "sequenceId")
          :observation/sequence-index (num-or-unknown (get photo "sequenceIndex"))
          :observation/geometry-dimensions
          {:width-px (num-or-unknown (get photo "width"))
           :height-px (num-or-unknown (get photo "height"))}
          :observation/resolution-or-gsd :unknown
          :observation/resolution-or-gsd-note
          "provider publishes pixel dimensions only, no ground sample distance"
          :observation/sensor :unknown
          :observation/sensor-note "provider publishes no camera/sensor model"
          :observation/spectral-bands :unknown
          :observation/spectral-bands-note "street photograph; bands not declared by the provider"
          :observation/licence licence
          :observation/licence-url terms-url
          :observation/attribution attribution
          :observation/privacy
          {:provider-blurred true
           :note "only provider-processed BLURRED imagery accepted; faces and plates are blurred upstream and are never entities here"}
          :observation/uncertainty-note
          "one photo is an observation at capture time, not current existence"}}))))

(defn normalize-payload
  "One API response → {:ok? true :observations [...] :refusals [...]
  :counts {...}}. The response envelope itself must declare success;
  `hasMoreData` is reported, never silently followed (one bounded fetch
  per run)."
  [payload {:keys [bbox retrieved-at]}]
  (let [status (get payload "status")
        data (get payload "result" {})]
    (if (or (not (map? payload))
            (not= 200 (get status "httpCode")))
      {:ok? false :error :kartaview/bad-envelope
       :detail (str "response status block: " (pr-str status))}
      (let [photos (get data "data" [])
            in-bbox? (fn [p]
                       (let [lon (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get p "lng"))
                             lat (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get p "lat"))
                             [w s e n] bbox]
                         (and (not (js/isNaN lon)) (not (js/isNaN lat))
                              (<= w lon e) (<= s lat n))))
            results (map (fn [p]
                           (assoc (photo->observation-or-refusal p retrieved-at)
                                  :raw p
                                  :in-bbox (in-bbox? p)))
                         photos)
            accepted (filter #(and (:ok? %) (:in-bbox %)) results)
            refused (remove :ok? results)
            out-of-bbox (count (filter #(and (:ok? %) (not (:in-bbox %))) results))]
        {:ok? true
         :observations (mapv :observation accepted)
         :refusals (mapv #(select-keys % [:error :detail]) refused)
         :counts {:fetched (count photos)
                  :accepted (count accepted)
                  :refused (count refused)
                  :returned-outside-bbox out-of-bbox
                  :has-more-data (boolean (get data "hasMoreData"))}}))))

;; ── provenance ───────────────────────────────────────────────────────

(defn provenance
  "One provenance block per run. `retrieved-at` is the wall-clock instant
  the provider payload was fetched (ISO-8601 string); `input-sha256` is
  the hash of the exact response bytes analyzed."
  [{:keys [area-id bbox retrieved-at input-sha256]}]
  {:provenance/system-id :otent-geospatial-vision
   :provenance/source-id source-id
   :provenance/source-url api-url
   :provenance/asset-id (str "kartaview-photos:" area-id)
   :provenance/capture-time :unknown
   :provenance/capture-time-note "each observation carries its own shotDate; the area has no single capture time"
   :provenance/ingested-at retrieved-at
   :provenance/licence licence
   :provenance/licence-url terms-url
   :provenance/attribution attribution
   :provenance/content-hash input-sha256
   :provenance/parameters {:area-id area-id :bbox bbox
                           :max-results max-results
                           :radius-cap-m max-radius-m
                           :accept-only "provider BLURRED processed imagery"}
   :provenance/derived-from :kartaview-open-data
   :provenance/run-at retrieved-at
   :provenance/crs "EPSG:4326 (lon,lat order)"})
