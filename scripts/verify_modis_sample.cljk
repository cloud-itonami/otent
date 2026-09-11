;; Live verification of the plan: URLs a plan produces must be exactly
;; what the service answers, and the bytes must be JPEGs.
(require '[otent.basemap :as bm])

(def plan (bm/ingest-plan "modis-terra-truecolor" 2 "2026-08-29"))
(assert (:ok? plan))
(println "plan ok, tiles:" (:tile-count plan))
(doseq [{:keys [url key]} (:tiles plan)]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
      (.then (fn [r]
               (println (.-status r) url "->" key)
               (-> (.arrayBuffer r)
                   (.then (fn [ab]
                            (let [b (js/Buffer.from ab)]
                              (println "  bytes" (.-length b)
                                       "jpeg-magic:" (and (= 0xFF (aget b 0)) (= 0xD8 (aget b 1))))))))))))
