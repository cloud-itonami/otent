(ns otent.street
  "Provider-published street detections (Mapillary `map_features`) → bounded
  geospatial feature observations, with provenance and uncertainty.

  Epistemic rules this ns enforces (from otent-vision-scope.edn):

  - a detection is an **observation**, not identity, ownership, inventory,
    availability, legal compliance or current existence
  - absence in the tile proves nothing outside the tile/time
  - Mapillary publishes no per-feature confidence and no spatial-uncertainty
    figure → both are carried as `:unknown` and stay visible
  - labels outside the registered taxonomy are **refused** and counted, never
    silently dropped (unknown/redacted counts must remain visible)
  - privacy: any value matching a forbidden pattern (face identity, licence
    plate, tracking) is refused as a privacy boundary, never normalized
  - GeoJSON order is [lon lat]; a point whose lon/lat look swapped for a
    plausible terrestrial range is refused as invalid geometry, not repaired."
  (:require [clojure.string :as str]))

;; ── taxonomy: only the strings the registered client already confirms ──

(def taxonomy
  "object_value strings confirmed in com-mapillary-graph-api.core. 推測した
  文字列を混ぜない — 存在しない値で絞ると空応答が『地物が無い』に見えてしまう."
  #{"object--support--utility-pole"
    "object--support--pole"
    "object--support--traffic-sign-frame"
    "object--street-light"
    "object--banner"
    "object--sign--advertisement"
    "object--sign--store"
    "object--sign--information"})

(defn taxonomy->kind
  [object-value]
  (case object-value
    "object--support--utility-pole" :utility-pole
    "object--support--pole" :pole
    "object--support--traffic-sign-frame" :traffic-sign-frame
    "object--street-light" :street-light
    "object--banner" :banner
    "object--sign--advertisement" :sign-advertisement
    "object--sign--store" :sign-store
    "object--sign--information" :sign-information
    nil))

;; ── privacy boundary ─────────────────────────────────────────────────

