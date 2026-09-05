(ns otent.panoramax-daylight
  "Derived capture-daylight classification over Panoramax open
  street-imagery metadata — one derived task per run per the vision
  scope (:derived-allow :locally-reproducible-model-run; one source,
  one area, one PR).

  What this task is: from imagery-asset observations already normalized
  by otent.panoramax (their own published UTC capture time and their
  own lon/lat), run ONE deterministic locally-reproducible model — the
  solar-elevation classifier in otent.solar-elevation — and emit the
  class counts (:daylight / :civil-twilight / :night) for the one
  0.01 deg area the run fetched.

  What this task is NOT:
  - a lighting class is derived from astronomy over published capture
    times, never from the pixels; no image is fetched, no face or
    plate or person or vehicle entity exists in this task
  - it says nothing about what the camera actually saw: overcast noon
    and clear noon are both :daylight; exposure and scene content are
    not classified here
  - the table is a LOWER BOUND over the fetched page (:links-next)
  - the model is approximate: a declared ±2° elevation uncertainty
    rides on every classification, so a result near a threshold is
    provisional by construction
  - every unknown stays visible: items whose published datetime does
    not conform to the provider's observed format are counted
    capture-unknown, never dropped, never classified"
  (:require [clojure.string :as str]
            [otent.panoramax :as px]
            [otent.solar-elevation :as solar]))

;; ── task identity ────────────────────────────────────────────────────

(def task-id "panoramax-capture-daylight-v1")
(def source-id px/source-id)
(def label-taxonomy #{:daylight :civil-twilight :night})

(defn- classify-observation
  "One accepted observation → [:ok :daylight] | [:ok :night] ...
  or [:unknown reason]. Deterministic over published fields only."
  [{:observation/keys [capture-time footprint]}]
  (let [lonlat (:coordinates footprint)
        parsed (solar/parse-utc-datetime capture-time)]
    (cond
      (not (:ok? parsed))
      [:unknown :capture-unknown]
      (or (not (vector? lonlat))
          (not= 2 (count lonlat))
          (not (every? number? lonlat)))
      [:unknown :geometry-unknown]
      :else
      (let [[lon lat] lonlat]
        [:ok (solar/classify (solar/solar-elevation-deg parsed lon lat))]))))

;; ── the derived table ────────────────────────────────────────────────

(defn daylight-table
  "One normalized Panoramax run → one capture-daylight table.

  `observations` are accepted imagery-asset observations (already
  privacy-gated upstream by otent.panoramax). `counts` is the run's
  refusal/visibility accounting, carried through unchanged."
  [{:keys [observations counts provenance]}]
  (let [results (mapv classify-observation observations)
        ok (filter #(= :ok (first %)) results)
        unknowns (frequencies (map second (filter #(= :unknown (first %)) results)))
        classes (frequencies (map second ok))]
    {:table/task-id task-id
     :table/kind :capture-daylight
     :table/source-id source-id
     :table/area-id (get-in provenance [:provenance/parameters :area-id])
     :table/bbox (get-in provenance [:provenance/parameters :bbox])
     :table/photos {:accepted (count observations)
                    :classified (count ok)
                    :capture-unknown (get unknowns :capture-unknown 0)
                    :geometry-unknown (get unknowns :geometry-unknown 0)}
     :table/classes
     {:daylight (get classes :daylight 0)
      :civil-twilight (get classes :civil-twilight 0)
      :night (get classes :night 0)}
     :table/model
     {:model-id solar/model-id
      :model-version solar/model-version
      :parameters {:daylight-threshold-deg solar/daylight-threshold-deg
                   :civil-twilight-floor-deg solar/civil-twilight-floor-deg
                   :elevation-uncertainty-deg solar/elevation-uncertainty-deg}
      ;; the artifact hash of the classifier source file is filled in by
      ;; the bin script (it reads the file bytes); stated as :unknown
      ;; when unavailable rather than hidden
      :model-artifact-hash :unknown
      :accuracy-note "NOAA low-precision algorithm; declared elevation uncertainty ±2°"}
     :table/spatial-uncertainty
     (str "elevation computed at each observation's own published point; "
          "the provider's quality:horizontal_accuracy is the positional "
          "uncertainty and is carried on each observation")
     :table/coverage-bound :lower-bound
     :table/coverage-bound-note
     (str "counts reflect one bounded fetch of one <=0.01 deg area; "
          "links-next=" (boolean (:links-next counts))
          " — the class counts prove nothing outside the fetched area or page")
     :table/run-counts counts
     :table/uncertainty-note
     (str "solar elevation is computed from the published UTC capture time "
          "and position with a ±" solar/elevation-uncertainty-deg
          "° declared uncertainty; a class near a threshold (0°, −6°) is "
          "provisional; capture time is not current existence")
     :table/epistemic-boundary
     "a capture-daylight class is not scene lighting, exposure, road condition, accessibility, ownership, inventory, availability, legal compliance, or current existence; absence of a class outside the fetched page proves nothing"}))

;; ── provenance for the derived run ───────────────────────────────────

(defn provenance
  "Derived-run provenance: wraps the upstream provenance block (source,
  licence, content-hash of the exact response bytes) and adds the task
  and model identity. `artifact-hash` is the sha256 of the classifier
  source file bytes (or :unknown)."
  [upstream {:keys [run-at artifact-hash]}]
  (assoc upstream
         :provenance/task-id task-id
         :provenance/label-taxonomy label-taxonomy
         :provenance/model-id solar/model-id
         :provenance/model-version solar/model-version
         :provenance/model-artifact-hash artifact-hash
         :provenance/model-artifact-note
         "sha256 of the classifier source file (src/otent/solar_elevation.cljc) at run time"
         :provenance/model-parameters
         {:daylight-threshold-deg solar/daylight-threshold-deg
          :civil-twilight-floor-deg solar/civil-twilight-floor-deg
          :elevation-uncertainty-deg solar/elevation-uncertainty-deg}
         :provenance/derived-run-at run-at
         :provenance/privacy-note
         "input observations were gated upstream (status=ready, visibility=anyone, EXIF redaction enforced); no image is fetched and no face, plate, person or vehicle entity exists in this task"))
