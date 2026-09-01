(ns otent.street-extent
  "Derived per-kind spatial extent over provider-published street
  detections (Mapillary `map_features`), one derived task per run per
  the vision scope (:derived-allow :provider-published-detection; one
  source, one area, one PR).

  What this task is: from the analysis table `otent.street/analyze`
  already produced (privacy-gated, taxonomy-gated, geometry-gated),
  produce a per-kind spatial extent — for every kind with at least one
  observation, the min/max published lon/lat of the observations —
  plus the refusal ledger carried through unchanged.

  What this task is NOT:
  - an extent is an observation about where the provider published
    detections in one bounded area, never a territory, a boundary, an
    occupancy claim, or a per-subject location. It is the convex box of
    detection points, and it can never be smaller than the query bbox
    is allowed to be — it is a LOWER BOUND of a lower bound, because
    the provider pages results and the analysis is bounded to one tile.
  - no per-subject tracking: rows are keyed by KIND only, never by
    source-id, object-value, person or vehicle. Faces and licence
    plates cannot become entities anywhere upstream or here.
  - no model inference (model-id :none, stated); the detections are
    the provider's own published output.
  - every unknown stays visible: an observation whose lon/lat the
    provider did not publish as numbers is counted in the per-row
    `lon-unknown`/`lat-unknown` and excluded from min/max by
    omission-with-count, never imputed — and the refusal ledger is
    re-summed and must reconcile with raw-count."
  (:require [otent.street :as street]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "street-detections-spatial-extent-v1")

;; ── the derived table ────────────────────────────────────────────────

(defn- coordinate?
  "Only provider-published finite numbers participate in min/max.
  Anything else is an explicit unknown, never coerced."
  [v]
  (and (number? v)
       #?(:cljs (not (js/isNaN v)) :clj (not (Double/isNaN v)))
       (>= v -180.0) (<= v 180.0)))

(defn extent
  "One `otent.street/analyze` result → one per-kind spatial-extent table.

  Only kinds with at least one observation get a row; kinds absent from
  the map do not get a fabricated nil-bbox row (the tally already makes
  zeros explicit). Rows are sorted by kind name so the table is
  deterministic regardless of map iteration order."
  [{:keys [observations refusals counts provenance] :as analysis}]
  (when-not (and (sequential? observations) (map? counts))
    (throw (ex-info "extent expects an otent.street/analyze result"
                    {:got (pr-str (mapv (comp type) [observations counts]))})))
  (let [rows (->> observations
                  (group-by :obs/kind)
                  (map (fn [[k os]]
                         (let [lons (filter coordinate? (map :obs/lon os))
                               lats (filter coordinate? (map :obs/lat os))]
                           {:kind k
                            :count (count os)
                            :min-lon (when (seq lons) (apply min lons))
                            :max-lon (when (seq lons) (apply max lons))
                            :min-lat (when (seq lats) (apply min lats))
                            :max-lat (when (seq lats) (apply max lats))
                            :lon-unknown (count (remove coordinate? (map :obs/lon os)))
                            :lat-unknown (count (remove coordinate? (map :obs/lat os)))})))
                  (sort-by (comp str :kind))
                  vec)
        ledger (->> refusals
                    (group-by :refusal/reason)
                    (map (fn [[reason rs]]
                           {:reason reason :count (count rs)}))
                    (sort-by (comp str :reason))
                    vec)
        reconciled? (= (:raw-count counts)
                       (+ (count observations)
                          (reduce + 0 (map :count ledger))))
        unknown-counts {:lon-unknown
                        (count (filter #(not (coordinate? (:obs/lon %)))
                                       observations))
                        :lat-unknown
                        (count (filter #(not (coordinate? (:obs/lat %)))
                                       observations))}]
    {:table :street-kind-spatial-extent
     :task-id task-id
     :derived-from (:table analysis)
     :provenance (assoc provenance
                        :provenance/model-id :none
                        :provenance/model-id-note
                        "no model inference in this task; the detections are the provider's own published output"
                        :provenance/derived-from task-id
                        :provenance/parameters
                        (assoc (:provenance/parameters provenance)
                               :derived-task task-id))
     :per-kind rows
     :refusal-ledger ledger
     :unknown-counts unknown-counts
     :counts (assoc counts :extent-total (count observations)
                    :ledger-reconciled? reconciled?)
     :epistemic-bounds
     ["a spatial extent is an observation about where the provider published detections in one bounded area, not a territory, not occupancy, not per-subject location"
      "rows are keyed by kind only; no source-id, person or vehicle is ever keyed, tracked or re-identified"
      "the extent is a lower bound of a lower bound: the provider pages results and the analysis is bounded to one tile"
      "an observation with an unpublished coordinate is an explicit unknown, counted, never imputed into the min or max"
      "the refusal ledger is carried through unchanged; nothing the upstream refused is re-admitted here"]}))

(defn readback
  "The readback projection: what a derived table in the catalog would
  hold. Columnar; the ledger/unknown counts ride along so a reader
  never has to infer that a missing coordinate came from a refusal or
  from a redacted field."
  [t]
  {:table :street-kind-spatial-extent
   :columns [:kind :count :min-lon :max-lon :min-lat :max-lat
             :lon-unknown :lat-unknown]
   :rows (mapv #(select-keys % [:kind :count :min-lon :max-lon
                                :min-lat :max-lat :lon-unknown
                                :lat-unknown])
               (:per-kind t))
   :refusal-ledger (:refusal-ledger t)
   :unknown-counts (:unknown-counts t)
   :ledger-reconciled? (get-in t [:counts :ledger-reconciled?])
   :content-hash (get-in t [:provenance :provenance/content-hash])})