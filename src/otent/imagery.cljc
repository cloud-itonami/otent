(ns otent.imagery
  "Provenance and licence controls for the earth-imagery slice.

  One bounded source: NASA GIBS WMTS, layer
  `BlueMarble_ShadedRelief_Bathymetry`, EPSG:4326 / 500m tile matrix,
  one tile fetched as a bounded sample (test fixture, hashed). The
  control lives here: a licence allowlist, a provenance-completeness
  check that refuses a record missing any required field, and the
  manifest that states exactly what exists -- no more, no less."
  (:require [clojure.set :as set]))

(def licence-allowlist
  "Licences this actor may ingest imagery under. Anything else is a
  refusal, not a judgement call."
  #{:nasa-public-domain :cc0 :public-domain})

(def required-provenance-keys
  "Every provenance record must carry all of these. A record missing any
  one is not a record; it is a rumour about an image."
  #{:asset-id :source-url :capture-time :footprint :crs :resolution-gsd-m
    :sensor :bands :licence :retrieved-at :payload-sha256})

(defn licence-allowed? [licence]
  (contains? licence-allowlist licence))

(defn provenance-complete?
  "True only when every required provenance key is present and non-nil."
  [rec]
  (and (map? rec)
       (empty? (set/difference required-provenance-keys (set (keys rec))))
       (every? (fn [[_ v]] (some? v)) rec)))

(defn refusal
  "The refusal verdict for a licence: :refused with the reason, or nil
  when the licence is on the allowlist."
  [licence]
  (when-not (licence-allowed? licence)
    {:refused true
     :reason (str "licence not on allowlist: " (pr-str licence))}))

(defn manifest
  "The coverage statement: exactly what exists, derived from the
  provenance record. Nothing here may claim coverage the record does
  not back. `:bounds` (optional) carries the footprint when it is not
  the whole planet; `:capture-time` is stated verbatim when the record
  declares a dated acquisition."
  [rec]
  (merge
   {:what-exists
    (str "One bounded GIBS WMTS sample: layer "
         (:layer rec) ", tile matrix " (:tile-matrix rec)
         ", tile z/x/y " (:tile-zxy rec)
         (if (:capture-time rec)
           (str ", declared capture date " (:capture-time rec) ".")
           (str ", " (or (:coverage-note rec)
                         "global EPSG:4326 footprint at level 0.")))
         (when (:coverage-note rec)
           (str " Coverage: " (:coverage-note rec) ".")))
    :asset-id (:asset-id rec)
    :licence (:licence rec)
    :payload-sha256 (:payload-sha256 rec)
    :retrieved-at (:retrieved-at rec)
    :level-0-only (= 0 (first (:tile-zxy rec)))}
   (when (:capture-time rec) {:capture-time (:capture-time rec)})
   {:bounds-epg4326-deg (:bounds rec (:footprint rec))}))

(def sample
  "The provenance record for the bounded sample this slice captured.
  NASA Blue Marble Next Generation (shaded relief + bathymetry) is a
  work of the US government and carries no copyright restriction; the
  layer is static, so capture time is the BMNG composite epoch, not a
  dated acquisition."
  {:asset-id "BlueMarble_ShadedRelief_Bathymetry/500m/0/0/0"
   :layer "BlueMarble_ShadedRelief_Bathymetry"
   :source-url
   "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/BlueMarble_ShadedRelief_Bathymetry/default/500m/"
   :capture-time "2004"
   :capture-note
   "Blue Marble Next Generation static composite; capture time is the
  composite epoch, not a dated satellite acquisition."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 500
   :sensor "BMNG (Blue Marble Next Generation), MODIS-composite shaded relief + bathymetry"
   :bands #{:r :g :b}
   :licence :nasa-public-domain
   :tile-matrix "500m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T07:53:00Z"
   :payload-sha256
   "bcba78c5d01ba5ff545281d3acd77f7429f724f6213bec949f8298c518a963ab"})

(def modis-terra-truecolor-sample
  "The second bounded sample: MODIS Terra CorrectedReflectance TrueColor,
  one EPSG:4326 level-0 tile for ONE declared capture date. Unlike the
  static Blue Marble layer this is a dated acquisition -- the capture
  date is declared by the run, never defaulted from the wall clock, and
  the record carries it verbatim. Same public-domain licence; the ingest
  stays bounded at one tile, level 0."
  {:asset-id "MODIS_Terra_CorrectedReflectance_TrueColor/250m/0/0/0"
   :layer "MODIS_Terra_CorrectedReflectance_TrueColor"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "MODIS_Terra_CorrectedReflectance_TrueColor/default/"
        "2026-09-01/250m/0/0/0.jpeg")
   :capture-time "2026-09-01"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "MODIS (Terra)"
   :bands #{:r :g :b}
   :band-source "bands 1,4,3 as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T02:52:37Z"
   :payload-sha256
   "ec1ca4b6b6aba2b6a30fa67a6bca7155649008677515a3f295c4b5e6122befda"})

