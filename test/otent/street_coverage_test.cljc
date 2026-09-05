(ns otent.street-coverage-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.kartaview :as kv]
            [otent.street-coverage :as sc]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- photo [id shot]
  {"id" id "sequenceId" "583739" "sequenceIndex" "1"
   "lat" "35.680192" "lng" "139.765267"
   "heading" "297.32" "projection" "PLANE" "gpsAccuracy" "10.0000"
   "width" "1920" "height" "1080"
   "shotDate" shot
   "autoImgProcessingResult" "BLURRED"
   "autoImgProcessingStatus" "FINISHED"
   "status" "active" "visibility" "public"
   "imageProcUrl" (str "https://cdn.kartaview.org/pr:sharp/" id)
   "qualityStatus" "FINISHED"})

(defn- payload [photos]
  {"status" {"httpCode" 200 "apiCode" 600}
   "result" {"data" photos "hasMoreData" false}})

(defn- run [photos]
  (let [norm (kv/normalize-payload (payload photos)
                                   {:bbox bbox :retrieved-at run-at})
        prov (kv/provenance {:area-id area-id :bbox bbox
                             :retrieved-at run-at :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    {:table (sc/vintage-table {:observations (:observations norm)
                               :counts (:counts norm)
                               :provenance prov})
     :prov (sc/provenance prov {:run-at run-at})
     :norm norm}))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "street-imagery-vintage-v1" sc/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "kartaview" sc/source-id)))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [r1 (run [(photo "1" "2017-09-09 08:28:31.000")
                 (photo "2" "2021-04-02 12:00:00.000")
                 (photo "3" "2019-01-15 07:30:00.000")])
        r2 (run [(photo "1" "2017-09-09 08:28:31.000")
                 (photo "2" "2021-04-02 12:00:00.000")
                 (photo "3" "2019-01-15 07:30:00.000")])]
    (t/is (= (:table r1) (:table r2)))
    (t/is (= {:earliest-published "2017-09-09 08:28:31.000"
              :latest-published "2021-04-02 12:00:00.000"
              :comparison "lexicographic over the provider's fixed YYYY-MM-DD HH:MM:SS.mmm format; no timezone conversion performed"}
             (:table/capture-span (:table r1))))
    ;; span endpoints are byte-identical to the provider's strings
    (t/is (some #(= "2017-09-09 08:28:31.000" (:observation/capture-time %))
                (:observations (:norm r1))))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-capture-times
  ;; upstream refuses an empty shotDate, so an unknown here is a photo
  ;; whose published string does not match the provider's own format
  (let [r (run [(photo "1" "2017-09-09 08:28:31.000")
                (photo "2" "sometime in 2019")])]
    (t/is (= 1 (:capture-unknown (:table/photos (:table r)))))
    (t/is (= 1 (:capture-known (:table/photos (:table r)))))
    ;; the span uses only known times; the unknown is counted, not dropped
    (t/is (= "2017-09-09 08:28:31.000"
             (:earliest-published (:table/capture-span (:table r)))))))

(t/deftest all-unknown-span
  (let [r (run [(photo "1" "not a date")])]
    (t/is (= :unknown (:table/capture-span (:table r))))
    (t/is (= 1 (:capture-unknown (:table/photos (:table r)))))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest privacy-gate-upstream
  ;; a non-blurred photo never becomes an observation, so it never
  ;; reaches the derived table
  (let [norm (kv/normalize-payload (payload [(assoc (photo "1" "2017-09-09 08:28:31.000")
                                                    "autoImgProcessingResult" "ORIGINAL")])
                                   {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:refused (:counts norm))))
    (t/is (empty? (:observations norm)))
    (let [table (sc/vintage-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance (kv/provenance {:area-id area-id :bbox bbox
                                                               :retrieved-at run-at
                                                               :input-sha256 "x"})})]
      (t/is (= 0 (:accepted (:table/photos table)))))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(photo "1" "2017-09-09 08:28:31.000")])]
    (t/is (str/includes? (:provenance/privacy-note prov) "provider-BLURRED"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table]} (run [(photo "1" "2017-09-09 08:28:31.000")])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "has-more-data=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id
                    (sc/provenance (kv/provenance {:area-id area-id :bbox bbox
                                                   :retrieved-at run-at
                                                   :input-sha256 "x"})
                                   {:run-at run-at}))))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(photo "1" "2017-09-09 08:28:31.000")])]
    (t/is (= "street-imagery-vintage-v1" (:provenance/task-id prov)))
    (t/is (= "kartaview" (:provenance/source-id prov)))
    (t/is (= "deadbeef" (:provenance/content-hash prov)))
    (t/is (= "CC-BY-SA 4.0 (KartaView terms of use)" (:provenance/licence prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    ;; run counts carried through unchanged and visible
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [table]} (run [(photo "1" "2017-09-09 08:28:31.000")
                              (photo "2" "2021-04-02 12:00:00.000")
                              (photo "3" "maybe 2020")])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify (clj->js table)))
        back (js->clj js :keywordize-keys true)]
    (t/is (= "street-imagery-vintage-v1" (:task-id back)))
    (t/is (= "2017-09-09 08:28:31.000" (get-in back [:capture-span :earliest-published])))
    (t/is (= "2021-04-02 12:00:00.000" (get-in back [:capture-span :latest-published])))
    (t/is (= 1 (:capture-unknown (:photos back))))
    (t/is (= 2 (:capture-known (:photos back))))))
