(ns otent.mapillary-coverage-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.mapillary-images :as mimg]
            [otent.mapillary-coverage :as mc]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- feature [id ms]
  {"id" id
   "geometry" {"type" "Point" "coordinates" [139.765267 35.680192]}
   "captured_at" ms
   "compass_angle" 297.32
   "is_pano" false})

(defn- payload [features]
  {"data" features "paging" {}})

(defn- run [features]
  (let [norm (mimg/normalize-payload (payload features)
                                     {:bbox bbox :retrieved-at run-at})
        prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at run-at
                               :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    {:table (mc/vintage-table {:observations (:observations norm)
                               :counts (:counts norm)
                               :provenance prov})
     :prov (mc/provenance prov {:run-at run-at})
     :norm norm}))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "mapillary-street-vintage-v1" mc/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "mapillary-images" mc/source-id)))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [r1 (run [(feature "1" 1504940911000)
                 (feature "2" 1617364800000)
                 (feature "3" 1504940924000)])
        r2 (run [(feature "1" 1504940911000)
                 (feature "2" 1617364800000)
                 (feature "3" 1504940924000)])]
    (t/is (= (:table r1) (:table r2)))
    (t/is (= {:earliest-captured-ms 1504940911000
              :latest-captured-ms 1617364800000
              :comparison "numeric comparison over the provider's own epoch milliseconds (captured_at); endpoints are the published values, no timezone conversion, no calendar string derived"}
             (:table/capture-span (:table r1))))
    ;; span endpoints are byte-identical to the provider's numbers
    (t/is (some #(= 1504940911000 (:observation/capture-time-ms %))
                (:observations (:norm r1))))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-capture-times
  ;; upstream REFUSES a non-numeric captured_at (missing-capture-time),
  ;; so through the live gate an unknown never reaches the table -- the
  ;; refusal is counted, the image is not folded into the span
  (let [norm (mimg/normalize-payload (payload [(feature "1" 1504940911000)
                                               (feature "2" "sometime in 2021")])
                                     {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:accepted (:counts norm))))
    (t/is (= 1 (:refused (:counts norm))))
    ;; the gate is the boundary; the derived task never re-admits
    (let [table (mc/vintage-table {:observations (:observations norm)
                                   :counts (:counts norm)
                                   :provenance (mimg/provenance {:area-id area-id :bbox bbox
                                                                 :retrieved-at run-at
                                                                 :input-sha256 "x"})})]
      (t/is (= 0 (:capture-unknown (:table/images table))))
      (t/is (= 1504940911000
               (:earliest-captured-ms (:table/capture-span table)))))))

(t/deftest unknown-stays-visible-in-table
  ;; directly at the derived-task boundary (independent of the gate):
  ;; an observation without a numeric capture time is counted
  ;; capture-unknown, never dropped, never folded into the span
  (let [obs [{:observation/capture-time-ms 1504940911000}
             {:observation/capture-time-ms "sometime in 2021"}]
        table (mc/vintage-table {:observations obs
                                 :counts {:fetched 2 :accepted 2 :refused 0
                                          :returned-outside-bbox 0 :links-next false}
                                 :provenance {:area-id area-id :bbox bbox}})]
    (t/is (= 1 (:capture-unknown (:table/images table))))
    (t/is (= 1 (:capture-known (:table/images table))))
    (t/is (= 1504940911000
             (:earliest-captured-ms (:table/capture-span table))))))

(t/deftest all-unknown-span
  (let [table (mc/vintage-table {:observations [{:observation/capture-time-ms "not a number"}]
                                 :counts {:fetched 1 :accepted 1 :refused 0
                                          :returned-outside-bbox 0 :links-next false}
                                 :provenance {:area-id area-id :bbox bbox}})]
    (t/is (= :unknown (:table/capture-span table)))
    (t/is (= 1 (:capture-unknown (:table/images table))))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest metadata-only-upstream
  ;; the observation carries no pixel URL at all: no thumb field ever
  ;; exists, so no derived table can leak one
  (let [{:keys [norm table]} (run [(feature "1" 1504940911000)])]
    (t/is (= 1 (:accepted (:table/images table))))
    ;; no observation carries any pixel/thumbnail URL key or value
    (let [ks (distinct (mapcat keys (:observations norm)))]
      (t/is (not-any? #(str/includes? (str %) "thumb") ks))
      (t/is (not-any? #(str/includes? (str %) "url") (remove #{:observation/evidence-url} ks))))
    ;; upstream blur story carried as NOT verified, not asserted
    (t/is (every? #(false? (:observation/provider-blur-verified %))
                  (:observations norm)))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(feature "1" 1504940911000)])]
    (t/is (str/includes? (:provenance/privacy-note prov) "metadata only"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table prov]} (run [(feature "1" 1504940911000)])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "paging-next=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id prov)))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(feature "1" 1504940911000)])]
    (t/is (= "mapillary-street-vintage-v1" (:provenance/task-id prov)))
    (t/is (= "mapillary-images" (:source prov)))
    (t/is (= "deadbeef" (:input-sha256 prov)))
    (t/is (= "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)"
             (:licence prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    ;; run counts carried through unchanged and visible
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [norm table]} (run [(feature "1" 1504940911000)
                                   (feature "2" 1617364800000)
                                   (feature "3" 1617364900000)])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify
                           (clj->js {:derived-table table
                                     :counts (:counts norm)
                                     :observations (:observations norm)})))
        back (js->clj js :keywordize-keys false)]
    (t/is (= "mapillary-street-vintage-v1" (get-in back ["derived-table" "task-id"])))
    (t/is (= 1504940911000 (get-in back ["derived-table" "capture-span" "earliest-captured-ms"])))
    (t/is (= 1617364900000 (get-in back ["derived-table" "capture-span" "latest-captured-ms"])))
    (t/is (= 0 (get-in back ["derived-table" "images" "capture-unknown"])))
    (t/is (= 3 (get-in back ["derived-table" "images" "capture-known"])))
    ;; the stored document passes its own readback check
    (t/is (= {:ok? true} (mc/provenance-checks back)))))

(t/deftest provenance-checks-refuse
  ;; a table whose accepted count disagrees with its observations refuses
  (let [{:keys [norm table]} (run [(feature "1" 1504940911000)])
        bad (js->clj (clj->js {:counts (:counts norm)
                               :observations (:observations norm)
                               :derived-table (assoc-in table [:table/images :accepted] 9)}))
        ;; and a span outside the stored times refuses too
        spanned (js->clj (clj->js {:counts (:counts norm)
                                   :observations (:observations norm)
                                   :derived-table (assoc-in table [:table/capture-span :earliest-captured-ms] 1)}))]
    (t/is (= :provenance/counts-disagree (:error (mc/provenance-checks bad))))
    (t/is (= :provenance/span-disagrees (:error (mc/provenance-checks spanned))))))
