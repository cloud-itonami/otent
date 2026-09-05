;; Live verification of the VIIRS Black Marble plan: the URLs the plan
;; produces must be exactly what the service answers, and the bytes must
;; be PNGs. Bounded: z2 = 21 tiles, one composite date. No bucket, no
;; token -- this fetches and checks, and writes nothing.
;;
;;   nbb --classpath src scripts/verify_night_lights_sample.cljs
;;
;; Exit 0 only if every tile answers 200 with PNG magic and the
;; URL <-> key pair matches what the plan declares.

(require '[otent.night-lights :as nl]
         '[clojure.string :as str])

(def plan (nl/plan {:composite "2016-01-01" :max-zoom 2}))
(assert (:ok? plan) (pr-str plan))
(println "plan ok, tiles:" (:tile-count plan)
         "composite:" (:composite plan)
         "layer:" (:layer nl/source))

(defn- png-magic? [b]
  (and (>= (.-length b) 8)
       (= 0x89 (aget b 0)) (= 0x50 (aget b 1)) (= 0x4E (aget b 2)) (= 0x47 (aget b 3))))

(def ^:private timer (atom nil))
(def ^:private watchdog (atom nil))

(let [errors (atom [])
      done (atom 0)]
  (doseq [tile (:tiles plan)]
    (let [url (nl/tile-url tile "2016-01-01")
          key (nl/object-key tile "2016-01-01")]
      (-> (js/fetch url #js {:headers #js {"user-agent" "otent-night-lights/0.1 (cloud-itonami)"}})
          (.then (fn [r]
                   (-> (.arrayBuffer r)
                       (.then (fn [ab]
                                (let [b (js/Buffer.from ab)]
                                  (swap! done inc)
                                  (when-not (and (= 200 (.-status r)) (png-magic? b))
                                    (swap! errors conj {:tile tile :detail (str "HTTP " (.-status r) " or bad magic")}))
                                  (when-not (and (str/starts-with? url "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_Black_Marble/default/2016-01-01/GoogleMapsCompatible_Level8/")
                                                 (str/ends-with? url (str "/" (nth tile 0) "/" (nth tile 2) "/" (nth tile 1) ".png"))
                                                 (= key (str "otent/night-lights/2016-01-01/" (str/join "/" tile) ".png")))
                                    (swap! errors conj {:tile tile :detail "url/key disagree with the plan"})))))))))))
  ;; single-shot poll-to-finish: 21 requests
  (reset! timer (js/setInterval
               (fn []
                 (when (= @done (:tile-count plan))
                   (js/clearInterval @timer)
                   (js/clearTimeout @watchdog)
                   (println "fetched:" @done "/" (:tile-count plan))
                   (doseq [e (take 5 @errors)] (println "  BAD" (pr-str e)))
                   (set! (.-exitCode js/process) (if (empty? @errors) 0 1))))
               200))
  (reset! watchdog (js/setTimeout (fn []
                   (js/clearInterval @timer)
                   (println "TIMED OUT at" @done "of" (:tile-count plan))
                   (set! (.-exitCode js/process) 1))
                 60000)))
