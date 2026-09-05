(ns otent.mapillary-images
  "Mapillary street-level imagery **metadata** (Graph API v4 `/images`,
  through the registered client `com-mapillary-graph-api`) -> bounded
  imagery-asset observations, with provenance and uncertainty.

  Source policy (otent-vision-scope.edn): :street-allow names Mapillary
  under its current terms; the registered client already encodes the API
  constraints (bbox <= 0.01 deg a side, limit <= 2000, token in the
  Authorization header -- never the URL). This ns adds only what the
  client deliberately does not decide:

  - one source, one area, one request per run (:run-bounds). A
    `paging.next` in the payload is COUNTED and recorded, not followed.
  - metadata only. No pixel is fetched or stored; the canonical evidence
    URL is the Mapillary viewer permalink, and the thumbnail URL is
    deliberately NOT requested -- a field we never read is a field we
    never have to defend.
  - the token is a capability of the caller. It is read from the
    environment by the bin, passed only through
    `com-mapillary-graph-api/authorization-header`, and never reaches a
    URL, a log line, or an observation.
  - capture time is `captured_at` (ms since epoch), never ingest time.
  - the provider publishes no per-image spatial-error figure and no
    per-image blur-result flag -> uncertainty is `:unknown` and the
    blur story is carried as `provider-blur-verified false` with the
    limitation stated, the same way `otent.panoramax` carries it.
    Faces and plates remain outside the observation space entirely.
  - geometry arrives GeoJSON lon/lat; a point that is only plausible
    if swapped is refused, not repaired."
  (:require [clojure.string :as str]
            [com-mapillary-graph-api.core :as mi]))

;; -- source identity -----------------------------------------------------------

(def source-id "mapillary-images")

(def terms-url "https://mapillary.com/legal/terms/")

(def attribution "© Mapillary contributors (imagery, CC-BY-SA as published by Mapillary)")

(def licence
  "As of retrieval, from Mapillary's published terms for contributor
  imagery. Not re-verified per item: the /images fields list carries no
  per-image licence field, and inventing one would be worse than naming
  where the licence actually lives."
  "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)")

;; Fields requested from /images. `thumb_1024_url` is deliberately
;; absent -- no pixel, no pixel URL.
(def image-fields ["id" "geometry" "captured_at" "compass_angle" "is_pano"])

(defn evidence-url [id] (str "https://www.mapillary.com/app/?pKey=" id))

;; -- bounds ---------------------------------------------------------------------

(def max-span 0.01)

