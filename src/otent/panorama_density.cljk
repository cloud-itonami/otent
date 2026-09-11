(ns otent.panorama-density
  "Derived spatial-density analysis over Panoramax open street imagery
  METADATA, one derived task per run per the vision scope
  (:derived-allow :panoramax-open-data; one source, one area, one PR)."
  ;; What this task is: from imagery-asset observations already
  ;; normalized by otent.panoramax (status=ready, visibility=anyone,
  ;; per-item license present, uploader EXIF redacted), bin the one
  ;; <=0.01 deg area the run fetched into a fixed deterministic grid
  ;; and count admissible pictures per cell.
  ;;
  ;; What this task is NOT:
  ;; - a per-cell count is an observation about published metadata in
  ;;   one fetched page, never about street conditions, road quality,
  ;;   accessibility, ownership, or current existence
  ;; - the grid is a LOWER BOUND: the provider pages results (a `next`
  ;;   link, counted not followed); an empty cell proves nothing about
  ;;   the provider's actual coverage there
  ;; - no model inference happens here (provider geometry only), so
  ;;   model-id is :none - stated, not hidden
  ;; - every unknown stays visible: a picture whose published view:azimuth
  ;;   is not a number is still counted in its cell, as
  ;;   heading-unknown; a cell with zero admissible pictures is kept
  ;;   as an explicit zero, never omitted
  (:require [otent.panoramax :as px]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "panoramax-street-density-v1")
(def source-id px/source-id)

;; The grid: the bbox is strictly under 0.01 deg a side (upstream
;; gate), so a fixed target cell size of 0.0025 deg yields at most a
;; 4x4 grid. The cell count per axis is (ceil span 0.0025), computed
;; from the declared bbox - the same bbox always produces the same
;; grid, whatever the observations are.
(def target-cell-deg 0.0025)
(def max-cells-per-axis 4)

(defn grid-shape
  "A bbox [W S E N] -> the deterministic cell counts per axis and the
  exact cell edges. Pure arithmetic over the declared bbox;
  independent of any observation."
  [[w s e n]]
  (let [span-x (- e w)
        span-y (- n s)
        nx (min max-cells-per-axis
                (max 1 (js/Math.ceil (- (/ span-x target-cell-deg) 1e-9))))
        ny (min max-cells-per-axis
                (max 1 (js/Math.ceil (- (/ span-y target-cell-deg) 1e-9))))]
    {:nx nx :ny ny
     :edges-x (mapv (fn [i] (+ w (* span-x (/ i nx)))) (range (inc nx)))
     :edges-y (mapv (fn [i] (+ s (* span-y (/ i ny)))) (range (inc ny)))}))

(defn cell-of
  "One observation -> its [ix iy] cell index, or nil if the point lies
  outside the declared bbox (which upstream already refuses to admit;
  a nil here is a bug signal, never silently folded into a cell)."
  [{:observation/keys [footprint]} {:keys [edges-x edges-y nx ny]}]
  (let [[lon lat] (:coordinates footprint)
        ix (some (fn [i]
                   (when (and (<= (nth edges-x i) lon)
                              (<= lon (nth edges-x (inc i))))
                     i))
                 (range nx))
        iy (some (fn [i]
                   (when (and (<= (nth edges-y i) lat)
                              (<= lat (nth edges-y (inc i))))
                     i))
                 (range ny))]
    (when (and ix iy) [ix iy])))

(defn- heading-known? [o]
  (let [orient (:observation/orientation o)]
    (boolean (and (map? orient) (number? (:heading-deg orient))))))

(defn- sequence-known? [o]
  (let [s (:observation/sequence-id o)]
    (boolean (and (string? s) (not (empty? s))))))

(defn density-table
  "One normalized Panoramax run -> one spatial-density table.

  `observations` are accepted imagery-asset observations (already
  gated upstream by otent.panoramax: status=ready, visibility=anyone,
  per-item license present, uploader EXIF redacted, inside the
  declared bbox). `counts` is the run's refusal/visibility accounting,
  carried through unchanged so unknowns stay visible."
  [{:keys [observations counts provenance]}]
  (let [bbox (get-in provenance [:provenance/parameters :bbox])
        shape (grid-shape bbox)
        per-cell (into (sorted-map)
                       (for [iy (range (:ny shape))
                             ix (range (:nx shape))]
                         [[ix iy]
                          {:pictures 0
                           :heading-known 0 :heading-unknown 0
                           :sequence-known 0}]))
        binned (reduce (fn [acc o]
                         (let [c (cell-of o shape)]
                           (if (nil? c)
                             ;; upstream admits only in-bbox points, so
                             ;; this would be a bug -- it is counted
                             ;; separately below, never folded into a
                             ;; neighbouring cell
                             acc
                             (let [k (get acc c)]
                               (assoc acc c
                                      (cond-> (assoc k :pictures (inc (:pictures k)))
                                        (heading-known? o)
                                        (update :heading-known inc)
                                        (not (heading-known? o))
                                        (update :heading-unknown inc)
                                        (sequence-known? o)
                                        (update :sequence-known inc)))))))
                       per-cell
                       observations)
        unplaceable (count (filter #(nil? (cell-of % shape)) observations))
        cells (vec (for [iy (range (:ny shape))
                         ix (range (:nx shape))
                         :let [k (get binned [ix iy])]]
                     {:cell-index [ix iy]
                      :bounds [(nth (:edges-x shape) ix)
                               (nth (:edges-y shape) iy)
                               (nth (:edges-x shape) (inc ix))
                               (nth (:edges-y shape) (inc iy))]
                      :pictures (:pictures k)
                      :heading-known (:heading-known k)
                      :heading-unknown (:heading-unknown k)
                      :sequence-known (:sequence-known k)}))]
    {:table/task-id task-id
     :table/kind :spatial-density
     :table/source-id source-id
     :table/area-id (get-in provenance [:provenance/parameters :area-id])
     :table/bbox bbox
     :table/grid {:target-cell-degrees target-cell-deg
                  :cells-x (:nx shape)
                  :cells-y (:ny shape)
                  :cell-count (count cells)}
     :table/cells cells
     :table/pictures {:accepted (count observations)
                      :placed (- (count observations) unplaceable)
                      :unplaceable unplaceable}
     ;; the fetched page is bounded; the provider may hold more
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "next-link-present=" (boolean (:links-next counts))
          " - an empty cell says nothing about the provider's actual coverage there")
     ;; counts carried through unchanged: refusals and out-of-bbox
     ;; items stay visible, never silently dropped
     :table/run-counts counts
     :table/uncertainty-note
     "picture positions are as published by the provider (GeoJSON lon/lat, with quality:horizontal_accuracy as the per-item spatial-uncertainty figure, which the provider states as a 95% interval, not a guaranteed error bound); a count in one cell is not density of anything but admissible metadata in one fetched page"
     :table/epistemic-boundary
     "a spatial-density observation is not road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence"}))

;; ── provenance for the derived run ───────────────────────────────────

(defn provenance
  "Derived-run provenance: wraps the upstream provenance block (source,
  licence, sha256 of the exact response bytes) and adds the task
  identity. No model is involved, so model-id is :none - stated, not
  hidden."
  [upstream {:keys [run-at]}]
  (assoc upstream
         :provenance/task-id task-id
         :provenance/model-id :none
         :provenance/model-note
         "deterministic binning of provider-published lon/lat into a fixed grid derived from the declared bbox; no model, no inference, no artifact"
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input observations were gated upstream (only status=ready, visibility=anyone, licence-carrying items admitted, uploader EXIF redacted); no pixel was fetched or stored; no face, plate, person or vehicle entity exists in this task"))

(defn provenance-checks
  "Readback check over the stored document: the derived table's own
  placed/unplaceable accounting must agree with the observation vector
  the document carries, and the per-cell picture counts must sum to the
  placed count."
  [doc]
  (let [obs (get doc "observations")
        t (get doc "derived-table")
        n (count obs)
        placed (get-in t ["pictures" "placed"])
        unplaceable (get-in t ["pictures" "unplaceable"])
        cell-sum (reduce + 0 (map #(get % "pictures") (get t "cells" [])))]
    (if (and (map? t) (number? placed) (number? unplaceable))
      (if (and (= n (+ placed unplaceable))
               (= placed cell-sum))
        {:ok? true}
        {:ok? false :error :provenance/counts-disagree
         :detail (str "observations=" n " placed=" placed
                      " unplaceable=" unplaceable " cell-sum=" cell-sum)})
      {:ok? false :error :provenance/counts-disagree
       :detail "derived table is missing its placed/unplaceable accounting"})))
