(ns otent.cloud-obs
  "The pure half of `bin/cloud_obs.cljs`: one pinned, deterministic
   analysis task over the already-ingested VIIRS NOAA-20 true colour
   asset -- per-tile cloud-cover fraction.

    What this is NOT: a weather report. Pixel/model output is not ground
    truth, so every row is a coarse tile-level cloud-cover estimate with
    an explicit confidence, an explicit uncertainty, and an :unknown
    class for every gray zone between the bins. Nothing here infers
    cause, people, activity, or anything on the ground under the cloud
    -- a z4 tile is roughly 2,500 km across.

    Distinct from `otent.analysis` (tile surface condition): this task
    estimates HOW MUCH of the tile is cloud, on a fractional taxonomy
    (cloud-cover/1), and refuses to answer inside the gaps between the
    bins rather than stretching a label to cover them.

    Raw and normalized stay distinguishable: each row carries the
    per-cell cloud/surface/nodata marks next to the normalized bin, and
    failed tiles become :failed rows -- never dropped."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ model

(def model
  "The one pinned model/task this run may use. `:artifact-hash` is the
   sha256 of `src/otent/cloud_obs.cljs` at run time, supplied by the bin
   script -- the code that decides the labels IS the artifact."
  {:model-id "otent/cloud-cover-estimator"
   :model-version "0.1.0"
   :task "per-tile cloud-cover fraction (coarse, non-ground-truth)"
   :runtime "nbb (ClojureScript on Node)"
   :params {:sample-grid 16          ;; 16x16 = 256 sampled cells per tile
            :nodata-max 8            ;; a cell whose max channel <= this is nodata
            :cloud-brightness>= 175  ;; a cell this bright with low spread reads as cloud
            :cloud-spread<= 40       ;; (clouds are flat white; sun-glint and ice are not)
            :coverage-min 0.5        ;; fraction of cells that must hold real pixels
            ;; Bin edges with deliberate gaps: the gap is honest.
            :clear< 0.10
            :scattered-lo 0.15 :scattered< 0.40
            :broken-lo 0.45 :broken< 0.70
            :overcast-lo 0.75}})

(def taxonomy
  "Versioned taxonomy. `:unknown` is a real class -- a cloud fraction in
   a gap between bins -- not a bucket for failures; failures are
   `:failed` rows, a different thing."
  {:taxonomy-version "cloud-cover/1"
   :labels [:clear :scattered :broken :overcast :unknown]})

(def ^:private p (:params model))

;; ------------------------------------------------------------------ geometry

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

;; ------------------------------------------------------------------ cell marks

(defn- px [data w i]
  [(aget data (* i 4)) (aget data (+ (* i 4) 1)) (aget data (+ (* i 4) 2))])

(defn- cell-mean
  "Mean RGB of one rectangular cell of pixels, or :nodata when the whole
   cell is dark (max channel at or below :nodata-max)."
  [data w x0 y0 x1 y1]
  (let [cw (- x1 x0), tot (* cw (- y1 y0))]
    (loop [i 0, rs 0, gs 0, bs 0, cnt 0]
      (if (< i tot)
        (let [p-i (+ (* (+ y0 (quot i cw)) w) (+ x0 (mod i cw)))
              [pr pg pb] (px data w p-i)]
          (recur (inc i) (+ rs pr) (+ gs pg) (+ bs pb) (inc cnt)))
        (let [rr (/ rs cnt) gg (/ gs cnt) bb (/ bs cnt)]
        (if (<= (max (int rr) (int gg) (int bb)) (:nodata-max p))
          {:nodata? true}
          (let [brightness (/ (+ rr gg bb) 3)
                spread (- (max rr gg bb) (min rr gg bb))]
            {:brightness brightness
             :spread spread
             :cloudy? (and (>= brightness (:cloud-brightness>= p))
                           (<= spread (:cloud-spread<= p)))})))))))

(defn cell-marks
  "Sample a `grid`x`grid` lattice over an RGBA tile buffer. Returns
   {:marks [cell...]} where each cell is either {:nodata? true} or
   {:brightness :spread :cloudy?}, in row-major order -- the raw half
   of the observation."
  [{:keys [width height data]} grid]
  (let [cw (/ width grid), ch (/ height grid)
        marks (for [cy (range grid), cx (range grid)]
                (cell-mean data width
                           (int (* cy cw)) (int (* cx ch))
                           (int (* (inc cy) cw)) (int (* (inc cx) ch))))]
    {:marks (vec marks)}))

(defn cloud-fraction
  "Tile-level summary of the cell marks: :coverage (fraction of cells
   with real pixels) and :cloud-fraction (fraction of REAL cells marked
   cloudy -- nodata cells count in coverage, never in the fraction)."
  [marks]
  (let [n (count marks)
        real (remove :nodata? marks)]
    (if (zero? (count real))
      {:coverage 0.0 :cloud-fraction nil}
      {:coverage (/ (double (count real)) n)
       :cloud-fraction (/ (double (count (filter :cloudy? real))) (count real))})))

