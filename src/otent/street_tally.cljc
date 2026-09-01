(ns otent.street-tally
  "Derived per-kind tally over provider-published street detections
  (Mapillary `map_features`), one derived task per run per the vision
  scope (:derived-allow :provider-published-detection; one source, one
  area, one PR).

  What this task is: from the analysis table `otent.street/analyze`
  already produced (privacy-gated, taxonomy-gated, geometry-gated),
  produce a per-kind tally — every registered taxonomy kind present with
  an explicit count, zeros included — plus the refusal ledger carried
  through unchanged.

  What this task is NOT:
  - a per-kind count is an observation about what the provider's own
    detector published for one bounded area, never inventory, never
    availability, never current existence. Zero in the tally is not
    absence in the world.
  - the tally is a LOWER BOUND of a lower bound: the provider pages
    results and the analysis itself is bounded to one tile
  - no model inference happens here (the detections are the provider's
    own published output), so model-id is :none — stated, not hidden
  - every unknown stays visible: observations whose confidence or
    spatial uncertainty the provider did not publish are counted as
    `:unknown` in the unknown-counts block, never dropped, never
    imputed — and the refusal ledger (privacy, out-of-taxonomy,
    invalid-geometry) is re-summed and must reconcile with raw-count."
  (:require [otent.street :as street]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "street-detections-kind-tally-v1")

;; ── the derived table ────────────────────────────────────────────────

(defn tally
  "One `otent.street/analyze` result → one per-kind tally table.

  Every kind in the registered taxonomy appears, zeros explicit: a kind
  missing from the map entirely must be distinguishable from a kind the
  run forgot to count. Rows are sorted by kind name so the table is
  deterministic regardless of map iteration order."
  [{:keys [observations refusals counts provenance] :as analysis}]
  (when-not (and (sequential? observations) (map? counts))
    (throw (ex-info "tally expects an otent.street/analyze result"
                    {:got (pr-str (mapv (comp type) [observations counts]))})))
  (let [per-obs (group-by :obs/kind observations)
        rows (->> street/taxonomy
                  (map (fn [ov]
                         (let [k (street/taxonomy->kind ov)]
                           {:kind k
                            :object-value ov
                            :count (count (get per-obs k []))})))
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
        unknown-counts {:confidence-unknown
                        (count (filter #(= :unknown (:obs/confidence %))
                                       observations))
                        :spatial-uncertainty-unknown
                        (count (filter #(= :unknown (:obs/spatial-uncertainty-m %))
                                       observations))}]
    {:table :street-kind-tally
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
     :counts (assoc counts :tally-total (count observations)
                    :ledger-reconciled? reconciled?)
     :epistemic-bounds
     ["a per-kind count is an observation about provider-published detections in one bounded area, not inventory, availability or current existence"
      "zero is not absence in the world — and not absence in the tile either: the provider pages results"
      "the refusal ledger is carried through unchanged; nothing the upstream refused is re-admitted here"]}))

(defn readback
  "The readback projection: what a derived table in the catalog would
  hold. Columnar; every registered taxonomy kind appears even when zero,
  and the ledger/unknown counts ride along so a reader never has to
  infer that a zero came from a refusal."
  [t]
  {:table :street-kind-tally
   :columns [:kind :object-value :count]
   :rows (mapv #(select-keys % [:kind :object-value :count]) (:per-kind t))
   :refusal-ledger (:refusal-ledger t)
   :unknown-counts (:unknown-counts t)
   :ledger-reconciled? (get-in t [:counts :ledger-reconciled?])
   :content-hash (get-in t [:provenance :provenance/content-hash])})
