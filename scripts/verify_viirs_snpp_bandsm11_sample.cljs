;; Live verification of the VIIRS SNPP BandsM11-I2-I1 plan: the URLs the
;; plan produces must be exactly what the service answers, and the bytes
;; must be JPEGs. Bounded: z2 = 21 tiles, one declared capture date.
;; This is daylit-side reflectance, not the DNB night side, so every
;; tile in the bound must answer 200 with JPEG magic -- a 404 here is a
;; missing day, not a sparse hole, and IS a failure.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def date "2026-08-31")
(def plan (bm/ingest-plan "viirs-snpp-bandsm11" 2 date))
(assert (:ok? plan))
(println "plan ok, tiles:" (:tile-count plan)
         "date:" (:date plan)
         "sparse:" (:sparse-coverage (:source plan))
         "keys-unique:" (= (count (:tiles plan))
                           (count (distinct (map :key (:tiles plan))))))

(def stats (atom {:done 0 :ok 0 :errors 0}))

(defn check-tile [{:keys [url key tile]}]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
      (.then
       (fn [r]
         (let [status (.-status r)]
           (-> (.arrayBuffer r)
               (.then
                (fn [ab]
                  (let [b (js/Buffer.from ab)
                        key-ok (and (str/starts-with? key (str "otent/basemap/viirs-snpp-bandsm11/" date "/"))
                                    (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".jpg")))
                        jpeg? (and (= 0xFF (aget b 0)) (= 0xD8 (aget b 1)))
                        ct (some-> (.-headers r) (.get "content-type"))]
                    (if (and (= 200 status) key-ok jpeg? (str/starts-with? (or ct "") "image/jpeg"))
                      (swap! stats (fn [s] (-> s (update :ok inc))))
                      (swap! stats (fn [s] (-> s (update :errors inc)
                                               (assoc :last (str status " " key " jpeg?" jpeg? " ct:" ct))))))
                    (swap! stats (fn [s] (update s :done inc)))
                    (when (= (:done @stats) (:tile-count plan))
                      (println "done:" (:done @stats) "ok:" (:ok @stats) "errors:" (:errors @stats))
                      (when (:last @stats) (println "last error:" (:last @stats)))
                      (when (pos? (:errors @stats)) (js/process.exit 1))
                      (println "VERIFY OK: every bounded tile answered 200 with JPEG magic")))))))))))

(doseq [t (:tiles plan)] (check-tile t))
