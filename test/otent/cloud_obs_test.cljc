(ns otent.cloud-obs-test
  "Deterministic tests for the cloud-cover analysis pipeline: tile maths,
   the cell-mark sampling, the bin ladder (including the gaps between
   bins), unknown-label handling, provenance shape, and the derived-table
   readback. No network, no JPEG -- tiles are synthetic RGBA buffers, so
   the same fixture always yields the same observation, and a change in
   behaviour is a change in this file."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [otent.cloud-obs :as co]
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

(defn- marks-of [tile]
  (:marks (co/cell-marks tile (:sample-grid (:params co/model)))))

(def no (bm/source-for "viirs-noaa20-truecolor"))

(defn- prov-of [tile]
  (co/provenance-record {:source no :date "2026-08-31" :tile tile
                         :sha256 "deadbeef" :key "otent/basemap/viirs-noaa20-truecolor/2026-08-31/2/1/2.jpg"}
                        "artifact-sha-test" "nbb test"))

(defn- obs-of [tile]
  (co/observation (prov-of [2 1 2]) (marks-of tile)))

;; ------------------------------------------------------------------ geometry

(t/deftest tile-bbox-and-centroid
  ;; z0 is the whole mercator world
  (let [[w s e n] (co/tile-bbox 0 0 0)]
    (t/is (and (< (- w -180) 0.01) (< (- e 180) 0.01)))
    (t/is (and (< (- s -85.05) 0.01) (< (- n 85.05) 0.01))))
  ;; z1 splits longitude in half
  (let [[w _ e _] (co/tile-bbox 1 0 0)]
    (t/is (and (< (- w -180) 0.01) (< (- e 0) 0.01))))
  ;; the centroid sits inside its own footprint
  (let [bbox (co/tile-bbox 4 8 5)
        [cx cy] (co/tile-centroid bbox)]
    (t/is (<= (first bbox) cx (nth bbox 2)))
    (t/is (<= (second bbox) cy (peek bbox)))))

;; ------------------------------------------------------------------ cell marks

(t/deftest all-dark-blue-is-clear
  ;; deep ocean: bright enough to measure, nowhere near cloud white
  (let [marks (marks-of (tile-of 256 256 [10 20 60]))
        summary (co/cloud-fraction marks)]
    (t/is (pos? (:coverage summary)))
    (t/is (zero? (:cloud-fraction summary)))
    (t/is (= :clear (:label (co/classify summary))))))

(t/deftest all-white-is-overcast
  (let [summary (co/cloud-fraction (marks-of (tile-of 256 256 [230 230 235])))]
    (t/is (= 1.0 (:cloud-fraction summary)))
    (t/is (= :overcast (:label (co/classify summary))))))

(t/deftest half-and-half-is-a-binned-fraction
  ;; 16 rows of cells: 8 cloud rows over 8 ocean rows -> fraction 0.5,
  ;; which is INSIDE the broken bin (0.45..0.70)
  (let [summary (co/cloud-fraction
                  (marks-of (mosaic 256 256 128 [230 230 235] [10 20 60])))]
    (t/is (< 0.45 (:cloud-fraction summary) 0.70))
    (t/is (= :broken (:label (co/classify summary))))))

(t/deftest low-spread-is-what-makes-cloud
  ;; bright but COLOURED (desert at 210,190,150 -- spread 60) is not
  ;; cloud, even though its brightness clears the threshold
  (let [summary (co/cloud-fraction (marks-of (tile-of 256 256 [210 190 150])))]
    (t/is (zero? (:cloud-fraction summary)))
    (t/is (= :clear (:label (co/classify summary))))))

;; ------------------------------------------------------------------ gray zones

(t/deftest gaps-between-bins-stay-unknown
  ;; a fraction in a gap between bins must NOT be stretched to the
  ;; nearest confident label
  (let [p (:params co/model)]
    (t/is (= :unknown (:label (co/classify {:cloud-fraction 0.12 :coverage 1.0}))))
    (t/is (= :unknown (:label (co/classify {:cloud-fraction 0.42 :coverage 1.0}))))
    (t/is (= :unknown (:label (co/classify {:cloud-fraction 0.72 :coverage 1.0}))))
    ;; and the refusal says why, instead of just shrugging
    (t/is (str/includes? (str (:detail (co/classify {:cloud-fraction 0.12 :coverage 1.0})))
                         "gap between bins"))))

(t/deftest low-coverage-stays-unknown
  (t/is (= :unknown (:label (co/classify {:cloud-fraction 0.9 :coverage 0.3}))))
  (t/is (str/includes? (str (:detail (co/classify {:cloud-fraction 0.9 :coverage 0.3})))
                       "not enough real pixels")))

(t/deftest no-pixels-at-all-is-unknown-not-a-guess
  (t/is (= :unknown (:label (co/classify {:cloud-fraction nil :coverage 0.0})))))

(t/deftest unknown-is-a-real-label-in-the-taxonomy
  (t/is (some #{:unknown} (:labels co/taxonomy)))
  (t/is (= "cloud-cover/1" (:taxonomy-version co/taxonomy))))

;; ------------------------------------------------------------------ confidence

(t/deftest confidence-and-uncertainty-sum-to-one
  (let [{{:keys [confidence uncertainty]} :observation} (obs-of (tile-of 256 256 [230 230 235]))]
    (t/is (<= 0.0 confidence 1.0))
    (t/is (<= 0.0 uncertainty 1.0))
    (t/is (< (- (+ confidence uncertainty) 1.0) 1e-9))))

;; ------------------------------------------------------------------ refusals

(t/deftest refuses-past-the-ingest-bound
  (let [r (co/analysis-refusal no 5 "2026-08-31")]
    (t/is (= :analysis/past-ingest-bound (:refusal r)))))

(t/deftest refuses-a-daily-asset-without-a-capture-date
  (let [r (co/analysis-refusal no 4 "not-a-date")]
    (t/is (= :analysis/capture-date-required (:refusal r))))
  (t/is (nil? (co/analysis-refusal no 4 "2026-08-31"))))

(t/deftest refuses-an-unregistered-source
  (let [r (co/analysis-refusal (bm/source-for "no-such-source") 4 "2026-08-31")]
    (t/is (= :licence/unknown-source (:refusal r)))))

;; ------------------------------------------------------------------ provenance shape

(t/deftest provenance-carries-the-pinned-model
  (let [prov (prov-of [2 1 2])]
    (t/is (= "viirs-noaa20-truecolor@2026-08-31/2/1/2" (:asset-id prov)))
    (t/is (= "NASA -- public domain" (:licence prov)))
    (t/is (= "2026-08-31T00:00:00Z" (:capture-time prov)))
    (t/is (= "otent/cloud-cover-estimator" (get-in prov [:model :id])))
    (t/is (= "0.1.0" (get-in prov [:model :version])))
    (t/is (= "artifact-sha-test" (get-in prov [:model :artifact-hash])))
    (t/is (= "cloud-cover/1" (get-in prov [:model :taxonomy :taxonomy-version])))
    ;; footprint is the WHOLE tile -- location stays broad
    (let [[w s e n] (:footprint-epsg3857 prov)]
      (t/is (< w e))
      (t/is (< s n)))))

(t/deftest raw-and-normalized-stay-distinguishable
  (let [{raw :raw obs :observation} (obs-of (tile-of 256 256 [230 230 235]))]
    ;; the raw half keeps the per-cell marks and the measured fraction
    (t/is (= 256 (count (:cell-marks raw))))
    (t/is (= 1.0 (:cloud-fraction raw)))
    ;; the normalized half is the bin, and both name the taxonomy
    (t/is (= :overcast (:label obs)))
    (t/is (= "cloud-cover/1" (:taxonomy-version obs)))))

(t/deftest failed-observation-is-a-row-not-a-drop
  (let [row (co/failed-observation [3 4 5] "some/key.jpg" :source/http-error "404")]
    (t/is (= :failed (:observation-status row)))
    (t/is (= :source/http-error (get-in row [:failure :reason])))
    (t/is (= "some/key.jpg" (get-in row [:provenance :object-key])))))

;; ------------------------------------------------------------------ summary + readback

(defn- to-jsonl [rows]
  (str/join "\n" (map #(js/JSON.stringify (clj->js %)) rows)))

(t/deftest run-summary-counts-add-up
  (let [rows [(obs-of (tile-of 256 256 [230 230 235]))
              (obs-of (tile-of 256 256 [10 20 60]))
              (co/failed-observation [1 0 0] "k" :source/http-error "500")]
        s (co/run-summary {:source no :date "2026-08-31" :max-z 4}
                          "hash" "nbb" rows 1234)]
    (t/is (= 3 (:tile-count s)))
    (t/is (= 2 (get-in s [:counts :committed])))
    (t/is (= 1 (get-in s [:counts :failed])))
    (t/is (= (+ (get-in s [:counts :committed]) (get-in s [:counts :failed]))
             (:tile-count s)))
    (t/is (= "otent/cloud-cover-estimator" (get-in s [:model :id])))
    (t/is (= 4 (:max-zoom s)))
    (t/is (= 4 (:ingest-bound s)))))

(t/deftest jsonl-readback-roundtrips
  (let [rows [(obs-of (tile-of 256 256 [230 230 235]))
              (co/failed-observation [0 0 0] "k" :decode/jpeg "bad")]
        back (->> (str/split (to-jsonl rows) "\n")
                  (remove str/blank?)
                  (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)))]
    (t/is (= 2 (count back)))
    (t/is (= "committed" (get-in back [0 :observation-status])))
    (t/is (= "overcast" (get-in back [0 :observation :label])))
    (t/is (= "failed" (get-in back [1 :observation-status])))))
