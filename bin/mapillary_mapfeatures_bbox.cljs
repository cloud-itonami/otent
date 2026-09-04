#!/usr/bin/env nbb
;; One Mapillary /map_features bbox sample -- one bounded run.
;;
;;   nbb --classpath src bin/mapillary_mapfeatures_bbox.cljs --fixture <features.json> [--bbox-str "minx,miny,maxx,maxy"]
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/mapillary_mapfeatures_bbox.cljs --live --bbox 139.76,35.67,139.77,35.68
;;
;; Live: ONE /map_features request built by the registered client
;; com-mapillary-graph-api (the client's `map-features-request`), token
;; in the Authorization header -- never the URL, never a log. Metadata
;; only: no pixel URL is requested and none is followed. paging.next is
;; counted and printed, never followed. The bbox is ONE 0.01-degree
;; tile; no split, no neighbouring tile, no second request.
;; Without MAPILLARY_ACCESS_TOKEN live mode hard-fails exit 2: a 401
;; must never be misread as an empty subject, and no data is invented.
;;
;; Exit 0 recorded · 1 refused · 2 could-not-act (no credential,
;; upstream unreachable). The promise chain is kept flat on purpose:
;; every .then returns data (or a {:refusal ...} map) and one final
;; .then decides the outcome, so the parens cannot drift.
(ns mapillary-mapfeatures-bbox
  (:require ["fs" :as fs]
            [clojure.string :as str]
            [otent.mapillary-mapfeatures-bbox :as mfb]
            [com-mapillary-graph-api.core :as mi]))

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
            (= k "--fixture")  (recur (+ i 2) (assoc acc :fixture (nth v (inc i))))
            (= k "--bbox")     (recur (+ i 2) (assoc acc :bbox-arg (nth v (inc i))))
            (= k "--live")     (recur (inc i) (assoc acc :live true))
            :else (recur (inc i) acc)))))))

(defn- parse-bbox-arg
  "\"139.76,35.67,139.77,35.68\" -> [139.76 35.67 139.77 35.68], refusing
  anything non-numeric before any request is built."
  [s]
  (let [parts (map str/trim (str/split (str s) #","))]
    (if (and (= 4 (count parts)) (every? #(re-matches #"-?\d+(\.\d+)?" %) parts))
      {:ok? true :bbox (mapv #(js/parseFloat %) parts)}
      {:ok? false :error :mly-mfbbox/bbox-arg-malformed
       :detail (str "--bbox must be \"minx,miny,maxx,maxy\" numeric, got " (pr-str s))})))

(defn- print-outcome [bbox-vec {:keys [records refused-n raw-count paging-next]}]
  (println (str "subject bbox=" (mfb/bbox-str bbox-vec)
                " raw=" raw-count
                " recorded=" (count records)
                " refused=" refused-n
                " paging.next present=" paging-next " (NOT followed)"))
  (doseq [r records]
    (let [check (mfb/check-record r)]
      (if-not (:ok? check)
        (refuse (:error check) (:detail check))
        (println (js/JSON.stringify (clj->js r) nil 2))))))

(defn- run-offline [{:keys [fixture bbox-arg]}]
  (try
    (let [payload (js->clj (js/JSON.parse (fs/readFileSync fixture "utf8")))
          bbox-str (or bbox-arg (get payload "bbox-str") "")
          parsed (if (seq bbox-str) (parse-bbox-arg bbox-str) {:ok? false :error :mly-mfbbox/bbox-arg-malformed :detail "--bbox-str is required in offline mode (or embed bbox-str in the fixture)"})]
      (if-not (:ok? parsed)
        (refuse (:error parsed) (:detail parsed))
        (let [bbox-str-norm (mfb/bbox-str (:bbox parsed))]
          (print-outcome (:bbox parsed)
                         (mfb/parse-payload (assoc payload
                                                   "bbox" (:bbox parsed)
                                                   "bbox-str" bbox-str-norm
                                                   "retrieved-at" (.toISOString (js/Date.))))))))
    (catch :default e
      (cant :mly-mfbbox/fixture-unreadable (.-message e)))))

(defn- step-body [bbox-vec body]
  (println (str "GET done (one /map_features request; metadata only)"))
  (let [parsed (js->clj (js/JSON.parse body))]
    (print-outcome bbox-vec
                   (mfb/parse-payload (assoc parsed
                                             "bbox" bbox-vec
                                             "bbox-str" (mfb/bbox-str bbox-vec)
                                             "retrieved-at" (.toISOString (js/Date.)))))))

(defn- run-live [{:keys [bbox-arg]}]
  (let [parsed (parse-bbox-arg bbox-arg)
        token (some-> js/process .-env .-MAPILLARY_ACCESS_TOKEN str/trim not-empty)]
    (cond
      (not (:ok? parsed)) (js/Promise.resolve (refuse (:error parsed) (:detail parsed)))
      :else
      (let [{:keys [ok? error detail request]} (mfb/build-request (:bbox parsed))]
        (cond
          (not ok?) (js/Promise.resolve (refuse error detail))
          (str/blank? token)
          (js/Promise.resolve
           (cant :mly-mfbbox/no-credential
                 "MAPILLARY_ACCESS_TOKEN is not set -- a 401 must never be misread as an empty subject, and no data is invented"))
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
                (.then (fn [body] (step-body (:bbox parsed) body)))
                (.catch (fn [e] (cant :mly-mfbbox/unreachable (.-message e)))))))))))

(defn -main []
  (let [{:keys [live fixture] :as args} (read-args)]
    (cond
      live (-> (run-live args)
               (.then (fn [m] (when (:refusal m) (fail! m)) (when (:could-not m) (fail! m)))))
      fixture (let [m (run-offline args)]
                (when (:refusal m) (fail! m))
                (when (:could-not m) (fail! m)))
      :else (fail! (refuse :mly-mfbbox/missing-args
                           "pass --fixture <features.json> [--bbox-str \"minx,miny,maxx,maxy\"] or --live --bbox minx,miny,maxx,maxy")))))

(-main)
