(ns otent.change
  "The pure half of `bin/compare.cljs`: one pinned, deterministic CHANGE
   OBSERVATION task -- per-tile surface-condition difference between two
   capture dates of the same already-ingested MODIS Terra true colour asset.

    What this is NOT: an explanation. `:change-detection-is-not-cause`
    is an epistemic boundary of the vision scope, so the label here is
    the transition and nothing else -- no cause, no event, no claim
    about what happened on the ground. A tile that brightened is a
    tile that brightened; whether that was cloud, snow, fire, flood
    or sensor calibration is deliberately unasked.

    Every comparison needs BOTH sides measured: a date whose tile
    failed to fetch or decode cannot silently become `no-change`, and
    a side whose surface condition came back `:unknown` (or with no
    pixels) makes the whole comparison :inconclusive -- never coerced.
    Raw per-date metrics ride along in the row so a re-pin of the
    thresholds can be recomputed without re-fetching a byte."
  (:require [clojure.string :as str]
            [otent.analysis :as an]))

;; ------------------------------------------------------------------ model

(def model
  "The one pinned model/task this run may use. `:artifact-hash` is the
   sha256 of `src/otent/change.cljs` at run time, supplied by the bin
   script -- the code that decides the transitions IS the artifact.
   The per-date surface condition itself is `otent.analysis`'s pinned
   classifier, whose artifact hash is recorded alongside."
  {:model-id "otent/tile-change-observer"
   :model-version "0.1.0"
   :task "per-tile surface-condition change between two capture dates (coarse, non-ground-truth, no cause)"
   :runtime "nbb (ClojureScript on Node)"
   ;; Brightness-mean deltas in 0..255 mean-space, on the SAME tile
   ;; geometry (same z/x/y) across the two dates. A deadband keeps
   ;; sensor noise and JPEG jitter from becoming a story; between the
   ;; deadband and the threshold the honest answer is :inconclusive.
   :params {:brightness-deadband 10   ;; |delta| <= this: no change
             :brightness-change>= 25  ;; |delta| at/above this: a change label
             :min-side-confidence 0.7 ;; each date's condition must be at least this confident
             :min-coverage 0.5}})     ;; each date's tile must have real pixels

(def taxonomy
  "Versioned taxonomy. :inconclusive is a real class -- a gray zone or
   an unmeasurable side lands there rather than being forced."
  {:taxonomy-version "tile-change/1"
   :labels [:no-change :brightening :darkening :inconclusive]})

(def ^:private p (:params model))

(defn- sub-model
  "The pinned per-date condition classifier this task consumes."
  [] an/model)

;; ------------------------------------------------------------------ refusals

