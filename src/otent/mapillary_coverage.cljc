(ns otent.mapillary-coverage
  "Derived temporal-coverage (vintage) analysis over Mapillary street
  imagery METADATA, one derived task per run per the vision scope
  (:derived-allow :provider-published-detection; one source, one area,
  one PR)."
  ;; What this task is: from imagery-asset observations already
  ;; normalized by otent.mapillary-images (Graph API /images, metadata
  ;; only, thumbnails never requested), produce a bounded coverage
  ;; table for the one <=0.01 deg area the run fetched: how many
  ;; admissible images, and the span of their published capture times.
  ;;
  ;; What this task is NOT:
  ;; - a vintage span is an observation about published capture times
  ;;   in one fetched tile, never about street conditions, road
  ;;   quality, accessibility, ownership, or current existence
  ;; - the table is a LOWER BOUND: the Graph API pages results
  ;;   (paging.next, counted not followed); a span proves nothing
  ;;   outside the fetched area or beyond the fetched page
  ;; - no model inference happens here (provider timestamps only), so
  ;;   model-id is :none - stated, not hidden
  ;; - capture times are the provider's own epoch milliseconds,
  ;;   compared numerically. No timezone is invented and no derived
  ;;   calendar string is stored: the endpoints are byte-identical to
  ;;   what the provider published
  ;; - every unknown stays visible: an image whose captured_at is not
  ;;   a number never reaches the span and never vanishes -- it is
  ;;   counted as capture-unknown
  (:require [otent.mapillary-images :as mimg]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "mapillary-street-vintage-v1")
(def source-id mimg/source-id)

(defn vintage-table
  "One normalized Mapillary run -> one coverage/vintage table.

  `observations` are accepted imagery-asset observations (already
  gated upstream by otent.mapillary-images: metadata only, redaction
  check passed, inside the declared bbox). `counts` is the run's
  refusal/visibility accounting, carried through unchanged so
  unknowns stay visible."
  [{:keys [observations counts provenance]}]
  (let [times (mapv (fn [o]
                      (let [v (:observation/capture-time-ms o)]
                        (if (number? v) v :unknown)))
                    observations)
        known (vec (filter number? times))
        unknown-count (count (filter #(= :unknown %) times))
        span (when (seq known)
               {:earliest-captured-ms (reduce (fn [a b] (if (pos? (compare a b)) b a))
                                              (first known) (rest known))
                :latest-captured-ms (reduce (fn [a b] (if (pos? (compare a b)) a b))
                                            (first known) (rest known))
                :comparison "numeric comparison over the provider's own epoch milliseconds (captured_at); endpoints are the published values, no timezone conversion, no calendar string derived"})]
    {:table/task-id task-id
     :table/kind :temporal-coverage
     :table/source-id source-id
     :table/area-id (get provenance :area-id)
     :table/bbox (get provenance :bbox)
     :table/images {:accepted (count observations)
                    :capture-known (count known)
                    :capture-unknown unknown-count}
     :table/capture-span (or span :unknown)
     ;; the fetched page is bounded; the provider may hold more
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "paging-next=" (boolean (:links-next counts))
          " — a span proves nothing outside the fetched area or page")
     ;; counts carried through unchanged: refusals and out-of-bbox
     ;; items stay visible, never silently dropped
     :table/run-counts counts
     :table/uncertainty-note
     "capture times are as published by the provider (epoch ms; Mapillary publishes no per-image capture-time confidence and no per-image spatial-error figure); an image's capture time is not the time the area was observed by anything else, and says nothing about present conditions"
     :table/epistemic-boundary
     "a temporal-coverage observation is not road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence"}))

;; ── provenance for the derived run ───────────────────────────────────

(defn provenance
  "Derived-run provenance: wraps the upstream provenance block (source,
  licence, sha256 of the exact response bytes, client constraints) and
  adds the task identity. No model is involved, so model-id is :none —
  stated, not hidden."
  [upstream {:keys [run-at]}]
  (assoc upstream
         :provenance/task-id task-id
         :provenance/model-id :none
         :provenance/model-note
         "deterministic comparison over provider-published epoch-millisecond timestamps; no model, no inference, no artifact"
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input is metadata only (no pixel was fetched or stored, thumbnail URLs never requested); provider blur state is not per-image verified (provider-blur-verified false); no face, plate, person or vehicle entity exists in this task"))

(defn provenance-checks
  "Readback check over the stored document: the derived table's own
  image counts must agree with the observation vector the document
  carries, and the span (if any) must lie inside the known set."
  [doc]
  (let [c (get doc "counts")
        obs (get doc "observations")
        t (get doc "derived-table")
        n (count obs)
        accepted (get-in t ["images" "accepted"])]
    (if (and (map? c) (map? t) (= n accepted))
      (let [span (get t "capture-span")]
        (if (or (nil? span) (= :unknown span))
          {:ok? true}
          (let [ms (keep #(let [v (or (get % "capture-time-ms")
                                      (get % "observation/capture-time-ms"))]
                            (when (number? v) v))
                         obs)]
            (if (and (seq ms)
                     (>= (get span "earliest-captured-ms") (apply min ms))
                     (<= (get span "latest-captured-ms") (apply max ms)))
              {:ok? true}
              {:ok? false :error :provenance/span-disagrees
               :detail "derived span is not inside the stored capture times"}))))
      {:ok? false :error :provenance/counts-disagree
       :detail (str "derived accepted=" accepted " observations=" n)})))
