;; Live verification of the VIIRS Suomi NPP plan: the URLs the plan
;; produces must be exactly what the service answers, and the bytes
;; must be JPEGs. Bounded: z2 = 21 tiles, one capture date.
(require '[otent.basemap :as bm]
         '[clojure.string :as str])

(def plan (bm/ingest-plan "viirs-snpp-truecolor" 2 "2026-08-30"))
(assert (:ok? plan))
(println "plan ok, tiles:" (:tile-count plan)
         "date:" (:date plan)
         "keys-unique:" (= (count (:tiles plan))
                           (count (distinct (map :key (:tiles plan))))))
(let [errors (atom 0)
      done (atom 0)]
  (doseq [{:keys [url key tile]} (:tiles plan)]
    (-> (js/fetch url #js {:headers #js {"user-agent" "otent-basemap/0.1 (cloud-itonami)"}})
        (.then (fn [r]
                 (-> (.arrayBuffer r)
                     (.then (fn [ab]
                              (let [b (js/Buffer.from ab)
                                    ok? (and (= 200 (.-status r))
                                             (= 0xFF (aget b 0))
                                             (= 0xD8 (aget b 1))
                                             (str/starts-with? key "otent/basemap/viirs-snpp-truecolor/2026-08-30/")
                                             (str/ends-with? key (str "/" (nth tile 0) "/" (nth tile 1) "/" (nth tile 2) ".jpg")))]
                                (when-not ok? (swap! errors inc))
                                (swap! done inc)
                                (println (if ok? "  ok " "  BAD") (.-status r) url "->" key (.-length b) "bytes"))))))))
  (js/setTimeout (fn []
                   (println "checked:" @done "of" (:tile-count plan)
                            "errors:" @errors)
                   (js/process.exit (if (and (= @done (:tile-count plan)) (zero? @errors)) 0 1)))
                 30000)))
