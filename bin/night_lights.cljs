(ns night-lights
  "Put Earth at night in R2, one composite at a time.

    nbb --classpath src bin/night_lights.cljs ingest --composite 2016-01-01 --max-zoom 4
    nbb --classpath src bin/night_lights.cljs manifest --composite 2016-01-01

  The I/O half of `otent.night-lights`, which holds every rule and every
  refusal; this file only moves bytes. Objects go to
  `otent/night-lights/<composite>/<z>/<x>/<y>.png` and a manifest per
  composite states exactly what exists.

  NASA GIBS `VIIRS_Black_Marble`, public domain, keyless. z9 answers 400,
  so unlike the daily true colour layers there is no upsampled tail to
  accidentally ingest -- but the ingest bound (z4, 341 tiles) is tighter
  than the source anyway, because 341 is what one bounded run takes."
  (:require ["crypto" :as crypto]
            [clojure.string :as str]
            [otent.night-lights :as nl]
            [otent.r2 :as r2]))

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- fetch-bytes [url]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-night-lights/0.1 (cloud-itonami)"}})
      (.then (fn [r]
               (if-not (.-ok r)
                 {:ok? false :error :source/http-error
                  :detail (str (.-status r) " from " url)}
                 (.then (.arrayBuffer r)
                        (fn [ab] {:ok? true :buf (js/Buffer.from ab)}))))))
      (.catch (fn [e] {:ok? false :error :source/unreachable :detail (str (.-message e))})))

(defn- png-magic? [buf]
  ;; 89 50 4E 47 0D 0A 1A 0A -- an error page that says image/png is not
  ;; a tile, and the manifest must not record it as one.
  (and (>= (.-length buf) 8)
       (= 0x89 (aget buf 0)) (= 0x50 (aget buf 1)) (= 0x4E (aget buf 2)) (= 0x47 (aget buf 3))))

(defn- ingest-tile! [[z x y :as tile] composite]
  (let [url (nl/tile-url tile composite)]
    (-> (fetch-bytes url)
        (.then (fn [r]
                 (cond
                   (not (:ok? r))
                   {:ok? false :tile tile :detail (:detail r)}

                   (not (png-magic? (:buf r)))
                   {:ok? false :tile tile :error :source/not-a-png :detail url}

                   :else
                   (-> (r2/put! (nl/object-key tile composite) (:buf r) "image/png")
                       (.then (fn [p]
                                (if (:ok? p)
                                  {:ok? true
                                   :provenance (nl/provenance
                                                {:tile tile :composite composite :url url
                                                 :buf (:buf r) :sha256-hex (sha256 (:buf r))
                                                 :retrieved-at (js/Date.now)})}
                                  {:ok? false :tile tile :detail (:detail p)}))))))))))

(defn- pool!
  "Eight at a time, same compromise as the basemap raster: sequential is
  341 round trips, unbounded is 341 concurrent sockets against a public
  NASA service."
  [tiles f]
  (let [conc 8
        queue (atom (vec tiles))
        results (atom [])
        next! (fn [] (let [[v] (swap-vals! queue #(if (seq %) (subvec % 1) %))]
                       (first v)))
        worker (fn worker []
                 (if-let [t (next!)]
                   (-> (f t)
                       (.then (fn [r] (swap! results conj r) (worker))))
                   (js/Promise.resolve nil)))]
    (-> (js/Promise.all (clj->js (repeatedly conc worker)))
        (.then (fn [_] (js->clj @results :keywordize-keys true))))))

(defn- measure-max-zoom
  "The deepest zoom actually PRESENT in the bucket for `composite`.
  A one-byte ranged GET per probe via `otent.r2/head` -- see the note
  there on why it is not a HEAD. Probing (z,0,0) is enough because the
  ingest writes whole levels or fails loudly."
  [composite]
  (letfn [(step [z best]
            (if (> z (:max-source-zoom nl/source))
              (js/Promise.resolve best)
              (-> (r2/head (nl/object-key [z 0 0] composite))
                  (.then (fn [r]
                           (if (and (:ok? r) (:present? r))
                             (step (inc z) z)
                             (js/Promise.resolve best)))))))]
    (step 0 -1)))

(defn- write-manifest! [composite]
  (-> (measure-max-zoom composite)
      (.then (fn [measured]
               (if (neg? measured)
                 {:ok? false :error :manifest/nothing-present
                  :detail (str "no tiles present for composite " composite
                               " -- refusing to write a manifest that claims night lights exist")}
                 (let [m (nl/manifest {:composite composite
                                       :written-at (js/Date.now)
                                       :measured-max-zoom measured
                                       :entries []})]
                   ;; The entries key is rebuilt by the ingest command; the
                   ;; standalone manifest command records the coverage it
                   ;; measured, and `tile-count 0` with measured-zoom >= 0 is
                   ;; a hole in coverage, not a claim.
                   (-> (r2/put! (str "otent/night-lights/" composite "/manifest.json")
                                (js/JSON.stringify (clj->js m)) "application/json")
                       (.then (fn [r] (assoc r :manifest m))))))))))

(defn user-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [i a] (when (str/ends-with? a "night_lights.cljs") i)) argv))]
    (if i (drop (inc i) argv) (drop 2 argv))))

(defn- opts [args]
  (apply hash-map (map-indexed (fn [i a] (if (even? i) (keyword (str/replace a "--" "")) a)) args)))

(defn- cmd-ingest! [composite max-zoom]
  (let [p (nl/plan {:composite composite :max-zoom max-zoom})]
    (if-not (:ok? p)
      (do (log "REFUSING:" (:refusal p) "--" (:detail p))
          (js/process.exit 1))
      (-> (pool! (:tiles p) #(ingest-tile! % composite))
          (.then (fn [rs]
                   (let [ok (filterv :ok? rs)
                         failed (remove :ok? rs)]
                     (log "ingest:" (count ok) "of" (count rs) "tiles, composite" composite)
                     (doseq [f (take 5 failed)] (log "  FAILED" (pr-str (:tile f)) (:detail f)))
                     (if (seq failed)
                       (js/process.exit 1)
                       (-> (measure-max-zoom composite)
                           (.then (fn [measured]
                                    (let [m (nl/manifest {:composite composite
                                                          :written-at (js/Date.now)
                                                          :measured-max-zoom measured
                                                          :entries (mapv :provenance ok)})]
                                      (-> (r2/put! (str "otent/night-lights/" composite "/manifest.json")
                                                   (js/JSON.stringify (clj->js m)) "application/json")
                                          (.then (fn [r]
                                                   (log "manifest:" (if (:ok? r) "written" (:detail r))
                                                        "measured max zoom:" measured)
                                                   (js/process.exit (if (:ok? r) 0 1)))))))))))))))))

(defn- cmd-manifest! [composite]
  (-> (write-manifest! composite)
      (.then (fn [r]
               (if (:ok? r)
                 (do (println (js/JSON.stringify (clj->js (:manifest r)) nil 2))
                     (js/process.exit 0))
                 (do (log "REFUSING:" (:error r) "--" (:detail r))
                     (js/process.exit 1)))))))

(let [[cmd & more] (user-args)
      {:keys [composite max-zoom]} (opts more)
      max-zoom (js/parseInt (or max-zoom "4") 10)]
  (case cmd
    "ingest" (cmd-ingest! composite max-zoom)
    "manifest" (cmd-manifest! composite)
    (do (println "usage: night_lights.cljs <ingest|manifest> --composite YYYY-MM-DD [--max-zoom N]")
        (js/process.exit 2))))
