;; Live verification of the Landsat WELD annual plan: the URLs the plan
;; produces must be exactly what the service answers. Bounded: z2 = 21
;; tiles, one declared composite year. Unlike the daily layers the
;; composite is sparse -- 404 over ocean and no-data is an EXPECTED
;; answer here, so the check is: every answer is 200-with-JPEG-magic or
;; exactly 404, and every 200 lands on the right key shape.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def year "1998-12-01")
(def plan (bm/ingest-plan "landsat-weld-truecolor-annual" 2 year))
(assert (:ok? plan))
(println "plan ok, candidate tiles:" (:tile-count plan)
         "period:" (:date plan)
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
             (if (= 404 status)
               ;; declared hole over no-data: expected, not an error
               (do (swap! stats update :absent inc)
                   (println "  absent" (pr-str tile)))
               (-> (.arrayBuffer r)
                   (.then
                    (fn [ab]
                      (let [b (js/Buffer.from ab)
                            ok? (and (= 200 status)
                                     (= 0xFF (aget b 0))
                                     (= 0xD8 (aget b 1))
                                     (str/starts-with? key (str "otent/basemap/landsat-weld-truecolor-annual/" year "/"))
                                     (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".jpg")))]
                        (if ok?
                          (swap! stats update :present inc)
                          (swap! stats update :errors inc))
                        (println (if ok? "  ok " "  BAD") status url "->" key (.-length b) "bytes")))))))))
        (.then on-done))))

(defn finish []
  (let [{:keys [done present absent errors]} @stats]
    (println "checked:" done "of" (:tile-count plan)
             "present:" present "absent:" absent "errors:" errors)
    (js/process.exit (if (and (= done (:tile-count plan))
                              (zero? errors)
                              (pos? present))
                       0 1))))

(doseq [t (:tiles plan)] (check-tile t))
(js/setTimeout finish 60000)
