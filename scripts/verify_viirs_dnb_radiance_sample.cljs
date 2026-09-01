;; Live verification of the VIIRS DNB at-sensor radiance plan: the URLs
;; the plan produces must be exactly what the service answers, and the
;; bytes must be PNGs. Bounded: z2 = 21 tiles, one declared capture
;; date. SPARSE -- the satellite only sees the night side, so a 404 is
;; an honest hole in the day's coverage, not a failure -- but at least
;; one tile must answer 200 with PNG magic, else the declared date has
;; no published data at all and that IS a failure.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def date "2026-08-31")
(def plan (bm/ingest-plan "viirs-snpp-dnb-radiance" 2 date))
(assert (:ok? plan))
(println "plan ok, candidate tiles:" (:tile-count plan)
         "date:" (:date plan)
         "sparse:" (:sparse-coverage (:source plan))
         "keys-unique:" (= (count (:tiles plan))
                           (count (distinct (map :key (:tiles plan))))))

(def stats (atom {:done 0 :present 0 :holes 0 :errors 0}))

(defn check-tile [{:keys [url key tile]}]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
      (.then
       (fn [r]
         (let [status (.-status r)]
           (-> (.arrayBuffer r)
               (.then
                (fn [ab]
                  (let [b (js/Buffer.from ab)
                        key-ok (and (str/starts-with? key (str "otent/basemap/viirs-snpp-dnb-radiance/" date "/"))
                                    (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".png")))
                        png? (and (= 0x89 (aget b 0))
                                  (= 0x50 (aget b 1)))]
                    (if (and (= 200 status) key-ok png?)
                      (do (swap! stats update :present inc)
                          (println "  ok  " status url "->" key (.-length b) "bytes"))
                      (if (and (= 404 status) key-ok)
                        (do (swap! stats update :holes inc)
                            (println "  hole" status url "->" key))
                        (do (swap! stats update :errors inc)
                            (println "  BAD " status url "->" key (.-length b) "bytes")))))))))))
      (.then (fn [] (swap! stats update :done inc)))))

(defn finish []
  (let [{:keys [done present holes errors]} @stats]
    (println "checked:" done "of" (:tile-count plan)
             "present:" present "holes:" holes "errors:" errors)
    (js/process.exit (if (and (= done (:tile-count plan))
                              (zero? errors)
                              (pos? present))
                       0 1))))

(doseq [t (:tiles plan)] (check-tile t))
(js/setTimeout finish 60000)
