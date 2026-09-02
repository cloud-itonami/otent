(ns otent.kartaview-image
  "One KartaView **image pixel sample**: one bounded photo, its processed
  image bytes fetched exactly once, hashed, and stored only under an
  explicit licence permission.

  Scope (`otent-vision-scope.edn`): the source is :kartaview-open-data
  (street-allow). KartaView metadata (PR #12) and one derived density task
  (PR #34) are already ingested with no pixel ever fetched; this ns is the
  KartaView counterpart of the Panoramax pixel sample (PR #39), and earns
  the exception the same way rather than assuming it:

  - **permission basis, checked per photo**: the KartaView terms of use
    publish street images CC-BY-SA 4.0, but that alone is not enough — a
    photo is pixel-admissible only when it is `public`, `active`,
    provider-processed `BLURRED` (faces and plates must stay blurred), and
    inside the run's bbox. A photo with any other processing result
    (including `UNPROCESSED` or a missing flag) keeps its metadata
    observation but is refused the pixel fetch.
  - **one photo, one pixel request**: the pixel URL is the photo's own
    published processed-image URL, fetched once; `hasMoreData` and sibling
    photos in the same sequence are counted, never followed (:run-bounds).
  - **what survives**: provider photo id, canonical evidence URL, capture
    time (provider `shotDate` string), geometry (lon/lat, refused not
    repaired), heading, sequence id + index, licence as published,
    attribution, retrieval time, sha256 of the exact pixel bytes, byte
    size, and uncertainty (provider `gpsAccuracy`, or `:unknown`). Raw
    pixels go to object storage behind `$CF_CATALOG_TOKEN` only; without
    the credential the run reports `nothing written` and exits 2 rather
    than pretending (:unmeasured-is-not-empty).
  - **privacy**: only provider-blurred processed imagery is accepted;
    faces and licence plates stay blurred upstream and can never become
    entities. No person/vehicle tracking or re-identification. A redaction
    check refuses the whole run if an `@` or an exif/email-shaped key
    reaches an emitted record."
  (:require [clojure.string :as str]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "kartaview-image")

(def terms-url "https://kartaview.org/terms")

(def attribution "© KartaView contributors (CC-BY-SA 4.0)")

(def licence "CC-BY-SA 4.0 (KartaView terms of use)")

(def permission-basis
  "Stated on every record that carries raw pixels."
  "KartaView terms of use publish street images CC-BY-SA 4.0; this photo is
  public, active and provider-processed BLURRED, and the attribution is
  carried on this record")

(def exif-keys-forbidden
  ["exif" "ImageDescription" "MAPSettingsEmail" "MAPSettingsUploadHash"])

(defn- redacted?
  "No forbidden key and no `@` anywhere in a string value."
  [v]
  (cond
    (map? v) (and (not-any? (fn [k]
                              (some #(str/includes? (str/lower-case (name k))
                                                    (str/lower-case %))
                                    exif-keys-forbidden))
                            (keys v))
                  (every? (fn [[_ v2]] (redacted? v2)) v))
    (vector? v) (every? redacted? v)
    (string? v) (not (str/includes? v "@"))
    :else true))

(defn- plausible-lonlat?
  [[lon lat :as c]]
  (and (vector? c) (= 2 (count c)) (every? number? c)
       (<= -180.0 lon 180.0) (<= -90.0 lat 90.0)))

;; ── the one pixel request this run may make ──────────────────────────

(defn pixel-url-of
  "The photo's own published processed-image URL. Anything else (the raw
  `imageUrl`, sequence siblings, next pages) is not requested."
  [photo]
  (get photo "imageProcUrl"))

;; ── per-photo pixel permission ───────────────────────────────────────

(defn photo->pixel-permission
  "One KartaView photo → {:ok? true :photo photo} when the pixel fetch is
  permitted, or a refusal naming the reason. Gates, in order: id, active
  (deletion/takedown respect), public, provider-blurred, geometry, capture
  time, pixel url. A photo without the blur flag is refused, never
  normalized."
  [photo]
  (let [id (get photo "id")]
    (cond
      (str/blank? id)
      {:ok? false :error :kartaview-image/missing-asset-id :detail "photo has no id"}

      (not= "active" (get photo "status"))
      {:ok? false :error :kartaview-image/not-active
       :detail (str "photo " id " status=" (pr-str (get photo "status"))
                    "; withdrawn photos are not re-admitted")}

      (not= "public" (get photo "visibility"))
      {:ok? false :error :kartaview-image/not-public
       :detail (str "photo " id " visibility=" (pr-str (get photo "visibility")))}

      (not= "BLURRED" (get photo "autoImgProcessingResult"))
      {:ok? false :error :kartaview-image/not-provider-blurred
       :detail (str "photo " id " autoImgProcessingResult="
                    (pr-str (get photo "autoImgProcessingResult"))
                    "; only BLURRED imagery is pixel-admissible")}

      :else
      (let [lon (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lng"))
            lat (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lat"))]
        (cond
          (or (js/isNaN lon) (js/isNaN lat) (not (plausible-lonlat? [lon lat])))
          {:ok? false :error :kartaview-image/invalid-geometry
           :detail (str "photo " id " coordinates do not parse as a plausible lon/lat")}

          (str/blank? (get photo "shotDate"))
          {:ok? false :error :kartaview-image/missing-capture-time
           :detail (str "photo " id " has no shotDate")}

          (str/blank? (pixel-url-of photo))
          {:ok? false :error :kartaview-image/missing-pixel-url
           :detail (str "photo " id " has no processed-image URL")}

          :else {:ok? true :photo photo})))))

;; ── normalization: the observation record ────────────────────────────

(defn- num-or-unknown [s]
  (let [n (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) s)]
    (if (js/isNaN n) :unknown n)))

(defn image->record
  "One admissible photo + its fetched pixel bytes → the imagery-asset
  observation. `pixel` is {:sha256 :byte-size :content-type}; the bytes
  themselves never enter the record. Deterministic given the same inputs."
  [photo pixel retrieved-at]
  (let [id (get photo "id")
        evidence-url (str "https://kartaview.org/sequence/"
                          (get photo "sequenceId") "/")]
    {:observation/kind :imagery-asset
     :observation/asset-id (str source-id "-photo:" id)
     :observation/source-id source-id
     :observation/source-url (pixel-url-of photo)
     :observation/evidence-url evidence-url
     :observation/capture-time (get photo "shotDate")
     :observation/capture-time-note
     "provider shotDate string, as published; timezone as recorded by the provider"
     :observation/ingested-at retrieved-at
     :observation/footprint
     {:type "Point"
      :coordinates [(#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lng"))
                    (#?(:cljs js/parseFloat :clj #(Double/parseDouble %)) (get photo "lat"))]}
     :observation/crs "EPSG:4326 (lon,lat order)"
     :observation/spatial-uncertainty-m (num-or-unknown (get photo "gpsAccuracy"))
     :observation/spatial-uncertainty-note
     "provider gpsAccuracy in metres; matched position (matchLat/matchLng) additionally snapped to OSM geometry; :unknown stays visible when the provider omits it"
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
     :observation/pixel
     {:sha256 (:sha256 pixel)
      :byte-size (:byte-size pixel)
      :content-type (:content-type pixel)
      :stored (:stored pixel false)
      :requests-made 1
      :permission-basis permission-basis}
     :observation/licence licence
     :observation/licence-url terms-url
     :observation/attribution attribution
     :observation/privacy
     {:provider-blurred true
      :note "only provider-processed BLURRED imagery accepted; faces and plates are blurred upstream and are never entities here"}
     :observation/uncertainty-note
     "one photo is an observation at capture time, not current existence"}))

;; ── last gate before anything is written ─────────────────────────────

(defn check-record
  "The last gate before anything is written: the record must carry no
  uploader identity and must name licence, attribution, pixel sha256 and
  permission basis. A violation refuses the whole run."
  [record]
  (let [lic (:observation/licence record)]
    (cond
      (not (redacted? record))
      {:ok? false :error :kartaview-image/privacy-redaction
       :detail "the record carries an `@` or a forbidden exif-shaped key"}

      (or (str/blank? (str lic)) (= :unknown lic))
      {:ok? false :error :kartaview-image/missing-licence
       :detail "the record does not name a licence"}

      (str/blank? (str (:sha256 (:observation/pixel record))))
      {:ok? false :error :kartaview-image/missing-pixel-hash
       :detail "the record does not carry the pixel sha256"}

      (str/blank? (str (get-in record [:observation/pixel :permission-basis])))
      {:ok? false :error :kartaview-image/missing-permission-basis
       :detail "raw pixels without a stated permission basis are never written"}

      :else {:ok? true})))
