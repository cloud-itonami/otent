;; Live verification of the MODIS Aqua Bands721 plan: the URLs the plan
;; produces must be exactly what the service answers, and the bytes must
;; be JPEGs. Bounded: z2 = 21 tiles, one declared capture date. NOT
;; sparse -- the layer answers 200 with a JPEG even over ocean -- so
;; every answer must be 200-with-JPEG-magic on the right key shape. A
;; 404 here means the declared date has no published data yet (the
;; day's tiles publish the day after capture), which is an error, not a
;; hole.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def date "2026-08-30")
(def plan (bm/ingest-plan "modis-aqua-bands721" 2 date))
(assert (:ok? plan))
(println "plan ok, candidate tiles:" (:tile-count plan)
         "date:" (:date plan)
         "sparse:" (:sparse-coverage (:source plan))
         "keys-unique:" (= (count (:tiles plan))
                           (count (distinct (map :key (:tiles plan))))))

(def stats (atom {:done 0 :present 0 :errors 0}))

(defn check-tile [{:keys [url key tile]}]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
      (.then
       (fn [r]
         (let [status (.-status r)]
           (-> (.arrayBuffer r)
               (.then
                (fn [ab]
                  (let [b (js/Buffer.from ab)
                        ok? (and (= 200 status)
                                 (= 0xFF (aget b 0))
                                 (= 0xD8 (aget b 1))
                                 (str/starts-with? key (str "otent/basemap/modis-aqua-bands721/" date "/"))
                                 (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".jpg")))]
                    (if ok?
                      (swap! stats update :present inc)
                      (swap! stats update :errors inc))
                    (println (if ok? "  ok " "  BAD") status url "->" key (.-length b) "bytes"))))))))
      (.then (fn [] (swap! stats update :done inc)))))

(defn finish []
  (let [{:keys [done present errors]} @stats]
    (println "checked:" done "of" (:tile-count plan)
             "present:" present "errors:" errors)
    (js/process.exit (if (and (= done (:tile-count plan))
                              (zero? errors)
                              (= present (:tile-count plan)))
                       0 1))))

(doseq [t (:tiles plan)] (check-tile t))
(js/setTimeout finish 60000)
