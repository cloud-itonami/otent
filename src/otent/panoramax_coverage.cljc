(ns otent.panoramax-coverage
  "One Panoramax **collections coverage manifest**: which open street-imagery
  collections the federation publishes, bounded to one request.

  Scope (`otent-vision-scope.edn`): :coverage-manifest is an admitted entity
  type; the source is :authority-owned-open-street-imagery / open data,
  verified live 2026-09-02 with no credential and no bypass
  (`GET https://api.panoramax.xyz/api/collections?limit=...` -> 200).

  ## What this is, and is not

  A collection is a *manifest row*: which datasets exist, where they say they
  span, when they say they were captured, under which licence, by whom. It is
  metadata the provider publishes about its own catalog -- no image is
  requested, no pixel URL is requested, and nothing here says a road is
  covered or current (:coverage-is-bounded-not-global-by-default;
  :map-feature-is-not-current-without-observation-time). The spatial extent
  is the provider's own declared bbox, kept as published; a bbox that does
  not hold together (west > east, south > north) is refused, not repaired.

  ## One request, not a crawl

  The endpoint pages. A `rel=next` link is counted and recorded in the
  provenance, never followed -- the run bound is the provenance, exactly as
  the `/images` metadata plane does it.

  ## Privacy

  Collections carry producer attribution (names + roles), which licence terms
  require preserving, and nothing else about people: no uploader contact
  fields exist at this plane, and a redaction check refuses the whole run if
  an `@` or an exif/email-shaped key reaches an emitted observation anyway.
  No face or plate can appear here: no image was fetched.")

(def redaction-forbidden-keys
  "Keys that must never reach an observation (uploader identity planes)."
  #{:exif :email :description-email :contact})

(defn redaction-hit
  "The first privacy problem found in an observation-shaped map, or nil.
  Checks forbidden keys and any string value carrying an `@`."
  [m]
  (or (some (fn [[k _]] (when (contains? redaction-forbidden-keys
                                        (keyword (name k))) k)) m)
      (some (fn [[k v]]
              (when (and (string? v) (.includes v "@")) k)) m)))

(defn- bbox-ok?
  "The published bbox must hold together as [west south east north]."
  [b]
  (and (vector? b) (= 4 (count b))
       (every? number? b)
       (<= (nth b 0) (nth b 2))
       (<= (nth b 1) (nth b 3))))

(defn- providers-of [c]
  (vec (for [p (or (:providers c) [])
             :when (map? p)
             :let [n (:name p)]]
         {:provider-name (or n :unknown)
          :provider-roles (vec (or (:roles p) []))})))

(defn- items-count-of [c]
  (or (get-in c [:stats:items :count]) :unknown))

(defn normalize-collection
  "One STAC Collection -> imagery coverage-manifest row, or a refusal map.
  Only curated fields are copied; the raw collection is never emitted."
  [c]
  (let [id (:id c)]
    (cond
      (not (string? id))
      {:refusal :missing-id}

      :else
      (let [bbox (get-in c [:extent :spatial :bbox 0])
            interval (get-in c [:extent :temporal :interval 0])
            row {:collection-id id
                 :title (or (:title c) :unknown)
                 :licence (or (:license c) :unknown)
                 :providers (providers-of c)
                 :spatial-bbox (if (bbox-ok? bbox) bbox :invalid)
                 :capture-start (or (first interval) :unknown)
                 :capture-end (or (second interval) :unknown)
                 :items-count (items-count-of c)
                 :created (:created c)
                 :updated (:updated c)
                 :accuracy-type (or (:quality:horizontal_accuracy_type c)
                                    :unknown)}]
        (cond
          (not (bbox-ok? bbox))
          (assoc (dissoc row :spatial-bbox) :refusal :invalid-bbox)

          :else row)))))

(defn- redaction-refusal [row]
  (when-let [k (redaction-hit row)] {:refusal :privacy-redaction :field k}))

(defn analyze
  "One collections-listing payload -> bounded manifest, counts, provenance.

  Pure. The payload is whatever one request returned; a `rel=next` link is
  counted (`:paging-next`) and never followed. Deterministic: rows sorted by
  collection-id."
  [payload {:keys [retrieved-at request-limit]}]
  (let [cols (or (:collections payload) [])
        paging-next (count (filter #(= "next" (:rel %))
                                   (or (:links payload) [])))
        norm (map normalize-collection cols)
        {refused true kept false} (group-by #(contains? % :refusal) norm)
        checked (map (fn [r] (if-let [pr (redaction-refusal r)]
                               (assoc pr :collection-id (:collection-id r))
                               r))
                     kept)
        {privacy-refused true rows false} (group-by #(contains? % :refusal)
                                                    checked)
        rows (sort-by :collection-id rows)
        counts {:fetched (count cols)
                :accepted (count rows)
                :refused (count refused)
                :privacy-refused (count privacy-refused)
                :paging-next paging-next}]
    {:observations rows
     :refusals (vec (concat refused privacy-refused))
     :counts counts
     :provenance
     {:source-id "panoramax-collections"
      :source-url "https://api.panoramax.xyz/api/collections"
      :asset-id "api.panoramax.xyz/api/collections"
      :capture-time :unknown
      :ingested-at retrieved-at
      :footprint :catalog-manifest-no-area
      :crs :as-published-provider-declared
      :resolution-or-gsd :unknown
      :sensor :street-imagery-federation
      :spectral-bands :none-metadata-only
      :licence :per-row-per-collection
      :attribution :providers-per-row
      :content-hash :sha256-of-exact-request-bytes
      :request-limit request-limit
      :requests-made 1
      :pagination :counted-not-followed
      :pixel-url-requested false
      :raw-pixels-stored false
      :provider-blur-verified false
      :blur-note "no image requested; collection metadata carries no blur flag"
      :epistemic-boundaries
      [:coverage-declared-not-verified
       :capture-window-is-provider-stated
       :extent-is-collection-declared-not-observed]}}))
