(ns basemap
  "Put the Earth's surface in R2, so the globe has one to draw.

    nbb --classpath src bin/basemap.cljs raster --max-zoom 4
    nbb --classpath src bin/basemap.cljs raster --source modis-terra-truecolor --date 2026-08-28
    nbb --classpath src bin/basemap.cljs vector
    nbb --classpath src bin/basemap.cljs manifest

  ## Why this exists at all

  The design this follows draws its globe with Google Photorealistic 3D
  Tiles: metered, key-gated, and fetched by the browser from Google. That
  is a fine choice for that project and the wrong one here, because the
  standing rule for this workspace is that the app reads what *we* have
  stored. A basemap fetched live from a third party is not that, however
  pretty it is -- it is an availability dependency, a rate limit and a
  billing surface sitting under every frame.

  So both layers are ingested once, keyless and public domain, and served
  from our own bucket:

  | layer | source | licence |
  |---|---|---|
  | raster | NASA GIBS `BlueMarble_ShadedRelief_Bathymetry` | NASA, public domain |
  | imagery | NASA GIBS `MODIS_Terra_CorrectedReflectance_TrueColor`, one capture date per run | NASA, public domain |
  | vector | Natural Earth 110m coastline + land borders | public domain (CC0) |

  Source definitions, tile maths, refusal rules and the shapes of the
  provenance records and manifest live in `otent.basemap`, where they are
  deterministic and under test; this file is only the network I/O."

  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [otent.basemap :as bm]))

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- token []
  (or (some-> (aget js/process.env "CF_CATALOG_TOKEN") str/trim not-empty)
      (do (log "CF_CATALOG_TOKEN is not set -- this run cannot write to R2, "
               "which is not the same as having written nothing")
          (js/process.exit 2))))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn r2-put!
  "PUT one object. Returns `{:ok? true :etag ...}` or a refusal.

  The Cloudflare REST object API rather than the S3 one: it takes the same
  Bearer token everything else here uses, so there is no second credential
  to store and no signing to get wrong."
  [key body content-type]
  (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" bm/account
                     "/r2/buckets/" bm/bucket "/objects/" key)
                #js {:method "PUT"
                     :headers #js {"Authorization" (str "Bearer " (token))
                                   "Content-Type" content-type}
                     :body body})
      (.then (fn [r]
               (if (.-ok r)
                 {:ok? true :key key}
                 (.then (.text r)
                        (fn [t] {:ok? false :error :r2/put-failed
                                 :detail (str (.-status r) " " (subs t 0 200))})))))
      (.catch (fn [e] {:ok? false :error :r2/unreachable :detail (str (.-message e))}))))

(defn- fetch-bytes [url]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
      (.then (fn [r]
               (if-not (.-ok r)
                 {:ok? false :error :source/http-error
                  :detail (str (.-status r) " from " url)}
                 (.then (.arrayBuffer r)
                        (fn [ab] {:ok? true :buf (js/Buffer.from ab)})))))
      (.catch (fn [e] {:ok? false :error :source/unreachable :detail (str (.-message e))}))))

;; ---------------------------------------------------------------- raster

(def conc
  "Eight at a time. Sequential is 341 round trips to two different hosts
  and takes longer than the two-minute patience of whoever ran it;
  unbounded is 341 concurrent sockets against a public NASA service,
  which is rude and gets throttled. Eight is the compromise, and it is
  the only tunable here on purpose."
  8)

