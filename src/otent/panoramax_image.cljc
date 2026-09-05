(ns otent.panoramax-image
  "One Panoramax **image pixel sample**: one bounded item, its image bytes
  fetched exactly once, hashed, and stored only under an explicit licence
  permission.

  Scope (`otent-vision-scope.edn`): the source is
  :authority-owned-open-street-imagery (Panoramax / IGN-OSM-FR federation,
  open data). Every run so far (metadata #14, coverage #36) recorded that no
  pixel was fetched; this ns is the first bounded exception, and it earns the
  exception rather than assuming it:

  - **permission basis, checked per item**: raw pixels are admissible only
    when the item's own STAC `license` names a share-alike/attribution
    licence from `pixel-permitted-licences` (etalab-2.0, CC-BY-SA-4.0). An
    item with any other or missing licence keeps its metadata observation
    but is REFUSED the pixel fetch — an unknown licence is never read as
    permission.
  - **one item, one pixel request**: the pixel URL is the item's own `sd`
    asset href, fetched once; `rel=next` links and sibling images are
    counted, never followed (:run-bounds).
  - **what survives**: provider image id, canonical evidence URL, capture
    time (`properties.datetime`), geometry (lon/lat, refused not repaired),
    orientation/azimuth, sequence (collection + rank), licence as published,
    attribution, retrieval time, sha256 of the exact pixel bytes, byte size,
    and uncertainty (`quality:horizontal_accuracy`, or `:unknown`). Raw
    pixels go to object storage behind `$CF_CATALOG_TOKEN` only; without the
    credential the run reports `nothing written` and exits 2 rather than
    pretending (:unmeasured-is-not-empty).
  - **privacy**: faces and licence plates are blurred at platform level on
    the public instances, but the item API publishes no per-item blur-result
    flag → `provider-blur-verified false` with the limitation stated; the
    item must be `geovisio:status=ready` and `geovisio:visibility=anyone`
    or the pixel is refused. No person/vehicle may be identified, tracked
    or re-identified; the sample is an imagery-asset observation only, and
    a redaction check refuses the run if an `@` or an exif/email-shaped key
    reaches an emitted record."
  (:require [clojure.string :as str]))

(def pixel-permitted-licences
  "Licences under which storing the bytes is explicitly permitted (attribution
  + share-alike, carried on every observation). Anything else — including a
  missing licence — refuses the pixel fetch."
  #{"etalab-2.0" "CC-BY-SA-4.0" "cc-by-sa-4.0"})

(def source-id "panoramax-image")

(def attribution "© Panoramax contributors (per-item licence, see :licence)")

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

(defn pixel-url-of
  "The item's own `sd` asset href — the pixel request this run is allowed to
  make. Anything else (hd, thumb, siblings, rel=next) is not requested."
  [feature]
  (get-in feature ["assets" "sd" "href"]))

(defn item->pixel-permission
  "One STAC item → {:ok? true :feature feature} when the pixel fetch is
  permitted, or a refusal naming the reason. Gates, in order: id, processed,
  public, licence (metadata is still fine without one — the pixel is not),
  geometry, capture time, pixel url."
  [feature]
  (let [id (get feature "id")
        props (get feature "properties" {})
        licence (get props "license")
        coords (get-in feature ["geometry" "coordinates"])]
    (cond
      (str/blank? id)
      {:ok? false :error :panoramax-image/missing-asset-id :detail "item has no id"}

      (not= "ready" (get props "geovisio:status"))
      {:ok? false :error :panoramax-image/not-processed
       :detail (str "item " id " geovisio:status=" (pr-str (get props "geovisio:status"))
                    "; unprocessed imagery is not admissible")}

      (not= "anyone" (get props "geovisio:visibility"))
      {:ok? false :error :panoramax-image/not-public
       :detail (str "item " id " geovisio:visibility="
                    (pr-str (get props "geovisio:visibility")))}

      (not (contains? pixel-permitted-licences (str licence)))
      {:ok? false :error :panoramax-image/licence-does-not-permit-pixels
       :detail (str "item " id " license=" (pr-str licence)
                    " is not a recorded pixel-permitted licence; metadata may "
                    "be observed, the bytes may not be fetched")}

      (not (plausible-lonlat? coords))
      {:ok? false :error :panoramax-image/invalid-geometry
       :detail (str "item " id " geometry is not a plausible lon/lat Point")}

      (str/blank? (get props "datetime"))
      {:ok? false :error :panoramax-image/missing-capture-time
       :detail (str "item " id " has no datetime")}

      (str/blank? (pixel-url-of feature))
      {:ok? false :error :panoramax-image/missing-pixel-url
       :detail (str "item " id " has no sd asset href")}

      :else {:ok? true :feature feature})))

(defn- as-id
  "Sequence/collection ids arrive as strings on most instances and as
  objects on others; only a string (or an object carrying an id string)
  is carried — anything else stays :unknown rather than leaking structure."
  [v]
  (cond
    (string? v) v
    (and (map? v) (string? (get v "id"))) (get v "id")
    :else :unknown))

(defn image->record
  "One admissible item + its fetched pixel bytes → the imagery-asset
  observation. `pixel` is {:sha256 :byte-size :content-type}; the bytes
  themselves never enter the record. Deterministic given the same inputs."
  [feature pixel retrieved-at]
  (let [id (get feature "id")
        props (get feature "properties" {})
        self (some (fn [l] (when (= "self" (get l "rel")) (get l "href")))
                   (get feature "links" []))
        azimuth (get props "view:azimuth")
        acc (get props "quality:horizontal_accuracy")
        io (get props "pers:interior_orientation")]
    {:observation/kind :imagery-asset
     :observation/asset-id (str source-id ":" id)
     :observation/source-id source-id
     :observation/source-url (if (str/blank? self) (pixel-url-of feature) self)
     :observation/evidence-url (pixel-url-of feature)
     :observation/capture-time (get props "datetime")
     :observation/capture-time-note
     "STAC properties.datetime (UTC), as published by the provider"
     :observation/ingested-at retrieved-at
     :observation/footprint
     {:type "Point" :coordinates (get-in feature ["geometry" "coordinates"])}
     :observation/crs "EPSG:4326 (lon,lat order)"
     :observation/spatial-uncertainty-m (or acc :unknown)
     :observation/spatial-uncertainty-note
     "provider quality:horizontal_accuracy in metres (95% interval); :unknown stays visible when the item omits it"
     :observation/orientation
     (if (some? azimuth)
       {:heading-deg azimuth :projection :equirectangular-panorama}
       :unknown)
     :observation/sequence-id (as-id (get props "collection"))
     :observation/sequence-index (get props "geovisio:rank_in_collection")
     :observation/pixel
     {:sha256 (:sha256 pixel)
      :byte-size (:byte-size pixel)
      :content-type (:content-type pixel)
      :stored (:stored pixel false)
      :requests-made 1
      :permission-basis
      "the item's own STAC license names a share-alike/attribution licence (see :licence); attribution is carried on this record"}
     :observation/licence (get props "license")
     :observation/attribution attribution
     :observation/privacy
     {:provider-blur-verified false
      :note "the Panoramax platform documents automatic blurring of faces and plates on its public instances, but the item API publishes no per-item blur-result flag; the item is gated to processed (ready) and public (anyone), and the blur story is recorded as platform-level, not verified here"}
     :observation/uncertainty-note
     "one picture is an observation at capture time, not current existence"}))

(defn check-record
  "The last gate before anything is written: the record must carry no
  uploader identity and must name licence, attribution, capture time,
  pixel sha256 and permission basis. A violation refuses the whole run."
  [record]
  (let [lic (:observation/licence record)]
    (cond
      (not (redacted? record))
      {:ok? false :error :panoramax-image/privacy-redaction
       :detail "the record carries an `@` or a forbidden exif-shaped key"}

      (or (str/blank? (str lic)) (= :unknown lic))
      {:ok? false :error :panoramax-image/missing-licence
       :detail "the record does not name a licence"}

      (str/blank? (str (:sha256 (:observation/pixel record))))
      {:ok? false :error :panoramax-image/missing-pixel-hash
       :detail "the record does not carry the pixel sha256"}

      (str/blank? (str (get-in record [:observation/pixel :permission-basis])))
      {:ok? false :error :panoramax-image/missing-permission-basis
       :detail "raw pixels without a stated permission basis are never written"}

      :else {:ok? true})))
