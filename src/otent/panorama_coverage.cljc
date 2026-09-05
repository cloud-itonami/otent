(ns otent.panorama-coverage
  "Derived temporal-coverage (vintage) analysis over Panoramax open
  street imagery METADATA, one derived task per run per the vision
  scope (:derived-allow :panoramax-open-data; one source, one area,
  one PR)."
  ;; What this task is: from imagery-asset observations already
  ;; normalized by otent.panoramax (status=ready, visibility=anyone,
  ;; per-item license present, uploader EXIF redacted), produce a
  ;; bounded vintage table for the one <=0.01 deg area the run
  ;; fetched: how many admissible pictures, and the span of their
  ;; published capture times.
  ;;
  ;; What this task is NOT:
  ;; - a vintage span is an observation about published capture times
  ;;   in one fetched page, never about street conditions, road
  ;;   quality, accessibility, ownership, or current existence
  ;; - the table is a LOWER BOUND: the provider pages results (a
  ;;   `next` link, counted not followed); a span proves nothing
  ;;   outside the fetched area or beyond the fetched page
  ;; - no model inference happens here (provider timestamps only), so
  ;;   model-id is :none - stated, not hidden
  ;; - capture times stay the provider's own published strings (no
  ;;   timezone conversion, no date math; comparison is lexicographic
  ;;   over the validated ISO-8601 UTC form, which sorts
  ;;   chronologically) - the span endpoints are byte-identical to
  ;;   what the provider published
  ;; - every unknown stays visible: a picture whose published datetime
  ;;   does not match the validated form is counted as
  ;;   capture-unknown, never dropped, never folded into the span
  (:require [clojure.string :as str]
            [otent.panoramax :as px]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "panoramax-street-vintage-v1")
(def source-id px/source-id)

;; The provider's own STAC datetime form, e.g.
;; \"2016-10-13T13:13:11.066709+00:00\": UTC (Z or +00:00), optional
;; fractional seconds. Validated, never reformatted.
(def ^:private datetime-re
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|\+00:00)$")

(defn parse-capture-time
  "The published string if it matches the provider's fixed ISO-8601
  UTC form, :unknown otherwise. No timezone conversion, no date math:
  the validated form sorts chronologically as a plain string, so span
  comparison is lexicographic and the span endpoints are
  byte-identical to what the provider published."
  [s]
  (if (and (string? s) (re-find datetime-re s)) s :unknown))

;; ── the derived table ────────────────────────────────────────────────

(defn vintage-table
  "One normalized Panoramax run → one coverage/vintage table.

  `observations` are accepted imagery-asset observations (already
  gated upstream by otent.panoramax: status=ready, visibility=anyone,
  per-item license present, uploader EXIF redacted, inside the
  declared bbox). `counts` is the run's refusal/visibility
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
                :comparison "lexicographic over the provider's published ISO-8601 UTC datetime form; no timezone conversion performed"})]
    {:table/task-id task-id
     :table/kind :temporal-coverage
     :table/source-id source-id
     :table/area-id (get-in provenance [:provenance/parameters :area-id])
     :table/bbox (get-in provenance [:provenance/parameters :bbox])
     :table/pictures {:accepted (count observations)
                      :capture-known (count known)
                      :capture-unknown unknown-count}
     :table/capture-span (or span :unknown)
     ;; the fetched page is bounded; the provider may hold more
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "next-link-present=" (boolean (:links-next counts))
          " - a span proves nothing outside the fetched area or page")
     ;; counts carried through unchanged: refusals and out-of-bbox
     ;; items stay visible, never silently dropped
     :table/run-counts counts
     :table/uncertainty-note
     "capture times are as published by the provider (STAC properties.datetime, UTC; no per-picture confidence interval is published); a picture's capture time is not the time the area was observed by anything else, and says nothing about present conditions"
     :table/epistemic-boundary
     "a temporal-coverage observation is not road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence"}))

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
         "deterministic comparison over provider-published timestamps; no model, no inference, no artifact"
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input observations were gated upstream (only status=ready, visibility=anyone, licence-carrying items admitted, uploader EXIF redacted); no pixel was fetched or stored; no face, plate, person or vehicle entity exists in this task"))

;; ── self-check over the stored document ──────────────────────────────

(defn provenance-checks
  "Readback check over the stored document: the vintage table's own
  accounting must agree with the observation vector the document
  carries, and the span endpoints (when present) must be members of
  the capture-known set derived from those observations."
  [doc]
  ;; the stored document arrives as parsed JSON (a JS object); coerce
  ;; once so a plain clj map works too
  (let [doc (js->clj doc)
        obs (get doc "observations")
        t (get doc "derived-table")
        n (count obs)
        known (get-in t ["pictures" "capture-known"])
        unknown (get-in t ["pictures" "capture-unknown"])
        ;; clj->js serializes keyword keys under their bare name
        ;; (:table/pictures → "pictures"); accept both forms so a
        ;; tampered or hand-written document cannot dodge the check
        pictures (or (get t "pictures") (get t "table/pictures"))
        known (or (get pictures "capture-known") known)
        unknown (or (get pictures "capture-unknown") unknown)
        known-times (into (sorted-set)
                          (keep (fn [o]
                                  (let [s (or (get o "capture-time")
                                              (get o "observation/capture-time"))]
                                    (when (and (string? s)
                                               (re-find datetime-re s))
                                      s)))
                                obs))
        span (or (get t "capture-span") (get t "table/capture-span"))]
    (if (and (map? t) (number? known) (number? unknown))
      (let [accounting? (= n (+ known unknown))
            span-ok? (or (nil? span)
                         (and (contains? known-times (get span "earliest-published"))
                              (contains? known-times (get span "latest-published"))))]
        (if (and accounting? span-ok?)
          {:ok? true}
          {:ok? false :error :provenance/counts-disagree
           :detail (str "observations=" n " capture-known=" known
                        " capture-unknown=" unknown
                        " span-membership=" span-ok?)}))
      {:ok? false :error :provenance/counts-disagree
       :detail "derived table is missing its capture-known/capture-unknown accounting"})))