(defn ingest-raster!
  "Fetch and store every tile of ONE source's plan. `plan` comes from
  `otent.basemap/ingest-plan`, which refuses before this is called; this
  checks again anyway, because the plan and the run must not be able to
  disagree."
  [plan]
  (if-not (:ok? plan)
    (do (log "REFUSING:" (name (:refusal plan)) "--" (:detail plan))
        (js/Promise.resolve {:refused true}))
    (let [{:keys [source date tiles]} plan
          retrieved-at (str (js/Date. (js/Date.now)))
          queue (atom (vec tiles))
          done (atom 0)
          failed (atom [])
          total (count tiles)]
      (log "raster:" (:id source) (count tiles) "tiles"
           (when date (str "capture date " date)))
      (letfn [(next-tile! []
                (let [[t] (swap-vals! queue #(if (seq %) (subvec % 1) %))]
                  (first t)))
              (worker []
                (if-let [{:keys [tile url key]} (next-tile!)]
                  (-> (fetch-bytes url)
                      (.then (fn [r]
                               (if-not (:ok? r)
                                 (do (swap! failed conj (assoc r :tile tile)) nil)
                                 (-> (r2-put! key (:buf r) "image/jpeg")
                                     (.then (fn [p]
                                              (when-not (:ok? p)
                                                (swap! failed conj (assoc p :tile tile))))))))))
                      (.then (fn [_]
                               (let [n (swap! done inc)]
                                 (when (zero? (mod n 50))
                                   (log "  " n "/" total (str "(" (count @failed) " failed)"))))
                               (worker)))
                  (js/Promise.resolve nil)))]
        (-> (js/Promise.all (clj->js (repeatedly conc worker)))
            (.then (fn [_] {:done @done :failed @failed
                            :source source :date date
                            :retrieved-at retrieved-at})))))))

;; ---------------------------------------------------------------- vector

(defn- ring-simplify
  "Drop points closer than `eps` degrees to the previous kept point.

  The endpoints are always kept: a coastline whose last point is dropped
  stops being closed, and a closed ring that is not closed renders as a
  crack rather than as an error."
  [coords eps]
  (if (< (count coords) 3)
    coords
    (let [last-pt (last coords)]
      (conj (reduce (fn [acc [x y :as p]]
                      (let [[px py] (peek acc)]
                        (if (and px (< (+ (Math/abs (- x px)) (Math/abs (- y py))) eps))
                          acc
                          (conj acc p))))
                    [(first coords)]
                    (rest (butlast coords)))
            last-pt))))

(defn geojson->lines
  "GeoJSON LineString/MultiLineString features -> a flat vector of
  coordinate rings, simplified.

  Anything else -- a Point, a Polygon, a GeometryCollection -- is counted
  and reported, not skipped in silence: the whole reason to look at the
  count is to notice the day the source changes shape."
  [parsed eps]
  (reduce
   (fn [acc f]
     (let [g (get f "geometry")
           t (get g "type")
           cs (get g "coordinates")]
       (case t
         "LineString" (update acc :lines conj (ring-simplify cs eps))
         "MultiLineString" (update acc :lines into (map #(ring-simplify % eps) cs))
         (update acc :skipped conj t))))
   {:lines [] :skipped []}
   (get parsed "features")))

(defn ingest-vector! []
  (letfn [(one [src]
            (-> (fetch-bytes (:url src))
                (.then (fn [r]
                         (if-not (:ok? r)
                           (assoc r :id (:id src))
                           (let [parsed (js->clj (js/JSON.parse (.toString (:buf r) "utf8")))
                                 {:keys [lines skipped]} (geojson->lines parsed 0.15)
                                 points (reduce + 0 (map count lines))
                                 ;; A flat [lon lat lon lat ...] payload per
                                 ;; line. The browser turns this into vertex
                                 ;; data; a GeoJSON object tree would be
                                 ;; parsed into millions of little arrays and
                                 ;; then immediately flattened again.
                                 payload {:id (:id src)
                                          :label (:label src)
                                          :licence (:licence src)
                                          :source-url (:url src)
                                          :source-sha256 (sha256 (:buf r))
                                          :ingested-at (js/Date.now)
                                          :line-count (count lines)
                                          :point-count points
                                          :skipped-geometry-types (frequencies skipped)
                                          :lines (mapv #(vec (flatten %)) lines)}
                                 body (js/JSON.stringify (clj->js payload))]
                             (log "  " (:id src) ":" (count lines) "lines,"
                                  points "points"
                                  (when (seq skipped)
                                    (str " SKIPPED " (frequencies skipped))))
                             (-> (r2-put! (str bm/prefix "/vector/" (:id src) ".json")
                                          body "application/json")
                                 (.then #(assoc % :id (:id src)
                                                :lines (count lines)
                                                :points points)))))))))]
    (js/Promise.all (clj->js (map one bm/vector-sources)))))

;; ---------------------------------------------------------------- manifest

(defn r2-exists?
  "Is this object in the bucket? Promise of true/false.

  A one-byte ranged GET, not HEAD: Cloudflare's REST object API answers
  HEAD with a non-2xx, so a HEAD probe reports every object as absent --
  and `measure-max-zoom` then concluded there was no basemap at all, in
  a bucket holding 1,365 tiles. Measured 2026-08-26."
  [key]
  (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" bm/account
                     "/r2/buckets/" bm/bucket "/objects/" key)
                #js {:headers #js {"Authorization" (str "Bearer " (token))
                                   "Range" "bytes=0-0"}})
      (.then (fn [r] (.-ok r)))
      (.catch (constantly false))))

(defn measure-max-zoom
  "The deepest zoom actually PRESENT in the bucket, for one source.

  Measured, not taken from a flag. The manifest is what the renderer
  clamps to, and the first version of it recorded whatever `--max-zoom`
  the *vector* command happened to default to -- 4 -- while the raster
  command had just written 1,365 tiles up to z5. The app then clamped one
  level short of the data it had, forever, and nothing was wrong enough to
  notice.

  Probing tile (z,0,0) is enough because the ingest writes whole levels or
  fails loudly; a partially written level shows up as holes, not as a
  wrong ceiling."
  ([source] (measure-max-zoom source nil))
  ([source date]
   (letfn [(step [z best]
             (if (> z (:max-source-zoom source))
               (js/Promise.resolve best)
               (-> (r2-exists? (bm/tile-key source [z 0 0] date))
                   (.then (fn [there?]
                            (if there? (step (inc z) z) (js/Promise.resolve best)))))))]
     (step 0 -1))))

(defn write-manifest! [raster-max-z imagery-results vector-results]
  (let [bm-source (bm/source-for "blue-marble")
        imagery (mapv (fn [{:keys [source date max-z tile-count retrieved-at]}]
                        (bm/manifest-imagery-entry source max-z date tile-count retrieved-at))
                      imagery-results)
        m {:version 2
           :written-at (js/Date.now)
           ;; :raster keeps its v1 shape -- the Worker reads it today, and
           ;; a new source must not cost the old reader its basemap.
           :raster (assoc (dissoc bm-source :url-template)
                          :prefix (str bm/prefix "/" (:id bm-source))
                          :max-zoom raster-max-z
                          :tile-count (count (bm/tiles-to-zoom raster-max-z))
                          :scheme "xyz"
                          :tile-size 256)
           :imagery imagery
           :vector (mapv (fn [v] {:id (:id v) :lines (:lines v) :points (:points v)
                                  :key (str bm/prefix "/vector/" (:id v) ".json")})
                         vector-results)}]
    (-> (r2-put! (str bm/prefix "/manifest.json")
                 (js/JSON.stringify (clj->js m)) "application/json")
        (.then (fn [r] (assoc r :manifest m))))))

(defn- run-manifest!
  "Measure what each imagery source has actually written in the bucket,
  then write the manifest. A daily source is measured with no date, so
  its probe keys cannot match a dated raster run's prefix -- its entry is
  written only by a run that ingested it, which is what 'states exactly
  what exists' means here."
  []
  (-> (measure-max-zoom (bm/source-for "blue-marble"))
      (.then (fn [bm-z]
               (if (neg? bm-z)
                 (do (log "no raster tiles at all -- refusing to write a manifest "
                          "that claims a basemap exists")
                     (js/process.exit 1))
                 (-> (js/Promise.all
                      (clj->js
                       (map (fn [s]
                              (let [date (when (= :daily (:time-mode s)) nil)]
                                (.then (measure-max-zoom s date)
                                       (fn [z] {:source s :date date :max-z z
                                                :retrieved-at nil}))))
                            bm/raster-sources)))
                     (.then (fn [imagery-results]
                              (let [imagery
                                    (->> (js->clj imagery-results :keywordize-keys true)
                                         (remove #(neg? (:max-z %)))
                                         (map (fn [r]
                                                (assoc r :tile-count
                                                       (count (bm/tiles-to-zoom (:max-z r))))))
                                         vec)
                                    retrieved-at (str (js/Date. (js/Date.now)))]
                                (.then (write-manifest!
                                        bm-z
                                        (map #(assoc % :retrieved-at retrieved-at) imagery)
                                        (map #(hash-map :id (:id %) :lines nil :points nil)
                                             bm/vector-sources))
                                       (fn [m]
                                         (println (js/JSON.stringify (clj->js (:manifest m)) nil 2))
                                         (js/process.exit (if (:ok? m) 0 1)))))))))))))

;; ---------------------------------------------------------------- main

(defn user-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [i a] (when (str/ends-with? a "basemap.cljs") i)) argv))]
    (if i (drop (inc i) argv) (drop 2 argv))))

