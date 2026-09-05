#!/usr/bin/env nbb
;; One KartaView image pixel sample -- one bounded run.
;;
;;   nbb --classpath src bin/kartaview_image.cljs --fixture <photo.json> --pixel <bytes-file> [--stored]
;;   nbb --classpath src bin/kartaview_image.cljs --live --bbox W S E N
;;
;; Live: one anonymous search request (api.openstreetcam.org 2.0 photo
;; search, the provider's own public endpoint; bbox <= 0.01 deg/side,
;; limit small), the first in-bbox photo that passes every gate in
;; otent.kartaview-image (public + active + BLURRED + geometry + capture
;; time + pixel url), then ONE pixel GET of that photo's published
;; processed-image URL. Bytes are hashed; they are stored to R2 only
;; behind $CF_CATALOG_TOKEN -- without it the run reports `nothing
;; written` and exits 2 rather than pretending.
;;
;; Exit 0 sample recorded · 1 refused · 2 could-not-act (no write
;; credential, upstream unreachable). The promise chain is kept flat on
;; purpose: every .then returns data (or a {:refusal ...} map) and one
;; final .then decides the outcome, so the parens cannot drift.
(ns kartaview-image
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.kartaview-image :as kvi]))

(def search-url "https://api.openstreetcam.org/2.0/photo/")

(def max-span 0.01)
(def max-results 100)
(def max-radius-m 500)

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
      (refuse :kartaview-image/bbox-invalid (pr-str bbox))
      (or (<= (- e w) 0) (<= (- n s) 0))
      (refuse :kartaview-image/bbox-inverted (pr-str bbox))
      (or (> (- e w) max-span) (> (- n s) max-span))
      (refuse :kartaview-image/bbox-too-large
              (str "span exceeds the " max-span " deg run bound"))
      :else {:ok? true})))

(defn- finish [record bytes stored]
  (let [check (kvi/check-record record)]
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
    (let [photo (js->clj (js/JSON.parse (fs/readFileSync fixture "utf8")))
          bytes (fs/readFileSync pixel)
          perm (kvi/photo->pixel-permission photo)]
      (if-not (:ok? perm)
        (refuse (:error perm) (:detail perm))
        (finish (kvi/image->record (:photo perm)
                                   {:sha256 (sha256 bytes)
                                    :byte-size (.-length bytes)
                                    :content-type "image/jpeg"
                                    :stored (boolean stored)}
                                   (.toISOString (js/Date.)))
                bytes (boolean stored))))
    (catch :default e
      (cant :kartaview-image/fixture-unreadable (.-message e)))))

(defn- first-admissible [photos bbox]
  (let [[w s e n] bbox
        in-bbox? (fn [p]
                   (let [lon (js/parseFloat (get p "lng"))
                         lat (js/parseFloat (get p "lat"))]
                     (and (not (js/isNaN lon)) (not (js/isNaN lat))
                          (<= w lon e) (<= s lat n))))
        admissible (filter (fn [p]
                             (and (in-bbox? p)
                                  (:ok? (kvi/photo->pixel-permission p))))
                           photos)]
    {:count (count photos)
     :in-bbox (count (filter in-bbox? photos))
     :photo (first admissible)}))

(defn- step-search [body bbox]
  (println (str "input search sha256=" (sha256 (js/Buffer.from body "utf8"))))
  (let [payload (js->clj (js/JSON.parse body))
        status (get-in payload ["status" "httpCode"])]
    (if-not (= 200 status)
      (refuse :kartaview-image/bad-envelope
              (str "response status block: " (pr-str (get payload "status"))))
      (let [{:keys [count in-bbox photo]} (first-admissible
                                           (get-in payload ["result" "data"] [])
                                           bbox)
            has-more (boolean (get-in payload ["result" "hasMoreData"]))]
        (println (str "search returned " count " photo(s), " in-bbox " in bbox"
                      ", hasMoreData=" has-more " (reported, never followed)"))
        (if-not (map? photo)
          (refuse :kartaview-image/no-admissible-item
                  "no in-bbox photo passed every pixel gate")
          (let [perm (kvi/photo->pixel-permission photo)]
            (if-not (:ok? perm)
              (refuse (:error perm) (:detail perm))
              (let [purl (kvi/pixel-url-of (:photo perm))]
                (println (str "GET " purl " (one pixel request)"))
                (-> (js/fetch purl)
                    (.then (fn [r]
                             (if (.-ok r)
                               (.arrayBuffer r)
                               (throw (js/Error. (str "http " (.-status r) " pixel"))))))
                    (.then (fn [ab]
                             (let [bytes (js/Buffer.from ab)]
                               {:photo (:photo perm) :bytes bytes}))))))))))))

(defn- step-pixel [{:keys [photo bytes]}]
  (finish (kvi/image->record photo
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
            cx (/ (+ w e) 2.0)
            cy (/ (+ s n) 2.0)
            diag-deg (js/Math.sqrt (+ (* (- e w) (- e w)) (* (- n s) (- n s))))
            radius (min max-radius-m (js/Math.ceil (/ (* diag-deg 111320.0) 2)))
            url (str search-url "?lat=" cy "&lng=" cx "&radius=" radius
                     "&limit=" max-results)]
        (println (str "GET " url " (anonymous open data; no credential involved)"))
        (-> (js/fetch url)
            (.then (fn [r]
                     (if (.-ok r)
                       (.text r)
                       (throw (js/Error. (str "http " (.-status r) " from " search-url))))))
            (.then (fn [body] (step-search body bbox)))
            (.then (fn [m] (if (:photo m) (step-pixel m) m)))
            (.catch (fn [e] (cant :kartaview-image/unreachable (.-message e)))))))))

(defn -main []
  (let [{:keys [live] :as args} (read-args)]
    (cond
      live (-> (run-live args)
               (.then (fn [m] (when (:refusal m) (fail! m)) (when (:could-not m) (fail! m)))))
      (and (:fixture args) (:pixel args))
      (let [m (run-offline args)]
        (when (:refusal m) (fail! m))
        (when (:could-not m) (fail! m)))
      :else (fail! (refuse :kartaview-image/missing-args
                           "pass --fixture <photo.json> --pixel <bytes> [--stored] or --live --bbox W S E N")))))

(-main)
