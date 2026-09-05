(ns otent.panoramax-coverage-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.panoramax :as px]
            [otent.panoramax-coverage :as pxc]))

(def ^:private bbox [139.765 35.678 139.77 35.682])
(def ^:private area-id "bbox-139.765-35.678-139.77-35.682")
(def ^:private run-at "2026-09-01T00:00:00Z")

(defn- item
  ([id datetime] (item id datetime {}))
  ([id datetime opts]
   {"id" id
    "type" "Feature"
    "geometry" {"type" "Point" "coordinates" [139.7655 35.6805]}
    "links" [{"rel" "self" "href" (str "https://api.panoramax.xyz/api/collections/x/items/" id)}]
    "properties"
    (cond-> {"datetime" datetime
             "license" "CC-BY-SA-4.0"
             "geovisio:status" "ready"
             "geovisio:visibility" "anyone"
             "collection" "seq-1"
             "geovisio:rank_in_collection" 1}
      true (merge opts))}))

(defn- payload [items]
  {"type" "FeatureCollection" "features" items "links" []})

(defn- run [items]
  (let [norm (px/normalize-payload (payload items)
                                   {:bbox bbox :retrieved-at run-at})
        prov (px/provenance {:area-id area-id :bbox bbox
                             :retrieved-at run-at :input-sha256 "deadbeef"})]
    (assert (:ok? norm))
    {:table (pxc/vintage-table {:observations (:observations norm)
                                :counts (:counts norm)
                                :provenance prov})
     :prov (pxc/provenance prov {:run-at run-at})
     :norm norm}))

;; ── task identity ────────────────────────────────────────────────────

(t/deftest task-identity
  (t/is (= "panoramax-imagery-vintage-v1" pxc/task-id))
  ;; exactly one derived task, one source — the run bound
  (t/is (= "panoramax" pxc/source-id)))

;; ── fixture determinism ──────────────────────────────────────────────

(t/deftest deterministic
  (let [items [(item "1" "2024-05-01T08:30:00+00:00")
               (item "2" "2021-04-02T12:00:00.500000+00:00")
               (item "3" "2019-01-15T07:30:01+00:00")]
        r1 (run items)
        r2 (run items)]
    (t/is (= (:table r1) (:table r2)))
    ;; lexicographic over the fixed-offset UTC format is chronological:
    ;; an exact second sorts before its own fractional extensions
    (t/is (= {:earliest-published "2019-01-15T07:30:01+00:00"
              :latest-published "2024-05-01T08:30:00+00:00"
              :comparison
              "lexicographic over the provider's fixed-offset UTC STAC datetime format; no timezone conversion performed; strings denoting the same instant may order either way, which cannot move the bounds outside the observed instants"}
             (:table/capture-span (:table r1))))
    ;; span endpoints are byte-identical to the provider's strings
    (t/is (some #(= "2019-01-15T07:30:01+00:00" (:observation/capture-time %))
                (:observations (:norm r1))))))

(t/deftest fractional-vs-exact-second
  ;; 08:30:00 (exact) must sort before 08:30:00.500000, and both before
  ;; 08:30:01 — the mixed-precision format the provider actually sends
  (let [{:keys [table]}
        (run [(item "1" "2024-05-01T08:30:01+00:00")
              (item "2" "2024-05-01T08:30:00+00:00")
              (item "3" "2024-05-01T08:30:00.500000+00:00")])]
    (t/is (= "2024-05-01T08:30:00+00:00"
             (:earliest-published (:table/capture-span table))))
    (t/is (= "2024-05-01T08:30:01+00:00"
             (:latest-published (:table/capture-span table))))))

;; ── unknown stays visible ────────────────────────────────────────────

(t/deftest unknown-capture-times
  ;; upstream refuses an empty datetime, so an unknown here is an item
  ;; whose published string does not conform to the provider's format
  (let [r (run [(item "1" "2024-05-01T08:30:00+00:00")
                (item "2" "sometime in 2019")])]
    (t/is (= 1 (:capture-unknown (:table/photos (:table r)))))
    (t/is (= 1 (:capture-known (:table/photos (:table r)))))
    ;; the span uses only known times; the unknown is counted, not dropped
    (t/is (= "2024-05-01T08:30:00+00:00"
             (:earliest-published (:table/capture-span (:table r)))))))

