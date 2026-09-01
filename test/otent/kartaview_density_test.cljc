(ns otent.kartaview-density-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.kartaview :as kv]
            [otent.kartaview-density :as kvd]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-02T00:00:00Z")

(defn- photo [id lat lng heading seq-id]
  {"id" id "sequenceId" seq-id "sequenceIndex" "1"
   "lat" lat "lng" lng
   "heading" heading "projection" "PLANE" "gpsAccuracy" "10.0000"
   "width" "1920" "height" "1080"
   "shotDate" "2017-09-09 08:28:31.000"
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
    {:table (kvd/density-table {:observations (:observations norm)
                                :counts (:counts norm)
                                :provenance prov})
     :prov (kvd/provenance prov {:run-at run-at})
     :norm norm}))

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
  ;; two photos in the SW cell, one in the NE; heading 297.32 numeric;
  ;; third photo heading "n/a" -> heading-unknown but still counted
  (let [r (run [(photo "1" "35.6785" "139.7655" "297.32" "583739")
                (photo "2" "35.6789" "139.7659" "90" "583739")
                (photo "3" "35.6815" "139.7695" "n/a" "")])
        table (:table r)
        cells (:table/cells table)
        sw (first cells)
        ne (nth cells 3)]
    ;; identical input -> identical table
    (t/is (= table (:table (run [(photo "1" "35.6785" "139.7655" "297.32" "583739")
                                 (photo "2" "35.6789" "139.7659" "90" "583739")
                                 (photo "3" "35.6815" "139.7695" "n/a" "")]))))
    (t/is (= 2 (:photos sw)))
    (t/is (= 2 (:heading-known sw)))
    (t/is (= 2 (:sequence-known sw)))
    ;; the NE cell counts the unknown-heading photo; it is not dropped
    (t/is (= 1 (:photos ne)))
    (t/is (= 1 (:heading-unknown ne)))
    (t/is (= 0 (:sequence-known ne)))
    ;; the two empty cells stay visible as explicit zeros
    (t/is (every? #(zero? (:photos %)) (take 2 (drop 1 cells))))
    (t/is (= 4 (count cells)))
    (t/is (= {:accepted 3 :placed 3 :unplaceable 0} (:table/photos table)))))

(t/deftest empty-page-is-explicit-zeros
  (let [r (run [])
        cells (:table/cells (:table r))]
    (t/is (= 4 (count cells)))
    (t/is (every? #(zero? (:photos %)) cells))
    (t/is (= :lower-bound (:table/coverage-bound (:table r))))
    (t/is (str/includes? (:table/coverage-bound-note (:table r))
                         "has-more-data=false"))))

;; ── provenance: no model, stated privacy, checks that agree ──────────

(t/deftest provenance-shape
  (let [r (run [(photo "1" "35.6785" "139.7655" "297.32" "583739")])]
    (t/is (= :none (:provenance/model-id (:prov r))))
    (t/is (= "kartaview-street-density-v1" (:provenance/task-id (:prov r))))
    (t/is (some? (:provenance/privacy-note (:prov r))))
    ;; bbox comes back through the upstream provenance parameters
    (t/is (= bbox (:table/bbox (:table r))))
    (t/is (= area-id (:table/area-id (:table r))))))

(t/deftest provenance-checks-agree
  (let [r (run [(photo "1" "35.6785" "139.7655" "297.32" "583739")
                (photo "2" "35.6815" "139.7695" "90" "583739")])
        doc {"observations" (mapv #(dissoc % :raw) (:observations (:norm r)))
             ;; the stored document serializes with plain keys, the
             ;; way the bin script's clj->js does
             "derived-table" (js->clj (clj->js {:task-id (get (:table r) :table/task-id)
                                                :photos (get (:table r) :table/photos)
                                                :cells (get (:table r) :table/cells)}))}]
    (t/is (= {:ok? true} (kvd/provenance-checks doc)))
    ;; a tampered count must refuse, not pass
    (let [bad (assoc-in doc ["derived-table" "photos" "placed"] 99)]
      (t/is (= :provenance/counts-disagree (:error (kvd/provenance-checks bad)))))))
