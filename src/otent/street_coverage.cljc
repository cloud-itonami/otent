(ns otent.street-coverage
  "Derived temporal-coverage (vintage) analysis over KartaView open
  street-imagery metadata, one derived task per run per the vision
  scope (:derived-allow :provider-published-detection; one source, one
  area, one PR)."
  ;; What this task is: from imagery-asset observations already
  ;; normalized by otent.kartaview (the provider's own published
  ;; per-photo shotDate), produce a bounded coverage table for the one
  ;; 0.01 deg area the run fetched: how many admissible photos, and the
  ;; span of their published capture times.
  ;;
  ;; What this task is NOT:
  ;; - a vintage span is an observation about published capture times
  ;;   in one fetched tile, never about street conditions, road
  ;;   quality, accessibility, ownership, or current existence
  ;; - the table is a LOWER BOUND: the provider pages results
  ;;   (hasMoreData); a span proves nothing outside the fetched area
  ;;   or beyond the fetched page
  ;; - no model inference happens here (provider timestamps only), so
  ;;   model-id is :none - stated, not hidden
  ;; - capture times stay the provider's own published strings (no
  ;;   timezone invented; comparison is lexicographic over the fixed
  ;;   YYYY-MM-DD HH:MM:SS.mmm format, which sorts chronologically)
  ;; - every unknown stays visible: photos whose published timestamp
  ;;   does not parse are counted as capture-unknown, never dropped,
  ;;   never folded into the span
  (:require [clojure.string :as str]
            [otent.kartaview :as kv]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "street-imagery-vintage-v1")
(def source-id kv/source-id)

;; the provider's own shotDate format; validated, never reformatted
(def ^:private shot-date-re
  #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$")

(defn- parse-capture-time
  "The published string if it matches the provider's fixed format,
  :unknown otherwise. No timezone conversion, no date math: the fixed
  format sorts chronologically as a plain string, so span comparison is
  lexicographic and the span endpoints are byte-identical to what the
  provider published."
  [s]
  (if (and (string? s) (re-find shot-date-re s)) s :unknown))

;; ── the derived table ────────────────────────────────────────────────

(defn vintage-table
  "One normalized KartaView run → one coverage/vintage table.

  `observations` are accepted imagery-asset observations (already
  privacy-gated upstream by otent.kartaview: only provider-BLURRED,
  public, active photos). `counts` is the run's refusal/visibility
  accounting, carried through unchanged so unknowns stay visible."
  [{:keys [observations counts provenance]}]
  (let [times (mapv #(parse-capture-time (:observation/capture-time %))
                    observations)
        known (vec (filter string? times))
        unknown-count (count (filter #(= :unknown %) times))
        span (when (seq known)
               {:earliest-published (reduce (fn [a b] (if (pos? (compare a b)) b a))
                                            (first known) (rest known))
                :latest-published (reduce (fn [a b] (if (pos? (compare a b)) a b))
                                          (first known) (rest known))
                :comparison "lexicographic over the provider's fixed YYYY-MM-DD HH:MM:SS.mmm format; no timezone conversion performed"})]
    {:table/task-id task-id
     :table/kind :temporal-coverage
     :table/source-id source-id
     :table/area-id (get-in provenance [:provenance/parameters :area-id])
     :table/bbox (get-in provenance [:provenance/parameters :bbox])
     :table/photos {:accepted (count observations)
                    :capture-known (count known)
                    :capture-unknown unknown-count}
     :table/capture-span (or span :unknown)
     ;; the fetched page is bounded; the provider may hold more
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "has-more-data=" (boolean (:has-more-data counts))
          " — a span proves nothing outside the fetched area or page")
     ;; counts carried through unchanged: refusals and out-of-bbox
     ;; items stay visible, never silently dropped
     :table/run-counts counts
     :table/uncertainty-note
     "capture times are as published by the provider (no per-photo confidence, no sensor accuracy published); a photo's capture time is not the time the area was observed by anything else, and says nothing about present conditions"
     :table/epistemic-boundary
     "a temporal-coverage observation is not road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence"}))

;; ── provenance for the derived run ───────────────────────────────────

(defn provenance
  "Derived-run provenance: wraps the upstream provenance block (source,
  licence, content-hash of the exact response bytes) and adds the task
  identity. No model is involved, so model-id is :none — stated, not
  hidden."
  [upstream {:keys [run-at]}]
  (assoc upstream
         :provenance/task-id task-id
         :provenance/model-id :none
         :provenance/model-note
         "deterministic comparison over provider-published timestamps; no model, no inference, no artifact"
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input observations were gated upstream (provider-BLURRED imagery only); no face, plate, person or vehicle entity exists in this task"))
