(ns otent.night-lights
  "VIIRS Black Marble night lights -- the pure half of the ingest.

  Nothing here touches the network or a bucket. `bin/night_lights.cljs`
  does the I/O; everything a refusal or a manifest claims lives here so
  it can be tested without a fetch.

  ## Which layer, and why this one

  NASA GIBS `VIIRS_Black_Marble`: the Suomi NPP VIIRS Day/Night Band
  composite of Earth at night. Public domain, keyless, served over the
  same WMTS surface the basemap raster already uses -- but it is NOT the
  basemap: it is an annual composite of a different sensor's radiance,
  useful for darkness analysis, and it has one property no daily true
  colour layer has, which is that it does not change every day. The
  ingest is therefore keyed by COMPOSITE DATE, and the composite date
  must be one the service itself declares.

  ## Measured 2026-09-01, from the live WMTS capabilities

  - EPSG:3857, TileMatrixSet `GoogleMapsCompatible_Level8`, max TileMatrix 8
    (a request past 8 answers HTTP 400, verified -- the source refuses
    rather than upsamples, unlike the daily true colour layers).
  - Format image/png; Time dimension declares exactly two values,
    2012-01-01 and 2016-01-01 (plus `default`, which aliases the most
    recent -- this ingest does not use `default`, because `default` is a
    value that changes under us without anything telling us).
  - At z4 the whole globe is 341 tiles (sum of 4^z, z=0..4), same bound
    the daily true colour sources use. One composite per run.")

(def source
  {:id "viirs-black-marble"
   :layer "VIIRS_Black_Marble"
   :label "NASA GIBS VIIRS Black Marble (Suomi NPP Day/Night Band composite)"
   :licence "NASA -- public domain"
   :attribution "NASA EOSDIS GIBS / Suomi NPP VIIRS DNB Black Marble"
   :source-url "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/1.0.0/WMTSCapabilities.xml"
   :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                      "VIIRS_Black_Marble/default/{composite}/"
                      "GoogleMapsCompatible_Level8/{z}/{y}/{x}.png")
   :format "png"
   :content-type "image/png"
   :crs "EPSG:3857"
   :tile-matrix-set "GoogleMapsCompatible_Level8"
   :scheme "xyz"
   :tile-size 256
   ;; Verified live: z9 answers HTTP 400, so there is no upsampled tail
   ;; to accidentally ingest.
   :max-source-zoom 8
   ;; The Time dimension's declared `Value` list, copied from the
   ;; capabilities. An ingest of a date not in this list would be
   ;; ingesting a guess about what the service serves.
   :composites ["2012-01-01" "2016-01-01"]})

(def ingest-bound-zoom
  "The ingest ceiling, independent of the source ceiling. z4 is 341
  tiles for one composite; the plan prints the count before the first
  request goes out."
  4)

(defn- tiles-to-zoom
  [max-z]
  (for [z (range (inc max-z))
        x (range (bit-shift-left 1 z))
        y (range (bit-shift-left 1 z))]
    [z x y]))

(defn plan
  "The tile plan for one composite at `max-zoom` -- or a refusal.

  Refusals, in the order they are checked:

  - `:plan/missing-composite` -- a composite source ingested with no
    composite date is ingesting `default`, which is a moving target.
  - `:plan/unknown-composite` -- a date the service does not declare.
    Ingesting it might return 200 with an upsampled sibling or an
    error page; either way the manifest would then claim a capture
    that cannot be traced to a service declaration.
  - `:plan/past-source-zoom` -- past z8 the service answers 400.
  - `:plan/past-ingest-bound` -- beyond what one bounded run takes."
  [{:keys [composite max-zoom]}]
  (cond
    (nil? composite)
    {:ok? false :refusal :plan/missing-composite
     :detail "a composite source requires --composite; `default` would be a moving target"
     :declared (:composites source)}

    (not (some #{composite} (:composites source)))
    {:ok? false :refusal :plan/unknown-composite
     :detail (str composite " is not a composite the service declares")
     :declared (:composites source)}

    (> max-zoom (:max-source-zoom source))
    {:ok? false :refusal :plan/past-source-zoom
     :detail (str "zoom " max-zoom " is past the layer's maximum of "
                  (:max-source-zoom source))}

    (> max-zoom ingest-bound-zoom)
    {:ok? false :refusal :plan/past-ingest-bound
     :detail (str "zoom " max-zoom " is past this ingest's bound of "
                  ingest-bound-zoom " (" (count (tiles-to-zoom ingest-bound-zoom)) " tiles)")}

    :else
    (let [tiles (tiles-to-zoom max-zoom)]
      {:ok? true
       :composite composite
       :max-zoom max-zoom
       :tiles tiles
       :tile-count (count tiles)})))

(defn tile-url
  "The source URL for one `[z x y]` tile of `composite`."
  [[z x y] composite]
  (-> (:url-template source)
      (clojure.string/replace "{composite}" (str composite))
      (clojure.string/replace "{z}" (str z))
      (clojure.string/replace "{x}" (str x))
      (clojure.string/replace "{y}" (str y))))

(defn object-key
  "Where the tile lives in the bucket. Date-keyed like the daily
  layers, except the date is a composite, not a capture day."
  [[z x y] composite]
  (str "otent/night-lights/" composite "/" z "/" x "/" y ".png"))

(defn provenance
  "The provenance record for one tile. Every field a later reader needs
  to know what this PNG is and where it came from; the sha256 is of the
  bytes we actually hold, not of anything the source claims."
  [{:keys [tile composite url buf sha256-hex retrieved-at]}]
  (let [[z x y] tile]
    {:asset-id (str (:layer source) "/" composite "/" z "/" x "/" y)
     :object-key (object-key tile composite)
     :source-url url
     :composite composite
     :capture-time composite
     :retrieved-at retrieved-at
     :content-sha256 sha256-hex
     :content-type (:content-type source)
     :format (:format source)
     :crs (:crs source)
     :tile-matrix-set (:tile-matrix-set source)
     :scheme (:scheme source)
     :tile-size (:tile-size source)
     :tile {:z z :x x :y y}
     :sensor "VIIRS Day/Night Band (Suomi NPP)"
     :bands ["DNB radiance composite (blue/yellow)"]
     :resolution-degrees-at-z (/ 360.0 (bit-shift-left 1 z))
     :licence (:licence source)
     :attribution (:attribution source)}))

(defn manifest
  "The coverage manifest for one composite. States exactly what exists:
  the zoom it was ingested to, how many tiles that is, and one
  provenance entry per tile. `measured-max-zoom` is what the BUCKET
  probe found, not what the flag said."
  [{:keys [composite written-at measured-max-zoom entries]}]
  {:version 1
   :written-at written-at
   :source (dissoc source :url-template)
   :prefix "otent/night-lights"
   :composite composite
   :measured-max-zoom measured-max-zoom
   :ingest-bound-zoom ingest-bound-zoom
   :tile-count (count entries)
   :scheme (:scheme source)
   :tile-size (:tile-size source)
   :entries entries})
