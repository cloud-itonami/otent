(ns otent.analysis
  "The pure half of `bin/analyze.cljs`: one pinned, deterministic analysis
   task over an already-ingested imagery asset -- per-tile surface
   condition from MODIS Terra true colour.

    What this is NOT: a ground-truth labeller. Rule 3 of the vision scope
    says pixel/model output is not ground truth, so every label here is a
    coarse, tile-level surface condition with an explicit confidence, an
    explicit uncertainty, and an :unknown class that anything ambiguous
    falls into rather than being quietly forced into a guess. Nothing
    here infers cause, ownership, legality, people, or activity -- a
    z4 tile is roughly 2,500 km across, which is broader than any
    sensitive inference could survive.

    Raw and normalized stay distinguishable: each observation row carries
    the raw per-class scores next to the normalized label, and failed
    tiles become :failed rows in the same table -- never dropped."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ model

(def model
  "The one pinned model/task this run may use. `:artifact-hash` is the
   sha256 of `src/otent/analysis.cljs` at run time, supplied by the bin
   script -- the code that decides the labels IS the artifact."
  {:model-id "otent/tile-condition-classifier"
   :model-version "0.1.0"
   :task "per-tile surface condition (coarse, non-ground-truth)"
   :runtime "nbb (ClojureScript on Node)"
   ;; Integer thresholds in 0..255 per-band-mean space. Deliberately
   ;; conservative margins so the honest answer to a gray zone is
   ;; :unknown, not a confident guess.
   :params {:sample-grid 16            ;; 16x16 = 256 sampled cells per tile
             :nodata-max 8              ;; a cell whose max channel <= this is nodata
             :water-brightness< 100     ;; mean brightness below this reads as water
             :bright-brightness>= 180   ;; at/above this with low spread: cloud or snow
             :bright-spread<= 40
             :classify-min-score 0.7    ;; a label must score at least this to stick
             :classify-min-coverage 0.5}}) ;; fraction of cells that must hold real pixels


(def taxonomy
  "Versioned taxonomy. `:unknown` is a real class, not a bucket for
   failures -- failures are `:failed` rows, a different thing."
  {:taxonomy-version "tile-condition/1"
   :labels [:water :bright :vegetated-land :barren-land :dim :unknown]})

(def ^:private p (:params model))

;; ------------------------------------------------------------------ geometry

(defn- merc-n [z y]
  (let [n (bit-shift-left 1 z)]
    (* (- (/ y n) 0.5) 2 Math/PI)))

(defn- merc-y->lat [y]
  (* 180 (/ (Math/atan (Math/sinh y)) Math/PI)))

(defn tile-bbox
  "The EPSG:3857 tile's extent, in WGS84 degrees: [west south east north].
   This is the footprint an observation claims -- a whole z4 tile, so
   location stays broad."
  [z x y]
  (let [n (bit-shift-left 1 z)
        unit (/ 360 n)
        west (- (* x unit) 180)
        east (- (* (inc x) unit) 180)
        lat (fn [ty] (* 180 (/ (Math/atan (Math/sinh (* Math/PI (- 1 (* 2 (/ ty n)))))) Math/PI)))]
    [west (lat (inc y)) east (lat y)]))

(defn tile-centroid
  "Broad centroid [lon lat] of the tile bbox."
  [[w s e n]]
  [(/ (+ w e) 2) (/ (+ s n) 2)])

;; ------------------------------------------------------------------ metrics

(defn- px [data w i] [(aget data (* i 4)) (aget data (+ (* i 4) 1)) (aget data (+ (* i 4) 2))])

(defn tile-metrics
  "Sample a `grid`x`grid` lattice over an RGBA tile buffer. Per cell: the
   mean RGB of its pixels. Per tile: the mean of cell means, the mean
   spread (max channel minus min channel of the cell mean -- low spread
   with high brightness is the cloud/snow signature), and :coverage, the
   fraction of cells that contain real pixels. Nodata cells are EXCLUDED
   from the means and REPORTED in coverage -- a half-empty tile is not a
   confident tile."
  [{:keys [width height data]} grid]
  (let [cw (/ width grid), ch (/ height grid)
        cells (for [cy (range grid), cx (range grid)]
                (let [x0 (int (* cy cw)), y0 (int (* cx ch))
                      x1 (int (* (inc cy) cw)), y1 (int (* (inc cx) ch))
                      ;; gather this cell's pixels
                      acc (atom {:r 0 :g 0 :b 0 :n 0 :mx 0})]
                  (doseq [yy (range y0 y1), xx (range x0 x1)]
                    (let [[r g b] (px data width (+ (* yy width) xx))]
                      (when (> (max r g b) (:nodata-max p))
                        (swap! acc (fn [a] {:r (+ (:r a) r) :g (+ (:g a) g)
                                            :b (+ (:b a) b) :n (inc (:n a))
                                            :mx (max (:mx a) (max r g b))})))))
                  (let [{:keys [r g b n]} @acc]
                    (if (zero? n)
                      {:nodata? true}
                      (let [mr (/ r n), mg (/ g n), mb (/ b n)]
                        {:r mr :g mg :b mb
                         :brightness (/ (+ mr mg mb) 3)
                         :spread (- (max mr mg mb) (min mr mg mb))})))))
        real (remove :nodata? cells)
        n (count real)]
    (if (zero? n)
      {:coverage 0.0}
      (let [mean (fn [k] (/ (reduce + 0 (map k real)) n))]
        {:coverage (/ (double n) (* grid grid))
         :r (mean :r) :g (mean :g) :b (mean :b)
         :brightness (mean :brightness)
         :spread (mean :spread)}))))

;; ------------------------------------------------------------------ classify

(defn classify
  "Tile metrics -> one normalized label + confidence, by a fixed ladder of
   rules. Each rule below :bright requires the previous, brighter class to
   have failed, so a dark blue tile is water even though a bright cloud
   over it would have said otherwise. :unknown is what a gray zone gets;
   it is never coerced, and the raw scores keep the near-misses visible."
  [{:keys [brightness spread coverage r g b]}]
  (let [;; per-class scores, kept in the raw half of the row
        scores (when brightness
                 {:bright (cond-> 0
                            (>= brightness (:bright-brightness>= p)) (+ 0.6)
                            (<= spread (:bright-spread<= p)) (+ 0.3))
                  :water (cond-> 0
                           (< brightness (:water-brightness< p)) (+ 0.5)
                           (>= b r) (+ 0.2)
                           (>= b g) (+ 0.2))
                  :vegetated-land (cond-> 0
                                    (>= brightness (:water-brightness< p)) (+ 0.3)
                                    (< brightness (:bright-brightness>= p)) (+ 0.2)
                                    (> g r) (+ 0.2)
                                    (> g b) (+ 0.2))
                  :barren-land (cond-> 0
                                 (>= brightness (:water-brightness< p)) (+ 0.3)
                                 (< brightness (:bright-brightness>= p)) (+ 0.2)
                                 (> r g) (+ 0.2)
                                 (> r b) (+ 0.2))})
        top (if scores (apply max-key val scores) nil)
        ;; a label sticks only when its own rule-set is convincing: the
        ;; winner clears :classify/min-score and the tile had pixels.
        confident? (and top
                        (>= (val top) (:classify-min-score p))
                        (>= coverage (:classify-min-coverage p)))]
    (cond
      (nil? brightness)
      {:label :dim :confidence 0.0 :raw-scores {} :detail "no measurable pixels in tile"}

      confident?
      {:label (key top) :confidence (min 1.0 (double (val top)))
       :raw-scores scores :detail nil}

      (< coverage (:classify-min-coverage p))
      {:label :unknown :confidence 0.0 :raw-scores (or scores {})
       :detail (str "coverage " (double coverage) " below "
                    (:classify-min-coverage p) " -- not enough real pixels")}

      :else
      {:label :unknown :confidence 0.0 :raw-scores (or scores {})
       :detail (str "winner " (pr-str (key top)) " scored " (double (val top))
                    " below " (:classify-min-score p) " -- gray zone stays unknown")})))

;; ------------------------------------------------------------------ refusals

(defn- valid-date?
  "Shape check only, inlined so this namespace stays free of
   `otent.basemap` (and its R2 constants)."
  [s]
  (boolean (and (string? s) (re-find #"^\d{4}-\d{2}-\d{2}$" s))))

(defn analysis-refusal
  "Why this analysis must not run, or nil if it may. Mirrors the ingest
   refusals: an unregistered source, a zoom past the imagery's ingest
   bound (analysing upsampled bytes would be analysing an artefact), and
   a daily layer without the capture date the bytes belong to."
  [source max-z date]
  (cond
    (:refusal source)
    source

    (> max-z (:max-ingest-zoom source))
    {:refusal :analysis/past-ingest-bound
     :detail (str "zoom " max-z " is past the asset's ingest bound of "
                  (:max-ingest-zoom source) " -- past it the stored tiles do not exist,"
                  " and re-fetching from the service past its own max would analyse upsampled bytes")}

    (and (= :daily (:time-mode source)) (not (valid-date? date)))
    {:refusal :analysis/capture-date-required
     :detail (str "a :daily asset needs a YYYY-MM-DD capture date, got " (pr-str date))}))

;; `otent.basemap` is required by the bin script for the plan; keep this
;; namespace free of that dependency (and its R2 constants) by inlining
;; the same date shape check.
(defn- bm-valid-date? [s]
  (boolean (and (string? s) (re-find #"^\d{4}-\d{2}-\d{2}$" s))))

;; ------------------------------------------------------------------ records

(defn provenance-record
  "The provenance line for one observation: what bytes, where from, whose
   licence, what geometry. Everything rule 2 asks to be recorded."
  [{:keys [source date tile sha256 key]} artifact-hash runtime]
  (let [bbox (tile-bbox (tile 0) (tile 1) (tile 2))]
    {:asset-id (str (:id source) (when date (str "@" date)) "/" (str/join "/" tile))
     :object-key key
     :content-sha256 sha256
     :capture-time (when date (str date "T00:00:00Z"))
     :crs (:crs source)
     :tile {:z (tile 0) :x (tile 1) :y (tile 2)}
     :footprint-epsg3857 bbox
     :centroid-wgs84 (tile-centroid bbox)
     :sensor (:sensor source)
     :bands (:bands source)
     :licence (:licence source)
     :attribution (:attribution source)
     :model {:id (:model-id model) :version (:model-version model)
             :artifact-hash artifact-hash :runtime runtime
             :params (:params model) :taxonomy taxonomy}}))

(defn observation
  "One normalized observation row. `raw` (the per-class scores and the
   tile metrics) and the normalized label stay side by side and
   distinguishable -- the raw half is what a re-classification with a
   different pin would need."
  [prov metrics]
  (let [res (classify metrics)]
    {:observation-status :committed
     :provenance prov
     :raw {:metrics (dissoc metrics :coverage)
           :coverage (:coverage metrics)
           :raw-scores (:raw-scores res)
           :detail (:detail res)}
     :observation {:label (:label res)
                   :taxonomy-version (:taxonomy-version taxonomy)
                   :confidence (double (:confidence res))
                   :uncertainty (- 1.0 (double (:confidence res)))
                   :location-granularity "tile (EPSG:3857 xyz, z<=4)"}}))

(defn failed-observation
  "A tile that could not be fetched or decoded. It goes in the table as a
   :failed row -- dropping it would make the coverage look better than it
   is, which is a lie of exactly the kind the refusal counts exist for."
  [tile key reason detail]
  {:observation-status :failed
   :provenance {:tile {:z (tile 0) :x (tile 1) :y (tile 2)} :object-key key}
   :failure {:reason reason :detail detail}})

(defn run-summary
  "The run manifest: counts that must add up, the pinned model record,
   and the bound the run actually kept."
  [{:keys [source date max-z]} artifact-hash runtime rows elapsed-ms]
  (let [by-status (frequencies (map :observation-status rows))
        labels (frequencies (keep #(some-> % :observation :label) rows))]
    {:source-id (:id source)
     :date date
     :max-zoom max-z
     :ingest-bound (:max-ingest-zoom source)
     :tile-count (count rows)
     :counts {:committed (get by-status :committed 0)
              :failed (get by-status :failed 0)}
     :unknown (get labels :unknown 0)
     :labels labels
     :taxonomy taxonomy
     :model {:id (:model-id model) :version (:model-version model)
             :artifact-hash artifact-hash :runtime runtime
             :params (:params model)}
     :run-ms elapsed-ms}))
