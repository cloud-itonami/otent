(ns otent.natural-earth
  "Natural Earth raster assets, described before they are fetched.

  Natural Earth is public-domain map data maintained by NACIS. The
  official distribution is the `naturalearth` S3 bucket, which lists its
  objects publicly and serves them keyless -- no credential, no scraping,
  no terms that depend on a login. One bounded asset per run: a fixed
  zip whose bytes are stable across fetches, so the expected sha256 can
  be pinned in the catalog and a different download is a REFUSAL, not a
  quiet overwrite.

  Pure -- catalogue, sizes and byte prefixes in, verdicts out. No clock,
  no network, no file handle: the network lives in `bin/natural_earth.cljs`
  and calls these functions to decide what it may do.")

;; The zip is stored as shipped, entry names and all; nothing is
;; re-packed or renamed, so what lands in R2 is bit-identical to what
;; Natural Earth published.
(def ^:private sha-NE1_50M_SR_W
  "sha256 of NE1_50M_SR_W.zip, measured 2026-09-01 from
  https://naturalearth.s3.amazonaws.com/50m_raster/NE1_50M_SR_W.zip and
  confirmed stable across the fetch used to measure it. A mismatch is an
  integrity refusal: the object changed upstream, and ingesting changed
  bytes under a pinned identity is how provenance starts lying."
  "9e85417223414bbed425aea5dca0f0b4c5661fc94d4f14494140a21a08dfa450")

(def assets
  {"NE1_50M_SR_W"
   {:id "NE1_50M_SR_W"
    :title "Natural Earth I with Shaded Relief and Water, 1:50m"
    :kind :imagery-asset
    :url "https://naturalearth.s3.amazonaws.com/50m_raster/NE1_50M_SR_W.zip"
    :object-format "application/zip"
    :entries ["NE1_50M_SR_W/"
              "NE1_50M_SR_W/NE1_50M_SR_W.tif"
              "NE1_50M_SR_W/NE1_50M_SR_W.tfw"
              "NE1_50M_SR_W/Read_me.txt"]
    :tiff-bytes 175041354
    :width-px 10800
    :height-px 5400
    ;; Measured from the shipped .tfw, not assumed: 0.03333333333333
    ;; degrees per pixel, origin -179.98333333333333 / 89.98333333333333.
    :degrees-per-pixel 0.03333333333333
    :gsd "~3.7 km at the equator (0.033333 deg/px, 1:50 million scale)"
    :crs "EPSG:4326 (Geographic, WGS84 datum -- per the shipped Read_me.txt)"
    ;; A cartographic composite, not a sensor capture: it has no capture
    ;; time, and saying so IS the provenance. Inventing a date would be
    ;; worse than leaving the field honest.
    :sensor "none (cartographic composite raster: satellite-derived land cover + shaded relief)"
    :spectral-bands "RGB composite GeoTIFF + TFW world file"
    ;; The capture-time question is answered by the asset's nature: a
    ;; static composite. The field is present and states the fact.
    :capture-time {:type :static-composite
                   :detail "no single capture time; satellite-derived land cover composited for the 1:50m release, shipped TFW/Read_me dated 2009-2010"}
    :licence "public-domain"
    :licence-detail "Natural Earth is public domain (no rights reserved); see naturalearthdata.com/about/terms-of-use"
    :attribution "Made with Natural Earth. Free vector and raster map data @ naturalearthdata.com."
    :sha256 sha-NE1_50M_SR_W
    :min-bytes 60000000
    :max-bytes 120000000}})

(def zip-magic
  "PK\\x03\\x04, little-endian. Checked before a download is called a
  zip -- a 404 HTML page has a Content-Length too."
  "PK\u0003\u0004")

(defn get-asset [id]
  (get assets (str id)))

(defn plan
  "Is this a known asset, and is a fetch of it within declared bounds?

  Returns `{:ok? true :asset a}` or a refusal that names the reason.
  The size band is a control, not decoration: today's Content-Length is
  checked against it before the body is read, so a bucket that starts
  serving something else entirely is refused at the header, not after
  88 MB."
  [id]
  (if-let [a (get-asset id)]
    {:ok? true :asset a}
    {:ok? false :error :natural-earth/unknown-asset
     :detail (str "no Natural Earth asset is catalogued under " (pr-str id)
                  "; known: " (pr-str (vec (sort (keys assets)))))}))

(defn size-ok?
  [asset n]
  (and (number? n) (>= n (:min-bytes asset)) (<= n (:max-bytes asset))))

(defn check-size
  "Declared Content-Length against the asset's bounds."
  [asset n]
  (cond
    (not (number? n))
    {:ok? false :error :natural-earth/no-content-length
     :detail "the response declared no Content-Length; a size bound cannot be honoured without one"}

    (not (size-ok? asset n))
    {:ok? false :error :natural-earth/size-out-of-bounds
     :detail (str "Content-Length " n " is outside the declared band ["
                  (:min-bytes asset) ", " (:max-bytes asset) "] for " (:id asset))}

    :else {:ok? true :bytes n}))

(defn check-magic
  "First bytes against the zip magic."
  [^js buf]
  (let [u8 (js/Uint8Array. (.slice buf 0 4))
        prefix (apply str (map #(js/String.fromCharCode (aget u8 %)) (range 4)))]
    (if (= prefix zip-magic)
      {:ok? true}
      {:ok? false :error :natural-earth/not-a-zip
       :detail (str "the body does not start with the zip magic; got "
                    (pr-str prefix))})))

(defn check-sha
  "Measured hash against the pinned one. A mismatch means the asset
  changed upstream -- REFUSE, do not record new bytes under the old
  identity."
  [asset measured]
  (if (= measured (:sha256 asset))
    {:ok? true}
    {:ok? false :error :natural-earth/sha-mismatch
     :detail (str "measured sha256 " measured " does not match the pinned "
                  (:sha256 asset) " for " (:id asset)
                  " -- the published zip changed; re-measure before re-ingesting")}))

(defn manifest
  "The provenance record for one ingested asset, every field the scope
  requires a name for:

    :source-id :source-url :asset-id :capture-time :ingested-at
    :footprint :crs :resolution-or-gsd :sensor :spectral-bands
    :licence :attribution :content-hash

  plus where the bytes actually landed. `retrieved-at` is ISO-8601 from
  the caller (the bin layer owns the clock); `key` is the R2 object key
  the bytes were PUT under."
  [asset {:keys [key retrieved-at bytes]}]
  {:source-id :natural-earth
   :source-url (:url asset)
   :asset-id (:id asset)
   :capture-time (:capture-time asset)
   :ingested-at retrieved-at
   :footprint {:type "Polygon"
               :coordinates [[[-180.0 -90.0] [180.0 -90.0]
                              [180.0 90.0] [-180.0 90.0]
                              [-180.0 -90.0]]]}
   :crs (:crs asset)
   :resolution-or-gsd {:scale "1:50 million"
                       :degrees-per-pixel (:degrees-per-pixel asset)
                       :width-px (:width-px asset)
                       :height-px (:height-px asset)
                       :approximate-ground-size (:gsd asset)}
   :sensor (:sensor asset)
   :spectral-bands (:spectral-bands asset)
   :licence (:licence asset)
   :licence-detail (:licence-detail asset)
   :attribution (:attribution asset)
   :content-hash {:algorithm "sha256" :value (:sha256 asset)}
   :object {:key key :bytes bytes :content-type (:object-format asset)}})
