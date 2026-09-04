(ns otent.mapillary-mapfeatures-bbox-test
  "Bounded tests for the one-tile map_features bbox source (offline,
  synthetic fixture)."
  (:require [clojure.test :as t]
            [otent.mapillary-mapfeatures-bbox :as mfb]))

(def ^:private bbox [139.76 35.67 139.7699 35.6799])
(def ^:private bbox-str "139.76,35.67,139.7699,35.6799")

(def ^:private pole-feature
  {"id" "982746150400001"
   "object_value" "object--support--utility-pole"
   "geometry" {"type" "Point" "coordinates" [139.7654 35.6789]}
   "first_seen_at" 1755100900000
   "last_seen_at" 1756500900000})

(def ^:private sign-feature
  {"id" "982746150400002"
   "object_value" "object--sign--information"
   "geometry" {"type" "Point" "coordinates" [139.7655 35.6790]}
   "first_seen_at" 1755100901000
   "last_seen_at" 1756500901000})

(def ^:private person-feature
  {"id" "982746150400003"
   "object_value" "object--human--person"
   "geometry" {"type" "Point" "coordinates" [139.7656 35.6791]}
   "first_seen_at" 1755100902000
   "last_seen_at" 1756500902000})

;; ── subject gates ────────────────────────────────────────────────────

(t/deftest the-bbox-is-gated-before-any-request-is-built
  (t/is (:ok? (mfb/bbox-numbers bbox)))
  (t/is (= :mly-mfbbox/bbox-shape (:error (mfb/bbox-numbers [139.76 35.67 139.77]))))
  (t/is (= :mly-mfbbox/bbox-non-numeric (:error (mfb/bbox-numbers [139.76 "35.67" 139.77 35.68]))))
  (t/is (= :mly-mfbbox/bbox-out-of-range (:error (mfb/bbox-numbers [139.77 35.67 139.76 35.68]))))
  (t/is (= :mly-mfbbox/bbox-out-of-range (:error (mfb/bbox-numbers [200.0 35.67 200.01 35.68]))))
  ;; the client's own 0.01-degree rule: one tile or refuse, never split
  (t/is (= :mly-mfbbox/bbox-too-large (:error (mfb/bbox-numbers [139.7 35.6 139.8 35.7])))))

(t/deftest the-request-is-the-registered-client-s
  (let [r (mfb/build-request bbox)]
    (t/is (:ok? r))
    (t/is (= "https://graph.mapillary.com/map_features" (get-in r [:request :url])))
    (t/is (= "139.76,35.67,139.7699,35.6799" (get-in r [:request :query-params "bbox"])))
    (t/is (= "id,object_value,geometry,first_seen_at,last_seen_at"
             (get-in r [:request :query-params "fields"])))
    ;; no token in the request — it rides in the Authorization header
    (t/is (nil? (get-in r [:request :query-params "access_token"]))))

  (let [r (mfb/build-request [139.7 35.6 139.8 35.7])]
    (t/is (not (:ok? r)))
    (t/is (= :mly-mfbbox/bbox-too-large (:error r)))))

;; ── privacy boundary ─────────────────────────────────────────────────

(t/deftest person-face-and-plate-values-are-refused-before-any-record
  (t/is (true? (mfb/privacy-refusal? "object--human--person")))
  (t/is (true? (mfb/privacy-refusal? "object--human--face--glasses")))
  (t/is (true? (mfb/privacy-refusal? "object--vehicle--license-plate")))
  (t/is (false? (mfb/privacy-refusal? "object--support--utility-pole")))
  (let [r (mfb/feature->admissible person-feature)]
    (t/is (not (:ok? r)))
    (t/is (= :mly-mfbbox/privacy-value (:error r)))))

(t/deftest admissible-features-pass-every-gate
  (t/is (:ok? (mfb/feature->admissible pole-feature)))
  (t/is (= :mly-mfbbox/missing-feature-id
           (:error (mfb/feature->admissible (assoc pole-feature "id" "")))))
  (t/is (= :mly-mfbbox/missing-object-value
           (:error (mfb/feature->admissible (assoc pole-feature "object_value" 7)))))
  (t/is (= :mly-mfbbox/invalid-geometry
           (:error (mfb/feature->admissible
                    (assoc-in pole-feature ["geometry" "coordinates"]
                              [35.6789 139.7654])))))
  (t/is (= :mly-mfbbox/missing-first-seen-at
           (:error (mfb/feature->admissible (assoc pole-feature "first_seen_at" nil))))))

;; ── record ───────────────────────────────────────────────────────────

(t/deftest a-record-carries-everything-the-scope-names
  (let [r (mfb/feature->record bbox pole-feature "2026-09-04T01:00:00.000Z")]
    (t/is (= "mapillary-mapfeatures-bbox" (:observation/source r)))
    (t/is (= "982746150400001" (:observation/source-id r)))
    (t/is (= "mapillary-mapfeatures-bbox:map-feature:982746150400001"
             (:observation/asset-id r)))
    (t/is (= "object--support--utility-pole" (:observation/value r)))
    (t/is (= 35.6789 (:observation/lat r)))
    (t/is (= 139.7654 (:observation/lon r)))
    (t/is (= 1755100900000 (:observation/first-seen-at-ms r)))
    (t/is (= 1756500900000 (:observation/last-seen-at-ms r)))
    (t/is (= bbox-str (:observation/bbox-str r)))
    (t/is (= "2026-09-04T01:00:00.000Z" (:observation/ingested-at r)))
    (t/is (= :unknown (:observation/spatial-uncertainty r)))
    (t/is (= "https://www.mapillary.com/app/?focus=map&bbox=139.76,35.67,139.7699,35.6799"
             (:observation/evidence-url r)))
    (t/is (seq (:observation/licence r)))
    (t/is (seq (:observation/attribution r)))
    (t/is (= 1 (:observation/requests-made r)))))

(t/deftest the-last-gate-refuses-what-must-not-ship
  (let [r (mfb/feature->record bbox pole-feature "2026-09-04T01:00:00.000Z")]
    (t/is (:ok? (mfb/check-record r)))
    (t/is (not (:ok? (mfb/check-record (dissoc r :observation/licence)))))
    (t/is (not (:ok? (mfb/check-record (assoc r :observation/requests-made 2)))))
    (t/is (not (:ok? (mfb/check-record (dissoc r :observation/lat)))))
    (t/is (not (:ok? (mfb/check-record (assoc-in r [:observation/value] "contact a@b.example")))))))

;; ── the one pass over the payload ────────────────────────────────────

(t/deftest the-payload-pass-parses-counts-and-never-follows-paging
  (let [payload {"bbox" bbox
                 "bbox-str" bbox-str
                 "retrieved-at" "2026-09-04T01:00:00.000Z"
                 "data" [pole-feature sign-feature person-feature]
                 "paging" {"next" "https://graph.mapillary.com/map_features?cursor=abc"}}
        p (mfb/parse-payload payload)]
    (t/is (= 3 (:raw-count p)))
    (t/is (= 2 (count (:records p))))
    (t/is (= 1 (:refused-n p)))
    (t/is (true? (:paging-next p)))
    ;; the refused feature's value must not survive anywhere in a record
    (t/is (every? #(not (re-matches #".*human.*" (str (:observation/value %))))
                   (:records p)))
    (t/is (= 1 (:observation/requests-made (first (:records p))))))

  (let [p (mfb/parse-payload {"bbox" bbox "bbox-str" bbox-str "data" []})]
    (t/is (zero? (:raw-count p)))
    (t/is (empty? (:records p)))
    (t/is (false? (:paging-next p)))))
