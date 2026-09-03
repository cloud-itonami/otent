(ns otent.panorama-coverage-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.panorama-coverage :as pxc]
            [otent.panoramax :as px]))

(def ^:private bbox [139.765 35.678 139.77 35.682])

(def ^:private good-item
  {"type" "Feature"
   "id" "cf6b0e8d-3eb5-442f-8259-b7ecdb165a07"
   "bbox" [139.767360413 35.68140155 139.767360413 35.68140155]
   "links" [{"rel" "self" "href" "https://api.panoramax.xyz/api/collections/abc/items/cf6b0e8d"
             "type" "application/geo+json"}]
   "assets" {"sd" {"href" "https://panoramax.openstreetmap.fr/derivates/cf/sd.jpg"}}
   "geometry" {"type" "Point" "coordinates" [139.767360413 35.68140155]}
   "properties" {"datetime" "2016-10-13T13:13:11.066709+00:00"
                 "license" "CC-BY-SA-4.0"
                 "geovisio:status" "ready"
                 "geovisio:visibility" "anyone"}})

(defn- envelope [features]
  {"type" "FeatureCollection" "features" features "links" []})

(defn- run-with
  "Normalize a payload through the upstream gates, then derive the
  vintage table the way the bin does (identical call sequence)."
  [features]
  (let [result (px/normalize-payload (envelope features)
                                     {:bbox bbox :retrieved-at "2026-09-03T00:00:00Z"})
        prov (px/provenance {:area-id "bbox-139.765-35.678-139.77-35.682"
                             :bbox bbox
                             :retrieved-at "2026-09-03T00:00:00Z"
                             :input-sha256 "abc123"})]
    (assoc result :table (pxc/vintage-table {:observations (:observations result)
                                             :counts (:counts result)
                                             :provenance prov})
                  :prov prov
                  :derived-prov (pxc/provenance prov {:run-at "2026-09-03T00:00:00Z"}))))

;; ── capture-time parsing ─────────────────────────────────────────────

(t/deftest capture-time-validation
  (t/is (= "2016-10-13T13:13:11.066709+00:00"
           (pxc/parse-capture-time "2016-10-13T13:13:11.066709+00:00")))
  ;; no fractional seconds, Z suffix
  (t/is (= "2021-04-02T12:00:00Z" (pxc/parse-capture-time "2021-04-02T12:00:00Z")))
  ;; a non-conforming published string stays visible as :unknown
  (t/is (= :unknown (pxc/parse-capture-time "13/10/2016 13:13")))
  (t/is (= :unknown (pxc/parse-capture-time "2016-10-13 13:13:11.066")))
  ;; a non-UTC offset is refused as a validated instant (no timezone
  ;; conversion is ever performed here)
  (t/is (= :unknown (pxc/parse-capture-time "2016-10-13T22:13:11+09:00")))
  (t/is (= :unknown (pxc/parse-capture-time nil)))
  (t/is (= :unknown (pxc/parse-capture-time 42))))

;; ── the derived table ────────────────────────────────────────────────

(t/deftest table-identity
  (let [{:keys [table]} (run-with [good-item])]
    (t/is (= "panoramax-street-vintage-v1" (:table/task-id table)))
    (t/is (= :temporal-coverage (:table/kind table)))
    (t/is (= "panoramax" (:table/source-id table)))
    (t/is (= "bbox-139.765-35.678-139.77-35.682" (:table/area-id table)))
    (t/is (= bbox (:table/bbox table)))
    (t/is (= :lower-bound (:table/coverage-bound table)))))

(t/deftest fixture-determinism
  (let [second (-> good-item
                   (assoc-in ["id"] "second")
                   (assoc-in ["properties" "datetime"] nil))
        a (:table (run-with [good-item second]))]
    ;; the same input always produces the same table
    (let [b (:table (run-with [good-item second]))]
      (t/is (= a b)))))

