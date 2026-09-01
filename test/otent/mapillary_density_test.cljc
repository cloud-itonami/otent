(ns otent.mapillary-density-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.mapillary-images :as mimg]
            [otent.mapillary-density :as md]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- feature [id lon lat & {:keys [ms compass pano]}]
  {"id" id
   "geometry" {"type" "Point" "coordinates" [lon lat]}
   "captured_at" (or ms 1504940911000)
   "compass_angle" compass
   "is_pano" (boolean pano)})

(defn- payload [features]
  {"data" features "paging" {}})

(defn- run [features]
  (let [norm (mimg/normalize-payload (payload features)
                                     {:bbox bbox :retrieved-at run-at})
        prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at run-at
                               :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    {:table (md/density-table {:observations (:observations norm)
                               :counts (:counts norm)
                               :provenance prov})
     :prov (md/provenance prov {:run-at run-at})
     :norm norm}))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "mapillary-street-density-v1" md/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "mapillary-images" md/source-id)))

;; ── grid shape is a pure function of the bbox ────────────────────────

(t/deftest grid-shape-deterministic
  (let [s (md/grid-shape bbox)]
    ;; span 0.005 x 0.004 deg at 0.0025 target -> 2 x 2 cells
    (t/is (= {:nx 2 :ny 2} (select-keys s [:nx :ny])))
    (t/is (= 3 (count (:edges-x s))))
    (t/is (= 139.765 (first (:edges-x s))))
    (t/is (= 139.77 (last (:edges-x s))))
    (t/is (= 35.678 (first (:edges-y s))))
    (t/is (= 35.682 (last (:edges-y s))))
    ;; the same bbox always produces the same grid
    (t/is (= s (md/grid-shape bbox)))))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [fs [(feature "1" 139.7655 35.6785)
            (feature "2" 139.7660 35.6790 :ms 1617364800000)
            (feature "3" 139.7653 35.6782 :ms 1504940924000)]
        r1 (run fs)
        r2 (run fs)]
    (t/is (= (:table r1) (:table r2)))
    (t/is (= 3 (get-in r1 [:table :table/images :placed])))
    (t/is (= 3 (reduce + (map :images (:table/cells (:table r1))))))))

;; ── per-cell binning, empty cells visible as explicit zeros ──────────

(t/deftest cell-binning
  ;; two points in the SW cell, one in the NE; the other two cells stay
  ;; visible as explicit zeros
  (let [{:keys [table]} (run [(feature "1" 139.7653 35.6783)
                              (feature "2" 139.7656 35.6786)
                              (feature "3" 139.7698 35.6818)])]
    (t/is (= 4 (get-in table [:table/grid :cell-count])))
    (let [cells (into {} (map (fn [c] [(:cell-index c) c])
                              (:table/cells table)))]
      (t/is (= 2 (:images (cells [0 0]))))
      (t/is (= 1 (:images (cells [1 1]))))
      ;; empty cells stay in the table as zeros — never omitted
      (t/is (= 0 (:images (cells [1 0]))))
      (t/is (= 0 (:images (cells [0 1]))))
      ;; every cell carries bounds inside the declared bbox
      (doseq [c (:table/cells table)]
        (let [[w s e n] (:bounds c)]
          (t/is (<= 139.765 w e 139.77))
          (t/is (<= 35.678 s n 35.682)))))))

;; ── coordinate order: swapped points are refused upstream, never binned

(t/deftest coordinate-order-refused
  ;; the synthetic fixture's known swapped point (lat,lon in the lon
  ;; slot) is a REFUSAL at the upstream gate: it never becomes a
  ;; coordinate, never lands in a cell, and stays visible in the counts
  (let [norm (mimg/normalize-payload
              (payload [(feature "1" 139.7653 35.6783)
                        {"id" "swapped"
                         "geometry" {"type" "Point"
                                     "coordinates" [35.6801 139.7668]}
                         "captured_at" 1755100920000
                         "compass_angle" 45.0
                         "is_pano" false}])
              {:bbox bbox :retrieved-at run-at})
        prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at run-at :input-sha256 "x"})
        table (md/density-table {:observations (:observations norm)
                                 :counts (:counts norm)
                                 :provenance prov})]
    (t/is (= 1 (:accepted (:counts norm))))
    (t/is (= 1 (:refused (:counts norm))))
    ;; and the derived table only ever saw the admitted point
    (t/is (= 1 (get-in table [:table/images :placed])))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-compass-stays-visible
  ;; an image without a numeric compass_angle is still counted in its
  ;; cell, as compass-unknown — never dropped, never turned into a guess
  (let [{:keys [table]} (run [(feature "1" 139.7653 35.6783 :compass 297.5)
                              (feature "2" 139.7656 35.6786)])]
    (let [cells (into {} (map (fn [c] [(:cell-index c) c])
                              (:table/cells table)))]
      (t/is (= 1 (:compass-known (cells [0 0]))))
      (t/is (= 1 (:compass-unknown (cells [0 0]))))
      (t/is (= 2 (:images (cells [0 0])))))))

