(ns buildings
  "Building footprints into R2, so the globe has something to extrude.

    nbb --classpath src bin/buildings.cljs areas
    nbb --classpath src bin/buildings.cljs ingest --area tokyo
    nbb --classpath src bin/buildings.cljs ingest --area tokyo,manhattan --radius 3
    nbb --classpath src bin/buildings.cljs manifest

  ## Why the decode happens HERE and not in the browser

  An OpenFreeMap z14 tile is about 730 KB of protobuf carrying sixteen
  layers, of which this wants one. Decoding it costs ~0.8 s in nbb, and
  every viewer would pay some fraction of that for every tile, forever, to
  arrive at the same answer.

  So the MVT is decoded once, here, by `kotoba.map.mvt`, and what lands in
  R2 is the building layer alone as flat coordinate arrays -- the same
  shape `basemap.cljs` uses for coastlines. Measured on central Tokyo:
  **730 KB of tile becomes about 30 KB of buildings**.

  This is also what the workspace rule asks for. The browser reads what we
  stored; it does not fetch a third party's tile and mine it at render
  time.

  ## Coverage is bounded, and the manifest says where

  OpenFreeMap serves buildings at z14 only, and the planet is 268 million
  z14 tiles. This ingests a **named list of metro areas** and writes the
  covered tile ranges into the manifest, so the renderer asks only where
  something exists and a reader can see exactly where that is.

  A globe with buildings in four cities and nowhere else is an honest
  picture of what was ingested. A globe that requests buildings everywhere
  and 404s is not -- it is the same picture plus a request storm.

  ## Licence

  OpenFreeMap serves OpenStreetMap data. **ODbL 1.0**, attribution
  required: (c) OpenStreetMap contributors. The app's Sources page carries
  it, and `:licence` travels in every object written here."
  (:require ["fs" :as fs]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [kotoba.map.mvt :as mvt]))

(def ACCOUNT "4da88288dc30d9ee257f319d3c33ecf0")
(def BUCKET "cloud-itonami-datalake")
(def PREFIX "otent/basemap/buildings")

(def source
  {:id "openfreemap-buildings"
   :label "OpenStreetMap buildings via OpenFreeMap"
   :licence "ODbL 1.0"
   :attribution "© OpenStreetMap contributors"
   :terms "https://openfreemap.org/"
   :tilejson "https://tiles.openfreemap.org/planet"
   :layer "building"
   ;; Extracted from the SAME tile fetch, at no extra request. Without
   ;; them a city at 3 km up sits on black: the raster basemap tops out at
   ;; z5, which is 5 km per pixel there, so the ground is one magnified
   ;; dark pixel and the buildings look like they are floating.
   :surface-layers ["water" "landcover" "park"]
   ;; The only zoom the building layer is served at. Asking for z15 returns
   ;; the z14 tile upsampled, which looks like more detail and is not.
   :zoom 14})

(def areas
  "The metro areas to cover. `radius` is in tiles either side of the
  centre, so 2 means a 5x5 block -- about 12 km across at z14."
  [{:id "tokyo" :label "Tokyo" :lat 35.6812 :lon 139.7671 :radius 2}
   {:id "manhattan" :label "New York (Manhattan)" :lat 40.7580 :lon -73.9855 :radius 2}
   {:id "london" :label "London" :lat 51.5074 :lon -0.1278 :radius 2}
   {:id "singapore" :label "Singapore" :lat 1.2897 :lon 103.8501 :radius 2}])

(def by-id (into {} (map (juxt :id identity)) areas))

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- token []
  (or (some-> (aget js/process.env "CF_CATALOG_TOKEN") str/trim not-empty)
      (do (log "CF_CATALOG_TOKEN is not set -- this run cannot write to R2, "
               "which is not the same as having written nothing")
          (js/process.exit 2))))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn lon->tile-x [lon z]
  (Math/floor (* (/ (+ lon 180.0) 360.0) (Math/pow 2 z))))