(t/deftest all-unknown-span
  (let [r (run [(item "1" "not a date")])]
    (t/is (= :unknown (:table/capture-span (:table r))))
    (t/is (= 1 (:capture-unknown (:table/photos (:table r)))))))

;; ── privacy boundary is upstream and stated ──────────────────────────

(t/deftest privacy-gate-upstream
  ;; an unprocessed item never becomes an observation, so it never
  ;; reaches the derived table
  (let [norm (px/normalize-payload (payload [(item "1" "2024-05-01T08:30:00+00:00"
                                                   {"geovisio:status" "waiting-automated-processing"})])
                                   {:bbox bbox :retrieved-at run-at})]
    (t/is (= 1 (:refused (:counts norm))))
    (t/is (empty? (:observations norm)))
    (let [table (pxc/vintage-table {:observations (:observations norm)
                                    :counts (:counts norm)
                                    :provenance (px/provenance {:area-id area-id :bbox bbox
                                                                :retrieved-at run-at
                                                                :input-sha256 "x"})})]
      (t/is (= 0 (:accepted (:table/photos table)))))))

(t/deftest privacy-asserted-in-provenance
  (let [{:keys [prov]} (run [(item "1" "2024-05-01T08:30:00+00:00")])]
    (t/is (str/includes? (:provenance/privacy-note prov) "status=ready"))
    ;; the forbidden entities must be named as absent
    (t/is (str/includes? (:provenance/privacy-note prov) "no face"))))

;; ── epistemic boundary ───────────────────────────────────────────────

(t/deftest epistemic-boundary
  (let [{:keys [table]} (run [(item "1" "2024-05-01T08:30:00+00:00")])]
    (t/is (= :lower-bound (:table/coverage-bound table)))
    (t/is (str/includes? (:table/coverage-bound-note table) "links-next=false"))
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    ;; the analysis declares no model
    (t/is (= :none (:provenance/model-id
                    (pxc/provenance (px/provenance {:area-id area-id :bbox bbox
                                                    :retrieved-at run-at
                                                    :input-sha256 "x"})
                                    {:run-at run-at}))))))

;; ── provenance readback ──────────────────────────────────────────────

(t/deftest provenance-readback
  (let [{:keys [prov table]} (run [(item "1" "2024-05-01T08:30:00+00:00")])]
    (t/is (= "panoramax-imagery-vintage-v1" (:provenance/task-id prov)))
    (t/is (= "panoramax" (:provenance/source-id prov)))
    (t/is (= "deadbeef" (:provenance/content-hash prov)))
    (t/is (= "CC-BY-SA-4.0 (per-item STAC license property)" (:provenance/licence prov)))
    (t/is (= run-at (:provenance/derived-run-at prov)))
    (t/is (= area-id (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    ;; run counts carried through unchanged and visible
    (t/is (= 1 (:accepted (:table/run-counts table))))))

;; ── derived-table readback (round trip) ──────────────────────────────

(t/deftest derived-table-readback
  (let [{:keys [table]} (run [(item "1" "2024-05-01T08:30:00+00:00")
                              (item "2" "2021-04-02T12:00:00.500000+00:00")
                              (item "3" "maybe 2020")])
        ;; round-trip through JSON like the R2 write does
        js (js/JSON.parse (js/JSON.stringify (clj->js table)))
        back (js->clj js :keywordize-keys true)]
    (t/is (= "panoramax-imagery-vintage-v1" (:task-id back)))
    (t/is (= "2021-04-02T12:00:00.500000+00:00"
             (get-in back [:capture-span :earliest-published])))
    (t/is (= "2024-05-01T08:30:00+00:00"
             (get-in back [:capture-span :latest-published])))
    (t/is (= 1 (:capture-unknown (:photos back))))
    (t/is (= 2 (:capture-known (:photos back))))))