(t/deftest panorama-counted-not-inferred
  ;; is_pano is the provider's own boolean, carried and counted as
  ;; published — not a property of anything the image depicts
  (let [{:keys [table]} (run [(feature "1" 139.7653 35.6783 :pano true)
                              (feature "2" 139.7656 35.6786)])]
    (let [cells (into {} (map (fn [c] [(:cell-index c) c])
                              (:table/cells table)))]
      (t/is (= 1 (:panoramas (cells [0 0]))))
      (t/is (= 2 (:images (cells [0 0])))))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest metadata-only-upstream
  (let [{:keys [norm table]} (run [(feature "1" 139.7653 35.6783)])]
    (t/is (= 1 (get-in table [:table/images :placed])))
    ;; no observation carries any pixel/thumbnail URL key
    (let [ks (distinct (mapcat keys (:observations norm)))]
      (t/is (not-any? #(str/includes? (str %) "thumb") ks)))
    (t/is (every? #(false? (:observation/provider-blur-verified %))
                  (:observations norm)))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(feature "1" 139.7653 35.6783)])]
    (t/is (str/includes? (:provenance/privacy-note prov) "metadata only"))
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table prov]} (run [(feature "1" 139.7653 35.6783)])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "paging-next=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id prov)))
    ;; spatial uncertainty inherited from the provider, stated
    (t/is (str/includes? (:table/uncertainty-note table)
                         "no per-image spatial-error figure"))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(feature "1" 139.7653 35.6783)])]
    (t/is (= "mapillary-street-density-v1" (:provenance/task-id prov)))
    (t/is (= "mapillary-images" (:source prov)))
    (t/is (= "deadbeef" (:input-sha256 prov)))
    (t/is (= "CC-BY-SA-4.0 (Mapillary contributor imagery, per Mapillary Terms of Service)"
             (:licence prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [norm table]} (run [(feature "1" 139.7653 35.6783)
                                   (feature "2" 139.7698 35.6818)
                                   (feature "3" 139.7699 35.6819)])
        js (js/JSON.parse (js/JSON.stringify
                           (clj->js {:derived-table table
                                     :counts (:counts norm)
                                     :observations (:observations norm)})))
        back (js->clj js :keywordize-keys false)]
    (t/is (= "mapillary-street-density-v1" (get-in back ["derived-table" "task-id"])))
    (t/is (= 4 (get-in back ["derived-table" "grid" "cell-count"])))
    (t/is (= 3 (get-in back ["derived-table" "images" "placed"])))
    (t/is (= 0 (get-in back ["derived-table" "images" "unplaceable"])))
    ;; the stored document passes its own readback check
    (t/is (= {:ok? true} (md/provenance-checks back)))))

(t/deftest provenance-checks-refuse
  ;; a table whose placed count disagrees with its observations refuses
  (let [{:keys [norm table]} (run [(feature "1" 139.7653 35.6783)])
        bad (js->clj (clj->js {:counts (:counts norm)
                               :observations (:observations norm)
                               :derived-table (assoc-in table [:table/images :placed] 9)}))
        ;; and a cell sum that disagrees with the placed count refuses
        sum (js->clj (clj->js {:counts (:counts norm)
                               :observations (:observations norm)
                               :derived-table (assoc-in table [:table/cells 0 :images] 7)}))]
    (t/is (= :provenance/counts-disagree (:error (md/provenance-checks bad))))
    (t/is (= :provenance/counts-disagree (:error (md/provenance-checks sum))))))

(t/deftest unplaceable-stays-visible
  ;; a point outside the declared bbox that somehow reached the derived
  ;; task would be counted unplaceable — visible, never folded into a
  ;; neighbouring cell
  (let [table (md/density-table
               {:observations [{:observation/lon 139.9 :observation/lat 35.6
                                :observation/is-panorama false
                                :observation/compass-angle-deg 90}]
                :counts {:fetched 1 :accepted 1 :refused 0
                         :returned-outside-bbox 0 :links-next false}
                :provenance {:area-id area-id :bbox bbox}})]
    (t/is (= 1 (get-in table [:table/images :unplaceable])))
    (t/is (= 0 (get-in table [:table/images :placed])))
    (t/is (every? #(zero? (:images %)) (:table/cells table)))))
