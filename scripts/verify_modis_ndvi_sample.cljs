;; Live verification of the MODIS Terra NDVI rolling 8-day plan: the URLs
;; the plan produces must be exactly what the service answers. Bounded:
;; z2 = 21 tiles, one declared 8-day window. NOT sparse -- the layer
;; answers 200 with a (largely empty) PNG even over ocean -- so every
;; answer must be 200-with-PNG-magic on the right key shape. A 404 here
;; means the declared window does not exist, which is an error, not a
;; hole.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def window "2026-08-30")
(def plan (bm/ingest-plan "modis-terra-ndvi-8day" 2 window))
(assert (:ok? plan))
(println "plan ok, candidate tiles:" (:tile-count plan)
         "window:" (:date plan)
         "sparse:" (:sparse-coverage (:source plan))
         "keys-unique:" (= (count (:tiles plan))
                           (count (distinct (map :key (:tiles plan))))))

(def stats (atom {:done 0 :present 0 :absent 0 :errors 0}))

(defn check-tile [{:keys [url key tile]}]
  (let [on-done (fn [] (swap! stats update :done inc))]
    (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
        (.then
         (fn [r]
           (let [status (.-status r)]
             (-> (.arrayBuffer r)
                 (.then
                  (fn [ab]
                    (let [b (js/Buffer.from ab)
                          ok? (and (= 200 status)
                                   (= 0x89 (aget b 0))
                                   (= 0x50 (aget b 1))
                                   (str/starts-with? key (str "otent/basemap/modis-terra-ndvi-8day/" window "/"))
                                   (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".png")))]
                      (if ok?
                        (swap! stats update :present inc)
                        (swap! stats update :errors inc))
                      (println (if ok? "  ok " "  BAD") status url "->" key (.-length b) "bytes"))))))))
        (.then on-done))))

(defn finish []
  (let [{:keys [done present absent errors]} @stats]
    (println "checked:" done "of" (:tile-count plan)
             "present:" present "absent:" absent "errors:" errors)
    (js/process.exit (if (and (= done (:tile-count plan))
                              (zero? errors)
                              (= present (:tile-count plan)))
                       0 1))))

(doseq [t (:tiles plan)] (check-tile t))
(js/setTimeout finish 60000)
