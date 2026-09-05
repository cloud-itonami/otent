(ns otent.kartaview-test
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [otent.kartaview :as kv]))

(def ^:private bbox [139.765 35.678 139.77 35.682])

(def ^:private good-photo
  {"id" "118823961"
   "sequenceId" "583739"
   "sequenceIndex" "1694"
   "lat" "35.680192"
   "lng" "139.765267"
   "matchLat" "35.680263519287110"
   "matchLng" "139.765304565429700"
   "heading" "297.32"
   "projection" "PLANE"
   "gpsAccuracy" "10.0000"
   "width" "1920"
   "height" "1080"
   "shotDate" "2017-09-09 08:28:31.000"
   "autoImgProcessingResult" "BLURRED"
   "autoImgProcessingStatus" "FINISHED"
   "status" "active"
   "visibility" "public"
   "imageProcUrl" "https://cdn.kartaview.org/pr:sharp/aHR0cHM6"
   "qualityStatus" "FINISHED"})

(defn- envelope [photos]
  {"status" {"httpCode" 200 "apiCode" 600}
   "result" {"data" photos "hasMoreData" false}})

(defn- accept [photo]
  (:observation (kv/photo->observation-or-refusal photo "2026-09-01T00:00:00Z")))

;; ── bounds ───────────────────────────────────────────────────────────

(t/deftest bbox-bounds
  (t/is (:ok? (kv/check-bbox bbox)))
  (t/is (= "bbox-139.765-35.678-139.77-35.682"
           (:area-id (kv/check-bbox bbox))))
  (t/is (= :kartaview/bbox-too-large (:error (kv/check-bbox [139.0 35.0 139.02 35.01]))))
  (t/is (= :kartaview/bbox-inverted (:error (kv/check-bbox [139.77 35.678 139.765 35.682]))))
  (t/is (= :kartaview/bbox-invalid (:error (kv/check-bbox ["a" 1 2 3]))))
  (t/is (= :kartaview/bbox-invalid (:error (kv/check-bbox [1 2 3])))))

(t/deftest bbox-query
  (let [{:keys [lat lng radius]} (kv/bbox->query bbox)]
    (t/is (number? lat))
    (t/is (number? lng))
    ;; the radius cap is what actually bounds the fetch
    (t/is (<= radius kv/max-radius-m))
    (t/is (> radius 0))))

;; ── privacy boundary ─────────────────────────────────────────────────

(t/deftest privacy
  (t/is (kv/blurred? good-photo))
  ;; a missing or non-blurred flag is a refusal, never an acceptance
  (t/is (not (kv/blurred? (dissoc good-photo "autoImgProcessingResult"))))
  (t/is (not (kv/blurred? (assoc good-photo "autoImgProcessingResult" "NONE")))))

;; ── per-photo refusals ───────────────────────────────────────────────

(t/deftest refusals
  (let [refused #(-> (kv/photo->observation-or-refusal % "2026-09-01T00:00:00Z")
                     (select-keys [:ok? :error]))]
    (t/is (= {:ok? false :error :kartaview/missing-asset-id}
             (refused (dissoc good-photo "id"))))
    (t/is (= {:ok? false :error :kartaview/not-provider-blurred}
             (refused (assoc good-photo "autoImgProcessingResult" "NONE"))))
    (t/is (= {:ok? false :error :kartaview/not-public}
             (refused (assoc good-photo "visibility" "private"))))
    (t/is (= {:ok? false :error :kartaview/not-active}
             (refused (assoc good-photo "status" "deleted"))))
    (t/is (= {:ok? false :error :kartaview/invalid-geometry}
             (refused (assoc good-photo "lat" "999"))))
    (t/is (= {:ok? false :error :kartaview/missing-capture-time}
             (refused (assoc good-photo "shotDate" ""))))
    (t/is (= {:ok? false :error :kartaview/missing-evidence-url}
             (refused (assoc good-photo "imageProcUrl" ""))))))

;; ── normalization ────────────────────────────────────────────────────

(t/deftest observation-shape
  (let [o (accept good-photo)]
    (t/is (= "kartaview-photo:118823961" (:observation/asset-id o)))
    (t/is (= "kartaview" (:observation/source-id o)))
    ;; lon/lat order, [lng lat] as parsed
    (t/is (= [139.765267 35.680192] (:coordinates (:observation/footprint o))))
    (t/is (= "2017-09-09 08:28:31.000" (:observation/capture-time o)))
    (t/is (= "2026-09-01T00:00:00Z" (:observation/ingested-at o)))
    (t/is (= 10.0 (:observation/spatial-uncertainty-m o)))
    (t/is (= "583739" (:observation/sequence-id o)))
    (t/is (= 1694 (:observation/sequence-index o)))
    (t/is (= "CC-BY-SA 4.0 (KartaView terms of use)" (:observation/licence o)))
    (t/is (= "https://cdn.kartaview.org/pr:sharp/aHR0cHM6" (:observation/source-url o)))
    (t/is (str/starts-with? (:observation/evidence-url o)
                            "https://kartaview.org/sequence/583739"))
    ;; the unmeasured stays visible
    (t/is (= :unknown (:observation/resolution-or-gsd o)))
    (t/is (= :unknown (:observation/sensor o)))
    (t/is (= :unknown (:observation/spectral-bands o)))
    ;; and the privacy declaration is on the observation itself
    (t/is (:provider-blurred (:observation/privacy o)))))

(t/deftest payload-normalization
  (let [r (kv/normalize-payload (envelope [good-photo good-photo])
                                {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? r))
    (t/is (= 2 (get-in r [:counts :fetched])))
    (t/is (= 2 (get-in r [:counts :accepted])))
    (t/is (= 0 (get-in r [:counts :refused])))
    (t/is (false? (get-in r [:counts :has-more-data])))
    (t/is (= 2 (count (:observations r))))))
(t/deftest payload-filters-and-counts
  ;; a good photo outside the bbox is counted, not silently dropped
  (let [far (assoc good-photo "lat" "36.0")
        bad (assoc good-photo "id" "x" "autoImgProcessingResult" "NONE")
        r (kv/normalize-payload (envelope [good-photo far bad])
                                {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? r))
    (t/is (= 1 (count (:observations r))))
    (t/is (= 1 (get-in r [:counts :refused])))
    (t/is (= 1 (get-in r [:counts :returned-outside-bbox])))
    (t/is (= :kartaview/not-provider-blurred (:error (first (:refusals r)))))))

(t/deftest bad-envelope
  (let [r (kv/normalize-payload {"status" {"httpCode" 401}}
                                {:bbox bbox :retrieved-at "x"})]
    (t/is (not (:ok? r)))
    (t/is (= :kartaview/bad-envelope (:error r)))))

;; ── provenance ───────────────────────────────────────────────────────

(t/deftest provenance-shape
  (let [p (kv/provenance {:area-id "bbox-test" :bbox bbox
                          :retrieved-at "2026-09-01T00:00:00Z"
                          :input-sha256 "abc"})]
    (t/is (= "kartaview" (:provenance/source-id p)))
    (t/is (= "abc" (:provenance/content-hash p)))
    (t/is (= :otent-geospatial-vision (:provenance/system-id p)))
    (t/is (= "bbox-test" (:area-id (:provenance/parameters p))))
    (t/is (= bbox (:bbox (:provenance/parameters p))))
    (t/is (str/includes? (:provenance/licence p) "CC-BY-SA"))
    (t/is (str/includes? (:provenance/source-url p) "openstreetcam"))))
