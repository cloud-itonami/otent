#!/usr/bin/env nbb
;; One Mapillary per-image DETECTIONS sample -- one bounded run.
;;
;;   nbb --classpath src bin/mapillary_image_detections.cljs --fixture <detections.json> [--image-id <id>]
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/mapillary_image_detections.cljs --live --image-id <id>
;;
;; Live: ONE /:image_id/detections request built by the registered
;; client com-mapillary-graph-api (the client's `detections-request`
;; with `kind :image`), token in the Authorization header -- never the
;; URL, never a log. Metadata only: no pixel URL is requested and none
;; is followed. paging.next is counted and printed, never followed.
;; Without MAPILLARY_ACCESS_TOKEN live mode hard-fails exit 2: a 401
;; must never be misread as an empty subject, and no data is invented.
;;
;; Exit 0 recorded · 1 refused · 2 could-not-act (no credential,
;; upstream unreachable). The promise chain is kept flat on purpose:
;; every .then returns data (or a {:refusal ...} map) and one final
;; .then decides the outcome, so the parens cannot drift.
(ns mapillary-image-detections
  (:require ["fs" :as fs]
            [clojure.string :as str]
            [otent.mapillary-image-detections :as mid]
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
            (= k "--image-id") (recur (+ i 2) (assoc acc :image-id (nth v (inc i))))
            (= k "--live")     (recur (inc i) (assoc acc :live true))
            :else (recur (inc i) acc)))))))

(defn- print-outcome [image-id {:keys [records refused-n raw-count paging-next]}]
  (println (str "subject image id=" image-id
                " raw=" raw-count
                " recorded=" (count records)
                " refused=" refused-n
                " paging.next present=" paging-next " (NOT followed)"))
  (doseq [r records]
    (let [check (mid/check-record r)]
      (if-not (:ok? check)
        (refuse (:error check) (:detail check))
        (println (js/JSON.stringify (clj->js r) nil 2))))))

(defn- run-offline [{:keys [fixture image-id]}]
  (try
    (let [payload (js->clj (js/JSON.parse (fs/readFileSync fixture "utf8")))
          id (or image-id (get payload "image-id"))
          {:keys [ok? error detail]} (mid/check-image-id id)]
      (if-not ok?
        (refuse error detail)
        (print-outcome id
                       (mid/parse-payload (assoc payload
                                                 "image-id" id
                                                 "retrieved-at" (.toISOString (js/Date.)))))))
    (catch :default e
      (cant :mly-imagedet/fixture-unreadable (.-message e)))))

(defn- step-body [image-id body]
  (println (str "GET done (one /:image_id/detections request; metadata only)"))
  (let [parsed (js->clj (js/JSON.parse body))]
    (print-outcome image-id
                   (mid/parse-payload (assoc parsed
                                             "image-id" image-id
                                             "retrieved-at" (.toISOString (js/Date.)))))))

(defn- run-live [{:keys [image-id]}]
  (let [{:keys [ok? error detail request]} (mid/build-request image-id)
        token (some-> js/process .-env .-MAPILLARY_ACCESS_TOKEN str/trim not-empty)]
    (cond
      (not ok?) (js/Promise.resolve (refuse error detail))
      (str/blank? token)
      (js/Promise.resolve
       (cant :mly-imagedet/no-credential
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
            (.then (fn [body] (step-body image-id body)))
            (.catch (fn [e] (cant :mly-imagedet/unreachable (.-message e)))))))))

(defn -main []
  (let [{:keys [live fixture] :as args} (read-args)]
    (cond
      live (-> (run-live args)
               (.then (fn [m] (when (:refusal m) (fail! m)) (when (:could-not m) (fail! m)))))
      fixture (let [m (run-offline args)]
                (when (:refusal m) (fail! m))
                (when (:could-not m) (fail! m)))
      :else (fail! (refuse :mly-imagedet/missing-args
                           "pass --fixture <detections.json> [--image-id <id>] or --live --image-id <id>")))))

(-main)
