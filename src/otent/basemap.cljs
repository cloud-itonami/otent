(ns otent.basemap
  "The pure half of `bin/basemap.cljs`: source definitions, tile maths,
   refusal rules and the shapes of the provenance records and manifest.

    Everything here is deterministic and tested by
    `otent.basemap-test`; the bin script keeps only the network I/O.
    A fetch is not provenance, so every raster source here carries the
    fields rule 3 of the ingest scope asks for: asset id, licence,
    attribution, CRS, tile geometry, sensor/bands and -- for the daily
    layer -- the capture date the bytes belong to.")

;; ------------------------------------------------------------------ sources

(def account "4da88288dc30d9ee257f319d3c33ecf0")
(def bucket "cloud-itonami-datalake")
(def prefix "otent/basemap")

(def raster-sources
  [{:id "blue-marble"
    :label "NASA GIBS BlueMarble_ShadedRelief_Bathymetry"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "BlueMarble_ShadedRelief_Bathymetry/default/"
                       "GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpeg")
    :time-mode :static
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "Blue Marble Next Generation + bathymetry composite"
    :bands "RGB shaded relief"
    ;; The layer is served to level 8. Going past it returns the last level
    ;; upsampled, which looks like more detail and is not.
    :max-source-zoom 8
    :max-ingest-zoom 8}

   {:id "modis-terra-truecolor"
    :label "NASA GIBS MODIS Terra CorrectedReflectance TrueColor (daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / MODIS Terra"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "MODIS_Terra_CorrectedReflectance_TrueColor/default/"
                       "{date}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpeg")
    :time-mode :daily
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "MODIS (Terra)"
    :bands "bands 1,4,3 as RGB true colour"
    :native-gsd "250 m"
    :max-source-zoom 9
    ;; The service goes to level 9, but the whole planet at z9 is 349,525
    ;; tiles a day. The ingest is BOUND to z4 -- 341 tiles per date -- and
    ;; the manifest records the bound as the coverage, not the service's.
    :max-ingest-zoom 4}

   {:id "viirs-noaa20-truecolor"
    :label "NASA GIBS VIIRS NOAA-20 CorrectedReflectance TrueColor (daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / VIIRS NOAA-20"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/"
                       "{date}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpeg")
    :time-mode :daily
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "VIIRS (NOAA-20)"
    :bands "bands M5, M4, M3 as RGB true colour"
    :native-gsd "375 m"
    ;; Same service, same shape as the MODIS layer: served to level 9,
    ;; bound to z4 -- 341 tiles per capture date.
    :max-source-zoom 9
    :max-ingest-zoom 4}

   {:id "viirs-snpp-truecolor"
    :label "NASA GIBS VIIRS SNPP CorrectedReflectance TrueColor (daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / VIIRS Suomi NPP"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "VIIRS_SNPP_CorrectedReflectance_TrueColor/default/"
                       "{date}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpeg")
    :time-mode :daily
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "VIIRS (Suomi NPP)"
    :bands "bands M5, M4, M3 as RGB true colour"
    :native-gsd "375 m"
    ;; The third daily true-colour layer on the same service: served to
    ;; level 9, bound to z4 -- 341 tiles per capture date.
    :max-source-zoom 9
    :max-ingest-zoom 4}

   {:id "landsat-weld-truecolor-annual"
    :label "NASA GIBS Landsat WELD CorrectedReflectance TrueColor Global Annual"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / Landsat 5 and 7 (WELD)"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/"
                       "{date}/GoogleMapsCompatible_Level12/{z}/{y}/{x}.jpeg")
    :time-mode :annual
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "Landsat 5 TM / Landsat 7 ETM+ (WELD annual composite)"
    :bands "bands 3,2,1 as RGB true colour"
    :native-gsd "30 m"
    ;; Unlike the daily reflectance layers this composite is SPARSE: the
    ;; service 404s over ocean and anywhere the WELD product has no data,
    ;; so a missing tile is an honest hole in coverage, not a failure.
    ;; The service serves level 12; the ingest is bound to z4 -- 341
    ;; candidate tiles per declared year, of which only the land ones
    ;; exist. Each run takes ONE declared composite year (the `date` is
    ;; the year's period start, e.g. 1998-12-01).
    :max-source-zoom 12
    :max-ingest-zoom 4
    :sparse-coverage true}

   {:id "modis-terra-ndvi-8day"
    :label "NASA GIBS MODIS Terra NDVI (rolling 8-Day)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / MODIS Terra NDVI"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "MODIS_Terra_NDVI_8Day/default/"
                       "{date}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.png")
    :time-mode :8day
    :format "png"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "MODIS (Terra)"
    :bands "NDVI derived from MODIS bands (250 m, colour-mapped by GIBS)"
    :native-gsd "250 m"
    ;; A rolling 8-day product: the service files it under the PERIOD'S
    ;; START date, on an 8-day grid with one-day gaps between windows
    ;; (verified live: 2026-08-30 serves, 2026-08-31 404s). Like the
    ;; annual composite it gets NO wall-clock default -- a period nobody
    ;; declared must be refused, not guessed. Tiles exist everywhere
    ;; (ocean comes back as an empty PNG, not a 404), so this is NOT a
    ;; sparse source. Served to level 9, bound to z4 -- 341 candidate
    ;; tiles per declared period, same as every daily layer.
    :max-source-zoom 9
    :max-ingest-zoom 4}

   {:id "modis-terra-lst-day"
    :label "NASA GIBS MODIS Terra Land Surface Temperature (Day, daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / MODIS Terra LST"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "MODIS_Terra_Land_Surface_Temp_Day/default/"
                       "{date}/GoogleMapsCompatible_Level7/{z}/{y}/{x}.png")
    :time-mode :daily
    :format "png"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "MODIS (Terra)"
    :bands "land surface temperature (MODIS bands 31/32, colour-mapped by GIBS)"
    :native-gsd "1 km"
    ;; A science-parameter layer, not reflectance: the colour ramp is the
    ;; data. Served to level 7 (1 km product), bound to z4 -- 341 tiles per
    ;; capture date. The day's tiles publish the day after capture: a
    ;; same-day date answers 404, which is an HTTP error, not a hole.
    :max-source-zoom 7
    :max-ingest-zoom 4}

   {:id "modis-aqua-bands721"
    :label "NASA GIBS MODIS Aqua CorrectedReflectance Bands721 (daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / MODIS Aqua"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "MODIS_Aqua_CorrectedReflectance_Bands721/default/"
                       "{date}/GoogleMapsCompatible_Level9/{z}/{y}/{x}.jpeg")
    :time-mode :daily
    :format "jpeg"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "MODIS (Aqua)"
    :bands "bands 7,2,1 as RGB false colour (SWIR burn/flood discrimination)"
    :native-gsd "500 m"
    ;; Same service, same shape as the Terra Bands721 layer, but the
    ;; afternoon overpass: the same date is a genuinely different
    ;; observation, and only Aqua works when Terra's daytime track is
    ;; degraded. Served to level 9, bound to z4 -- 341 tiles per capture
    ;; date. The day's tiles publish the day after capture: a same-day
    ;; date answers 404, an HTTP error, not a hole.
    :max-source-zoom 9
    :max-ingest-zoom 4}

   {:id "viirs-snpp-dnb-radiance"
    :label "NASA GIBS VIIRS SNPP Day/Night Band At-Sensor Radiance (daily)"
    :licence "NASA -- public domain"
    :attribution "NASA EOSDIS GIBS / VIIRS Suomi NPP DNB"
    :url-template (str "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/"
                       "VIIRS_SNPP_DayNightBand_At_Sensor_Radiance/default/"
                       "{date}/GoogleMapsCompatible_Level8/{z}/{y}/{x}.png")
    :time-mode :daily
    :format "png"
    :crs "EPSG:3857"
    :tile-size 256
    :sensor "VIIRS (Suomi NPP)"
    :bands "day/night band (DNB) at-sensor radiance, greyscale night lights"
    :native-gsd "750 m"
    ;; Night lights, not reflectance: the satellite only sees the night
    ;; side of its orbit, and GIBS answers 404 where no DNB granule
    ;; covered a tile that day (verified live: a Pacific night-side tile
    ;; 404s while populated night-side tiles answer 200 with PNG magic).
    ;; So this is a SPARSE source -- a 404 is an honest hole in the
    ;; day's coverage, not an error. Served to level 8 (750 m product),
    ;; bound to z4 -- 341 candidate tiles per declared capture date.
    ;; The day's tiles publish the day after capture: a same-day date
    ;; answers 404, which is also just a hole, not a refusal to store.
    :max-source-zoom 8
    :max-ingest-zoom 4
    :sparse-coverage true}])

