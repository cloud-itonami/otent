(ns otent.mapillary-image-test
  "Bounded tests for the one-image pixel sample (offline, synthetic)."
  (:require [clojure.test :as t]
            [otent.mapillary-image :as mxi]))

(def ^:private feature
  {"id" "482194673015931"
   "geometry" {"type" "Point" "coordinates" [139.7654 35.6789]}
   "captured_at" 1755100800000
   "compass_angle" 271.5
   "is_pano" false
   "sequence" "q-9f79a75d"
   "thumb_1024_url" "https://mapillary.openstreetmap.media/482194673015931-1024.jpg"})

(t/deftest an-admissible-image-passes-all-gates
  (let [p (mxi/image->pixel-permission feature)]
    (t/is (:ok? p))
    (t/is (= "https://mapillary.openstreetmap.media/482194673015931-1024.jpg"
             (mxi/pixel-url-of (:feature p))))))

(t/deftest a-missing-or-unparseable-gate-refuses
  (t/is (= :mapillary-image/missing-asset-id
           (:error (mxi/image->pixel-permission (assoc feature "id" "")))))
  (t/is (= :mapillary-image/invalid-geometry
           (:error (mxi/image->pixel-permission
                    (assoc-in feature ["geometry" "coordinates"]
                              [35.6789 139.7654])))))
  (t/is (= :mapillary-image/missing-capture-time
           (:error (mxi/image->pixel-permission (assoc feature "captured_at" nil)))))
  (t/is (= :mapillary-image/no-published-pixel-url
           (:error (mxi/image->pixel-permission (assoc feature "thumb_1024_url" ""))))))

(t/deftest a-record-carries-everything-the-scope-names
  (let [r (mxi/image->record feature
                             {:sha256 "5f1c1e0a91d4e1d0a2d8e8f6f0e2b9a1c3d4e5f60718293a4b5c6d7e8f9a0b1c"
                              :byte-size 4096
                              :content-type "image/jpeg"
                              :stored false}
                             "2026-09-03T03:00:00.000Z")]
    (t/is (= "mapillary-image:482194673015931" (:observation/asset-id r)))
    (t/is (= "mapillary-image" (:observation/source r)))
    (t/is (= 1755100800000 (:observation/capture-time-ms r)))
    (t/is (= "https://www.mapillary.com/app/?pKey=482194673015931"
             (:observation/evidence-url r)))
    (t/is (= 271.5 (:observation/compass-angle-deg r)))
    (t/is (= "q-9f79a75d" (:observation/sequence-id r)))
    (t/is (false? (get-in r [:observation/privacy :provider-blur-verified])))
    (t/is (= 4096 (get-in r [:observation/pixel :byte-size])))
    (t/is (= 1 (get-in r [:observation/pixel :requests-made])))))

(t/deftest the-check-refuses-records-that-cannot-prove-themselves
  (let [r (mxi/image->record feature
                             {:sha256 "abc" :byte-size 4096
                              :content-type "image/jpeg" :stored false}
                             "2026-09-03T03:00:00.000Z")]
    (t/is (:ok? (mxi/check-record r)))
    (t/is (= :mapillary-image/missing-pixel-hash
             (:error (mxi/check-record (assoc-in r [:observation/pixel :sha256] nil)))))
    (t/is (= :mapillary-image/missing-permission-basis
             (:error (mxi/check-record (assoc-in r [:observation/pixel :permission-basis] "")))))
    (t/is (= :mapillary-image/privacy-redaction
             (:error (mxi/check-record
                      (assoc-in r [:observation/pixel :permission-basis] "contact ops@example.com")))))))

(t/deftest redaction-refuses-any-string-carrying-an-at-sign
  (t/is (false? (mxi/redacted? {"thumb_1024_url" "a@b.example"})))
  (t/is (false? (mxi/redacted? {"ok" {"nested" ["x@y"]}})))
  (t/is (true? (mxi/redacted? feature))))

(t/deftest bbox-check-refuses-before-any-request-is-built
  (t/is (= :mapillary-image/bbox-too-large (:error (mxi/check-bbox [0.0 0.0 0.02 0.005]))))
  (t/is (= :mapillary-image/bbox-inverted (:error (mxi/check-bbox [0.5 0.0 0.2 0.005]))))
  (t/is (:ok? (mxi/check-bbox [0.0 0.0 0.005 0.005]))))