(def aster-gdem-color-sample
  "The third bounded sample: ASTER GDEM Color Index -- a digital elevation
  model rendered as a colour index, not a reflectance image. The layer is
  a static composite with no time dimension, so capture time is the GDEM
  composite epoch (version 3), not a dated acquisition. The 31.25m tile
  matrix is 2x1 tiles at level 0, so the single level-0 tile (0/0/0) is
  the north-west half of the globe -- the record and manifest state that
  footprint exactly instead of claiming the planet."
  {:asset-id "ASTER_GDEM_Color_Index/31.25m/0/0/0"
   :layer "ASTER_GDEM_Color_Index"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/"
        "ASTER_GDEM_Color_Index/default/31.25m/0/0/0.png")
   :capture-time "2019"
   :capture-note
   "ASTER Global Digital Elevation Model version 3 static composite; the
  layer exposes no time dimension, so capture time is the GDEM v3
  composite epoch, not a dated satellite acquisition."
   :coverage-note
   "one 512x512 level-0 tile of a 2x1-tile matrix: the north-west half
  of the globe (lon -180..0, lat 0..90)"
   :footprint [-180.0 0.0 0.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 31.25
   :sensor "ASTER (Terra), GDEM v3 elevation composite"
   :bands #{:colour-index}
   :band-source "single-band elevation rendered through the GIBS colour map"
   :licence :nasa-public-domain
   :tile-matrix "31.25m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T09:44:08Z"
   :payload-sha256
   "fae46382a606e67415bf3acea247c149560eeab5eb8ea98a62749db917d2d405"})

(def modis-terra-bands367-sample
  "The fourth bounded sample: MODIS Terra CorrectedReflectance Bands367,
  one EPSG:4326 level-0 tile for ONE declared capture date. Bands 3, 6
  and 7 map visible blue and two shortwave-infrared bands into RGB, so
  this carries information the true-colour layer does not -- it is a
  different observation, not a re-render of one. Same shape as the
  true-colour sample: dated acquisition, declared capture date, one
  tile, level 0."
  {:asset-id "MODIS_Terra_CorrectedReflectance_Bands367/250m/0/0/0"
   :layer "MODIS_Terra_CorrectedReflectance_Bands367"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/"
        "MODIS_Terra_CorrectedReflectance_Bands367/default/"
        "2026-09-01/250m/0/0/0.jpeg")
   :capture-time "2026-09-01"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "MODIS (Terra)"
   :bands #{:r :g :b}
   :band-source "bands 3,6,7 as RGB false colour (visible blue + SWIR discrimination)"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T13:47:03Z"
   :payload-sha256
   "2a36384d0eeecdad6377ce1422f28538f191cc930947437a3468efb0f525c951"})

(def landsat-weld-truecolor-1985-sample
  "The eighth bounded sample: Landsat WELD CorrectedReflectance TrueColor
  Global Annual, one EPSG:4326 level-0 tile for ONE declared composite
  year. Landsat is a new sensor family in this slice -- 30m-class
  surface reflectance mosaics rather than the 250m daily MODIS/VIIRS
  sweeps -- so it is a different observation, not a re-render. The
  layer's time dimension is annual, declared as the composite period
  date GIBS exposes (1985-12-01); the record carries that date
  verbatim and the note says what it is. One tile, level 0, public
  domain."
  {:asset-id
   "Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/31.25m/0/0/0"
   :layer "Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/"
        "Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/"
        "1985-12-01/31.25m/0/0/0.jpeg")
   :capture-time "1985-12-01"
   :capture-note
   "Annual Landsat WELD surface-reflectance composite; the layer's time
  dimension is annual and the date is the declared composite period as
  GIBS exposes it, not a single satellite overpass and not a date
  guessed from the wall clock."
   :coverage-note
   "one 512x512 level-0 tile of a 2x1-tile matrix: the north-west half
  of the globe (lon -180..0, lat 0..90)"
   :footprint [-180.0 0.0 0.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 31.25
   :sensor "Landsat (WELD annual global surface-reflectance composite)"
   :bands #{:r :g :b}
   :band-source "Landsat bands 3,2,1 as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "31.25m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T22:48:25Z"
   :payload-sha256
   "e4b1965e50c28155f52a03e2489c8c017cf9fd4d0fcb6e492afea28fe95393a5"})

(defn verify-sample
  "The object readback: re-derive the sample's provenance completeness
  and that its sha256 matches the fixture bytes' hash."
  [rec fixture-sha256]
  {:provenance-complete (provenance-complete? rec)
   :sha256-matches (= (:payload-sha256 rec) fixture-sha256)})

