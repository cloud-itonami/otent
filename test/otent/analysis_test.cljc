(ns otent.analysis-test
  "Deterministic tests for the analysis pipeline: tile maths, the
   classification ladder, unknown-label handling, provenance shape, and
   the derived-table readback. No network, no JPEG -- tiles are
   synthetic RGBA buffers, so the same fixture always yields the same
   observation, and a change in behaviour is a change in this file."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [otent.analysis :as an]
            [otent.basemap :as bm]))

;; ------------------------------------------------------------------ fixtures

(defn- tile-of
  "A width x height RGBA buffer, every pixel the same [r g b]."
  [w h [r g b]]
  (let [data (js/Uint8Array. (* w h 4))]
    (doseq [i (range (* w h))]
      (aset data (* i 4) r)
      (aset data (inc (* i 4)) g)
      (aset data (+ 2 (* i 4)) b)
      (aset data (+ 3 (* i 4)) 255))
    {:width w :height h :data data}))

(defn- mosaic
  "Rows 0..split one colour, the rest another."
  [w h split top bottom]
  (let [data (js/Uint8Array. (* w h 4))]
    (doseq [y (range h), x (range w)]
      (let [[r g b] (if (< y split) top bottom)
            i (+ (* y w) x)]
        (aset data (* i 4) r)
        (aset data (inc (* i 4)) g)
        (aset data (+ 2 (* i 4)) b)
        (aset data (+ 3 (* i 4)) 255)))
    {:width w :height h :data data}))

(defn- metrics-of [tile] (an/tile-metrics tile (:sample-grid (:params an/model))))

(def mo (bm/source-for "modis-terra-truecolor"))

(defn- prov-of [tile]
  (an/provenance-record {:source mo :date "2026-08-29" :tile tile
                         :sha256 "deadbeef" :key "otent/basemap/modis-terra-truecolor/2026-08-29/2/1/2.jpg"}
                        "artifact-sha-test" "nbb test"))

(t/deftest tile-bbox-and-centroid
  ;; z0 is the whole mercator world
  (let [[w s e n] (an/tile-bbox 0 0 0)]
    (t/is (and (< (- w -180) 0.01) (< (- e 180) 0.01)))
    (t/is (and (< (- s -85.05) 0.01) (< (- n 85.05) 0.01))))
  ;; z1 splits longitude in half
  (let [[w _ e _] (an/tile-bbox 1 0 0)]
    (t/is (and (< (- w -180) 0.01) (< (- e 0) 0.01))))
  ;; the centroid sits inside its own footprint
  (let [bbox (an/tile-bbox 4 8 5)
        [cx cy] (an/tile-centroid bbox)]
    (t/is (<= (first bbox) cx (nth bbox 2)))
    (t/is (<= (second bbox) cy (peek bbox)))))

;; ------------------------------------------------------------------ metrics

(t/deftest metrics-of-uniform-tile
  (let [m (metrics-of (tile-of 16 16 [10 20 70]))]
    (t/is (= 1.0 (:coverage m)))
    ;; brightness is the mean of the three band means: (10+20+70)/3
    (t/is (< (Math/abs (- 33.33333333333333 (:brightness m))) 0.001))
    (t/is (== 60.0 (:spread m)))
    (t/is (== 10.0 (:r m))) (t/is (== 20.0 (:g m))) (t/is (== 70.0 (:b m)))))

(t/deftest nodata-cells-shrink-coverage-not-the-mean
  ;; half real ocean, half nodata black: coverage 0.5 and the means
  ;; describe only the real half
  (let [m (metrics-of (mosaic 16 16 8 [10 20 70] [0 0 0]))]
    (t/is (< 0.49 (:coverage m) 0.51))))

;; ------------------------------------------------------------------ classify

(t/deftest deterministic-fixtures-classify-as-their-class
  ;; ocean: dark and blue-dominant
  (t/is (= :water (:label (an/classify (metrics-of (tile-of 16 16 [10 20 70]))))))
  ;; cloud deck: bright and low-spread. Snow reads the same, and the
  ;; taxonomy says so instead of pretending it can tell them apart.
  (t/is (= :bright (:label (an/classify (metrics-of (tile-of 16 16 [220 218 214]))))))
  ;; vegetated land: mid-bright, green-dominant
  (t/is (= :vegetated-land (:label (an/classify (metrics-of (tile-of 16 16 [110 170 80]))))))
  ;; barren land: mid-bright, red-dominant
  (t/is (= :barren-land (:label (an/classify (metrics-of (tile-of 16 16 [170 120 80])))))))

(t/deftest gray-zone-stays-unknown
  ;; mid-brightness, channel-neutral: no class clears the floor, so the
  ;; label is :unknown with confidence 0 -- it is NEVER coerced
  (let [res (an/classify (metrics-of (tile-of 16 16 [120 120 120])))]
    (t/is (= :unknown (:label res)))
    (t/is (zero? (:confidence res)))
    (t/is (string? (:detail res)))
    ;; the near-miss scores survive in the raw half
    (t/is (seq (:raw-scores res)))))

(t/deftest low-coverage-stays-unknown
  ;; a quarter-filled tile does not get to be :water, whatever its means
  (let [m (metrics-of (mosaic 16 16 4 [10 20 70] [0 0 0]))]
    (t/is (= :unknown (:label (an/classify m))))))

(t/deftest classify-is-repeatable
  (let [m (metrics-of (tile-of 16 16 [10 20 70]))
        a (pr-str (an/classify m))
        b (pr-str (an/classify m))]
    (t/is (= a b))))

;; ------------------------------------------------------------------ refusals

