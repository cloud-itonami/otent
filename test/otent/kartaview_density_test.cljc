(ns otent.kartaview-density-test
  "Tests for the derived spatial-density (per-cell grid) task over
  KartaView open-data photo search metadata. The fixture is SYNTHETIC —
  the anonymous endpoint needs no credential, but no live payload is
  claimed here."
  (:require [kotoba.lang.text :as str]
            [clojure.test :as t]
            [otent.kartaview :as kv]
            [otent.kartaview-density :as kvd]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- photo [id lat lng heading]
  (cond-> {"id" id "sequenceId" "583739" "sequenceIndex" "1"
           "lat" (str lat) "lng" (str lng)
           "gpsAccuracy" "10.0000"
           "width" "1920" "height" "1080"
           "shotDate" "2017-09-09 08:28:31.000"
           "autoImgProcessingResult" "BLURRED"
           "autoImgProcessingStatus" "FINISHED"
           "status" "active" "visibility" "public"
           "imageProcUrl" (str "https://cdn.kartaview.org/pr:sharp/" id)
           "qualityStatus" "FINISHED"}
    (some? heading) (assoc "heading" heading)))

(defn- payload [photos]
  {"status" {"httpCode" 200 "apiCode" 600}
   "result" {"data" photos "hasMoreData" false}})

(defn- run [photos]
  (let [norm (kv/normalize-payload (payload photos)
                                   {:bbox bbox :retrieved-at run-at})
        prov (kv/provenance {:area-id area-id :bbox bbox
                             :retrieved-at run-at :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    (let [table (kvd/density-table {:observations (:observations norm)
                                    :counts (:counts norm)
                                    :provenance prov})]
      {:table table
       :prov (kvd/provenance prov {:run-at run-at})
       :norm norm})))

(defn- doc-of
  "The stored-document shape (plain string keys, as clj->js serializes)
  that provenance-checks reads."
  [{:keys [table norm]}]
  {"observations" (mapv #(dissoc % :raw) (:observations norm))
   "derived-table" (js->clj (clj->js {:task-id (get table :table/task-id)
                                      :pictures (get table :table/pictures)
                                      :cells (get table :table/cells)}))})

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "kartaview-street-density-v1" kvd/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "kartaview" kvd/source-id)))

;; ── the grid is a pure function of the declared bbox ─────────────────

(t/deftest grid-shape-deterministic
  ;; 0.005 x 0.004 deg -> ceil(0.005/0.0025)=2 x ceil(0.004/0.0025)=2
  (let [s (kvd/grid-shape bbox)]
    (t/is (= {:nx 2 :ny 2} (select-keys s [:nx :ny])))
    (t/is (= 3 (count (:edges-x s))))
    ;; the same bbox always gives the same grid
    (t/is (= s (kvd/grid-shape bbox)))))

;; ── deterministic binning; explicit zeros kept; unknowns visible ─────

(t/deftest deterministic-and-binned
  ;; two pictures in the SW cell, one in the NE; heading 198 numeric,
  ;; heading 90 numeric; third item heading nil -> heading-unknown but
  ;; still counted; every item carries a sequence id
  (let [r (run [(photo "1" 35.6785 139.7655 198)
                (photo "2" 35.6789 139.7659 90)
                (photo "3" 35.6815 139.7695 nil)])
        table (:table r)
        cells (:table/cells table)
        sw (first cells)
        ne (nth cells 3)]
    ;; identical input -> identical table
    (t/is (= table (:table (run [(photo "1" 35.6785 139.7655 198)
                                 (photo "2" 35.6789 139.7659 90)
                                 (photo "3" 35.6815 139.7695 nil)]))))
    (t/is (= 2 (:pictures sw)))
    (t/is (= 2 (:heading-known sw)))
    (t/is (= 2 (:sequence-known sw)))
    ;; the NE cell counts the unknown-heading item; it is not dropped
    (t/is (= 1 (:pictures ne)))
    (t/is (= 1 (:heading-unknown ne)))
    ;; the fixture item carries a sequence id, so the sequence is known
    (t/is (= 1 (:sequence-known ne)))
    ;; the two empty cells stay visible as explicit zeros
    (t/is (every? #(zero? (:pictures %)) (take 2 (drop 1 cells))))
    (t/is (= 4 (count cells)))
    (t/is (= {:accepted 3 :placed 3 :unplaceable 0} (:table/pictures table)))))

(t/deftest empty-page-is-explicit-zeros
  (let [r (run [])
        cells (:table/cells (:table r))]
    (t/is (= 4 (count cells)))
    (t/is (every? #(zero? (:pictures %)) cells))
    (t/is (= :lower-bound (:table/coverage-bound (:table r))))
    (t/is (str/includes? (:table/coverage-bound-note (:table r))
                         "has-more-data=false"))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest privacy-gate-upstream
  ;; a non-blurred photo never becomes an observation, so it never
  ;; reaches the derived table
  (let [norm (kv/normalize-payload
              (payload [(assoc (photo "1" 35.6785 139.7655 90)
                               "autoImgProcessingResult" "ORIGINAL")])
              {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:refused (:counts norm))))
    (t/is (empty? (:observations norm)))
    (let [table (kvd/density-table {:observations (:observations norm)
                                    :counts (:counts norm)
                                    :provenance (kv/provenance {:area-id area-id :bbox bbox
                                                                :retrieved-at run-at
                                                                :input-sha256 "x"})})]
      (t/is (= 0 (:accepted (:table/pictures table)))))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(photo "1" 35.6785 139.7655 90)])]
    (t/is (str/includes? (:provenance/privacy-note prov) "gated upstream"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))
    (t/is (str/includes? (:provenance/privacy-note prov) "plate"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table prov]} (run [(photo "1" 35.6785 139.7655 90)])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "has-more-data=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    (t/is (str/includes? (:table/uncertainty-note table) "gpsAccuracy")))
  ;; no model is involved
  (let [{:keys [prov]} (run [(photo "1" 35.6785 139.7655 90)])]
    (t/is (= :none (:provenance/model-id prov)))
    (t/is (= "kartaview-street-density-v1" (:provenance/task-id prov)))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(photo "1" 35.6785 139.7655 90)])]
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
  (let [r (run [(photo "1" 35.6785 139.7655 271.5)
                (photo "2" 35.6815 139.7695 92.0)
                (photo "3" 35.6790 139.7670 nil)])]
    (t/is (= {:ok? true} (kvd/provenance-checks (doc-of r))))))

(t/deftest provenance-checks-refuse-disagreement
  ;; a table whose own numbers disagree refuses to read back as good
  (let [r (run [(photo "1" 35.6785 139.7655 90)])
        broken (assoc-in (doc-of r) ["derived-table" "pictures" "placed"] 99)]
    (t/is (= :provenance/counts-disagree (:error (kvd/provenance-checks broken))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [table]} (run [(photo "1" 35.6785 139.7655 271.5)
                              (photo "2" 35.6815 139.7695 92.0)
                              (photo "3" 35.6790 139.7670 nil)])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify (clj->js table)))
        back (js->clj js :keywordize-keys true)
        cell-sum (reduce + 0 (map #(get % :pictures) (:cells back)))]
    (t/is (= "kartaview-street-density-v1" (:task-id back)))
    (t/is (= 3 (:placed (:pictures back))))
    (t/is (= 3 (:accepted (:pictures back))))
    ;; per-cell heading accounting survives the round-trip
    (t/is (= 3 cell-sum))
    (t/is (= 2 (reduce + 0 (map #(get % :heading-known) (:cells back)))))
    (t/is (= 1 (reduce + 0 (map #(get % :heading-unknown) (:cells back)))))
    (t/is (= 4 (:cell-count (:grid back))))))

;; ── spatial-uncertainty surfaces ─────────────────────────────────────

(t/deftest spatial-uncertainty-carried
  ;; the table does not assert spatial error; the note points at the
  ;; provider gpsAccuracy which each observation already carries
  (let [{:keys [table]} (run [(photo "1" 35.6785 139.7655 90)])
        obs (get-in table [:table/run-counts])]
    (t/is (= 1 (:accepted obs)))
    (t/is (str/includes? (:table/uncertainty-note table) "not a guaranteed error bound"))))