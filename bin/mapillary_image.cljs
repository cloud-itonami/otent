#!/usr/bin/env nbb
;; One Mapillary image PIXEL sample -- one bounded run.
;;
;;   nbb --classpath src bin/mapillary_image.cljs --fixture <image.json> --pixel <bytes-file> [--stored]
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/mapillary_image.cljs --live --bbox W S E N
;;
;; Live: ONE /images request built by the registered client
;; com-mapillary-graph-api (bbox strictly under 0.01 deg a side, token
;; in the Authorization header -- never the URL), then ONE pixel GET of
;; the first admissible image's own `thumb_1024_url`. paging.next and
;; siblings are counted, never followed. Bytes are hashed; they are
;; stored to R2 only behind $CF_CATALOG_TOKEN -- without it the run
;; reports `nothing written` and exits 2 rather than pretending.
;; Without MAPILLARY_ACCESS_TOKEN live mode hard-fails exit 2: a 401
;; must never be misread as an empty tile, and no data is invented.
;;
;; Exit 0 sample recorded · 1 refused · 2 could-not-act (no credential,
;; upstream unreachable). The promise chain is kept flat on purpose:
;; every .then returns data (or a {:refusal ...} map) and one final
;; .then decides the outcome, so the parens cannot drift.
(ns mapillary-image
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.mapillary-image :as mxi]
            [com-mapillary-graph-api.core :as mi]))

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

(defn- finish [record bytes stored]
  (let [check (mxi/check-record record)]
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
    (let [payload (js->clj (js/JSON.parse (fs/readFileSync fixture "utf8")))
          ;; accept either the /images payload shape or a bare feature
          [feature others next-link]
          (if (sequential? (get payload "data"))
            [(first (get payload "data"))
             (count (rest (get payload "data")))
             (get-in payload ["paging" "next"])]
            [payload 0 (get-in payload ["paging" "next"])])
          bytes (fs/readFileSync pixel)]
      (when (or (pos? others) next-link)
        (println (str "siblings fetched but NOT followed: " others
                      " other image(s); paging.next present=" (boolean next-link)
                      " (:run-bounds)")))
      (if-not (map? feature)
        (refuse :mapillary-image/no-admissible-image
                "the /images payload carried no image")
        (let [perm (mxi/image->pixel-permission feature)]
          (if-not (:ok? perm)
            (refuse (:error perm) (:detail perm))
            (finish (mxi/image->record (:feature perm)
                                       {:sha256 (sha256 bytes)
                                        :byte-size (.-length bytes)
                                        :content-type "image/jpeg"
                                        :stored (boolean stored)}
                                       (.toISOString (js/Date.)))
                    bytes (boolean stored))))))
    (catch :default e
      (cant :mapillary-image/fixture-unreadable (.-message e)))))

(defn- pixel-request [{:keys [feature]}]
  (let [purl (mxi/pixel-url-of feature)]
    (println (str "GET " purl " (one pixel request; the thumbnail host needs no token)"))
    (-> (js/fetch purl)
        (.then (fn [r]
                 (if (.-ok r)
                   (.arrayBuffer r)
                   (throw (js/Error. (str "http " (.-status r) " pixel"))))))
        (.then (fn [ab] {:feature feature :bytes (js/Buffer.from ab)})))))

(defn- step-search [body]
  (println (str "input search sha256=" (sha256 (js/Buffer.from body "utf8"))))
  (let [parsed (js->clj (js/JSON.parse body))
        features (get parsed "data")
        next-link (get-in parsed ["paging" "next"])
        feature (first features)]
    (when next-link
      (println (str "paging.next present but NOT followed: " (count features)
                    " image(s) fetched, the rest stay unfetched (:run-bounds)")))
    (if-not (map? feature)
      (refuse :mapillary-image/no-admissible-image
              "the /images payload carried no image")
      (let [perm (mxi/image->pixel-permission feature)]
        (if-not (:ok? perm)
          (refuse (:error perm) (:detail perm))
          (pixel-request perm))))))

(defn- step-pixel [{:keys [feature bytes]}]
  (finish (mxi/image->record feature
                             {:sha256 (sha256 bytes)
                              :byte-size (.-length bytes)
                              :content-type "image/jpeg"
                              :stored false}
                             (.toISOString (js/Date.)))
          bytes false))

(defn- run-live [{:keys [bbox]}]
  (let [{:keys [ok? error detail request]} (mxi/build-request bbox)
        token (some-> js/process .-env .-MAPILLARY_ACCESS_TOKEN str/trim not-empty)]
    (cond
      (not ok?) (js/Promise.resolve (refuse error detail))
      (str/blank? token)
      (js/Promise.resolve
       (cant :mapillary-image/no-credential
             "MAPILLARY_ACCESS_TOKEN is not set -- a 401 must never be misread as an empty tile, and no data is invented"))
      :else
      (let [url (str (:url request) "?"
                     (->> (:query-params request)
                          (map (fn [[k v]] (str k "=" v)))
                          (str/join "&")))]
        (println (str "GET " (:url request)
                      " (token in Authorization header, not the URL)"))
        (-> (js/fetch url #js {:method "GET"
                               :headers (clj->js (mi/authorization-header token))})
            (.then (fn [r]
                     (if (.-ok r)
                       (.text r)
                       (throw (js/Error. (str "http " (.-status r) " from " (:url request)))))))
            (.then step-search)
            (.then (fn [m] (if (:feature m) (step-pixel m) m)))
            (.catch (fn [e] (cant :mapillary-image/unreachable (.-message e)))))))))

(defn -main []
  (let [{:keys [live] :as args} (read-args)]
    (cond
      live (-> (run-live args)
               (.then (fn [m] (when (:refusal m) (fail! m)) (when (:could-not m) (fail! m)))))
      (and (:fixture args) (:pixel args))
      (let [m (run-offline args)]
        (when (:refusal m) (fail! m))
        (when (:could-not m) (fail! m)))
      :else (fail! (refuse :mapillary-image/missing-args
                           "pass --fixture <image.json> --pixel <bytes> [--stored] or --live --bbox W S E N")))))

(-main)
