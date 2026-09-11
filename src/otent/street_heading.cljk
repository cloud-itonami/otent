(ns otent.street-heading
  "Derived heading/panorama coverage analysis over Mapillary /images
  metadata, one derived task per run per the vision scope
  (:derived-allow :provider-published-detection; one source, one area,
  one PR)."
  ;; What this task is: from imagery-asset observations already
  ;; normalized by otent.mapillary-images (the provider's own published
  ;; compass_angle and is_pano flags), produce a bounded heading
  ;; histogram (8 compass sectors) and panorama count for the one
  ;; 0.01 deg area the run fetched.
  ;;
  ;; What this task is NOT:
  ;; - a heading sector is an observation about the camera bearing the
  ;;   provider published for one capture, never about what the image
  ;;   shows, what faces it, road condition, ownership, or current
  ;;   existence
  ;; - the histogram is a LOWER BOUND: the provider pages results
  ;;   (paging.next is counted, not followed); a sector count proves
  ;;   nothing outside the fetched area or beyond the fetched page
  ;; - no model inference happens here (provider metadata only), so
  ;;   model-id is :none - stated, not hidden
  ;; - angles are the provider's own degrees, binned by
  ;;   floor(mod(angle,360)/45) into N,NE,E,SE,S,SW,W,NW; no
  ;;   smoothing, no interpolation, no re-projection
  ;; - every unknown stays visible: images whose published compass
  ;;   angle is missing or non-numeric are counted as heading-unknown,
  ;;   never dropped, never folded into a sector
  (:require [otent.mapillary-images :as mimg]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "street-imagery-heading-v1")
(def source-id mimg/source-id)

(def sectors ["N" "NE" "E" "SE" "S" "SW" "W" "NW"])

(defn sector
  "The 45-degree compass sector of a published angle in degrees, or
  :unknown when the provider published none. The bin is pure integer
  arithmetic over the provider's own number — nothing else is
  inferred."
  [angle]
  (if (number? angle)
    (nth sectors (int (Math/floor (/ (mod angle 360) 45))))
    :unknown))

;; ── the derived table ────────────────────────────────────────────────

(defn heading-table
  "One normalized Mapillary /images run → one heading/pano coverage
  table.

  `observations` are accepted imagery-asset observations (already
  privacy-gated upstream by otent.mapillary-images: curated fields,
  redaction check, lon/lat order). `counts` is the run's refusal/
  visibility accounting, carried through unchanged so unknowns stay
  visible."
  [{:keys [observations counts provenance]}]
  (let [hits (mapv #(sector (:observation/compass-angle-deg %))
                   observations)
        hist (reduce (fn [m s] (update m s (fnil inc 0)))
                     (zipmap sectors (repeat 0))
                     (filter #(not= :unknown %) hits))
        unknown-count (count (filter #(= :unknown %) hits))
        pano-count (count (filter :observation/is-panorama observations))]
    {:table/task-id task-id
     :table/kind :heading-coverage
     :table/source-id source-id
     ;; otent.mapillary-images provenance carries area-id/bbox at the top level
     :table/area-id (:area-id provenance)
     :table/bbox (:bbox provenance)
     :table/images {:accepted (count observations)
                    :heading-known (- (count observations) unknown-count)
                    :heading-unknown unknown-count
                    :panorama pano-count
                    :non-panorama (- (count observations) pano-count)}
     :table/heading-histogram hist
     :table/sector-definition
     "floor(mod(published angle,360)/45); sector bounds [0,45)=N, [45,90)=NE, [90,135)=E, [135,180)=SE, [180,225)=S, [225,270)=SW, [270,315)=W, [315,360)=NW — binned, never smoothed"
     ;; the fetched page is bounded; the provider may hold more
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "links-next=" (boolean (:links-next counts))
          " — a sector count proves nothing outside the fetched area or page")
     ;; counts carried through unchanged: refusals and out-of-bbox
     ;; items stay visible, never silently dropped
     :table/run-counts counts
     :table/uncertainty-note
     "headings are as published by the provider (no per-image confidence, no spatial-error figure published); compass_angle is the camera bearing at capture, not a property of any object in the frame, and says nothing about present conditions"
     :table/epistemic-boundary
     "a heading-coverage observation is not image content, road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence"}))

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
         "deterministic binning over provider-published compass angles and panorama flags; no model, no inference, no artifact"
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input observations were gated upstream (curated metadata fields, redaction check, geometry order); no face, plate, person or vehicle entity exists in this task"))

;; ── readback checks ──────────────────────────────────────────────────

(defn provenance-checks
  "Every invariant a caller should be able to verify from the stored
  table alone: heading-known + heading-unknown = accepted, the
  histogram sums to heading-known, panorama + non-panorama = accepted,
  and the run counts carried through still agree with the upstream
  accounting."
  [table]
  (let [imgs (:table/images table)
        hist (:table/heading-histogram table)
        hist-sum (apply + (map #(get hist % 0) sectors))
        run-counts (:table/run-counts table)]
    (if (and (= (+ (:heading-known imgs) (:heading-unknown imgs))
                (:accepted imgs))
             (= hist-sum (:heading-known imgs))
             (= (+ (:panorama imgs) (:non-panorama imgs))
                (:accepted imgs))
             (= (:accepted run-counts) (:accepted imgs)))
      {:ok? true}
      {:ok? false :error :provenance/counts-disagree
       :detail (str "accepted=" (:accepted imgs)
                    " known=" (:heading-known imgs)
                    " unknown=" (:heading-unknown imgs)
                    " hist-sum=" hist-sum
                    " pano=" (:panorama imgs))})))
