(ns otent.basemap-test
  "Deterministic tests for the pure half of the basemap ingest: tile
   maths, refusal rules, provenance shape and manifest shape. No network."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [otent.basemap :as bm]))

(t/deftest tiles-to-zoom-counts
  (t/is (= 1 (count (bm/tiles-to-zoom 0))))
  (t/is (= 341 (count (bm/tiles-to-zoom 4))))
  (t/is (= 5461 (count (bm/tiles-to-zoom 6))))
  (t/is (= [0 0 0] (first (bm/tiles-to-zoom 2))))
  (t/is (= [2 3 3] (last (bm/tiles-to-zoom 2)))))

(t/deftest sources-carry-licence-and-provenance-fields
  (doseq [s bm/raster-sources]
    (t/is (string? (not-empty (:id s))))
    (t/is (str/starts-with? (:licence s) "NASA -- public domain"))
    (t/is (string? (not-empty (:attribution s))))
    (t/is (= "EPSG:3857" (:crs s)))
    (t/is (= 256 (:tile-size s)))
    (t/is (string? (not-empty (:sensor s))))
    (t/is (string? (not-empty (:bands s))))
    (t/is (integer? (:max-source-zoom s)))
    ;; every source is ingested at or below what it serves
    (t/is (<= (:max-ingest-zoom s) (:max-source-zoom s)))))

(t/deftest only-registered-sources-are-ingestable
  (t/is (= "blue-marble" (:id (bm/source-for "blue-marble"))))
  (t/is (= "modis-terra-truecolor" (:id (bm/source-for "modis-terra-truecolor"))))
  (t/is (= "viirs-noaa20-truecolor" (:id (bm/source-for "viirs-noaa20-truecolor"))))
  (t/is (= "viirs-snpp-truecolor" (:id (bm/source-for "viirs-snpp-truecolor"))))
  (t/is (= "modis-terra-ndvi-8day" (:id (bm/source-for "modis-terra-ndvi-8day"))))
  (let [r (bm/source-for "google-photorealistic-tiles")]
    (t/is (= :licence/unknown-source (:refusal r)))))

(t/deftest static-tile-urls-ignore-date
  (let [s (bm/source-for "blue-marble")]
    (t/is (str/starts-with? (bm/tile-url s [3 4 5])
                            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble"))
    (t/is (str/includes? (bm/tile-url s [3 4 5]) "Level8/3/5/4.jpeg"))
    (t/is (not (str/includes? (bm/tile-url s [3 4 5] "2026-08-28") "{")))))

(t/deftest daily-tile-urls-carry-the-capture-date
  (let [s (bm/source-for "modis-terra-truecolor")]
    (t/is (str/includes? (bm/tile-url s [4 1 3] "2026-08-28")
                         "MODIS_Terra_CorrectedReflectance_TrueColor/default/2026-08-28/"))
    (t/is (str/ends-with? (bm/tile-url s [4 1 3] "2026-08-28") "/4/3/1.jpeg"))
    (t/is (str/includes? (bm/tile-url s [4 1 3] nil) "{date}")))
  (let [s (bm/source-for "viirs-noaa20-truecolor")]
    (t/is (str/includes? (bm/tile-url s [4 1 3] "2026-08-28")
                         "VIIRS_NOAA20_CorrectedReflectance_TrueColor/default/2026-08-28/"))
    (t/is (str/ends-with? (bm/tile-url s [4 1 3] "2026-08-28") "/4/3/1.jpeg"))
    (t/is (str/includes? (bm/tile-url s [4 1 3] nil) "{date}")))
  (let [s (bm/source-for "viirs-snpp-truecolor")]
    (t/is (str/includes? (bm/tile-url s [4 1 3] "2026-08-28")
                         "VIIRS_SNPP_CorrectedReflectance_TrueColor/default/2026-08-28/"))
    (t/is (str/ends-with? (bm/tile-url s [4 1 3] "2026-08-28") "/4/3/1.jpeg"))
    (t/is (str/includes? (bm/tile-url s [4 1 3] nil) "{date}"))))

(t/deftest daily-tiles-are-keyed-by-capture-date
  (let [s (bm/source-for "modis-terra-truecolor")]
    (t/is (= "otent/basemap/modis-terra-truecolor/2026-08-28/4/1/3.jpg"
             (bm/tile-key s [4 1 3] "2026-08-28"))))
  (let [s (bm/source-for "viirs-noaa20-truecolor")]
    (t/is (= "otent/basemap/viirs-noaa20-truecolor/2026-08-28/4/1/3.jpg"
             (bm/tile-key s [4 1 3] "2026-08-28")))
    (t/is (not= (bm/tile-key s [4 1 3] "2026-08-28")
                (bm/tile-key (bm/source-for "viirs-snpp-truecolor") [4 1 3] "2026-08-28"))
          "two daily sources must never share a key prefix"))
  (let [s (bm/source-for "blue-marble")]
    (t/is (= "otent/basemap/blue-marble/3/4/5.jpg" (bm/tile-key s [3 4 5])))))

;; ------------------------------------------------------------ annual composite

(t/deftest annual-composite-urls-and-keys-carry-the-period
  (let [s (bm/source-for "landsat-weld-truecolor-annual")]
    (t/is (bm/dated? s))
    (t/is (:sparse-coverage s) "the WELD composite 404s over ocean -- that must be declared")
    (t/is (str/includes? (bm/tile-url s [4 12 6] "1998-12-01")
                         "Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual/default/1998-12-01/GoogleMapsCompatible_Level12/4/6/12.jpeg"))
    (t/is (= "otent/basemap/landsat-weld-truecolor-annual/1998-12-01/4/12/6.jpg"
             (bm/tile-key s [4 12 6] "1998-12-01")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a period the template must leak, not silently fetch `default`")))

(t/deftest annual-composite-refusals
  (let [w (bm/source-for "landsat-weld-truecolor-annual")]
    ;; the service serves level 12, but this run's bound is still z4
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal w 5 "1998-12-01"))))
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal w 13 "1998-12-01"))))
    ;; a composite year is still a declared period
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal w 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal w 4 "1998"))))
    (t/is (nil? (bm/ingest-refusal w 4 "1998-12-01"))))
  (let [p (bm/ingest-plan "landsat-weld-truecolor-annual" 4 "1998-12-01")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)) "candidate tiles: holes over ocean are measured later, not planned away")
    (t/is (= "1998-12-01" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/landsat-weld-truecolor-annual/1998-12-01/"))))

(t/deftest annual-manifest-entry-states-what-exists
  (let [w (bm/source-for "landsat-weld-truecolor-annual")
        m (bm/manifest-imagery-entry w 4 "1998-12-01" 187 "t")]
    (t/is (= "annual" (:time-mode m)))
    (t/is (= "1998-12-01" (:capture-date m)))
    (t/is (= 187 (:tile-count m)) "the STORED count -- sparse coverage means fewer than 341")
    (t/is (= "NASA -- public domain" (:licence m)))
    (t/is (= "otent/basemap/landsat-weld-truecolor-annual/1998-12-01" (:prefix m)))))

;; ------------------------------------------------------------ 8-day product

(t/deftest ndvi-8day-urls-and-keys-carry-the-window-start
  (let [s (bm/source-for "modis-terra-ndvi-8day")]
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the 8-day layer serves an (empty) PNG over ocean -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Terra_NDVI_8Day/default/2026-08-30/GoogleMapsCompatible_Level9/4/6/12.png"))
    (t/is (= "otent/basemap/modis-terra-ndvi-8day/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a window the template must leak, not silently fetch `default`")))

(t/deftest ndvi-8day-refusals
  (let [s (bm/source-for "modis-terra-ndvi-8day")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 10 "2026-08-30"))))
    ;; an 8-day window is a declared period: no wall-clock default
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-terra-ndvi-8day" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-terra-ndvi-8day/2026-08-30/"))))

