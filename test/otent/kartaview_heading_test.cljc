(ns otent.kartaview-heading-test
  "Tests for the derived heading/projection coverage task over KartaView
  open-data photo search metadata. The fixture is SYNTHETIC — the
  anonymous endpoint needs no credential, but no live payload is
  claimed here."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.kartaview :as kv]
            [otent.kartaview-heading :as kh]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- photo [id heading projection]
  (cond-> {"id" id "sequenceId" "583739" "sequenceIndex" "1"
           "lat" "35.680192" "lng" "139.765267"
           "gpsAccuracy" "10.0000"
           "width" "1920" "height" "1080"
           "shotDate" "2017-09-09 08:28:31.000"
           "autoImgProcessingResult" "BLURRED"
           "autoImgProcessingStatus" "FINISHED"
           "status" "active" "visibility" "public"
           "imageProcUrl" (str "https://cdn.kartaview.org/pr:sharp/" id)
           "qualityStatus" "FINISHED"}
    (some? heading) (assoc "heading" heading)
    (some? projection) (assoc "projection" projection)))

(defn- payload [photos]
  {"status" {"httpCode" 200 "apiCode" 600}
   "result" {"data" photos "hasMoreData" false}})

(defn- run [photos]
  (let [norm (kv/normalize-payload (payload photos)
                                   {:bbox bbox :retrieved-at run-at})
        prov (kv/provenance {:area-id area-id :bbox bbox
                             :retrieved-at run-at :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    (let [table (kh/heading-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance prov})]
      {:table table
       :checks (kh/provenance-checks table)
       :prov (kh/provenance prov {:run-at run-at})
       :norm norm})))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "kartaview-imagery-heading-v1" kh/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "kartaview" kh/source-id))
  (t/is (= ["N" "NE" "E" "SE" "S" "SW" "W" "NW"] kh/sectors)))

;; ── sector binning ───────────────────────────────────────────────────

