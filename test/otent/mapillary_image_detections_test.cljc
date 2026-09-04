(ns otent.mapillary-image-detections-test
  "Bounded tests for the one-image detections source (offline, synthetic)."
  (:require [clojure.test :as t]
            [otent.mapillary-image-detections :as mid]))

(def ^:private image-id "482194673015931")

(def ^:private pole-detection
  {"id" "193892647102583"
   "value" "object--support--utility-pole"
   "geometry" {"type" "Point" "coordinates" [139.7654 35.6789]}
   "created_at" 1755100900000})

(def ^:private sign-detection
  {"id" "193892647102584"
   "value" "object--sign--information"
   "geometry" {"type" "Point" "coordinates" [139.7655 35.6790]}
   "created_at" 1755100901000})

(def ^:private person-detection
  {"id" "193892647102585"
   "value" "object--human--person"
   "geometry" {"type" "Point" "coordinates" [139.7656 35.6791]}
   "created_at" 1755100902000})

;; ── subject gates ────────────────────────────────────────────────────

(t/deftest an-image-id-is-gated-before-any-request-is-built
  (t/is (:ok? (mid/check-image-id image-id)))
  (t/is (= :mly-imagedet/missing-image-id (:error (mid/check-image-id ""))))
  (t/is (= :mly-imagedet/image-id-malformed
           (:error (mid/check-image-id "482194673015931; DROP")))))

(t/deftest the-request-is-the-registered-client-s
  (let [r (mid/build-request image-id)]
    (t/is (:ok? r))
    (t/is (= "https://graph.mapillary.com/482194673015931/detections"
             (get-in r [:request :url])))
    (t/is (= "id,value,geometry,created_at"
             (get-in r [:request :query-params "fields"])))
    ;; no token in the request — it rides in the Authorization header
    (t/is (nil? (get-in r [:request :query-params "access_token"])))))

;; ── privacy boundary ─────────────────────────────────────────────────

(t/deftest person-face-and-plate-values-are-refused-before-any-record
  (t/is (true? (mid/privacy-refusal? "object--human--person")))
  (t/is (true? (mid/privacy-refusal? "object--human--face--glasses")))
  (t/is (true? (mid/privacy-refusal? "object--vehicle--license-plate")))
  (t/is (false? (mid/privacy-refusal? "object--support--utility-pole")))
  (let [r (mid/detection->admissible person-detection)]
    (t/is (not (:ok? r)))
    (t/is (= :mly-imagedet/privacy-value (:error r)))))

(t/deftest admissible-detections-pass-every-gate
  (t/is (:ok? (mid/detection->admissible pole-detection)))
  (t/is (= :mly-imagedet/missing-detection-id
           (:error (mid/detection->admissible (assoc pole-detection "id" "")))))
  (t/is (= :mly-imagedet/missing-value
           (:error (mid/detection->admissible (assoc pole-detection "value" 7)))))
  (t/is (= :mly-imagedet/invalid-geometry
           (:error (mid/detection->admissible
                    (assoc-in pole-detection ["geometry" "coordinates"]
                              [35.6789 139.7654])))))
  (t/is (= :mly-imagedet/missing-created-at
           (:error (mid/detection->admissible (assoc pole-detection "created_at" nil))))))

;; ── record ───────────────────────────────────────────────────────────

(t/deftest a-record-carries-everything-the-scope-names
  (let [r (mid/detection->record image-id pole-detection "2026-09-04T01:00:00.000Z")]
    (t/is (= "mapillary-image-detections" (:observation/source r)))
    (t/is (= "mapillary-image-detections:image:482194673015931:193892647102583"
             (:observation/asset-id r)))
    (t/is (= "object--support--utility-pole" (:observation/value r)))
    (t/is (= 35.6789 (:observation/lat r)))
    (t/is (= 139.7654 (:observation/lon r)))
    (t/is (= 1755100900000 (:observation/created-at-ms r)))
    (t/is (= "2026-09-04T01:00:00.000Z" (:observation/ingested-at r)))
    (t/is (= "https://www.mapillary.com/app/?pKey=482194673015931"
             (:observation/evidence-url r)))
    (t/is (seq (:observation/licence r)))
    (t/is (= 1 (:observation/requests-made r)))))

(t/deftest the-check-refuses-records-that-cannot-prove-themselves
  (let [r (mid/detection->record image-id pole-detection "2026-09-04T01:00:00.000Z")]
    (t/is (:ok? (mid/check-record r)))
    (t/is (= :mly-imagedet/missing-licence
             (:error (mid/check-record (assoc r :observation/licence nil)))))
    (t/is (= :mly-imagedet/run-bounds
             (:error (mid/check-record (assoc r :observation/requests-made 2)))))
    (t/is (= :mly-imagedet/privacy-redaction
             (:error (mid/check-record
                      (assoc r :observation/attribution "ops@example.com")))))))

;; ── the one pass ─────────────────────────────────────────────────────

(t/deftest the-pass-counts-privacy-refusals-and-never-stores-them
  (let [payload {"image-id" image-id
                 "data" [pole-detection person-detection sign-detection]
                 "paging" {"next" "https://graph.mapillary.com/482194673015931/detections?cursor=x"}
                 "retrieved-at" "2026-09-04T01:00:00.000Z"}
        out (mid/parse-payload payload)]
    (t/is (= 3 (:raw-count out)))
    (t/is (= 2 (count (:records out))))
    (t/is (= 1 (:refused-n out)))
    (t/is (true? (:paging-next out)))
    (t/is (every? #(not (re-matches #".*human.*" (str (:observation/value %))))
                  (:records out)))))

(t/deftest redaction-refuses-any-string-carrying-an-at-sign
  (t/is (false? (mid/redacted? {"note" "a@b.example"})))
  (t/is (false? (mid/redacted? {"ok" {"nested" ["x@y"]}})))
  (t/is (true? (mid/redacted? {"value" "object--street-light"}))))