(t/deftest ndvi-8day-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-terra-ndvi-8day")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "8day" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-terra-ndvi-8day/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- land surface temperature

(t/deftest lst-day-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "modis-terra-lst-day")]
    (t/is (= "modis-terra-lst-day" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the LST layer serves a (coloured) PNG over ocean -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Terra_Land_Surface_Temp_Day/default/2026-08-30/GoogleMapsCompatible_Level7/4/6/12.png"))
    (t/is (= "otent/basemap/modis-terra-lst-day/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest lst-day-refusals
  (let [s (bm/source-for "modis-terra-lst-day")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 7: the 1 km product must not be upsampled
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 8 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-terra-lst-day" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-terra-lst-day/2026-08-30/"))))

(t/deftest lst-day-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-terra-lst-day")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-terra-lst-day/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- Terra LST night

(t/deftest terra-lst-night-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "modis-terra-lst-night")]
    (t/is (= "modis-terra-lst-night" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the LST layer serves a (coloured) PNG over ocean -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Terra_Land_Surface_Temp_Night/default/2026-08-30/GoogleMapsCompatible_Level7/4/6/12.png"))
    (t/is (= "otent/basemap/modis-terra-lst-night/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest terra-lst-night-refusals
  (let [s (bm/source-for "modis-terra-lst-night")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 7: the 1 km product must not be upsampled
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 8 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-terra-lst-night" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-terra-lst-night/2026-08-30/"))))

(t/deftest terra-lst-night-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-terra-lst-night")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-terra-lst-night/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- Aqua LST night

(t/deftest aqua-lst-night-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "modis-aqua-lst-night")]
    (t/is (= "modis-aqua-lst-night" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the LST layer serves a (coloured) PNG over ocean -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Aqua_Land_Surface_Temp_Night/default/2026-08-30/GoogleMapsCompatible_Level7/4/6/12.png"))
    (t/is (= "otent/basemap/modis-aqua-lst-night/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest aqua-lst-night-refusals
  (let [s (bm/source-for "modis-aqua-lst-night")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 7: the 1 km product must not be upsampled
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 8 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-aqua-lst-night" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-aqua-lst-night/2026-08-30/"))))

(t/deftest aqua-lst-night-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-aqua-lst-night")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-aqua-lst-night/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- Aqua LST day

(t/deftest aqua-lst-day-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "modis-aqua-lst-day")]
    (t/is (= "modis-aqua-lst-day" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the LST layer serves a (coloured) PNG over ocean -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Aqua_Land_Surface_Temp_Day/default/2026-08-30/GoogleMapsCompatible_Level7/4/6/12.png"))
    (t/is (= "otent/basemap/modis-aqua-lst-day/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest aqua-lst-day-refusals
  (let [s (bm/source-for "modis-aqua-lst-day")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 7: the 1 km product must not be upsampled
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 8 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-aqua-lst-day" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-aqua-lst-day/2026-08-30/"))))

(t/deftest aqua-lst-day-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-aqua-lst-day")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-aqua-lst-day/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- Aqua Bands721

(t/deftest aqua-bands721-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "modis-aqua-bands721")]
    (t/is (= "modis-aqua-bands721" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "reflectance JPEGs cover the whole day's disk -- not sparse")
    (t/is (= "jpeg" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "MODIS_Aqua_CorrectedReflectance_Bands721/default/2026-08-30/GoogleMapsCompatible_Level9/4/6/12.jpeg"))
    (t/is (= "otent/basemap/modis-aqua-bands721/2026-08-30/4/12/6.jpg"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest aqua-bands721-refusals
  (let [s (bm/source-for "modis-aqua-bands721")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 9 the service would upsample -- and
    ;; past the ingest bound the unbounded-crawl refusal bites first
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 10 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "modis-aqua-bands721" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/modis-aqua-bands721/2026-08-30/"))))

(t/deftest aqua-bands721-manifest-entry-states-what-exists
  (let [s (bm/source-for "modis-aqua-bands721")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (= 341 (:tile-count m)) "not sparse: full candidate count is the stored count")
    (t/is (false? (:sparse-coverage m)))
    (t/is (= "otent/basemap/modis-aqua-bands721/2026-08-30" (:prefix m)))))

;; ------------------------------------------------- VIIRS DNB radiance

(t/deftest viirs-dnb-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "viirs-snpp-dnb-radiance")]
    (t/is (= "viirs-snpp-dnb-radiance" (:id s)))
    (t/is (bm/dated? s))
    (t/is (:sparse-coverage s) "the night side only: 404s are holes, not errors")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-31")
                         "VIIRS_SNPP_DayNightBand_At_Sensor_Radiance/default/2026-08-31/GoogleMapsCompatible_Level8/4/6/12.png"))
    (t/is (= "otent/basemap/viirs-snpp-dnb-radiance/2026-08-31/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-31")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest viirs-dnb-refusals
  (let [s (bm/source-for "viirs-snpp-dnb-radiance")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-31"))))
    ;; past the layer's own level 8 the service would upsample -- and
    ;; past the ingest bound the unbounded-crawl refusal bites first
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 9 "2026-08-31"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-09-01T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-31"))))
  (let [p (bm/ingest-plan "viirs-snpp-dnb-radiance" 4 "2026-08-31")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-31" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/viirs-snpp-dnb-radiance/2026-08-31/"))))

(t/deftest viirs-dnb-manifest-entry-states-what-exists
  (let [s (bm/source-for "viirs-snpp-dnb-radiance")
        m (bm/manifest-imagery-entry s 4 "2026-08-31" 217 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-31" (:capture-date m)))
    (t/is (true? (:sparse-coverage m)))
    (t/is (= 217 (:tile-count m)) "sparse: the manifest states the tiles that EXIST")
    (t/is (= "otent/basemap/viirs-snpp-dnb-radiance/2026-08-31" (:prefix m)))))

(t/deftest refusals
  (let [bm (bm/source-for "blue-marble")
        mo (bm/source-for "modis-terra-truecolor")]
    ;; past the source's own max zoom: upsampled bytes, not detail
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal bm 9 nil))))
    ;; past the run's own bound: an unbounded planet crawl
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal mo 5 "2026-08-28"))))
    ;; at the bound, but no date for a daily layer
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal mo 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal mo 4 "aug 28"))))
    (t/is (nil? (bm/ingest-refusal mo 4 "2026-08-28")))
    (t/is (nil? (bm/ingest-refusal bm 8 nil)))))

(t/deftest date-shape-check
  (t/is (bm/valid-date? "2026-08-28"))
  (t/is (not (bm/valid-date? "2026-8-28")))
  (t/is (not (bm/valid-date? "2026-08-28T00:00:00Z")))
  (t/is (not (bm/valid-date? "")))
  (t/is (not (bm/valid-date? nil))))

(t/deftest plans-are-fetched-only-when-allowed
  (let [p (bm/ingest-plan "modis-terra-truecolor" 4 "2026-08-28")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-28" (:date p)))
    (t/is (= 341 (count (:tiles p))))
    (let [first-tile (first (:tiles p))]
      (t/is (str/includes? (:url first-tile) "2026-08-28"))
      (t/is (str/starts-with? (:key first-tile) "otent/basemap/modis-terra-truecolor/2026-08-28/"))))
  (let [p (bm/ingest-plan "blue-marble" 4 nil)]
    (t/is (:ok? p))
    (t/is (nil? (:date p))))
  (let [p (bm/ingest-plan "viirs-noaa20-truecolor" 4 "2026-08-28")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/viirs-noaa20-truecolor/2026-08-28/")))
  (let [p (bm/ingest-plan "viirs-snpp-truecolor" 4 "2026-08-28")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/viirs-snpp-truecolor/2026-08-28/")))
  (let [p (bm/ingest-plan "viirs-snpp-truecolor" 5 "2026-08-28")]
    (t/is (not (:ok? p)))
    (t/is (= :source/past-ingest-bound (:refusal p))))
  (let [bad (bm/ingest-plan "blue-marble" 9 nil)]
    (t/is (not (:ok? bad)))
    (t/is (= :source/past-max-zoom (:refusal bad)))))

(t/deftest provenance-lines-carry-rule-3-fields
  (let [mo (bm/source-for "modis-terra-truecolor")
        p (bm/tile-provenance
           {:source mo
            :url (bm/tile-url mo [4 1 3] "2026-08-28")
            :key (bm/tile-key mo [4 1 3] "2026-08-28")
            :sha256 "abc123"
            :date "2026-08-28"
            :retrieved-at "2026-08-30T10:10:04Z"
            :content-type "image/jpeg"}
           [4 1 3])]
    (t/is (= "modis-terra-truecolor@2026-08-28/4/1/3" (:asset-id p)))
    (t/is (= "2026-08-28T00:00:00Z" (:capture-time p)))
    (t/is (= "2026-08-30T10:10:04Z" (:retrieved-at p)))
    (t/is (= "abc123" (:content-sha256 p)))
    (t/is (str/includes? (:source-url p) "2026-08-28"))
    (t/is (= "EPSG:3857" (:crs p)))
    (t/is (= "MODIS (Terra)" (:sensor p)))
    (t/is (= "NASA -- public domain" (:licence p))))
  (let [bm (bm/source-for "blue-marble")
        p (bm/tile-provenance
           {:source bm :url "u" :key "k" :sha256 "h"
            :retrieved-at "t" :content-type "image/jpeg"}
           [1 0 1])]
    (t/is (= "blue-marble/1/0/1" (:asset-id p)))
    (t/is (nil? (:capture-time p)))))

(t/deftest manifest-entry-states-exactly-what-exists
  (let [mo (bm/source-for "modis-terra-truecolor")
        m (bm/manifest-imagery-entry mo 4 "2026-08-28" 341 "2026-08-30T10:10:04Z")]
    (t/is (= "modis-terra-truecolor" (:id m)))
    (t/is (= 4 (:max-zoom m)) "measured, not asked for")
    (t/is (= 4 (:ingest-bound m)) "what a run may fetch, not what the service could")
    (t/is (= 341 (:tile-count m)))
    (t/is (= "2026-08-28" (:capture-date m)))
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "NASA -- public domain" (:licence m)))
    (t/is (string? (not-empty (:attribution m))))
    (t/is (= "otent/basemap/modis-terra-truecolor/2026-08-28" (:prefix m))))
  (let [bm (bm/source-for "blue-marble")
        m (bm/manifest-imagery-entry bm 8 nil 21845 "t")]
    (t/is (= "static" (:time-mode m)))
    (t/is (nil? (:capture-date m)))
    (t/is (= "otent/basemap/blue-marble" (:prefix m)))))

;; ------------------------------------------------------------ bandsM11 composite

(t/deftest viirs-bandsm11-urls-and-keys
  (let [s (bm/source-for "viirs-snpp-bandsm11")]
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "daylit-side reflectance: full-bound coverage expected")
    (t/is (str/includes? (bm/tile-url s [4 1 3] "2026-08-31")
                         "VIIRS_SNPP_CorrectedReflectance_BandsM11-I2-I1/default/2026-08-31/"))
    (t/is (str/ends-with? (bm/tile-url s [4 1 3] "2026-08-31") "/4/3/1.jpeg"))
    (t/is (str/includes? (bm/tile-url s [4 1 3] nil) "{date}"))
    (t/is (= "otent/basemap/viirs-snpp-bandsm11/2026-08-31/4/1/3.jpg"
             (bm/tile-key s [4 1 3] "2026-08-31")))
    (t/is (not= (bm/tile-key s [4 1 3] "2026-08-31")
                (bm/tile-key (bm/source-for "viirs-snpp-truecolor") [4 1 3] "2026-08-31"))
          "two daily sources must never share a key prefix")))

(t/deftest viirs-bandsm11-refusals
  (let [s (bm/source-for "viirs-snpp-bandsm11")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-31"))))
    ;; past the layer's own level 9 the service would upsample
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 10 "2026-08-31"))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-31"))))
  (let [p (bm/ingest-plan "viirs-snpp-bandsm11" 4 "2026-08-31")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-31" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/viirs-snpp-bandsm11/2026-08-31/"))))

(t/deftest viirs-bandsm11-manifest-entry-states-what-exists
  (let [s (bm/source-for "viirs-snpp-bandsm11")
        m (bm/manifest-imagery-entry s 4 "2026-08-31" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-31" (:capture-date m)))
    (t/is (false? (:sparse-coverage m)))
    (t/is (= 341 (:tile-count m)))
    (t/is (= "otent/basemap/viirs-snpp-bandsm11/2026-08-31" (:prefix m)))))

;; ------------------------------------------------- GHRSST L4 MUR SST

(t/deftest ghrsst-mur-sst-urls-and-keys-carry-the-capture-date
  (let [s (bm/source-for "ghrsst-mur-sst")]
    (t/is (= "ghrsst-mur-sst" (:id s)))
    (t/is (bm/dated? s))
    (t/is (not (:sparse-coverage s)) "the SST layer serves a (coloured) PNG over land as well -- not sparse")
    (t/is (= "png" (:format s)))
    (t/is (str/includes? (bm/tile-url s [4 12 6] "2026-08-30")
                         "GHRSST_L4_MUR_Sea_Surface_Temperature/default/2026-08-30/GoogleMapsCompatible_Level7/4/6/12.png"))
    (t/is (= "otent/basemap/ghrsst-mur-sst/2026-08-30/4/12/6.png"
             (bm/tile-key s [4 12 6] "2026-08-30")))
    (t/is (str/includes? (bm/tile-url s [4 12 6] nil) "{date}")
          "without a date the template must leak, not silently fetch `default`")))

(t/deftest ghrsst-mur-sst-refusals
  (let [s (bm/source-for "ghrsst-mur-sst")]
    (t/is (= :source/past-ingest-bound (:refusal (bm/ingest-refusal s 5 "2026-08-30"))))
    ;; past the layer's own level 7: the analysis must not be upsampled
    (t/is (= :source/past-max-zoom (:refusal (bm/ingest-refusal s 8 "2026-08-30"))))
    ;; a daily capture date is declared, not defaulted
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 nil))))
    (t/is (= :source/capture-date-required (:refusal (bm/ingest-refusal s 4 "2026-08-31T00:00:00Z"))))
    (t/is (nil? (bm/ingest-refusal s 4 "2026-08-30"))))
  (let [p (bm/ingest-plan "ghrsst-mur-sst" 4 "2026-08-30")]
    (t/is (:ok? p))
    (t/is (= 341 (:tile-count p)))
    (t/is (= "2026-08-30" (:date p)))
    (t/is (str/starts-with? (:key (first (:tiles p)))
                            "otent/basemap/ghrsst-mur-sst/2026-08-30/"))))

(t/deftest ghrsst-mur-sst-manifest-entry-states-what-exists
  (let [s (bm/source-for "ghrsst-mur-sst")
        m (bm/manifest-imagery-entry s 4 "2026-08-30" 341 "t")]
    (t/is (= "daily" (:time-mode m)))
    (t/is (= "2026-08-30" (:capture-date m)))
    (t/is (false? (:sparse-coverage m)))
    (t/is (= 341 (:tile-count m)))
    (t/is (= "otent/basemap/ghrsst-mur-sst/2026-08-30" (:prefix m)))))
