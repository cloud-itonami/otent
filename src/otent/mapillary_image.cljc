(ns otent.mapillary-image
  "One Mapillary **image pixel sample**: one bounded image metadata
  request through the registered client `com-mapillary-graph-api`, then
  ONE pixel GET of that image's own `thumb_1024_url`, hashed and stored
  only behind an explicit write credential.

  Scope (`otent-vision-scope.edn`): the source is street-allow for
  Mapillary under its current terms. Mapillary metadata (PR #20) and the
  derived tasks over it (#22 #24 #25) never fetched a pixel; this ns is
  the Mapillary counterpart of the Panoramax pixel sample (PR #39) and
  the KartaView pixel sample (PR #41), and earns the exception the same
  way rather than assuming it:

  - **one image, one pixel request**: the pixel URL is the image's own
    provider-published `thumb_1024_url`, fetched once. Siblings, paging
    (`paging.next`) and other thumbnails are counted, never followed
    (:run-bounds).
  - **permission basis, stated on the record**: Mapillary contributor
    imagery is published CC-BY-SA under Mapillary's Terms of Service.
    The /images payload carries no per-image licence field, so the
    licence is named where it actually lives, and the record carries
    the basis rather than inventing a per-image flag.
  - **privacy**: Mapillary publishes no per-image blur-result flag, so
    `provider-blur-verified` is `false` with the limitation carried on
    the record — the same story the Panoramax source states. Faces and
    licence plates stay outside the observation space entirely; no
    person/vehicle tracking, re-identification or sensitive-site
    targeting is possible from this record.
  - **what survives**: provider image id, canonical evidence URL, capture
    time (`captured_at` ms since epoch), geometry (GeoJSON lon/lat,
    refused not repaired), heading, panorama flag, sequence id as
    published (or `:unknown`), licence, attribution, retrieval time,
    sha256 + byte-size of the exact bytes, and uncertainty.
  - **storage gated**: bytes go to object storage only behind
    `$CF_CATALOG_TOKEN`; without the credential the run reports
    `nothing written` and exits 2 rather than pretending
    (:unmeasured-is-not-empty).
  - **token**: `MAPILLARY_ACCESS_TOKEN` from the environment only, sent
    through the client's `authorization-header` — never a URL, never a
    log line, never an observation."
  (:require [clojure.string :as str]
            [com-mapillary-graph-api.core :as mi]))

;; ── source identity ──────────────────────────────────────────────────

(def source-id "mapillary-image")

(def terms-url "https://mapillary.com/legal/terms/")

(def attribution "© Mapillary contributors (CC-BY-SA as published by Mapillary)")

(def licence
  "As of retrieval, from Mapillary's published terms for contributor
  imagery. The /images field list carries no per-image licence field,
  and inventing one would be worse than naming where the licence lives."
  "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)")

(def permission-basis
  "Stated on every record that carries raw pixels."
  "Mapillary contributor imagery is published CC-BY-SA under Mapillary's
  Terms of Service; this image is public provider-processed street
  imagery inside the run's bbox and the attribution is carried on this
  record")

(def exif-keys-forbidden ["exif" "email"])

(defn evidence-url [id] (str "https://www.mapillary.com/app/?pKey=" id))

;; Fields requested from /images. `thumb_1024_url` is present BECAUSE
;; this run earns a pixel: one URL, fetched once. No higher-resolution
;; variant is ever requested.
(def image-fields ["id" "geometry" "captured_at" "compass_angle"
                   "is_pano" "sequence" "thumb_1024_url"])

;; ── bounds ───────────────────────────────────────────────────────────

(def max-span 0.01)

(defn check-bbox
  "`bbox` is [west south east north] in degrees. Refuses anything bigger
  than the client limit, non-numeric, or folded (west > east). The
  client enforces the same limit for its own request; this check exists
  so a refusal happens BEFORE any request is built."
  [bbox]
  (let [[w s e n] bbox]
    (cond
      (or (not (vector? bbox)) (not= 4 (count bbox))
          (some #(or (not (number? %)) (js/isNaN %)) bbox))
      {:ok? false :error :mapillary-image/bbox-invalid
       :detail (str "bbox must be 4 numbers [W S E N], got " (pr-str bbox))}
      :else
      (let [dw (- e w) dn (- n s)]
        (cond
          (or (<= dw 0) (<= dn 0))
          {:ok? false :error :mapillary-image/bbox-inverted
           :detail (str "west/south must be smaller: " (pr-str bbox))}
          (or (>= dw max-span) (>= dn max-span))
          {:ok? false :error :mapillary-image/bbox-too-large
           :detail (str "span " dw "x" dn " deg must stay strictly under the "
                        max-span " deg client limit; one area per run")}
          :else {:ok? true
                 :area-id (str "bbox-" w "-" s "-" e "-" n)})))))

(defn build-request
  "The exact request the registered client builds for this run's bbox.
  Returns `{:request {:url :query-params} :bbox bbox :area-id ...}`.
  No token in the result — it rides in the Authorization header at
  fetch time."
  [bbox]
  (let [{:keys [ok? error detail area-id]} (check-bbox bbox)]
    (if-not ok?
      {:ok? false :error error :detail detail}
      {:ok? true
       :area-id area-id
       :request (mi/images-request {:west (nth bbox 0)
                                    :south (nth bbox 1)
                                    :east (nth bbox 2)
                                    :north (nth bbox 3)}
                                   {:fields image-fields})
       :bbox bbox})))

;; ── geometry: GeoJSON lon/lat, refused not repaired ──────────────────

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

;; ── privacy boundary ─────────────────────────────────────────────────

(defn- clean-value? [v]
  (cond
    (string? v) (not (str/includes? v "@"))
    (map? v) (every? (fn [[k v2]] (and (not-any? #(str/includes? (str/lower-case (str k)) %)
                                                 exif-keys-forbidden)
                                       (clean-value? v2)))
                     v)
    (vector? v) (every? clean-value? v)
    :else true))

(defn redacted?
  "The /images payload is metadata curated by the field list, but the
  redaction check runs anyway: any record carrying an `@` (an email
  riding in on some string) or a key matching exif/email is a bug, and
  a bug that ships uploader identity is the one that must refuse."
  [record]
  (clean-value? record))

;; ── the one pixel request this run may make ──────────────────────────

(defn pixel-url-of
  "The image's own published 1024px thumbnail URL. Anything else (other
  thumbnails, sequence siblings, `paging.next`) is not requested."
  [feature]
  (get feature "thumb_1024_url"))

;; ── per-image pixel permission ───────────────────────────────────────

(defn image->pixel-permission
  "One /images feature → {:ok? true :feature feature} when the pixel
  fetch is permitted, or a refusal naming the reason. Gates, in order:
  id, geometry (refused not repaired), capture time, pixel URL. An
  image without a published thumbnail URL keeps its metadata
  observability but is refused the pixel fetch."
  [feature]
  (let [id (get feature "id")]
    (cond
      (str/blank? id)
      {:ok? false :error :mapillary-image/missing-asset-id
       :detail "image has no id"}

      (not (valid-geometry? feature))
      {:ok? false :error :mapillary-image/invalid-geometry
       :detail (str "image " id " geometry is not a plausible GeoJSON Point lon/lat: "
                    (pr-str (get feature "geometry")))}

      (not (number? (get feature "captured_at")))
      {:ok? false :error :mapillary-image/missing-capture-time
       :detail (str "image " id " has no numeric captured_at")}

      (str/blank? (pixel-url-of feature))
      {:ok? false :error :mapillary-image/no-published-pixel-url
       :detail (str "image " id " publishes no thumb_1024_url; metadata stays
observable, the bytes are not fetched")}

      :else {:ok? true :feature feature})))

(defn- as-id [v]
  (cond (string? v) v
        (and (map? v) (string? (get v "id"))) (get v "id")
        :else :unknown))

;; ── record ───────────────────────────────────────────────────────────

(defn image->record
  "One admissible feature + the exact pixel bytes' hash facts → one
  pixel-carrying record. Deterministic given the inputs: no wall-clock
  read here (the caller passes `retrieved-at`)."
  [feature pixel retrieved-at]
  (let [id (str (get feature "id"))
        coords (get-in feature ["geometry" "coordinates"])]
    {:observation/source source-id
     :observation/source-id id
     :observation/asset-id (str source-id ":" id)
     :observation/lat (nth coords 1)
     :observation/lon (nth coords 0)
     :observation/capture-time-ms (get feature "captured_at")
     :observation/ingested-at retrieved-at
     :observation/compass-angle-deg (if (number? (get feature "compass_angle"))
                                      (get feature "compass_angle")
                                      :unknown)
     :observation/is-panorama (boolean (get feature "is_pano"))
     :observation/sequence-id (as-id (get feature "sequence"))
     :observation/spatial-uncertainty :unknown
     :observation/evidence-url (evidence-url id)
     :observation/licence licence
     :observation/attribution attribution
     :observation/pixel (assoc pixel :requests-made 1
                               :permission-basis permission-basis)
     :observation/privacy {:provider-blur-verified false
                           :note "Mapillary publishes no per-image blur-result flag; faces and plates are outside the observation space"}}))

(defn check-record
  "The last gate before anything leaves the process: a record that
  cannot prove its pixel provenance, or that carries identity, refuses."
  [record]
  (let [pixel (:observation/pixel record)]
    (cond
      (not (seq (:observation/licence record)))
      {:ok? false :error :mapillary-image/missing-licence
       :detail "record carries no licence"}

      (str/blank? (get pixel :permission-basis))
      {:ok? false :error :mapillary-image/missing-permission-basis
       :detail "a pixel record must state its permission basis"}

      (str/blank? (get pixel :sha256))
      {:ok? false :error :mapillary-image/missing-pixel-hash
       :detail "a pixel record must carry the sha256 of the exact bytes"}

      (not (pos-int? (get pixel :byte-size)))
      {:ok? false :error :mapillary-image/missing-pixel-size
       :detail "a pixel record must carry the byte-size of the exact bytes"}

      (not (redacted? record))
      {:ok? false :error :mapillary-image/privacy-redaction
       :detail "record carries an `@` or an exif/email-shaped key"}

      :else {:ok? true})))
