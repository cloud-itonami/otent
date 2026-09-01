#!/usr/bin/env nbb
;; Derived per-kind tally over provider-published street detections
;; (Mapillary `map_features`) — one source, one area, one derived task
;; per run.
;;
;;   nbb --classpath src bin/street_tally.cljs --fixture payload.json --area-id <area>
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/street_tally.cljs \
;;     --bbox W,S,E,N --area-id <area>
;;
;; The upstream analysis (privacy gate, taxonomy gate, lon/lat order,
;; unknowns visible) belongs to otent.street and is reused unchanged.
;; This script derives the per-kind tally + refusal ledger and prints
;; the report. No writes without an explicit store step elsewhere;
;; nothing is faked when a credential is missing.
;;
;; Exit 0 derived · 1 refused (bad args / upstream refused) ·
;; 2 could not act (no credential, network unreachable, unreadable fixture).

(ns bin.street-tally
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.street :as street]
            [otent.street-tally :as tally]))

(defn- sha256 [^js buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- iso-now []
  (.toISOString (js/Date.)))

(defn- parse-args [args]
  (loop [a args m {}]
    (if (seq a)
      (let [x (first a)]
        (case x
          "--bbox" (recur (nnext a) (assoc m :bbox (second a)))
          "--area-id" (recur (nnext a) (assoc m :area-id (second a)))
          "--fixture" (recur (nnext a) (assoc m :fixture (second a)))
          (recur (rest a) m)))
      m)))

(defn- refuse! [code m]
  (println (str "REFUSED " (name (:error m)) ": " (:detail m)))
  (js/process.exit code))

(defn- check-bbox [bbox-str]
  (let [nums (mapv js/parseFloat (str/split (or bbox-str "") #","))]
    (if (and (= 4 (count nums)) (every? #(not (js/isNaN %)) nums))
      {:ok? true :bbox nums}
      {:ok? false :error :mapillary/bad-bbox
       :detail "bbox must be W,S,E,N as four comma-separated numbers"})))

(defn- fetch-map-features! [bbox area-id]
  (let [token (.-MAPILLARY_ACCESS_TOKEN js/process.env)]
    (cond
      (str/blank? token)
      (js/Promise.resolve {:ok? false :code 2 :error :mapillary/no-credential
                           :detail "MAPILLARY_ACCESS_TOKEN is not set: a 401 must never be misread as an empty tile, and no data is invented"})
      :else
      (let [[w s e n] bbox
            url (str "https://graph.mapillary.com/map_features?bbox="
                     w "," s "," e "," n)]
        (println "GET (token in Authorization header, never the URL)")
        (-> (js/fetch url #js {:headers #js {"Authorization" (str "OAuth " token)}
                               :signal (dl/signal)})
            (.then (fn [r]
                     (if-not (.-ok r)
                       (js/Promise.resolve
                        {:ok? false :code 2 :error :mapillary/fetch-failed
                         :detail (str "status " (.-status r) " from the Graph API")})
                       (.then (.text r)
                              (fn [txt]
                                (js/Promise.resolve
                                 {:ok? true :buf (js/Buffer.from txt "utf8")
                                  :payload (js/JSON.parse txt)}))))))
            (.catch (fn [err]
                      (js/Promise.resolve {:ok? false :code 2
                                           :error :mapillary/unreachable
                                           :detail (.-message err)}))))))))

(defn- load-fixture! [fixture area-id]
  (if (or (not fixture) (not area-id))
    (js/Promise.resolve {:ok? false :code 2 :error :args/missing
                         :detail "usage: --fixture payload.json --area-id <area> (or --bbox W,S,E,N --area-id <area>)"})
    (try
      (let [txt (str (fs/readFileSync fixture "utf8"))]
        (js/Promise.resolve
         {:ok? true :buf (js/Buffer.from txt "utf8")
          :payload (js/JSON.parse txt)}))
      (catch :default e
        (js/Promise.resolve {:ok? false :code 2 :error :fixture/unreadable
                             :detail (.-message e)})))))

(defn- derive! [payload area-id retrieved-at input-sha256]
  (let [analysis (street/analyze (js->clj payload)
                                 {:area-id area-id
                                  :retrieved-at retrieved-at
                                  :input-sha256 input-sha256})
        t (tally/tally analysis)]
    (println (str "counts: raw=" (get-in analysis [:counts :raw-count])
                  " observations=" (get-in analysis [:counts :observations])
                  " unknown-labels=" (get-in analysis [:counts :unknown-labels])
                  " privacy-refusals=" (get-in analysis [:counts :privacy-refusals])
                  " geometry-refusals=" (get-in analysis [:counts :geometry-refusals])))
    (doseq [row (:per-kind t)]
      (println (str "  " (:kind row) "\t" (:count row)
                    "\t(" (:object-value row) ")")))
    (doseq [r (:refusal-ledger t)]
      (println (str "  LEDGER " (name (:reason r)) "\t" (:count r))))
    (println (str "ledger-reconciled=" (get-in t [:counts :ledger-reconciled?])
                  " confidence-unknown=" (get-in t [:unknown-counts :confidence-unknown])
                  " spatial-uncertainty-unknown=" (get-in t [:unknown-counts :spatial-uncertainty-unknown])))
    (println (-> t clj->js (js/JSON.stringify nil 2)))))

(defn -main [& args]
  (let [{:keys [bbox fixture area-id] :as opts} (parse-args args)]
    (cond
      (not area-id)
      (refuse! 2 {:error :args/missing :detail "--area-id is required"})

      (not fixture)
      (let [c (check-bbox bbox)]
        (if-not (:ok? c)
          (refuse! 1 c)
          (-> (fetch-map-features! (:bbox c) area-id)
              (.then (fn [{:keys [ok? code error detail payload buf]}]
                       (if-not ok?
                         (refuse! code {:error error :detail detail})
                         (derive! payload area-id (iso-now) (sha256 buf))))))))

      :else
      (-> (load-fixture! fixture area-id)
          (.then (fn [{:keys [ok? code error detail payload buf]}]
                   (if-not ok?
                     (refuse! code {:error error :detail detail})
                     (derive! payload area-id (iso-now) (sha256 buf)))))))))

(when (exists? (.-argv js/process))
  (apply -main (vec (drop 2 (.-argv js/process)))))