(t/deftest sector-binning
  ;; pure integer arithmetic over the provider's own number
  (t/is (= "N" (kh/sector 0)))
  (t/is (= "N" (kh/sector 44.9)))
  (t/is (= "NE" (kh/sector 45)))
  (t/is (= "E" (kh/sector 92.0)))
  (t/is (= "S" (kh/sector 180)))
  (t/is (= "W" (kh/sector 271.5)))
  (t/is (= "W" (kh/sector 300.0)))
  (t/is (= "NW" (kh/sector 330.0)))
  (t/is (= "N" (kh/sector 360)))    ; mod first, so 360 folds to N
  (t/is (= "W" (kh/sector -90)))    ; mod of a negative still bins
  (t/is (= :unknown (kh/sector nil)))
  (t/is (= :unknown (kh/sector "north")))
  (t/is (= :unknown (kh/sector :unknown))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-heading-counted
  ;; upstream turns a missing or non-numeric heading into :unknown in
  ;; the orientation map; it is counted, never dropped, never folded
  (let [{:keys [table]} (run [(photo "1" "271.5" "PLANE")
                              (photo "2" nil "PLANE")
                              (photo "3" "not a number" "PLANE")])]
    (t/is (= 1 (:heading-known (:table/photos table))))
    (t/is (= 2 (:heading-unknown (:table/photos table))))
    (t/is (= 3 (:accepted (:table/photos table))))
    ;; the histogram uses only known angles
    (t/is (= 1 (get (:table/heading-histogram table) "W")))
    (t/is (= 0 (get (:table/heading-histogram table) "N")))))

(t/deftest all-unknown-histogram
  (let [{:keys [table]} (run [(photo "1" nil "PLANE")])]
    (t/is (= 0 (:heading-known (:table/photos table))))
    (t/is (= 1 (:heading-unknown (:table/photos table))))
    ;; every sector zero, but present — zeros are visible, not absent
    (t/is (every? #(zero? %) (vals (:table/heading-histogram table))))))

;; ── projection counts (no is-pano flag: projection is carried verbatim)

(t/deftest projection-counts
  (let [{:keys [table]} (run [(photo "1" "90" "PLANE")
                              (photo "2" "180" "PLANE")
                              (photo "3" "270" nil)])]
    (t/is (= 2 (:projection-known (:table/photos table))))
    (t/is (= 1 (:projection-unknown (:table/photos table))))
    ;; the projection is the provider's own string, counted verbatim
    (t/is (= {"PLANE" 2 nil 1} (:table/projections table)))))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [r1 (run [(photo "1" "271.5" "PLANE")
                 (photo "2" "92.0" "PLANE")
                 (photo "3" "300.0" "SPHERE")])
        r2 (run [(photo "1" "271.5" "PLANE")
                 (photo "2" "92.0" "PLANE")
                 (photo "3" "300.0" "SPHERE")])]
    (t/is (= (:table r1) (:table r2)))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest privacy-gate-upstream
  ;; a non-blurred photo never becomes an observation, so it never
  ;; reaches the derived table
  (let [norm (kv/normalize-payload
              (payload [(assoc (photo "1" "90" "PLANE")
                               "autoImgProcessingResult" "ORIGINAL")])
              {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:refused (:counts norm))))
    (t/is (empty? (:observations norm)))
    (let [table (kh/heading-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance (kv/provenance {:area-id area-id :bbox bbox
                                                               :retrieved-at run-at
                                                               :input-sha256 "x"})})]
      (t/is (= 0 (:accepted (:table/photos table)))))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(photo "1" "90" "PLANE")])]
    (t/is (str/includes? (:provenance/privacy-note prov) "gated upstream"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))
    (t/is (str/includes? (:provenance/privacy-note prov) "plate"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table prov]} (run [(photo "1" "90" "PLANE")])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "has-more-data=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not image content"))
    (t/is (str/includes? (:table/uncertainty-note table) "bearing"))
    ;; a sector is not a statement about any object in the frame
    (t/is (str/includes? (:table/uncertainty-note table) "not a property of any object"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id prov)))
    (t/is (= "kartaview-imagery-heading-v1" (:provenance/task-id prov)))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(photo "1" "90" "PLANE")])]
    (t/is (= "kartaview" (:provenance/source-id prov)))
    (t/is (= "deadbeef" (:provenance/content-hash prov)))
    (t/is (= "CC-BY-SA 4.0 (KartaView terms of use)" (:provenance/licence prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    ;; run counts carried through unchanged and visible
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── readback checks ──────────────────────────────────────────────────

(t/deftest provenance-checks-agree
  (let [{:keys [checks]} (run [(photo "1" "271.5" "PLANE")
                               (photo "2" nil "PLANE")
                               (photo "3" "90" "SPHERE")])]
    (t/is (:ok? checks))))

(t/deftest provenance-checks-refuse-disagreement
  ;; a table whose own numbers disagree refuses to read back as good
  (let [{:keys [table]} (run [(photo "1" "90" "PLANE")])
        broken (assoc-in table [:table/photos :heading-known] 5)]
    (t/is (not (:ok? (kh/provenance-checks broken))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [table]} (run [(photo "1" "271.5" "PLANE")
                              (photo "2" "92.0" "PLANE")
                              (photo "3" nil nil)])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify (clj->js table)))
        back (js->clj js :keywordize-keys true)]
    (t/is (= "kartaview-imagery-heading-v1" (:task-id back)))
    (t/is (= 2 (:heading-known (:photos back))))
    (t/is (= 1 (:heading-unknown (:photos back))))
    (t/is (= 2 (:projection-known (:photos back))))
    (t/is (= 1 (:projection-unknown (:photos back))))
    ;; js->clj keywordization turns the sector strings into keywords
    (t/is (= 1 (get (:heading-histogram back) :W)))
    (t/is (= 1 (get (:heading-histogram back) :E)))
    (t/is (= 0 (get (:heading-histogram back) :N)))))