(defn lat->tile-y [lat z]
  (let [r (* lat (/ Math/PI 180.0))]
    (Math/floor (* (/ (- 1.0 (/ (Math/asinh (Math/tan r)) Math/PI)) 2.0)
                   (Math/pow 2 z)))))

(defn area-tiles
  "Every tile of an area's block, as `{:z :x :y}`."
  [{:keys [lat lon radius]}]
  (let [z (:zoom source)
        cx (lon->tile-x lon z)
        cy (lat->tile-y lat z)
        n (Math/pow 2 z)]
    (for [dx (range (- radius) (inc radius))
          dy (range (- radius) (inc radius))
          :let [x (+ cx dx) y (+ cy dy)]
          ;; y is clamped rather than wrapped: past the poles there is no
          ;; tile, whereas x genuinely wraps at the antimeridian.
          :when (and (<= 0 y (dec n)))]
      {:z z :x (mod x n) :y y})))

;; ---------------------------------------------------------------- io

(defn r2-put! [key body content-type]
  (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" ACCOUNT
                     "/r2/buckets/" BUCKET "/objects/" key)
                #js {:method "PUT"
                     :headers #js {"Authorization" (str "Bearer " (token))
                                   "Content-Type" content-type}
                     :body body})
      (.then (fn [r] (if (.-ok r)
                       {:ok? true :key key :bytes (count body)}
                       (.then (.text r) (fn [t] {:ok? false :error :r2/put-failed
                                                 :detail (str (.-status r) " " (subs t 0 200))})))))
      (.catch (fn [e] {:ok? false :error :r2/unreachable :detail (str (.-message e))}))))

(defn- tilejson-url []
  (-> (js/fetch (:tilejson source))
      (.then (fn [r] (if (.-ok r) (.json r) nil)))
      (.then (fn [j]
               (if j
                 {:ok? true :template (aget (aget j "tiles") 0)}
                 {:ok? false :error :source/no-tilejson
                  :detail (str "could not read " (:tilejson source))})))
      (.catch (fn [e] {:ok? false :error :source/unreachable :detail (.-message e)}))))

