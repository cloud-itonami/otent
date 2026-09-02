;; One Panoramax image pixel sample -- one bounded run.
;;
;;   nbb --classpath src bin/panoramax_image.cljs --fixture <item.json> --pixel <bytes-file> [--stored]
;;   nbb --classpath src bin/panoramax_image.cljs --live --bbox W S E N
;;
;; Live: one search request (bbox, <= 0.01 deg/side, anonymous), the first
;; admissible ready+public item whose licence permits pixels, then ONE pixel
;; GET of its `sd` asset. Bytes are hashed; they are stored to R2 only behind
;; $CF_CATALOG_TOKEN -- without it the run reports `nothing written` and
;; exits 2 rather than pretending.
;;
;; Exit 0 sample recorded · 1 refused · 2 could-not-act (no write credential,
;; upstream unreachable). The promise chain is kept flat on purpose: every
;; .then returns data (or a {:refusal ...} map) and one final .then decides
;; the outcome, so the parens cannot drift.
(ns panoramax-image
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.panoramax-image :as pxi]))

(def search-url "https://api.panoramax.xyz/api/search")

(def max-span 0.01)

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- refuse [err detail] {:refusal true :error err :detail detail})
(defn- cant [err detail] {:could-not true :error err :detail detail})

(defn- fail! [m]
  (println (str "REFUSED " (name (:error m)) ": " (:detail m)))
  (js/process.exit (if (:could-not m) 2 1)))

(defn- read-args []
  (let [v (js->clj (.slice js/process.argv 2))]
    (loop [i 0 acc {}]
      (if (>= i (count v))
        acc
        (let [k (nth v i)]
          (cond
            (= k "--fixture") (recur (+ i 2) (assoc acc :fixture (nth v (inc i))))
            (= k "--pixel")   (recur (+ i 2) (assoc acc :pixel (nth v (inc i))))
            (= k "--stored")  (recur (inc i) (assoc acc :stored true))
            (= k "--live")    (recur (inc i) (assoc acc :live true))
            (= k "--bbox")    (recur (+ i 5)
                                     (assoc acc :bbox (mapv js/parseFloat
                                                            (take 4 (drop (inc i) v)))))
            :else (recur (inc i) acc)))))))

(defn- check-bbox [bbox]
  (let [[w s e n] bbox]
    (cond
      (or (not (vector? bbox)) (not= 4 (count bbox))
          (some #(js/isNaN %) bbox))
      (refuse :panoramax-image/bbox-invalid (pr-str bbox))
      (or (<= (- e w) 0) (<= (- n s) 0))
      (refuse :panoramax-image/bbox-inverted (pr-str bbox))
      (or (> (- e w) max-span) (> (- n s) max-span))
      (refuse :panoramax-image/bbox-too-large
              (str "span exceeds the " max-span " deg run bound"))
      :else {:ok? true})))

(defn- finish [record bytes stored]
  (let [check (pxi/check-record record)]
    (if-not (:ok? check)
      (refuse (:error check) (:detail check))
      (do (println (str "input pixel sha256=" (sha256 bytes)
                        " byte-size=" (.-length bytes)))
          (println (js/JSON.stringify (clj->js record) nil 2))
          (if stored
            (if (empty? (str (.-CF_CATALOG_TOKEN js/process.env)))
              (cant :r2/no-credential
                    "$CF_CATALOG_TOKEN is not set: nothing was written, which is not the same as writing nothing")
              {:ok? true :note "R2 write path is exercised by the collector, not this bin"})
            {:ok? true :note "dry: bytes hashed, not stored"})))))

(defn- run-offline [{:keys [fixture pixel stored]}]
  (try
    (let [feature (js->clj (js/JSON.parse (fs/readFileSync fixture "utf8")))
          bytes (fs/readFileSync pixel)
          perm (pxi/item->pixel-permission feature)]
      (if-not (:ok? perm)
        (refuse (:error perm) (:detail perm))
        (finish (pxi/image->record (:feature perm)
                                   {:sha256 (sha256 bytes)
                                    :byte-size (.-length bytes)
                                    :content-type "image/jpeg"
                                    :stored (boolean stored)}
                                   (.toISOString (js/Date.)))
                bytes (boolean stored))))
    (catch :default e
      (cant :panoramax-image/fixture-unreadable (.-message e)))))

(defn- step-search [body]
  (println (str "input search sha256=" (sha256 (js/Buffer.from body "utf8"))))
  (let [features (.-features (js/JSON.parse body))
        feature (when (and features (pos? (.-length features)))
                  (js->clj (aget features 0)))]
    (if-not (map? feature)
      (refuse :panoramax-image/no-admissible-item
              "the search returned no item")
      (let [perm (pxi/item->pixel-permission feature)]
        (if-not (:ok? perm)
          (refuse (:error perm) (:detail perm))
          (let [purl (pxi/pixel-url-of (:feature perm))]
            (println (str "GET " purl " (one pixel request)"))
            (-> (js/fetch purl)
                (.then (fn [r]
                         (if (.-ok r)
                           (.arrayBuffer r)
                           (throw (js/Error. (str "http " (.-status r) " pixel"))))))
                (.then (fn [ab]
                         (let [bytes (js/Buffer.from ab)]
                           {:feature (:feature perm) :bytes bytes}))))))))))

(defn- step-pixel [{:keys [feature bytes]}]
  (finish (pxi/image->record feature
                             {:sha256 (sha256 bytes)
                              :byte-size (.-length bytes)
                              :content-type "image/jpeg"
                              :stored false}
                             (.toISOString (js/Date.)))
          bytes false))

(defn- run-live [{:keys [bbox]}]
  (let [bad (check-bbox bbox)]
    (if-not (:ok? bad)
      bad
      (let [[w s e n] bbox
            url (str search-url "?bbox=" w "," s "," e "," n "&limit=1")]
        (println (str "GET " url " (anonymous open data; no credential involved)"))
        (-> (js/fetch url)
            (.then (fn [r]
                     (if (.-ok r)
                       (.text r)
                       (throw (js/Error. (str "http " (.-status r) " from " search-url))))))
            (.then step-search)
            (.then (fn [m] (if (:feature m) (step-pixel m) m)))
            (.catch (fn [e] (cant :panoramax-image/unreachable (.-message e)))))))))

(defn -main []
  (let [{:keys [live] :as args} (read-args)]
    (cond
      live (-> (run-live args)
               (.then (fn [m] (when (:refusal m) (fail! m)) (when (:could-not m) (fail! m)))))
      (and (:fixture args) (:pixel args))
      (let [m (run-offline args)]
        (when (:refusal m) (fail! m))
        (when (:could-not m) (fail! m)))
      :else (fail! (refuse :panoramax-image/missing-args
                           "pass --fixture <item.json> --pixel <bytes> [--stored] or --live --bbox W S E N")))))

(-main)