(defn- valid-date? [s]
  (boolean (and (string? s) (re-find #"^\d{4}-\d{2}-\d{2}$" s))))

(defn change-refusal
  "Why this comparison must not run, or nil if it may. Same family as
   `otent.analysis/analysis-refusal`: the ingest bound holds, both dates
   must be real capture dates, and they must be distinct and ordered --
   an unordered pair would make `brightening` and `darkening` the same
   claim read in opposite directions."
  [source max-z date-a date-b]
  (cond
    (:refusal source)
    source

    (> max-z (:max-ingest-zoom source))
    {:refusal :change/past-ingest-bound
     :detail (str "zoom " max-z " is past the asset's ingest bound of "
                  (:max-ingest-zoom source))}

    (not (and (valid-date? date-a) (valid-date? date-b)))
    {:refusal :change/capture-dates-required
     :detail (str "two YYYY-MM-DD capture dates are required, got "
                  (pr-str date-a) " and " (pr-str date-b))}

    (= date-a date-b)
    {:refusal :change/dates-must-differ
     :detail (str "both dates are " date-a " -- comparing a capture to itself"
                  " would be a noise measurement, not a change observation")}

    (pos? (compare date-a date-b))
    {:refusal :change/dates-out-of-order
     :detail (str date-a " is after " date-b
                  " -- pass them as --from EARLIER --to LATER so the transition"
                  " direction is defined")}))

;; ------------------------------------------------------------------ classify

(defn classify-change
  "The two per-date condition results ({:label :confidence :raw-scores}
   from `otent.analysis/classify`, plus the tile metrics) and the two
   metrics maps -> one normalized transition label.

   Order of refusals inside the row: a side that was never measured
   (nil) is :unmeasured; a side that was :unknown or low-confidence
   makes :inconclusive; only two confident readings get compared."
  [a b]
  (let [delta (when (and (:brightness (:metrics a)) (:brightness (:metrics b)))
                (- (:brightness (:metrics b)) (:brightness (:metrics a))))
        both-confident? (and (>= (double (:confidence a)) (:min-side-confidence p))
                             (>= (double (:confidence b)) (:min-side-confidence p))
                             (not= :unknown (:label a)) (not= :unknown (:label b)))
        scores (when delta
                 {:brightening (cond-> 0
                                 (>= delta (:brightness-change>= p)) (+ 0.8)
                                 (pos? delta) (+ 0.1))
                  :darkening (cond-> 0
                               (<= delta (- (:brightness-change>= p))) (+ 0.8)
                               (neg? delta) (+ 0.1))
                  :no-change (cond-> 0
                               (<= (Math/abs delta) (:brightness-deadband p)) (+ 0.8)
                               (< (Math/abs delta) (:brightness-change>= p)) (+ 0.1))})]
    (cond
      (or (nil? (:metrics a)) (nil? (:metrics b)))
      {:label :inconclusive :confidence 0.0 :raw-scores {}
       :detail "one side has no measurable pixels -- absence of data is not absence of change"}

      (not both-confident?)
      {:label :inconclusive :confidence 0.0 :raw-scores (or scores {})
       :detail (str "side conditions " (:label a) "/" (:confidence a) " and "
                    (:label b) "/" (:confidence b)
                    " -- both dates need a confident surface condition first")}

      (<= (Math/abs delta) (:brightness-deadband p))
      {:label :no-change :confidence (min 1.0 (min (double (:confidence a)) (double (:confidence b))))
       :raw-scores scores :detail nil}

      (>= delta (:brightness-change>= p))
      {:label :brightening :confidence (min 1.0 (min (double (:confidence a)) (double (:confidence b))))
       :raw-scores scores :detail nil}

      (<= delta (- (:brightness-change>= p)))
      {:label :darkening :confidence (min 1.0 (min (double (:confidence a)) (double (:confidence b))))
       :raw-scores scores :detail nil}

      :else
      {:label :inconclusive :confidence 0.0 :raw-scores scores
       :detail (str "brightness delta " delta " between the deadband "
                    (:brightness-deadband p) " and the change threshold "
                    (:brightness-change>= p) " -- gray zone stays inconclusive")})))

;; ------------------------------------------------------------------ records

(defn change-observation
  "One normalized change-observation row for tile [z x y] between the
   two dates. Both dates' provenance halves stay in the row and stay
   distinguishable; raw per-date metrics + raw-scores sit beside the
   normalized transition."
  [{:keys [source tile date-from date-to key-a key-b sha-a sha-b metrics-a metrics-b res-a res-b]}
   artifact-hash sub-artifact-hash runtime]
  (let [res (classify-change
              (merge {:label (:label res-a) :confidence (:confidence res-a)}
                     {:metrics metrics-a})
              (merge {:label (:label res-b) :confidence (:confidence res-b)}
                     {:metrics metrics-b}))
        bbox (an/tile-bbox (tile 0) (tile 1) (tile 2))]
    {:observation-status :committed
     :provenance
     {:asset-id (str (:id source) "/" (str/join "/" tile) "/"
                    date-from ".." date-to)
      :from {:date date-from :content-sha256 sha-a :object-key key-a
             :capture-time (str date-from "T00:00:00Z")
             :surface-condition {:label (:label res-a) :confidence (double (:confidence res-a))
                                 :taxonomy-version (:taxonomy-version an/taxonomy)}}
      :to {:date date-to :content-sha256 sha-b :object-key key-b
           :capture-time (str date-to "T00:00:00Z")
           :surface-condition {:label (:label res-b) :confidence (double (:confidence res-b))
                               :taxonomy-version (:taxonomy-version an/taxonomy)}}
      :crs (:crs source)
      :tile {:z (tile 0) :x (tile 1) :y (tile 2)}
      :footprint-epsg3857 bbox
      :centroid-wgs84 (an/tile-centroid bbox)
      :sensor (:sensor source)
      :bands (:bands source)
      :licence (:licence source)
      :attribution (:attribution source)
      :model {:id (:model-id model) :version (:model-version model)
              :artifact-hash artifact-hash :runtime runtime
              :params (:params model) :taxonomy taxonomy
              :consumes {:model-id (:model-id (sub-model))
                         :artifact-hash sub-artifact-hash}}}
     :raw {:metrics-from (dissoc metrics-a :coverage)
           :metrics-to (dissoc metrics-b :coverage)
           :coverage-from (:coverage metrics-a)
           :coverage-to (:coverage metrics-b)
           :raw-scores (:raw-scores res)
           :detail (:detail res)}
     :observation {:label (:label res)
                   :taxonomy-version (:taxonomy-version taxonomy)
                   :confidence (double (:confidence res))
                   :uncertainty (- 1.0 (double (:confidence res)))
                   :location-granularity "tile (EPSG:3857 xyz, z<=4)"
                   :cause nil}}))

(defn failed-change-observation
  "A tile where either date could not be fetched or decoded. It is a
   :failed row in the same table -- never dropped, never read as
   no-change."
  [tile key-a key-b reason detail]
  {:observation-status :failed
   :provenance {:tile {:z (tile 0) :x (tile 1) :y (tile 2)}
                :object-key-from key-a :object-key-to key-b}
   :failure {:reason reason :detail detail}})

(defn run-summary
  "The run manifest: counts that must add up, the pinned model record,
   both dates, and the bound the run actually kept."
  [{:keys [source date-from date-to max-z]} artifact-hash rows elapsed-ms]
  (let [by-status (frequencies (map :observation-status rows))
        labels (frequencies (keep #(some-> % :observation :label) rows))]
    {:source-id (:id source)
     :date-from date-from
     :date-to date-to
     :max-zoom max-z
     :ingest-bound (:max-ingest-zoom source)
     :tile-count (count rows)
     :counts {:committed (get by-status :committed 0)
              :failed (get by-status :failed 0)}
     :inconclusive (get labels :inconclusive 0)
     :labels labels
     :taxonomy taxonomy
     :model {:id (:model-id model) :version (:model-version model)
             :artifact-hash artifact-hash
             :consumes {:model-id (:model-id (sub-model))}}
     :run-ms elapsed-ms}))
