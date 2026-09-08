;; Live verification of the MAIAC aerosol optical depth sample: the URL
;; the record declares must be exactly what the service answers. Bounded:
;; ONE tile (level 0, 1km matrix), one declared capture date. A 200 with
;; PNG magic and the record's sha256 proves the declared URL is real; a
;; 404 here means the declared date has no published data yet, which is
;; an error, not a hole.
(require '[otent.imagery :as imagery]
         '[kotoba.lang.text :as str])

(def sample imagery/modis-maiac-aerosol-aod-sample)
(println "sample:" (:asset-id sample)
         "date:" (:capture-time sample))

(-> (js/fetch (:source-url sample)
              #js {:headers #js {"user-agent" "otent-imagery/0.1 (cloud-itonami)"}})
    (.then (fn [r]
             (-> (.arrayBuffer r)
                 (.then (fn [ab]
                          (let [b (js/Buffer.from ab)
                                crypto (js/require "node:crypto")
                                sha256 (-> (crypto.createHash "sha256")
                                           (.update b)
                                           (.digest "hex"))
                                ok? (and (= 200 (.-status r))
                                         (= 0x89 (aget b 0))
                                         (= 0x50 (aget b 1))
                                         (= (:payload-sha256 sample) sha256))]
                            (println (if ok? "ok" "BAD")
                                     (.-status r)
                                     (.-length b) "bytes"
                                     "sha256" sha256
                                     (if (= (:payload-sha256 sample) sha256)
                                       "matches record"
                                       "DOES NOT match record"))
                            (js/process.exit (if ok? 0 1)))))))))