(defn check-bbox
  "`bbox` is [west south east north] in degrees. Refuses anything bigger
  than the run bound, non-numeric, or folded (west > east). The client
  enforces the same limit for its own request; this check exists so a
  refusal happens BEFORE any request is built."
  [bbox]
  (let [[w s e n] bbox]
    (cond
      (or (not (vector? bbox)) (not= 4 (count bbox))
          (some #(or (not (number? %)) (js/isNaN %)) bbox))
      {:ok? false :error :mapillary/bbox-invalid
       :detail (str "bbox must be 4 numbers [W S E N], got " (pr-str bbox))}
      :else
      (let [dw (- e w) dn (- n s)]
        (cond
          (or (<= dw 0) (<= dn 0))
          {:ok? false :error :mapillary/bbox-inverted
           :detail (str "west/south must be smaller: " (pr-str bbox))}
          (or (>= dw max-span) (>= dn max-span))
          {:ok? false :error :mapillary/bbox-too-large
           :detail (str "span " dw "x" dn " deg must stay strictly under the "
                        max-span " deg client limit; one area per run")}
          :else {:ok? true
                 :area-id (str "bbox-" w "-" s "-" e "-" n)})))))

(defn build-request
  "The exact request the registered client builds for this run's bbox.
  Returns `{:request {:url :query-params} :bbox bbox}`. No token in the
  result -- it rides in the Authorization header at fetch time."
  [bbox]
  (let [{:keys [ok? error detail]} (check-bbox bbox)]
    (if-not ok?
      {:ok? false :error error :detail detail}
      {:ok? true
       :request (mi/images-request {:west (nth bbox 0)
                                    :south (nth bbox 1)
                                    :east (nth bbox 2)
                                    :north (nth bbox 3)}
                                   {:fields image-fields})
       :bbox bbox})))

;; -- geometry: GeoJSON lon/lat, refused not repaired ----------------------------

(defn- plausible-lonlat?
  [lonlat]
  (let [[lon lat :as c] lonlat]
    (and (vector? c) (= 2 (count c))
         (every? number? c)
         (<= -180.0 lon 180.0)
         (<= -90.0 lat 90.0))))

(defn valid-geometry?
  [feature]
  (let [g (get feature "geometry")]
    (and (map? g)
         (= "Point" (get g "type"))
         (plausible-lonlat? (get g "coordinates")))))

;; -- privacy boundary -------------------------------------------------------------

(defn- clean-value? [v]
  (cond
    (string? v) (not (str/includes? v "@"))
    (map? v) (every? (fn [[k v2]] (and (not (str/includes? (str/lower-case (str k)) "exif"))
                                       (not (str/includes? (str/lower-case (str k)) "email"))
                                       (clean-value? v2)))
                     v)
    (vector? v) (every? clean-value? v)
    :else true))

(defn redacted?
  "The /images payload is metadata curated by the field list, but the
  redaction check runs anyway: any observation carrying an `@` (an email
  riding in on some string) or a key matching exif/email is a bug, and a
  bug that ships uploader identity is the one that must refuse."
  [obs]
  (clean-value? obs))

;; -- item gate + normalization ---------------------------------------------------

(defn image->observation-or-refusal
  "One /images feature -> one imagery-asset observation, or a refusal
  that names the reason. Every refusal is counted by the caller; nothing
  is silently dropped."
  [feature retrieved-at]
  (let [id (get feature "id")
        coords (get-in feature ["geometry" "coordinates"])
        captured-at (get feature "captured_at")
        compass (get feature "compass_angle")
        pano (get feature "is_pano")]
    (cond
      (str/blank? id)
      {:ok? false :error :mapillary/missing-asset-id :detail "image has no id"}

      (not (valid-geometry? feature))
      {:ok? false :error :mapillary/invalid-geometry
       :detail (str "image " id " geometry is not a plausible GeoJSON Point lon/lat: "
                    (pr-str (get feature "geometry")))}

      (not (number? captured-at))
      {:ok? false :error :mapillary/missing-capture-time
       :detail (str "image " id " has no numeric captured_at")}

      :else
      {:ok? true
       :observation {:observation/source source-id
                     :observation/source-id (str id)
                     :observation/lat (nth coords 1)
                     :observation/lon (nth coords 0)
                     :observation/capture-time-ms captured-at
                     :observation/retrieved-at retrieved-at
                     :observation/compass-angle-deg (if (number? compass) compass :unknown)
                     :observation/is-panorama (boolean pano)
                     :observation/spatial-uncertainty :unknown
                     :observation/provider-blur-verified false
                     :observation/evidence-url (evidence-url id)
                     :observation/licence licence
                     :observation/attribution attribution}})))

(defn normalize-payload
  "Graph API `{\"data\" [...]}` (as parsed clj) -> observations, refusals
  and counts, filtered back down to the declared bbox. The API's bbox
  semantics are inclusive of boundary tiles; anything returned outside
  the declared area stays visible in the counts rather than vanishing."
  [payload {:keys [bbox retrieved-at]}]
  (let [[w s e n] bbox
        data (get payload "data" [])
        paging-next (get-in payload ["paging" "next"])
        steps (map (fn [f]
                     (let [r (image->observation-or-refusal f retrieved-at)]
                       (if-not (:ok? r)
                         {:kind :refused :refusal (dissoc r :ok?)}
                         (let [o (:observation r)]
                           (if (redacted? o)
                             (let [lon (:observation/lon o) lat (:observation/lat o)]
                               (if (and (<= w lon e) (<= s lat n))
                                 {:kind :accepted :observation o}
                                 {:kind :outside-bbox :observation o}))
                             {:kind :refused
                              :refusal {:error :mapillary/redaction-check
                                        :detail (str "image " (:observation/source-id o)
                                                     " failed the redaction check")}})))))
                   data)
        accepted (vec (keep #(when (= :accepted (:kind %)) (:observation %)) steps))
        refusals (vec (keep #(when (= :refused (:kind %)) (:refusal %)) steps))
        outside (count (filter #(= :outside-bbox (:kind %)) steps))]
    {:ok? true
     :observations accepted
     :refusals refusals
     :counts {:fetched (count data)
              :accepted (count accepted)
              :refused (count refusals)
              :returned-outside-bbox outside
              :links-next (boolean paging-next)}}))

;; -- provenance -------------------------------------------------------------------

(defn provenance
  [{:keys [area-id bbox retrieved-at input-sha256 request-url]}]
  {:source source-id
   :source-url terms-url
   :request-url request-url
   :api-base mi/base-url
   :area-id area-id
   :bbox bbox
   :retrieved-at retrieved-at
   :input-sha256 input-sha256
   :licence licence
   :attribution attribution
   :client "com-mapillary-graph-api"
   :client-constraints {:max-bbox-degrees mi/max-bbox-degrees
                        :max-limit mi/max-limit}
   :auth "Authorization header, token from environment; never in a URL"
   :pixels-stored false
   :run-bound "one source, one area, one request; paging.next counted, not followed"})

(defn provenance-checks
  "Every key a caller should be able to verify from the stored document
  alone. The count check refuses a document whose own counts disagree
  with the observation vector it carries."
  [doc]
  (let [c (get doc "counts")
        n (count (get doc "observations"))
        fetched (get c "fetched")
        accepted (get c "accepted")
        refused (get c "refused")
        outside (get c "returned-outside-bbox")]
    (if (and (number? fetched) (number? accepted) (number? refused) (number? outside)
             (= fetched (+ accepted refused outside))
             (= accepted n))
      {:ok? true}
      {:ok? false :error :provenance/counts-disagree
       :detail (str "fetched=" fetched " accepted=" accepted " refused=" refused
                    " outside=" outside " observations=" n)})))
