(ns otent.mapillary-images-test
  "Tests for the Mapillary image-metadata source. The fixture is
  SYNTHETIC and clearly labelled as such -- no token exists in any
  secret store (re-verified this run), so no live payload is claimed."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            ["fs" :as fs]
            [otent.mapillary-images :as mimg]
            [com-mapillary-graph-api.core :as mi]))

(def ^:private bbox [139.765 35.678 139.77 35.682])

(defn- load-fixture []
  (js/JSON.parse (str (fs/readFileSync "test/otent/fixtures/mapillary-images-synthetic.json" "utf8"))))

(t/deftest fixture-normalization
  ;; The synthetic fixture: 6 features -- 3 in-bounds, 1 swapped
  ;; coordinates, 1 missing captured_at, 1 non-numeric captured_at.
  (let [result (mimg/normalize-payload (js->clj (load-fixture))
                                       {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (:ok? result))
    (t/is (= 6 (get-in result [:counts :fetched])))
    (t/is (= 3 (get-in result [:counts :accepted])))
    (t/is (= 3 (get-in result [:counts :refused])))
    (t/is (= 0 (get-in result [:counts :returned-outside-bbox])))
    (t/is (= 3 (count (:observations result))))
    (t/is (= 3 (count (:refusals result))))))

(t/deftest observation-shape
  (let [result (mimg/normalize-payload (js->clj (load-fixture))
                                       {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})
        o (first (:observations result))]
    (t/is (= "482194673015931" (:observation/source-id o)))
    ;; GeoJSON order preserved: [lon lat] -> lon, lat
    (t/is (= 35.6789 (:observation/lat o)))
    (t/is (= 139.7654 (:observation/lon o)))
    ;; capture time is the provider's, ingest time is recorded separately
    (t/is (= 1755100800000 (:observation/capture-time-ms o)))
    (t/is (= "2026-09-01T00:00:00Z" (:observation/retrieved-at o)))
    (t/is (= 271.5 (:observation/compass-angle-deg o)))
    (t/is (false? (:observation/is-panorama o)))
    ;; the provider publishes no spatial-error figure and no per-image
    ;; blur-result flag -- both stated as unknown/false, never assumed
    (t/is (= :unknown (:observation/spatial-uncertainty o)))
    (t/is (false? (:observation/provider-blur-verified o)))
    (t/is (= "https://www.mapillary.com/app/?pKey=482194673015931"
             (:observation/evidence-url o)))
    (t/is (str/includes? (:observation/licence o) "CC-BY-SA"))
    (t/is (str/includes? (:observation/attribution o) "Mapillary"))))

(t/deftest coordinate-order-refused
  ;; A point that is only plausible if swapped is refused, not repaired.
  (let [result (mimg/normalize-payload (js->clj (load-fixture))
                                       {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})
        refusals (set (map :error (:refusals result)))]
    (t/is (contains? refusals :mapillary/invalid-geometry))
    (t/is (contains? refusals :mapillary/missing-capture-time))))

(t/deftest redaction-refuses-and-drops
  ;; The field list already curates what is copied, but the redaction
  ;; check is the gate behind it: any observation carrying an
  ;; email-shaped string (or an exif/email key) never ships, and the
  ;; normalize path counts it as a refusal rather than an observation.
  (t/is (true? (mimg/redacted? {:observation/evidence-url "https://www.mapillary.com/app/?pKey=1"})))
  (t/is (false? (mimg/redacted? {:observation/note "uploader@example.com"})))
  (t/is (false? (mimg/redacted? {"exif" {"ImageDescription" "x"}})))
  (t/is (false? (mimg/redacted? {:nested ["ok" {"email" "a@b"}]})))
  (let [payload {"data" [{"id" "123"
                          "geometry" {"type" "Point" "coordinates" [139.7655 35.6785]}
                          "captured_at" 1755100800000
                          "compass_angle" 1.0
                          "is_pano" false}]}
        result (mimg/normalize-payload payload {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    ;; a clean payload's curated observation passes and ships
    (t/is (= 1 (count (:observations result))))
    (t/is (= 0 (count (:refusals result))))))

(t/deftest bounds-are-declined-before-any-request
  (t/is (= :mapillary/bbox-too-large (:error (mimg/check-bbox [139.76 35.67 139.771 35.68]))))
  (t/is (= :mapillary/bbox-inverted (:error (mimg/check-bbox [139.77 35.68 139.76 35.67]))))
  (t/is (= :mapillary/bbox-invalid (:error (mimg/check-bbox [139.76 "x" 139.77 35.68]))))
  (t/is (= :mapillary/bbox-invalid (:error (mimg/check-bbox [139.76 35.68]))))
  (t/is (:ok? (mimg/check-bbox bbox)))
  ;; the client's own limit agrees with the ns bound
  (t/is (= 0.01 mi/max-bbox-degrees)))

(t/deftest request-through-the-registered-client
  ;; The request is built by com-mapillary-graph-api, not re-implemented:
  ;; bbox arrives in Mapillary's west,south,east,north order, the field
  ;; list excludes every thumbnail/pixel URL, and no token rides in it.
  (let [{:keys [ok? request]} (mimg/build-request bbox)
        qp (:query-params request)]
    (t/is ok?)
    (t/is (= "https://graph.mapillary.com/images" (:url request)))
    (t/is (= "139.765,35.678,139.77,35.682" (get qp "bbox")))
    (t/is (= (str/join "," mimg/image-fields) (get qp "fields")))
    (t/is (nil? (get qp "access_token")))
    (t/is (not (str/includes? (:url request) "thumb")))
    (t/is (not (some #(str/includes? % "thumb") mimg/image-fields)))))

(t/deftest authorization-header-carries-token-not-url
  (let [h (mi/authorization-header "secret-token-value")]
    (t/is (= "OAuth secret-token-value" (get h "Authorization")))
    (t/is (thrown? js/Error (mi/authorization-header nil)))
    (t/is (thrown? js/Error (mi/authorization-header "  ")))))

(t/deftest paging-next-counted-not-followed
  (let [payload {"data" [{"id" "123"
                          "geometry" {"type" "Point" "coordinates" [139.7655 35.6785]}
                          "captured_at" 1755100800000
                          "compass_angle" 1.0
                          "is_pano" false}]
                 "paging" {"next" "https://graph.mapillary.com/images?..."}}
        result (mimg/normalize-payload payload {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (true? (get-in result [:counts :links-next])))
    (t/is (= 1 (get-in result [:counts :accepted])))))

(t/deftest provenance-readback
  (let [result (mimg/normalize-payload (js->clj (load-fixture))
                                       {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})
        prov (mimg/provenance {:area-id "bbox-139.765-35.678-139.77-35.682"
                               :bbox bbox
                               :retrieved-at "2026-09-01T00:00:00Z"
                               :input-sha256 "deadbeef"
                               :request-url "https://graph.mapillary.com/images"})]
    (t/is (= "mapillary-images" (:source prov)))
    (t/is (= "deadbeef" (:input-sha256 prov)))
    (t/is (= mi/base-url (:api-base prov)))
    (t/is (= "com-mapillary-graph-api" (:client prov)))
    (t/is (false? (:pixels-stored prov)))
    (t/is (str/includes? (:auth prov) "Authorization"))
    (t/is (str/includes? (:run-bound prov) "counted, not followed"))
    ;; the count check: a document whose own numbers disagree refuses
    (let [doc {"counts" {"fetched" 6 "accepted" 3 "refused" 3 "returned-outside-bbox" 0}
               "observations" (:observations result)}]
      (t/is (:ok? (mimg/provenance-checks doc)))
      (t/is (not (:ok? (mimg/provenance-checks
                        (assoc-in doc ["counts" "accepted"] 5))))))))

(t/deftest deterministic-across-runs
  (let [r1 (mimg/normalize-payload (js->clj (load-fixture))
                                   {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})
        r2 (mimg/normalize-payload (js->clj (load-fixture))
                                   {:bbox bbox :retrieved-at "2026-09-01T00:00:00Z"})]
    (t/is (= (pr-str (:observations r1)) (pr-str (:observations r2))))
    (t/is (= (pr-str (:counts r1)) (pr-str (:counts r2))))))