(t/deftest span-and-unknowns
  ;; two known times + one unknown: the span keeps the provider's own
  ;; bytes, the unknown is counted, never folded into the span
  (let [second (-> good-item
                   (assoc-in ["id"] "second")
                   (assoc-in ["properties" "datetime"] "2021-04-02T12:00:00Z"))
        unknown (-> good-item
                    (assoc-in ["id"] "third")
                    (assoc-in ["properties" "datetime"] "not a date at all"))
        {:keys [table]} (run-with [good-item second unknown])
        span (:table/capture-span table)]
    (t/is (= 3 (get-in table [:table/pictures :accepted])))
    (t/is (= 2 (get-in table [:table/pictures :capture-known])))
    (t/is (= 1 (get-in table [:table/pictures :capture-unknown])))
    (t/is (= "2016-10-13T13:13:11.066709+00:00" (:earliest-published span)))
    (t/is (= "2021-04-02T12:00:00Z" (:latest-published span)))))

(t/deftest all-unknown-span
  ;; every published datetime non-conforming: the span is :unknown,
  ;; visibly, not an empty result that looks like a pass
  (let [{:keys [table]} (run-with [(assoc-in good-item ["properties" "datetime"]
                                             "2016/10/13")])]
    (t/is (= :unknown (:table/capture-span table)))
    (t/is (= 0 (get-in table [:table/pictures :capture-known])))
    (t/is (= 1 (get-in table [:table/pictures :capture-unknown])))))

(t/deftest upstream-privacy-gate
  ;; a refused item (unprocessed upstream) never reaches the table
  (let [{:keys [counts table]}
        (run-with [(assoc-in good-item ["properties" "geovisio:status"] "draft")])]
    (t/is (= 1 (:refused counts)))
    (t/is (= 0 (get-in table [:table/pictures :accepted])))
    (t/is (= 1 (get-in table [:table/run-counts :refused])))
    ;; privacy asserted in the derived provenance
    (let [dp (pxc/provenance {} {:run-at "t"})]
      (t/is (str/includes? (:provenance/privacy-note dp) "no pixel"))
      (t/is (str/includes? (:provenance/privacy-note dp) "no face, plate, person or vehicle")))))

(t/deftest epistemic-boundary
  (let [{:keys [table]} (run-with [good-item])]
    (t/is (str/includes? (:table/epistemic-boundary table) "not road condition"))
    (t/is (str/includes? (:table/coverage-bound-note table) "next-link-present=false"))
    ;; model-id is :none, stated not hidden
    (let [dp (pxc/provenance {} {:run-at "t"})]
      (t/is (= :none (:provenance/model-id dp))))))

;; ── provenance readback check ────────────────────────────────────────

(t/deftest provenance-checks
  (let [{:keys [observations table]} (run-with
                                      [(assoc-in good-item ["properties" "datetime"]
                                                 "2021-04-02T12:00:00Z")])
        doc (clj->js {:observations observations :derived-table table})]
    (t/is (:ok? (pxc/provenance-checks doc)))
    ;; tamper with the accounting: the check refuses
    (let [tampered (js/JSON.parse (js/JSON.stringify doc))
          _ (aset tampered "derived-table" "pictures"
                  (js-obj "accepted" 99 "capture-known" 0 "capture-unknown" 0))]
      (t/is (= :provenance/counts-disagree (:error (pxc/provenance-checks tampered)))))
    ;; tamper with a span endpoint: the check refuses
    (let [tampered (js/JSON.parse (js/JSON.stringify doc))
          _ (aset (aget tampered "derived-table") "capture-span"
                  (js-obj "earliest-published" "2000-01-01T00:00:00Z"
                          "latest-published" "2021-04-02T12:00:00Z"))]
      (t/is (= :provenance/counts-disagree (:error (pxc/provenance-checks tampered)))))))

(t/deftest table-json-roundtrip
  ;; the stored document must survive a JSON round-trip with its keys
  ;; in the string form the readback check reads
  (let [{:keys [table]} (run-with [good-item])
        doc (js/JSON.parse (js/JSON.stringify (clj->js {:derived-table table})))
        t (aget doc "derived-table")]
    (t/is (= "panoramax-street-vintage-v1" (aget t "task-id")))
    (t/is (= 1 (aget (aget t "pictures") "capture-known")))))