;; ------------------------------------------------------------------ classify

(defn classify
  "Tile summary -> one normalized cloud-cover bin + confidence. Each bin
   is a band of cloud fraction with a deliberate gap after it; a tile in
   a gap is :unknown, never stretched into the nearest confident bin."
  [{:keys [cloud-fraction coverage]}]
  (cond
    (nil? cloud-fraction)
    {:label :unknown :confidence 0.0
     :detail "no measurable pixels in tile"}

    (< coverage (:coverage-min p))
    {:label :unknown :confidence 0.0
     :detail (str "coverage " (double coverage) " below " (:coverage-min p)
                  " -- not enough real pixels")}

    (< cloud-fraction (:clear< p))
    {:label :clear :confidence (min 1.0 (+ 0.5 (* 5.0 (- (:clear< p) cloud-fraction))))
     :detail nil}

    (and (>= cloud-fraction (:scattered-lo p)) (< cloud-fraction (:scattered< p)))
    {:label :scattered
     :confidence (min 1.0 (+ 0.5 (* 5.0 (min (- cloud-fraction (:scattered-lo p))
                                             (- (:scattered< p) cloud-fraction)))))
     :detail nil}

    (and (>= cloud-fraction (:broken-lo p)) (< cloud-fraction (:broken< p)))
    {:label :broken
     :confidence (min 1.0 (+ 0.5 (* 5.0 (min (- cloud-fraction (:broken-lo p))
                                             (- (:broken< p) cloud-fraction)))))
     :detail nil}

    (>= cloud-fraction (:overcast-lo p))
    {:label :overcast
     :confidence (min 1.0 (+ 0.5 (* 5.0 (- cloud-fraction (:overcast-lo p)))))
     :detail nil}

    :else
    {:label :unknown :confidence 0.0
     :detail (str "cloud fraction " (double cloud-fraction)
                  " sits in a gap between bins -- gray zone stays unknown")}))

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

;; ------------------------------------------------------------------ records

(defn provenance-record
  "The provenance line for one observation: what bytes, where from,
   whose licence, what geometry. Everything rule 2 asks to be recorded."
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

(defn- cell-kind
  "One-word raw mark per cell, for the row's raw half."
  [m]
  (cond (:nodata? m) :nodata
        (:cloudy? m) :cloud
        :else :surface))

(defn observation
  "One normalized observation row. Raw (the cloud fraction, coverage and
   the per-cell marks) and the normalized bin stay side by side and
   distinguishable -- the raw half is what a re-classification with a
   different pin would need."
  [prov marks]
  (let [summary (cloud-fraction marks)
        res (classify summary)]
    {:observation-status :committed
     :provenance prov
     :raw {:coverage (:coverage summary)
           :cloud-fraction (:cloud-fraction summary)
           :cell-marks (mapv cell-kind marks)
           :detail (:detail res)}
     :observation {:label (:label res)
                   :taxonomy-version (:taxonomy-version taxonomy)
                   :confidence (double (:confidence res))
                   :uncertainty (- 1.0 (double (:confidence res)))
                   :location-granularity "tile (EPSG:3857 xyz, z<=4)"}}))

(defn failed-observation
  "A tile that could not be fetched or decoded. It goes in the table as
   a :failed row -- dropping it would make the coverage look better than
   it is."
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