(def viirs-citylights-2012-sample
  "The fifth bounded sample: Earth at Night 2012 (VIIRS City Lights,
  Suomi NPP) -- a static composite with no time dimension, so capture
  time is the 2012 composite epoch, not a dated acquisition. Night-lights
  imagery the daytime reflectance layers do not carry. Same shape as the
  Blue Marble sample: static composite, one tile, level 0, public domain."
  {:asset-id "VIIRS_CityLights_2012/500m/0/0/0"
   :layer "VIIRS_CityLights_2012"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "VIIRS_CityLights_2012/default/500m/0/0/0.jpeg")
   :capture-time "2012"
   :capture-note
   "VIIRS day/night band composite for 2012 (Earth at Night); the layer
  exposes no time dimension, so capture time is the composite epoch, not
  a dated satellite acquisition."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 500
   :sensor "VIIRS (Suomi NPP), day/night band night-lights composite"
   :bands #{:r :g :b}
   :band-source "day/night band radiance rendered as a night-lights image"
   :licence :nasa-public-domain
   :tile-matrix "500m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T18:19:36Z"
   :payload-sha256
   "0530f0aa7ab41c8f71bd91b8468f4192abb5b8569b94376d272a4f454d318f10"})

(def viirs-snpp-truecolor-sample
  "The sixth bounded sample: VIIRS SNPP CorrectedReflectance TrueColor,
  one EPSG:4326 level-0 tile for ONE declared capture date. A daytime
  reflectance image from a new sensor in this slice -- Suomi NPP's VIIRS
  rather than Terra's or Aqua's MODIS -- so it is a different observation,
  not a re-render of an existing sample. Same shape as the MODIS
  true-colour sample: dated acquisition, declared capture date, one tile,
  level 0, public domain."
  {:asset-id "VIIRS_SNPP_CorrectedReflectance_TrueColor/250m/0/0/0"
   :layer "VIIRS_SNPP_CorrectedReflectance_TrueColor"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "VIIRS_SNPP_CorrectedReflectance_TrueColor/default/"
        "2026-09-01/250m/0/0/0.jpeg")
   :capture-time "2026-09-01"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "VIIRS (Suomi NPP)"
   :bands #{:r :g :b}
   :band-source "VIIRS imaging bands I-1, M-4, M-3 as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T19:45:50Z"
   :payload-sha256
   "376d09b371b6d150a8716626b7a1da4d9c0e656904185343499b02b272762a37"})

(def modis-aqua-truecolor-sample
  "The seventh bounded sample: MODIS Aqua CorrectedReflectance TrueColor,
  one EPSG:4326 level-0 tile for ONE declared capture date. Same daytime
  reflectance family as the Terra sample but from the afternoon platform
  -- a different sensor, a different observation, not a re-render. Dated
  acquisition, declared capture date, one tile, level 0, public domain."
  {:asset-id "MODIS_Aqua_CorrectedReflectance_TrueColor/250m/0/0/0"
   :layer "MODIS_Aqua_CorrectedReflectance_TrueColor"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "MODIS_Aqua_CorrectedReflectance_TrueColor/default/"
        "2026-09-02/250m/0/0/0.jpeg")
   :capture-time "2026-09-02"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "MODIS (Aqua)"
   :bands #{:r :g :b}
   :band-source "bands 1,4,3 as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-02T19:51:17Z"
   :payload-sha256
   "530d2c14e4c680de1ebaba761395d0206adf6d10e0df8ff66d6b9d9fa601ccc9"})

(def modis-terra-bands721-sample
  "The ninth bounded sample: MODIS Terra CorrectedReflectance Bands721,
  one EPSG:4326 level-0 tile for ONE declared capture date. A false-colour
  rendering -- bands 7, 2, 1 as RGB -- from the same Terra platform as the
  true-colour sample, but a different band product and therefore a
  different observation, not a re-render: snow and ice read bright blue,
  vegetation red, bare earth green, and burned areas a deep red. Dated
  acquisition, declared capture date, one tile, level 0, public domain."
  {:asset-id "MODIS_Terra_CorrectedReflectance_Bands721/250m/0/0/0"
   :layer "MODIS_Terra_CorrectedReflectance_Bands721"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "MODIS_Terra_CorrectedReflectance_Bands721/default/"
        "2026-09-01/250m/0/0/0.jpeg")
   :capture-time "2026-09-01"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "MODIS (Terra)"
   :bands #{:r :g :b}
   :band-source "bands 7, 2, 1 as RGB false colour (SWIR, NIR, red)"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-03T05:27:21Z"
   :payload-sha256
   "dabd312d93d1703b99e1cf6c419913d80da0a1547a06cd62ea2e73ea97eccf42"})