(defn- fetch-tile [template {:keys [z x y]}]
  (let [url (-> template
                (str/replace "{z}" (str z)) (str/replace "{x}" (str x))
                (str/replace "{y}" (str y)))]
    (-> (js/fetch url #js {:headers #js {"user-agent" "otent-buildings/0.1 (cloud-itonami)"}})
        (.then (fn [r]
                 (if-not (.-ok r)
                   {:ok? false :error :source/http-error :detail (str (.-status r) " " url)}
                   (.then (.arrayBuffer r)
                          (fn [ab] {:ok? true :buf (js/Buffer.from ab) :url url})))))
        (.catch (fn [e] {:ok? false :error :source/unreachable :detail (.-message e)})))))

;; ---------------------------------------------------------------- extract

(defn extract-surface
  "Water, landcover and parks from the same tile, as flat rings.

  Outer rings only; holes are dropped, as for buildings.

  **No size filter.** A z14 tile holds hundreds of small polygons -- 512
  `grass` rings in central Manhattan -- and each costs a triangulation in
  the browser. Filtering them here would be the right place if it were
  needed; it is not filtered because the cost has not been measured yet,
  and dropping data to fix a slowness nobody has seen is how a map ends up
  missing its parks. The count travels as `:surface-count` so the decision
  can be made from a number."
  [bs tile]
  (reduce
   (fn [acc layer]
     (let [{:keys [features]} (mvt/decode-layer-features bs tile layer)]
       (into acc
             (keep (fn [{:keys [geometry properties]}]
                     (when (= :polygon (:type geometry))
                       (let [outer (first (:coordinates geometry))]
                         (when (and outer (>= (count outer) 4))
                           {:l layer
                            :c (or (get properties "class") layer)
                            :r (vec (mapcat (fn [[lon lat]] [lon lat]) outer))})))))
             features)))
   []
   (:surface-layers source)))

(defn extract-buildings
  "MVT bytes -> the compact form that goes to R2.

  Each building is `{:h height-m :b base-m :r [lon lat lon lat ...]}` --
  the outer ring only, flat, so the browser turns it into vertex data
  without walking an object tree it would immediately flatten again.

  Rings with `hide_3d` are kept but marked, and rings with no
  `render_height` get the OSM default of 3 m per level with one level.
  Dropping them instead would leave holes in a city block that look like
  missing data rather than like missing metadata."
  [buf tile]
  (let [bs (vec (js/Uint8Array. buf))
        {:keys [features]} (mvt/decode-layer-features bs tile (:layer source))]
    (reduce
     (fn [acc {:keys [geometry properties]}]
       (if-not (= :polygon (:type geometry))
         (update acc :skipped conj (name (or (:type geometry) :nil)))
         ;; An MVT polygon's coordinates are a list of rings: the first is
         ;; the outer boundary, the rest are holes. Holes are dropped --
         ;; a courtyard rendered solid is a building slightly too big, and
         ;; a hole triangulated as a separate ring is a building-shaped
         ;; pit. At this scale the first is invisible and the second is not.
         (let [outer (first (:coordinates geometry))
               h (get properties "render_height")
               b (get properties "render_min_height" 0)
               hide (get properties "hide_3d")]
           (if (or (nil? outer) (< (count outer) 4))
             (update acc :skipped conj "degenerate-ring")
             (update acc :buildings conj
                     (cond-> {:h (double (or h 3.0))
                              :b (double (or b 0))
                              :r (vec (mapcat (fn [[lon lat]] [lon lat]) outer))}
                       (nil? h) (assoc :default-height true)
                       hide (assoc :hide-3d true)))))))
     {:buildings [] :skipped []}
     features)))

;; ---------------------------------------------------------------- ingest

(defn ingest-area! [template {:keys [id] :as area}]
  (let [tiles (vec (area-tiles area))]
    (log "  " id ":" (count tiles) "tiles at z" (:zoom source))
    (letfn [(step [remaining acc]
              (if (empty? remaining)
                (js/Promise.resolve acc)
                (let [[t & more] remaining]
                  (-> (fetch-tile template t)
                      (.then
                       (fn [r]
                         (if-not (:ok? r)
                           (js/Promise.resolve (update acc :failed conj (assoc r :tile t)))
                           (let [{:keys [buildings skipped]} (extract-buildings (:buf r) t)
                                 surface (extract-surface (vec (js/Uint8Array. (:buf r))) t)
                                 payload {:tile t
                                          :source (:id source)
                                          :licence (:licence source)
                                          :attribution (:attribution source)
                                          :source-url (:url r)
                                          :source-sha256 (sha256 (:buf r))
                                          :ingested-at (js/Date.now)
                                          :count (count buildings)
                                          :surface-count (count surface)
                                          :skipped (frequencies skipped)
                                          :buildings buildings
                                          :surface surface}
                                 body (js/JSON.stringify (clj->js payload))]
                             (-> (r2-put! (str PREFIX "/" (:z t) "/" (:x t) "/" (:y t) ".json")
                                          body "application/json")
                                 (.then (fn [p]
                                          (if (:ok? p)
                                            (-> acc
                                                (update :written inc)
                                                (update :buildings + (count buildings))
                                                (update :tile-bytes + (.-length (:buf r)))
                                                (update :out-bytes + (count body)))
                                            (update acc :failed conj (assoc p :tile t))))))))))
                      (.then #(step more %))))))]
      (step tiles {:written 0 :buildings 0 :tile-bytes 0 :out-bytes 0 :failed []}))))

(defn tile-ranges
  "The covered tile block for an area, as the renderer needs to ask it."
  [area]
  (let [ts (area-tiles area)]
    {:z (:zoom source)
     :x0 (apply min (map :x ts)) :x1 (apply max (map :x ts))
     :y0 (apply min (map :y ts)) :y1 (apply max (map :y ts))
     :tiles (count ts)}))

(defn write-manifest! [covered]
  (let [m {:version 1
           :written-at (js/Date.now)
           :source (dissoc source :tilejson)
           :zoom (:zoom source)
           :prefix PREFIX
           ;; Named ranges, not "everywhere". The renderer asks only where
           ;; something exists, so a globe with buildings in four cities
           ;; does not also produce a 404 storm over the other 99.99%.
           :areas (mapv (fn [{:keys [id label lat lon] :as a}]
                          (merge {:id id :label label :lat lat :lon lon}
                                 (tile-ranges a)
                                 (select-keys (get covered id) [:buildings :written])))
                        areas)}]
    (-> (r2-put! (str PREFIX "/manifest.json") (js/JSON.stringify (clj->js m)) "application/json")
        (.then #(assoc % :manifest m)))))

;; ---------------------------------------------------------------- main

(defn user-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [i a] (when (str/ends-with? a "buildings.cljs") i)) argv))]
    (if i (drop (inc i) argv) (drop 2 argv))))

(let [[cmd & more] (user-args)
      opts (apply hash-map (map-indexed (fn [i a] (if (even? i) (keyword (str/replace a "--" "")) a)) more))
      chosen (if-let [a (:area opts)]
               (keep by-id (str/split a #","))
               areas)
      chosen (if-let [r (:radius opts)]
               (mapv #(assoc % :radius (js/parseInt r 10)) chosen)
               chosen)]
  (case cmd
    "areas"
    (doseq [a areas]
      (println (str (:id a) "  " (:label a) "  " (:lat a) "," (:lon a)
                    "  " (:tiles (tile-ranges a)) " tiles")))

    "ingest"
    (if (empty? chosen)
      (do (println "no area matched --area" (:area opts)) (js/process.exit 2))
      (-> (tilejson-url)
          (.then
           (fn [tj]
             (if-not (:ok? tj)
               (do (log "REFUSING:" (:detail tj)) (js/process.exit 2))
               (do
                 (log "tile template:" (:template tj))
                 (letfn [(step [remaining acc]
                           (if (empty? remaining)
                             (js/Promise.resolve acc)
                             (let [[a & more] remaining]
                               (-> (ingest-area! (:template tj) a)
                                   (.then (fn [r]
                                            (log "  " (:id a) ":" (:written r) "tiles,"
                                                 (:buildings r) "buildings,"
                                                 (Math/round (/ (:tile-bytes r) 1024)) "KB of tile ->"
                                                 (Math/round (/ (:out-bytes r) 1024)) "KB stored"
                                                 (when (seq (:failed r))
                                                   (str "  " (count (:failed r)) " FAILED")))
                                            (step more (assoc acc (:id a) r)))))))) ]
                   (-> (step chosen {})
                       (.then (fn [acc]
                                (-> (write-manifest! acc)
                                    (.then (fn [m]
                                             (log "manifest:" (if (:ok? m) "written" (:detail m)))
                                             (let [bad (reduce + 0 (map (comp count :failed val) acc))]
                                               (println "buildings:"
                                                        (reduce + 0 (map (comp :buildings val) acc))
                                                        "across"
                                                        (reduce + 0 (map (comp :written val) acc))
                                                        "tiles;" bad "failed")
                                               ;; `(or bad ...)` where bad is 0
                                               ;; returns 0, and 0 is TRUTHY in
                                               ;; ClojureScript -- so a clean run
                                               ;; of 9 tiles and 944 buildings
                                               ;; exited 1. `pos?` is the check.
                                               (js/process.exit
                                                (if (or (pos? bad) (not (:ok? m))) 1 0))))))))))))))))

    "manifest"
    (-> (write-manifest! {})
        (.then (fn [m] (println (js/JSON.stringify (clj->js (:manifest m)) nil 2))
                 (js/process.exit (if (:ok? m) 0 1)))))

    (do (println "usage: buildings.cljs <areas|ingest|manifest> [--area a,b] [--radius N]")
        (js/process.exit 2))))
