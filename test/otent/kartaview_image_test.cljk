(ns otent.kartaview-image-test
  "Bounded tests for the one-photo pixel sample (offline, synthetic bytes)."
  (:require [clojure.test :as t]
            [otent.kartaview-image :as kvi]))

(def ^:private photo
  {"id" "9c1d2f0a-3b4e-4f56-a7b8-1c2d3e4f5a6b"
   "sequenceId" "4a8b2c1d-7777-4aaa-bbbb-2e3f4a5b6c7d"
   "sequenceIndex" "42"
   "lng" "139.7671"
   "lat" "35.6812"
   "shotDate" "2025-04-12T02:14:55+00:00"
   "heading" "91.5"
   "projection" "equirectangular"
   "gpsAccuracy" "1.8"
   "width" "2048"
   "height" "1536"
   "visibility" "public"
   "status" "active"
   "autoImgProcessingResult" "BLURRED"
   "imageProcUrl"
   "https://api.openstreetcam.org/2.0/photo/9c1d2f0a-3b4e-4f56-a7b8-1c2d3e4f5a6b/processed.jpg"})

(def ^:private retrieved-at "2026-09-02T06:00:00.000Z")

(def ^:private pixel
  {:sha256 "5f1c1e0a91d4e1d0a2d8e8f6f0e2b9a1c3d4e5f60718293a4b5c6d7e8f9a0b1c"
   :byte-size 51200
   :content-type "image/jpeg"
   :stored false})

(t/deftest admissible-photo-passes-all-gates
  (let [p (kvi/photo->pixel-permission photo)]
    (t/is (:ok? p))
    (t/is (= "https://api.openstreetcam.org/2.0/photo/9c1d2f0a-3b4e-4f56-a7b8-1c2d3e4f5a6b/processed.jpg"
             (kvi/pixel-url-of (:photo p))))))

(t/deftest unblurred-unknown-flag-and-withdrawn-photos-are-refused
  (t/is (= :kartaview-image/not-provider-blurred
           (:error (kvi/photo->pixel-permission
                    (assoc photo "autoImgProcessingResult" "UNPROCESSED")))))
  (t/is (= :kartaview-image/not-provider-blurred
           (:error (kvi/photo->pixel-permission
                    (assoc photo "autoImgProcessingResult" nil)))))
  (t/is (= :kartaview-image/not-active
           (:error (kvi/photo->pixel-permission (assoc photo "status" "deleted")))))
  (t/is (= :kartaview-image/not-public
           (:error (kvi/photo->pixel-permission (assoc photo "visibility" "private"))))))

(t/deftest swapped-geometry-is-refused-not-repaired
  (t/is (= :kartaview-image/invalid-geometry
           (:error (kvi/photo->pixel-permission
                    (assoc photo "lng" "35.6812" "lat" "139.7671")))))
  (t/is (= :kartaview-image/invalid-geometry
           (:error (kvi/photo->pixel-permission (assoc photo "lng" "not-a-number"))))))

(t/deftest missing-capture-time-or-pixel-url-refuses-the-fetch
  (t/is (= :kartaview-image/missing-capture-time
           (:error (kvi/photo->pixel-permission (assoc photo "shotDate" "")))))
  (t/is (= :kartaview-image/missing-pixel-url
           (:error (kvi/photo->pixel-permission (assoc photo "imageProcUrl" ""))))))

(t/deftest record-carries-everything-the-scope-names
  (let [r (kvi/image->record photo pixel retrieved-at)]
    (t/is (= "kartaview-image-photo:9c1d2f0a-3b4e-4f56-a7b8-1c2d3e4f5a6b"
             (:observation/asset-id r)))
    (t/is (= "kartaview-image" (:observation/source-id r)))
    (t/is (= "2025-04-12T02:14:55+00:00" (:observation/capture-time r)))
    (t/is (= retrieved-at (:observation/ingested-at r)))
    (t/is (= [139.7671 35.6812] (get-in r [:observation/footprint :coordinates])))
    (t/is (= "EPSG:4326 (lon,lat order)" (:observation/crs r)))
    (t/is (= 1.8 (:observation/spatial-uncertainty-m r)))
    (t/is (= 91.5 (get-in r [:observation/orientation :heading-deg])))
    (t/is (= "4a8b2c1d-7777-4aaa-bbbb-2e3f4a5b6c7d" (:observation/sequence-id r)))
    (t/is (= 42 (:observation/sequence-index r)))
    (t/is (= "CC-BY-SA 4.0 (KartaView terms of use)" (:observation/licence r)))
    (t/is (string? (:observation/attribution r)))
    (t/is (= "5f1c1e0a91d4e1d0a2d8e8f6f0e2b9a1c3d4e5f60718293a4b5c6d7e8f9a0b1c"
             (get-in r [:observation/pixel :sha256])))
    (t/is (= 51200 (get-in r [:observation/pixel :byte-size])))
    (t/is (false? (get-in r [:observation/pixel :stored])))
    (t/is (= 1 (get-in r [:observation/pixel :requests-made])))
    (t/is (string? (get-in r [:observation/pixel :permission-basis])))
    (t/is (true? (get-in r [:observation/privacy :provider-blurred])))
    (t/is (= :unknown (:observation/resolution-or-gsd r)))
    (t/is (= :unknown (:observation/sensor r)))))

(t/deftest missing-numeric-fields-stay-unknown
  (let [r (kvi/image->record (dissoc photo "gpsAccuracy" "heading" "sequenceIndex")
                             pixel retrieved-at)]
    (t/is (= :unknown (:observation/spatial-uncertainty-m r)))
    (t/is (= :unknown (:observation/orientation r)))
    (t/is (= :unknown (:observation/sequence-index r)))))

(t/deftest the-check-refuses-records-that-cannot-prove-themselves
  (let [r (kvi/image->record photo pixel retrieved-at)]
    (t/is (:ok? (kvi/check-record r)))
    (t/is (= :kartaview-image/missing-pixel-hash
             (:error (kvi/check-record (assoc-in r [:observation/pixel :sha256] nil)))))
    (t/is (= :kartaview-image/missing-permission-basis
             (:error (kvi/check-record (assoc-in r [:observation/pixel :permission-basis] "")))))
    (t/is (= :kartaview-image/missing-licence
             (:error (kvi/check-record (assoc r :observation/licence :unknown)))))))

(t/deftest an-identity-plane-in-the-record-refuses-the-run
  (let [r (kvi/image->record photo pixel retrieved-at)]
    (t/is (= :kartaview-image/privacy-redaction
             (:error (kvi/check-record (assoc-in r [:observation/source-url] "mailto:up@ex.com")))))
    (t/is (= :kartaview-image/privacy-redaction
             (:error (kvi/check-record (assoc-in r [:observation/privacy :MAPSettingsEmail] "a@b")))))))

(t/deftest record-is-deterministic
  (t/is (= (kvi/image->record photo pixel retrieved-at)
           (kvi/image->record photo pixel retrieved-at))))