(def viirs-noaa20-truecolor-sample
  "The tenth bounded sample: VIIRS NOAA-20 CorrectedReflectance TrueColor,
  one EPSG:4326 level-0 tile for ONE declared capture date. A daytime
  reflectance image from a new sensor in this slice -- the JPSS-1/NOAA-20
  platform's VIIRS rather than Suomi NPP's -- so it is a different
  observation, not a re-render of the SNPP sample. Same shape as the SNPP
  true-colour sample: dated acquisition, declared capture date, one tile,
  level 0, public domain."
  {:asset-id "VIIRS_NOAA20_CorrectedReflectance_TrueColor/250m/0/0/0"
   :layer "VIIRS_NOAA20_CorrectedReflectance_TrueColor"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/"
        "2026-09-01/250m/0/0/0.jpeg")
   :capture-time "2026-09-01"
   :capture-note
   "Dated satellite acquisition: the declared capture date selects the
  layer's time dimension. The date is declared per run, not guessed
  from the wall clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 250
   :sensor "VIIRS (NOAA-20 / JPSS-1)"
   :bands #{:r :g :b}
   :band-source "VIIRS imaging bands I-1, M-4, M-3 as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "250m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-03T05:58:00Z"
   :payload-sha256
   "a460d7f324aa1a401effb7ee7925151205cc52af3c3dd656a58ae1b535049673"})

(def viirs-black-marble-2016-sample
  "The eleventh bounded sample: Black Marble (VIIRS, Suomi NPP) -- the
  2016 day/night band annual composite, one EPSG:4326 level-0 tile. A
  second night-lights source in this slice, distinct from the 2012 City
  Lights composite in epoch, calibration (VIIRS DNB annual processing)
  and file format (PNG rather than JPEG) -- so it is a different
  observation, not a re-render. The layer is static on GIBS, so capture
  time is the 2016 composite epoch, not a dated acquisition. One tile,
  level 0, public domain."
  {:asset-id "VIIRS_Black_Marble/500m/0/0/0"
   :layer "VIIRS_Black_Marble"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "VIIRS_Black_Marble/default/500m/0/0/0.png")
   :capture-time "2016"
   :capture-note
   "VIIRS day/night band annual composite for 2016 (Black Marble); the
  layer is static on GIBS, so capture time is the composite epoch, not
  a dated satellite acquisition."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 500
   :sensor "VIIRS (Suomi NPP), day/night band annual night-lights composite"
   :bands #{:r :g :b}
   :band-source "day/night band radiance rendered as a night-lights image"
   :licence :nasa-public-domain
   :tile-matrix "500m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-03T11:07:43Z"
   :payload-sha256
   "61c65af49b7acc532fa409b2cec0ec168caf3df70dad940d55c4b837e9c0c850"})

(def modis-terra-bands143-8day-sample
  "The twelfth bounded sample: MODIS Terra Land Surface Reflectance,
  Bands 1-4-3 (8-Day, True Color) -- one EPSG:4326 level-0 tile for ONE
  declared 8-day period start. A different quantity from the daily
  corrected-reflectance slices: L3 8-day composite surface reflectance
  (MYD09/MOD09 processing family) rather than a daily corrected
  reflectance swath render, and a different band combination from the
  Terra 3-6-7 and 7-2-1 false-colour samples. One tile, level 0, public
  domain."
  {:asset-id "MODIS_Terra_L3_SurfaceReflectance_Bands143_8Day/500m/0/0/0"
   :layer "MODIS_Terra_L3_SurfaceReflectance_Bands143_8Day"
   :source-url
   (str "https://gibs.earthdata.nasa.gov/wmts/epsg4326/all/"
        "MODIS_Terra_L3_SurfaceReflectance_Bands143_8Day/default/"
        "2026-02-02/500m/0/0/0.jpeg")
   :capture-time "2026-02-02"
   :capture-note
   "Dated acquisition: the declared value selects the layer's 8-day
  composite period start (2026-02-02), the latest period the layer
  publishes. The date is declared per run, not guessed from the wall
  clock."
   :footprint [-180.0 180.0 -90.0 90.0]
   :crs "EPSG:4326"
   :resolution-gsd-m 500
   :sensor "MODIS (Terra), L3 8-day surface reflectance composite"
   :bands #{:r :g :b}
   :band-source "surface reflectance bands 1 (645nm), 4 (555nm), 3 (469nm) as RGB true colour"
   :licence :nasa-public-domain
   :tile-matrix "500m"
   :tile-zxy [0 0 0]
   :retrieved-at "2026-09-03T12:08:59Z"
   :payload-sha256
   "8dbea2ce1222c4cf1908ac2b65f832e6ce4a8c387ff2c7338fd08fa15d243f8b"})