(def ^:private forbidden-patterns
  "Anything matching is refused as a privacy boundary before it can become an
  observation. Defensive: the registered taxonomy admits none of these, but a
  future API value must not slip through by omission."
  [#"(?i)face"
   #"(?i)licen[cs]e[ -_]?plate"
   #"(?i)number[ -_]?plate"
   #"(?i)person"
   #"(?i)pedestrian"
   #"(?i)human"])

(defn privacy-refusal?
  "True when an object_value hits a forbidden pattern (face identity, licence
  plate, person/vehicle tracking, protected-trait inference)."
  [object-value]
  (boolean (some #(re-find % (str object-value)) forbidden-patterns)))

;; ── geometry: GeoJSON is [lon lat], and we refuse to guess ───────────

(defn valid-point?
  "Point geometry with numeric [lon lat] inside plausible terrestrial bounds,
  ordered (lon in [-180,180], lat in [-90,90])."
  [geometry]
  (let [g (if (string? geometry) nil geometry)]
    (boolean
     (when (and (map? g) (= "Point" (get g "type")))
       (let [[lon lat :as c] (get g "coordinates")]
         (and (vector? c) (= 2 (count c))
              (number? lon) (number? lat)
              (<= -180.0 lon 180.0)
              (<= -90.0 lat 90.0)))))))

;; ── provenance (required-imagery-fields / required-analysis-fields) ──

(defn provenance
  "One provenance block per analysis run. `retrieved-at` is the wall-clock
  instant the provider payload was fetched (ISO-8601 string); `input-sha256`
  is the hash of the exact bytes analyzed — fixture or live response body.
  Mapillary terms are recorded as of retrieval, never pinned by us."
  [{:keys [area-id object-values retrieved-at input-sha256 licence
           source-url attribution]}]
  {:provenance/system-id :otent-geospatial-vision
   :provenance/source-id "mapillary"
   :provenance/source-url (or source-url "https://graph.mapillary.com/map_features")
   :provenance/asset-id (str "map_features:" area-id)
   :provenance/capture-time :unknown
   :provenance/capture-time-note "each observation carries its own first/last_seen_at; the tile has no single capture time"
   :provenance/ingested-at retrieved-at
   :provenance/licence (or licence "Mapillary API Terms of Use, as current at retrieval")
   :provenance/attribution (or attribution "© Mapillary contributors")
   :provenance/content-hash input-sha256
   :provenance/model-id "provider-published-detection"
   :provenance/model-version "mapillary-vistas-object-values-at-retrieval"
   :provenance/model-artifact-hash :unknown
   :provenance/model-artifact-hash-note "provider inference; no local artifact, so no hash we can pin"
   :provenance/parameters {:area-id area-id
                           :object-values object-values
                           :taxonomy "registered-mapillary-object-values"}
   :provenance/derived-from :mapillary-map-features
   :provenance/run-at retrieved-at
   :provenance/label-taxonomy "mapillary-object_value"
   :provenance/crs "EPSG:4326 (GeoJSON lon,lat order)"})

;; ── normalization ────────────────────────────────────────────────────

(defn feature->observation-or-refusal
  [feature {:keys [area-id]}]
  (let [ov (get feature "object_value")
        id (get feature "id")
        geo (get feature "geometry")
        evidence-url (str "https://www.mapillary.com/app/?focus=map&mapFeature=" id)]
    (cond
      (privacy-refusal? ov)
      {:refusal {:refusal/feature-id (str id)
                 :refusal/object-value (str ov)
                 :refusal/reason :privacy-forbidden}}

      (not (taxonomy ov))
      {:refusal {:refusal/feature-id (str id)
                 :refusal/object-value (str ov)
                 :refusal/reason :label-not-in-taxonomy}}

      (not (valid-point? geo))
      {:refusal {:refusal/feature-id (str id)
                 :refusal/object-value (str ov)
                 :refusal/reason :invalid-geometry}}

      :else
      (let [[lon lat] (get-in geo ["coordinates"])
            kind (taxonomy->kind ov)]
        {:observation
         {:obs/source :mapillary
          :obs/source-id (str id)
          :obs/area-id area-id
          :obs/kind kind
          :obs/object-value ov
          :obs/lon lon
          :obs/lat lat
          :obs/first-seen-at (or (get feature "first_seen_at") :unknown)
          :obs/last-seen-at (or (get feature "last_seen_at") :unknown)
          ;; Mapillary publishes neither confidence nor a spatial-error figure
          ;; per map feature. Both stay :unknown and visible — 欠損は未計測.
          :obs/confidence :unknown
          :obs/spatial-uncertainty-m :unknown
          :obs/temporal-uncertainty "between first_seen_at and last_seen_at, both possibly stale"
          :obs/evidence-url evidence-url
          :obs/interpretation "an observation that this kind of feature was detected here in imagery, not identity, ownership, inventory, availability, legal compliance or current existence"}}))))

(defn analyze
  "Mapillary `map_features` JSON (as a Clojure map) → analysis table.

  Returns observations, refusals (each with a reason), and the counts that
  must stay visible: raw-count, unknown-labels (non-privacy out-of-taxonomy),
  privacy-refusals, geometry-refusals. Nothing is silently dropped."
  [json {:keys [area-id retrieved-at input-sha256 object-values licence
                source-url attribution] :as opts}]
  (let [data (get json "data" [])
        split (map #(feature->observation-or-refusal % opts) data)
        observations (vec (keep :observation split))
        refusals (vec (keep :refusal split))
        count-reason (fn [r] (count (filter #(= r (:refusal/reason %)) refusals)))]
    {:table :street-feature-observations
     :provenance (provenance (merge
                              {:object-values (vec (sort taxonomy))}
                              (select-keys opts [:area-id :retrieved-at :input-sha256
                                                 :licence :source-url :attribution])))
     :observations observations
     :refusals refusals
     :counts {:raw-count (count data)
              :observations (count observations)
              :unknown-labels (count-reason :label-not-in-taxonomy)
              :privacy-refusals (count-reason :privacy-forbidden)
              :geometry-refusals (count-reason :invalid-geometry)}
     :epistemic-bounds
     ["detections are observations, not identity/ownership/inventory/availability/legal-compliance/current-existence"
      "absence in the tile is not absence in the world"
      "capture time is not ingest time"
      "coordinates are not exact beyond the (unknown) provider uncertainty"]}))

(defn derived-table
  "The readback projection: what a derived table (e.g. in the catalog) would
  hold. Columnar so a caller can round-trip without re-parsing observations."
  [analysis]
  (let [{:keys [observations provenance]} analysis]
    {:table :street-feature-observations
     :columns [:obs/source-id :obs/area-id :obs/kind :obs/object-value
               :obs/lon :obs/lat :obs/first-seen-at :obs/last-seen-at
               :obs/confidence :obs/spatial-uncertainty-m :obs/evidence-url]
     :provenance-hash-of-asset (:provenance/content-hash provenance)
     :rows (mapv (fn [o]
                   (select-keys o [:obs/source-id :obs/area-id :obs/kind
                                   :obs/object-value :obs/lon :obs/lat
                                   :obs/first-seen-at :obs/last-seen-at
                                   :obs/confidence :obs/spatial-uncertainty-m
                                   :obs/evidence-url]))
                 observations)}))
