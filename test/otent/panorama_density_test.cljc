(ns otent.panorama-density-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.panoramax :as px]
            [otent.panorama-density :as pxd]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-02T00:00:00Z")

(defn- item [id lat lng azimuth]
  {"type" "Feature"
   "id" id
   "links" [{"rel" "license" "href" "https://creativecommons.org/licenses/by-sa/4.0/"}
            {"rel" "self" "href" (str "https://api.panoramax.xyz/api/collections/abc/items/" id)
             "type" "application/geo+json"}]
   "assets" {"sd" {"href" (str "https://panoramax.openstreetmap.fr/derivates/" id "/sd.jpg")}}
   "geometry" {"type" "Point" "coordinates" [lng lat]}
   "properties" {"datetime" "2016-10-13T13:13:11.066709+00:00"
                 "license" "CC-BY-SA-4.0"
                 "geovisio:status" "ready"
                 "geovisio:visibility" "anyone"
                 "geovisio:rank_in_collection" 2
                 "collection" "2075995f-a707-476e-9065-4956504f66aa"
                 "view:azimuth" azimuth
                 "quality:horizontal_accuracy" 5.0}})

(defn- payload [items]
  {"type" "FeatureCollection" "features" items "links" []})

(defn- run [items]
  (let [norm (px/normalize-payload (payload items)
                                   {:bbox bbox :retrieved-at run-at})
        prov (px/provenance {:area-id area-id :bbox bbox
                             :retrieved-at run-at :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    {:table (pxd/density-table {:observations (:observations norm)
                                :counts (:counts norm)
                                :provenance prov})
     :prov (pxd/provenance prov {:run-at run-at})
     :norm norm}))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "panoramax-street-density-v1" pxd/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "panoramax" pxd/source-id)))

;; ── the grid is a pure function of the declared bbox ─────────────────

(t/deftest grid-shape-deterministic
  ;; 0.005 x 0.004 deg -> ceil(0.005/0.0025)=2 x ceil(0.004/0.0025)=2
  (let [s (pxd/grid-shape bbox)]
    (t/is (= {:nx 2 :ny 2} (select-keys s [:nx :ny])))
    (t/is (= 3 (count (:edges-x s))))
    ;; the same bbox always gives the same grid
    (t/is (= s (pxd/grid-shape bbox)))))

;; ── deterministic binning; explicit zeros kept; unknowns visible ─────

(t/deftest deterministic-and-binned
  ;; two pictures in the SW cell, one in the NE; view:azimuth 198 numeric;
  ;; third item azimuth null -> heading-unknown but still counted
  (let [r (run [(item "i1" 35.6785 139.7655 198)
                (item "i2" 35.6789 139.7659 90)
                (item "i3" 35.6815 139.7695 nil)])
        table (:table r)
        cells (:table/cells table)
        sw (first cells)
        ne (nth cells 3)]
    ;; identical input -> identical table
    (t/is (= table (:table (run [(item "i1" 35.6785 139.7655 198)
                                 (item "i2" 35.6789 139.7659 90)
                                 (item "i3" 35.6815 139.7695 nil)]))))
    (t/is (= 2 (:pictures sw)))
    (t/is (= 2 (:heading-known sw)))
    (t/is (= 2 (:sequence-known sw)))
    ;; the NE cell counts the unknown-heading item; it is not dropped
    (t/is (= 1 (:pictures ne)))
    (t/is (= 1 (:heading-unknown ne)))
    ;; the fixture item carries a collection id, so the sequence is known
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
                         "next-link-present=false"))))

;; ── provenance: no model, stated privacy, checks that agree ──────────

(t/deftest provenance-shape
  (let [r (run [(item "i1" 35.6785 139.7655 198)])]
    (t/is (= :none (:provenance/model-id (:prov r))))
    (t/is (= "panoramax-street-density-v1" (:provenance/task-id (:prov r))))
    (t/is (some? (:provenance/privacy-note (:prov r))))
    ;; bbox comes back through the upstream provenance parameters
    (t/is (= bbox (:table/bbox (:table r))))
    (t/is (= area-id (:table/area-id (:table r))))))

(t/deftest provenance-checks-agree
  (let [r (run [(item "i1" 35.6785 139.7655 198)
                (item "i2" 35.6815 139.7695 90)])
        doc {"observations" (mapv #(dissoc % :raw) (:observations (:norm r)))
             ;; the stored document serializes with plain keys, the
             ;; way the bin script's clj->js does
             "derived-table" (js->clj (clj->js {:task-id (get (:table r) :table/task-id)
                                                :pictures (get (:table r) :table/pictures)
                                                :cells (get (:table r) :table/cells)}))}]
    (t/is (= {:ok? true} (pxd/provenance-checks doc)))
    ;; a tampered count must refuse, not pass
    (let [bad (assoc-in doc ["derived-table" "pictures" "placed"] 99)]
      (t/is (= :provenance/counts-disagree (:error (pxd/provenance-checks bad)))))))
