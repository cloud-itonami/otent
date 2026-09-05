(ns otent.street-span
  "Derived per-kind temporal span over provider-published street detections
  (Mapillary `map_features`), one derived task per run per the vision
  scope (:derived-allow :provider-published-detection; one source, one
  area, one PR).

  What this task is: from the analysis table `otent.street/analyze`
  already produced (privacy-gated, taxonomy-gated, geometry-gated),
  produce a per-kind temporal span — for every kind with at least one
  observation, the earliest `first_seen_at` and the latest
  `last_seen_at` the provider published — plus the refusal ledger
  carried through unchanged.

  What this task is NOT:
  - a span is an observation about what the provider published for one
    bounded area, never a lifetime, never presence/absence, never
    current existence. A kind's last_seen_at is not 'still there'.
  - the span is a LOWER BOUND of a lower bound: the provider pages
    results and the analysis itself is bounded to one tile
  - no model inference happens here (the detections are the provider's
    own published output), so model-id is :none — stated, not hidden
  - every unknown stays visible: an observation whose first_seen_at or
    last_seen_at the provider did not publish is counted as `:unknown`
    in the unknown-counts block and excluded from the min/max by
    omission-with-count, never imputed — and the refusal ledger is
    re-summed and must reconcile with raw-count."
  (:require [otent.street :as street]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "street-detections-temporal-span-v1")

;; ── the derived table ────────────────────────────────────────────────

(defn- comparable?
  "Only provider-published strings participate in min/max. Anything else
  (nil, :unknown, a non-string) is an explicit unknown, never coerced."
  [v]
  (string? v))

(defn span
  "One `otent.street/analyze` result → one per-kind temporal-span table.

  Only kinds with at least one observation get a row; kinds absent from
  the map do not get a fabricated nil-date row (the tally already makes
  zeros explicit). Rows are sorted by kind name so the table is
  deterministic regardless of map iteration order."
  [{:keys [observations refusals counts provenance] :as analysis}]
  (when-not (and (sequential? observations) (map? counts))
    (throw (ex-info "span expects an otent.street/analyze result"
                    {:got (pr-str (mapv (comp type) [observations counts]))})))
  (let [per-obs (group-by :obs/kind observations)
        rows (->> observations
                  (group-by :obs/kind)
                  (map (fn [[k os]]
                         (let [firsts (filter comparable? (map :obs/first-seen-at os))
                               lasts  (filter comparable? (map :obs/last-seen-at os))]
                           {:kind k
                            :count (count os)
                            :earliest-first-seen-at (when (seq firsts) (apply min firsts))
                            :latest-last-seen-at (when (seq lasts) (apply max lasts))
                            :first-seen-unknown (count (remove comparable? (map :obs/first-seen-at os)))
                            :last-seen-unknown (count (remove comparable? (map :obs/last-seen-at os)))})))
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
        unknown-counts {:first-seen-unknown
                        (count (filter #(not (comparable? (:obs/first-seen-at %)))
                                       observations))
                        :last-seen-unknown
                        (count (filter #(not (comparable? (:obs/last-seen-at %)))
                                       observations))}]
    {:table :street-kind-temporal-span
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
     :counts (assoc counts :span-total (count observations)
                    :ledger-reconciled? reconciled?)
     :epistemic-bounds
     ["a temporal span is an observation about provider-published first/last seen stamps in one bounded area, not a lifetime, not presence, not current existence"
      "latest-last-seen-at is not 'still there': the provider pages results and the stamps may be stale"
      "an observation with an unpublished stamp is an explicit unknown, counted, never imputed into the min or max"
      "the refusal ledger is carried through unchanged; nothing the upstream refused is re-admitted here"]}))

(defn readback
  "The readback projection: what a derived table in the catalog would
  hold. Columnar; the ledger/unknown counts ride along so a reader
  never has to infer that a missing date came from a refusal or from a
  redacted field."
  [t]
  {:table :street-kind-temporal-span
   :columns [:kind :count :earliest-first-seen-at :latest-last-seen-at
             :first-seen-unknown :last-seen-unknown]
   :rows (mapv #(select-keys % [:kind :count :earliest-first-seen-at
                                :latest-last-seen-at :first-seen-unknown
                                :last-seen-unknown])
               (:per-kind t))
   :refusal-ledger (:refusal-ledger t)
   :unknown-counts (:unknown-counts t)
   :ledger-reconciled? (get-in t [:counts :ledger-reconciled?])
   :content-hash (get-in t [:provenance :provenance/content-hash])})
