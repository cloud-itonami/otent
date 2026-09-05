(ns otent.street-heading-test
  "Tests for the derived heading/panorama coverage task over Mapillary
  /images metadata. The fixture is SYNTHETIC and clearly labelled as
  such -- no token exists in any secret store (re-verified this run),
  so no live payload is claimed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.mapillary-images :as mimg]
            [otent.street-heading :as sh]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- payload [features]
  {"data" features})

(defn- image [id angle pano]
  (cond-> {"id" id
           "geometry" {"type" "Point" "coordinates" [139.7654 35.6789]}
           "captured_at" 1755100800000
           "is_pano" pano}
    (some? angle) (assoc "compass_angle" angle)))

(defn- run [features]
  (let [norm (mimg/normalize-payload (payload features)
                                     {:bbox bbox :retrieved-at run-at})
        prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at run-at :input-sha256 "deadbeef"
                               :request-url nil})]
    (assert (:ok? norm))
    (let [table (sh/heading-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance prov})]
      {:table table
       :checks (sh/provenance-checks table)
       :prov (sh/provenance prov {:run-at run-at})
       :norm norm})))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "street-imagery-heading-v1" sh/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "mapillary-images" sh/source-id))
  (t/is (= ["N" "NE" "E" "SE" "S" "SW" "W" "NW"] sh/sectors)))

;; ── sector binning ───────────────────────────────────────────────────

(t/deftest sector-binning
  ;; pure integer arithmetic over the provider's own number
  (t/is (= "N" (sh/sector 0)))
  (t/is (= "N" (sh/sector 44.9)))
  (t/is (= "NE" (sh/sector 45)))
  (t/is (= "E" (sh/sector 92.0)))
  (t/is (= "S" (sh/sector 180)))
  (t/is (= "W" (sh/sector 271.5)))
  (t/is (= "W" (sh/sector 300.0)))
  (t/is (= "NW" (sh/sector 330.0)))
  (t/is (= "N" (sh/sector 360)))    ; mod first, so 360 folds to N
  (t/is (= "W" (sh/sector -90)))    ; mod of a negative still bins
  (t/is (= :unknown (sh/sector nil)))
  (t/is (= :unknown (sh/sector "north")))
  (t/is (= :unknown (sh/sector :unknown))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-angle-counted
  ;; upstream turns a missing compass_angle into :unknown; it is
  ;; counted, never dropped, never folded into a sector
  (let [{:keys [table]} (run [(image "1" 271.5 false)
                              (image "2" nil false)])]
    (t/is (= 1 (:heading-known (:table/images table))))
    (t/is (= 1 (:heading-unknown (:table/images table))))
    (t/is (= 2 (:accepted (:table/images table))))
    ;; the histogram uses only known angles
    (t/is (= 1 (get (:table/heading-histogram table) "W")))
    (t/is (= 0 (get (:table/heading-histogram table) "N")))))

(t/deftest all-unknown-histogram
  (let [{:keys [table]} (run [(image "1" nil true)])]
    (t/is (= 0 (:heading-known (:table/images table))))
    (t/is (= 1 (:heading-unknown (:table/images table))))
    ;; every sector zero, but present — zeros are visible, not absent
    (t/is (every? #(zero? %) (vals (:table/heading-histogram table))))))

;; ── panorama counts ─────────────────────────────────────────────────

(t/deftest panorama-counts
  (let [{:keys [table]} (run [(image "1" 90 true)
                              (image "2" 180 true)
                              (image "3" 270 false)])]
    (t/is (= 2 (:panorama (:table/images table))))
    (t/is (= 1 (:non-panorama (:table/images table))))))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [r1 (run [(image "1" 271.5 false)
                 (image "2" 92.0 true)
                 (image "3" 300.0 false)])
        r2 (run [(image "1" 271.5 false)
                 (image "2" 92.0 true)
                 (image "3" 300.0 false)])]
    (t/is (= (:table r1) (:table r2)))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest privacy-gate-upstream
  ;; the field list curates what is copied, but the redaction check is
  ;; the gate behind it: an image whose id carries an email-shaped
  ;; string never becomes an observation, so it never reaches the
  ;; derived table — and no coordinates from it exist downstream
  (let [norm (mimg/normalize-payload
              (payload [(image "someone@example.com" 90.0 false)
                        (image "2" 180.0 false)])
              {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:refused (:counts norm))))
    (t/is (= 1 (count (:observations norm))))
    (let [table (sh/heading-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance (mimg/provenance {:area-id area-id :bbox bbox
                                                                 :retrieved-at run-at
                                                                 :input-sha256 "x"
                                                                 :request-url nil})})]
      (t/is (= 1 (:accepted (:table/images table))))
      (t/is (= 1 (get (:table/heading-histogram table) "S"))))))

(t/deftest redaction-check-direct
  ;; the gate behind the field list, as the upstream ns defines it:
  ;; `true` = clean and shippable, `false` = refused upstream
  (t/is (false? (mimg/redacted? {:observation/note "uploader@example.com"})))
  (t/is (true? (mimg/redacted? {:observation/evidence-url "https://www.mapillary.com/app/?pKey=1"}))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(image "1" 90.0 false)])]
    (t/is (str/includes? (:provenance/privacy-note prov) "gated upstream"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))
    (t/is (str/includes? (:provenance/privacy-note prov) "plate"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table prov]} (run [(image "1" 90.0 false)])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "links-next=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not image content"))
    (t/is (str/includes? (:table/uncertainty-note table) "bearing"))
    ;; a sector is not a statement about any object in the frame
    (t/is (str/includes? (:table/uncertainty-note table) "not a property of any object"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id prov)))
    (t/is (= "street-imagery-heading-v1" (:provenance/task-id prov)))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(image "1" 90.0 false)])]
    (t/is (= "mapillary-images" (:source prov)))
    (t/is (= "deadbeef" (:input-sha256 prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    ;; run counts carried through unchanged and visible
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── readback checks ──────────────────────────────────────────────────

(t/deftest provenance-checks-agree
  (let [{:keys [checks]} (run [(image "1" 271.5 false)
                               (image "2" nil false)
                               (image "3" 90.0 true)])]
    (t/is (:ok? checks))))

(t/deftest provenance-checks-refuse-disagreement
  ;; a table whose own numbers disagree refuses to read back as good
  (let [{:keys [table]} (run [(image "1" 90.0 false)])
        broken (assoc-in table [:table/images :heading-known] 5)]
    (t/is (not (:ok? (sh/provenance-checks broken))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [table]} (run [(image "1" 271.5 false)
                              (image "2" 92.0 true)
                              (image "3" nil false)])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify (clj->js table)))
        back (js->clj js :keywordize-keys true)]
    (t/is (= "street-imagery-heading-v1" (:task-id back)))
    (t/is (= 2 (:heading-known (:images back))))
    (t/is (= 1 (:heading-unknown (:images back))))
    (t/is (= 1 (:panorama (:images back))))
    ;; js->clj keywordization turns the sector strings into keywords
    (t/is (= 1 (get (:heading-histogram back) :W)))
    (t/is (= 1 (get (:heading-histogram back) :E)))
    (t/is (= 0 (get (:heading-histogram back) :N)))))