(defn- yesterday-utc
  "The default capture date for a daily layer: UTC yesterday, since GIBS
  files a day under the date it was taken and 'today' is frequently
  partial."
  []
  (let [d (js/Date. (- (js/Date.now) 86400000))
        pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
    (str (.getUTCFullYear d) "-"
         (pad (inc (.getUTCMonth d))) "-"
         (pad (.getUTCDate d)))))

(let [[cmd & more] (user-args)
      opts (apply hash-map (map-indexed (fn [i a] (if (even? i) (keyword (str/replace a "--" "")) a))
                                        more))
      max-z (js/parseInt (get opts :max-zoom "4") 10)
      source-id (get opts :source "blue-marble")
      date (get opts :date)]
  (case cmd
    "raster"
    (let [daily? (when-let [s (and (not= :licence/unknown-source (:refusal (bm/source-for source-id)))
                                   (bm/source-for source-id))]
                   (= :daily (:time-mode s)))
          date (cond
                 date date
                 ;; A daily source run without --date gets UTC yesterday
                 ;; from the wall clock; a static one gets nil.
                 daily? (yesterday-utc)
                 :else nil)]
      (-> (bm/ingest-plan source-id max-z date)
          (ingest-raster!)
          (.then (fn [{:keys [done failed refused source]}]
                   (if refused
                     (js/process.exit 1)
                     (do (log "raster: wrote" (- done (count failed)) "of" done "tiles")
                         (doseq [f (take 5 failed)] (log "  FAILED" (pr-str (:tile f)) (:detail f)))
                         (js/process.exit (if (seq failed) 1 0))))))))

    "vector"
    (-> (ingest-vector!)
        (.then (fn [rs]
                 (let [rs (js->clj rs :keywordize-keys true)]
                   (doseq [r rs] (log (if (:ok? r) "  ok " "  FAILED ") (:id r) (:detail r)))
                   (-> (measure-max-zoom (bm/source-for "blue-marble"))
                       (.then #(write-manifest! % [] rs))
                       (.then (fn [m]
                                (log "manifest:" (if (:ok? m) "written" (:detail m)))
                                (js/process.exit (if (and (:ok? m) (every? :ok? rs)) 0 1)))))))))

    "manifest"
    ;; The zoom is MEASURED here, not passed in, for every imagery source.
    (run-manifest!)

    (do (println "usage: basemap.cljs <raster|vector|manifest> [--source ID] [--date YYYY-MM-DD] [--max-zoom N]")
        (js/process.exit 2))))
