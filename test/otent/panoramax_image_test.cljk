(ns otent.panoramax-image-test
  "Bounded tests for the one-image pixel sample (offline, synthetic bytes)."
  (:require [clojure.test :as t]
            [otent.panoramax-image :as pxi]))

(def ^:private item
  {"id" "9f79a75d-3ff0-4e0e-8b52-4b8ef3a2cf11"
   "type" "Feature"
   "geometry" {"type" "Point" "coordinates" [139.7671 35.6812]}
   "properties"
   {"datetime" "2025-04-12T02:14:55+00:00"
    "license" "etalab-2.0"
    "geovisio:status" "ready"
    "geovisio:visibility" "anyone"
    "geovisio:rank_in_collection" 42
    "collection" "d9-decroissant"
    "view:azimuth" 91.5
    "quality:horizontal_accuracy" 1.8
    "pers:interior_orientation"
    {"camera_model" "GoPro MAX"
     "camera_manufacturer" "GoPro"
     "sensor_array_dimensions" [5376 2688]}}
   "assets" {"sd" {"href" "https://api.panoramax.xyz/api/collections/d9/items/9f79a75d/sd.jpg"}}
   "links" [{"href" "https://api.panoramax.xyz/api/collections/d9/items/9f79a75d"
             "rel" "self"}]})

(def ^:private retrieved-at "2026-09-02T03:00:00.000Z")

(def ^:private pixel
  {:sha256 "5f1c1e0a91d4e1d0a2d8e8f6f0e2b9a1c3d4e5f60718293a4b5c6d7e8f9a0b1c"
   :byte-size 51200
   :content-type "image/jpeg"
   :stored false})

(t/deftest admissible-item-passes-all-gates
  (let [p (pxi/item->pixel-permission item)]
    (t/is (:ok? p))
    (t/is (= "https://api.panoramax.xyz/api/collections/d9/items/9f79a75d/sd.jpg"
             (pxi/pixel-url-of (:feature p))))))

(t/deftest an-unknown-licence-is-never-read-as-permission
  (let [p (pxi/item->pixel-permission (assoc-in item ["properties" "license"] "all-rights-reserved"))]
    (t/is (= :panoramax-image/licence-does-not-permit-pixels (:error p))))
  (t/is (= :panoramax-image/licence-does-not-permit-pixels
           (:error (pxi/item->pixel-permission
                    (update-in item ["properties"] dissoc "license"))))))

(t/deftest unprocessed-and-non-public-items-are-refused
  (t/is (= :panoramax-image/not-processed
           (:error (pxi/item->pixel-permission
                    (assoc-in item ["properties" "geovisio:status"] "waiting-for-process")))))
  (t/is (= :panoramax-image/not-public
           (:error (pxi/item->pixel-permission
                    (assoc-in item ["properties" "geovisio:visibility"] "hidden"))))))

(t/deftest swapped-geometry-is-refused-not-repaired
  (t/is (= :panoramax-image/invalid-geometry
           (:error (pxi/item->pixel-permission
                    (assoc-in item ["geometry" "coordinates"] [35.6812 139.7671]))))))

(t/deftest record-carries-everything-the-scope-names
  (let [r (pxi/image->record item pixel retrieved-at)]
    (t/is (= "panoramax-image:9f79a75d-3ff0-4e0e-8b52-4b8ef3a2cf11"
             (:observation/asset-id r)))
    (t/is (= "panoramax-image" (:observation/source-id r)))
    (t/is (= "2025-04-12T02:14:55+00:00" (:observation/capture-time r)))
    (t/is (= retrieved-at (:observation/ingested-at r)))
    (t/is (= [139.7671 35.6812] (get-in r [:observation/footprint :coordinates])))
    (t/is (= 1.8 (:observation/spatial-uncertainty-m r)))
    (t/is (= 91.5 (get-in r [:observation/orientation :heading-deg])))
    (t/is (= "d9-decroissant" (:observation/sequence-id r)))
    (t/is (= 42 (:observation/sequence-index r)))
    (t/is (= "etalab-2.0" (:observation/licence r)))
    (t/is (= "5f1c1e0a91d4e1d0a2d8e8f6f0e2b9a1c3d4e5f60718293a4b5c6d7e8f9a0b1c"
             (get-in r [:observation/pixel :sha256])))
    (t/is (= 51200 (get-in r [:observation/pixel :byte-size])))
    (t/is (false? (get-in r [:observation/pixel :stored])))
    (t/is (= 1 (get-in r [:observation/pixel :requests-made])))
    (t/is (string? (get-in r [:observation/pixel :permission-basis])))
    (t/is (false? (get-in r [:observation/privacy :provider-blur-verified])))))

(t/deftest the-check-refuses-records-that-cannot-prove-themselves
  (let [r (pxi/image->record item pixel retrieved-at)]
    (t/is (:ok? (pxi/check-record r)))
    (t/is (= :panoramax-image/missing-pixel-hash
             (:error (pxi/check-record (assoc-in r [:observation/pixel :sha256] nil)))))
    (t/is (= :panoramax-image/missing-permission-basis
             (:error (pxi/check-record (assoc-in r [:observation/pixel :permission-basis] "")))))
    (t/is (= :panoramax-image/missing-licence
             (:error (pxi/check-record (assoc r :observation/licence :unknown)))))))

(t/deftest an-identity-plane-in-the-record-refuses-the-run
  (let [r (pxi/image->record item pixel retrieved-at)]
    (t/is (= :panoramax-image/privacy-redaction
             (:error (pxi/check-record (assoc-in r [:observation/source-url] "mailto:up@ex.com")))))
    (t/is (= :panoramax-image/privacy-redaction
             (:error (pxi/check-record (assoc-in r [:observation/privacy :MAPSettingsEmail] "a@b")))))))

(t/deftest an-object-collection-id-is-carried-as-its-id-or-unknown
  (t/is (= "d9-decroissant"
           (#'pxi/as-id "d9-decroissant")))
  (t/is (= "d9-decroissant"
           (#'pxi/as-id {"id" "d9-decroissant" "semantics" []})))
  (t/is (= :unknown (#'pxi/as-id {"semantics" []})))
  (t/is (= :unknown (#'pxi/as-id 42))))

(t/deftest record-is-deterministic
  (t/is (= (pxi/image->record item pixel retrieved-at)
           (pxi/image->record item pixel retrieved-at))))