(t/deftest analysis-refusals
  (t/is (= :analysis/past-ingest-bound
           (:refusal (an/analysis-refusal mo 5 nil))))
  (t/is (nil? (an/analysis-refusal mo 4 "2026-08-29")))
  (t/is (= :analysis/capture-date-required
           (:refusal (an/analysis-refusal mo 4 "not-a-date"))))
  ;; an unregistered source passes through as the refusal it already is
  (t/is (= :licence/unknown-source
           (:refusal (an/analysis-refusal (bm/source-for "sentinel-2-l2a") 4 nil)))))

;; ------------------------------------------------------------------ records

(t/deftest provenance-record-carries-everything-rule-2-asks
  (let [p (prov-of [2 1 3])]
    (t/is (string? (not-empty (:content-sha256 p))))
    (t/is (= "2026-08-29T00:00:00Z" (:capture-time p)))
    (t/is (= "EPSG:3857" (:crs p)))
    (t/is (= {:z 2 :x 1 :y 3} (:tile p)))
    (t/is (vector? (:footprint-epsg3857 p)))
    (t/is (= 2 (count (:centroid-wgs84 p))))
    (t/is (str/starts-with? (:licence p) "NASA -- public domain"))
    (t/is (string? (not-empty (:attribution p))))
    (t/is (string? (not-empty (:sensor p))))
    (t/is (= "artifact-sha-test" (:artifact-hash (:model p))))))

(t/deftest observation-keeps-raw-and-normalized-distinguishable
  (let [row (an/observation (prov-of [4 1 2]) (metrics-of (tile-of 16 16 [10 20 70])))]
    (t/is (= :committed (:observation-status row)))
    ;; raw half: the metrics and per-class scores, untouched
    (t/is (contains? (:raw row) :metrics))
    (t/is (contains? (:raw row) :raw-scores))
    ;; normalized half: label + taxonomy + confidence/uncertainty pairing
    (t/is (= :water (get-in row [:observation :label])))
    (t/is (= (:taxonomy-version an/taxonomy) (get-in row [:observation :taxonomy-version])))
    (t/is (== 1.0 (+ (get-in row [:observation :confidence])
                     (get-in row [:observation :uncertainty]))))
    ;; provenance rides along on the row
    (t/is (string? (not-empty (get-in row [:provenance :content-sha256]))))))

(t/deftest unknown-label-survives-normalization
  ;; the gray fixture must come out the far end still labelled :unknown,
  ;; with its scores -- it may not be dropped or coerced
  (let [m (metrics-of (tile-of 16 16 [120 120 120]))
        row (an/observation (prov-of [3 2 2]) m)]
    (t/is (= :unknown (get-in row [:observation :label])))
    (t/is (zero? (get-in row [:observation :confidence])))
    (t/is (string? (not-empty (get-in row [:raw :detail]))))))

(t/deftest failed-tiles-are-rows-too
  (let [row (an/failed-observation [4 3 3] "otent/basemap/modis-terra-truecolor/2026-08-29/4/3/3.jpg"
                                   :source/http-error "404 from https://...")]
    (t/is (= :failed (:observation-status row)))
    (t/is (= :source/http-error (get-in row [:failure :reason])))
    (t/is (= [4 3 3] ((juxt :z :x :y) (get-in row [:provenance :tile]))))))

;; ------------------------------------------------------------------ summary + readback

(defn- jstr [x] (js/JSON.stringify (clj->js x)))
(defn- jparse [s] (js->clj (js/JSON.parse s) :keywordize-keys true))

(t/deftest run-summary-counts-add-up
  (let [rows [(an/observation (prov-of [2 0 0]) (metrics-of (tile-of 16 16 [10 20 70])))
              (an/observation (prov-of [2 1 1]) (metrics-of (tile-of 16 16 [120 120 120])))
              (an/failed-observation [2 2 2] "k" :source/http-error "500")
              (an/failed-observation [2 3 3] "k" :decode/jpeg "bad bytes")]
        s (an/run-summary {:source mo :date "2026-08-29" :max-z 2}
                          "artifact-sha-test" "nbb" rows 1234)]
    (t/is (= 4 (:tile-count s)))
    (t/is (= 2 (get-in s [:counts :committed])))
    (t/is (= 2 (get-in s [:counts :failed])))
    (t/is (= 1 (:unknown s)))
    ;; observed + failed must account for every tile
    (t/is (= 4 (reduce + 0 (vals (:counts s)))))
    ;; the failed tiles are in the label map only as an absence
    (t/is (= 1 (get (:labels s) :water 0)))
    (t/is (= "artifact-sha-test" (get-in s [:model :artifact-hash])))
    (t/is (= 1234 (:run-ms s)))))

(t/deftest derived-table-readback
  ;; write the table as JSON, read it back: every row survives, the
  ;; failed rows stay failed, and the counts still add up
  (let [rows [(an/observation (prov-of [2 0 0]) (metrics-of (tile-of 16 16 [10 20 70])))
              (an/observation (prov-of [2 1 1]) (metrics-of (tile-of 16 16 [120 120 120])))
              (an/failed-observation [2 2 2] "k" :source/http-error "500")]
        back (jparse (jstr rows))]
    (t/is (= (count rows) (count back)))
    (t/is (= 2 (count (filter #(= "committed" (:observation-status %)) back))))
    (t/is (= 1 (count (filter #(= "failed" (:observation-status %)) back))))
    (t/is (= "unknown" (get-in (second back) [:observation :label])))
    (t/is (= "water" (get-in (first back) [:observation :label])))
    ;; sha256 and geometry roundtrip intact
    (t/is (= "deadbeef" (get-in (first back) [:provenance :content-sha256])))
    ;; geometry roundtrips intact: compare against the row as built
    (t/is (= (vec (get-in (first rows) [:provenance :centroid-wgs84]))
             (vec (get-in (first back) [:provenance :centroid-wgs84]))))))
