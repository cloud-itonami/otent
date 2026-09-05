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