(def vector-sources
  [{:id "coastline"
    :label "Natural Earth 110m coastline"
    :licence "public domain (CC0)"
    :url "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_coastline.geojson"}
   {:id "borders"
    :label "Natural Earth 110m admin-0 land boundaries"
    :licence "public domain (CC0)"
    :url "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_boundary_lines_land.geojson"}])

(defn dated?
  "True when a source is keyed by a capture period: :daily layers take a
   capture date, :annual composites take the year's period start, :8day
   products take the 8-day window's start. Only :static layers ignore it."
  [source] (not= :static (:time-mode source)))

(defn- src [id] (some #(when (= (:id %) id) %) raster-sources))

(defn source-for
  "The raster source with this id, or a refusal map."
  [id]
  (or (src id)
      {:refusal :licence/unknown-source
       :detail (str "no raster source registered with id " (pr-str id)
                    " -- adding one means adding it here, with a licence, first")}))

;; ------------------------------------------------------------------ dates

(def date-re #"^\d{4}-\d{2}-\d{2}$")

(defn valid-date?
  "Shape check only: the GIBS `TIME` value must be a bare YYYY-MM-DD.
   Whether that day actually has imagery is a property of the service,
   and a missing day is an HTTP error, not a local refusal."
  [s] (boolean (and (string? s) (re-find date-re s))))

;; ------------------------------------------------------------------ tiles

(defn tiles-to-zoom
  "Every XYZ tile from z=0 to `max-z`. 4^z tiles per level, so this is
  (4^(max-z+1) - 1) / 3 in total -- 341 at z4, 5,461 at z6. Printed by the
  caller before any request goes out, because the difference between those
  two numbers is the difference between a minute and twenty."
  [max-z]
  (for [z (range (inc max-z)), x (range (bit-shift-left 1 z)), y (range (bit-shift-left 1 z))]
    [z x y]))

(defn tile-url
  "The source URL for one tile. `date` is required for dated sources
   (:daily, :annual) and ignored by :static ones."
  ([source tile] (tile-url source tile nil))
  ([source [z x y] date]
   (let [t (-> (:url-template source)
               (clojure.string/replace "{z}" (str z))
               (clojure.string/replace "{x}" (str x))
               (clojure.string/replace "{y}" (str y)))]
     (if (and (dated? source) date)
       (clojure.string/replace t "{date}" (str date))
       t))))

(defn tile-key
  "The R2 object key for one tile. A dated layer is keyed by capture
   period, so two days (or two composite years) can sit side by side and
   the manifest says which exists."
  ([source tile] (tile-key source tile nil))
  ([source [z x y] date]
   (str prefix "/" (:id source)
        (when (dated? source) (str "/" date))
        "/" z "/" x "/" y "."
        (case (:format source) "png" "png" "jpg"))))

;; ------------------------------------------------------------------ refusals

(defn ingest-refusal
  "Why this ingest must not run, or nil if it may.

  Three refusals, each a different lie the ingest could otherwise tell:
  past the source's own max zoom it would store upsampled bytes and call
  them detail; past the ingest bound it would crawl the planet a day at a
  time; without a well-formed date a dated run would silently fetch
  `default` and never say what day (or year) the pixels are."
  [source max-z date]
  (cond
    (:refusal source) ;; already a refusal from source-for
    source

    (> max-z (:max-source-zoom source))
    {:refusal :source/past-max-zoom
     :detail (str "zoom " max-z " is past the layer's own maximum of "
                  (:max-source-zoom source)
                  " -- past it the service upsamples, which looks like more detail and is not")}

    (> max-z (:max-ingest-zoom source))
    {:refusal :source/past-ingest-bound
     :detail (str "zoom " max-z " is past this run's bound of "
                  (:max-ingest-zoom source)
                  " tiles-to-fetch (" (count (tiles-to-zoom (:max-ingest-zoom source)))
                  " tiles) -- unbounded planet crawl is refused")}

    (and (dated? source) (not (valid-date? date)))
    {:refusal :source/capture-date-required
     :detail (str "a dated source needs a YYYY-MM-DD capture period, got "
                  (pr-str date))}))

(defn ingest-plan
  "The full plan for one raster ingest: the source, the tiles it will
   fetch, the URL and object key of each, and any refusal. Pure, so the
   plan can be asserted before a single request goes out."
  [source-id max-z date]
  (let [source (source-for source-id)
        refusal (ingest-refusal source max-z date)]
    (if refusal
      {:ok? false :refusal (:refusal refusal) :detail (:detail refusal)}
      (let [tiles (tiles-to-zoom max-z)]
        {:ok? true
         :source source
         :date (when (dated? source) date)
         :tile-count (count tiles)
         :tiles (map (fn [t]
                       {:tile t
                        :url (tile-url source t date)
                        :key (tile-key source t date)})
                     tiles)}))))

;; ------------------------------------------------------------------ records

(defn tile-provenance
  "The provenance line for one stored tile. The sha256 is of the bytes as
   fetched; `retrieved-at` is the wall clock of the ingest run, supplied
   by the caller so this stays pure."
  [{:keys [source url key sha256 date retrieved-at content-type]}
   [z x y]]
  {:asset-id (str (:id source) (when date (str "@" date)) "/" z "/" x "/" y)
   :object-key key
   :source-url url
   :capture-time (if date (str date "T00:00:00Z") nil)
   :retrieved-at retrieved-at
   :content-sha256 sha256
   :content-type content-type
   :crs (:crs source)
   :tile-zoom z :tile-x x :tile-y y
   :tile-size (:tile-size source)
   :sensor (:sensor source)
   :bands (:bands source)
   :licence (:licence source)
   :attribution (:attribution source)})

(defn manifest-imagery-entry
  "What the manifest records for one imagery source: exactly what exists,
   at the bound it was fetched to, with the capture date a daily layer
   was taken on. `max-z` is the zoom MEASURED in the bucket, not asked
   for -- a partially written level shows up as holes, not as a ceiling."
  [source max-z date tile-count retrieved-at]
  {:id (:id source)
   :label (:label source)
   :licence (:licence source)
   :attribution (:attribution source)
   :crs (:crs source)
   :tile-size (:tile-size source)
   :sensor (:sensor source)
   :bands (:bands source)
   :time-mode (name (:time-mode source))
   :capture-date date
   :retrieved-at retrieved-at
   :prefix (str prefix "/" (:id source) (when date (str "/" date)))
   :scheme "xyz"
   :max-zoom max-z
   :ingest-bound (:max-ingest-zoom source)
   :sparse-coverage (boolean (:sparse-coverage source))
   ;; For a sparse source the caller supplies the count of tiles it
   ;; actually stored (a generic measurement can only count the bound).
   :candidate-tile-count (when (:sparse-coverage source)
                           (count (tiles-to-zoom (:max-ingest-zoom source))))
   :tile-count tile-count
   :format (:format source)})
