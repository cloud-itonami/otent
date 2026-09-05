(ns otent.natural-earth-test
  "The catalogue and its controls, without touching the network.

  The interesting cases are the refusals: an unknown id, a body that is
  not a zip, a size that drifted out of band, a hash that no longer
  matches what was pinned. Each one exists because ingesting changed
  bytes under a fixed identity would look identical to a healthy run in
  every downstream manifest."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [otent.natural-earth :as ne]))

(def ^:private asset (ne/get-asset "NE1_50M_SR_W"))

(deftest catalogue-is-complete
  (testing "the pinned asset resolves"
    (is (some? asset)))
  (testing "an unknown id refuses, and names what IS known"
    (let [r (ne/plan "NOT_AN_ASSET")]
      (is (false? (:ok? r)))
      (is (= :natural-earth/unknown-asset (:error r)))
      (is (re-find #"NE1_50M_SR_W" (:detail r)))))
  (testing "every catalogued asset carries every catalogue field"
    (doseq [[id a] ne/assets]
      (doseq [k [:id :title :url :object-format :entries :width-px :height-px
                 :degrees-per-pixel :gsd :crs :sensor :spectral-bands
                 :capture-time :licence :licence-detail :attribution
                 :sha256 :min-bytes :max-bytes]]
        (is (contains? a k) (str id " missing " k)))))
  (testing "the licence is the one the scope allows for Natural Earth"
    (is (= "public-domain" (:licence asset))))
  (testing "the sha256 is actually pinned, not nil"
    (is (re-find #"[0-9a-f]{64}" (:sha256 asset)))))

(deftest size-bound-refuses-drift
  (testing "inside the band passes"
    (is (:ok? (ne/check-size asset (:min-bytes asset))))
    (is (:ok? (ne/check-size asset (:max-bytes asset)))))
  (testing "outside the band refuses, naming the bound"
    (let [r (ne/check-size asset (dec (:min-bytes asset)))]
      (is (= :natural-earth/size-out-of-bounds (:error r)))
      (is (re-find #"\[" (:detail r))))
    (is (= :natural-earth/size-out-of-bounds
           (:error (ne/check-size asset (inc (:max-bytes asset)))))))
  (testing "a missing Content-Length is a refusal, not a pass"
    (is (= :natural-earth/no-content-length (:error (ne/check-size asset nil))))))

(deftest magic-refuses-non-zip
  ;; A real Buffer whose first four bytes are the zip magic.
  (let [zip-buf (js/Buffer.from #js [0x50 0x4B 0x03 0x04 0xFF 0xFF])
        html-buf (js/Buffer.from "<!DOCTYPE html>404 not found")]
    (testing "PK\\x03\\x04 passes"
      (is (:ok? (ne/check-magic zip-buf))))
    (testing "an error page's bytes refuse"
      (let [r (ne/check-magic html-buf)]
        (is (= :natural-earth/not-a-zip (:error r)))
        (is (re-find #"<!DO" (:detail r)))))))

(deftest sha-refuses-changed-bytes
  (testing "the pinned hash passes"
    (is (:ok? (ne/check-sha asset (:sha256 asset)))))
  (testing "a different hash refuses, and says re-measure rather than overwrite"
    (let [r (ne/check-sha asset "0000000000000000000000000000000000000000000000000000000000000000")]
      (is (= :natural-earth/sha-mismatch (:error r)))
      (is (re-find #"re-measure" (:detail r))))))

(deftest manifest-names-everything
  (let [m (ne/manifest asset {:key "otent/natural-earth/NE1_50M_SR_W/abc/NE1_50M_SR_W.zip"
                              :retrieved-at "2026-09-01T00:00:00.000Z"
                              :bytes 88413091})]
    (testing "every required imagery field has a value"
      (doseq [k [:source-id :source-url :asset-id :capture-time :ingested-at
                 :footprint :crs :resolution-or-gsd :sensor :spectral-bands
                 :licence :attribution :content-hash]]
        (is (some? (get m k)) (str "manifest missing " k))))
    (testing "the content hash is the pinned one, with its algorithm"
      (is (= "sha256" (get-in m [:content-hash :algorithm])))
      (is (= (:sha256 asset) (get-in m [:content-hash :value]))))
    (testing "the object record points at real bytes"
      (is (= "otent/natural-earth/NE1_50M_SR_W/abc/NE1_50M_SR_W.zip"
             (get-in m [:object :key])))
      (is (= 88413091 (get-in m [:object :bytes]))))
    (testing "footprint is the whole-earth polygon, closed"
      (let [ring (first (get-in m [:footprint :coordinates]))]
        (is (= (first ring) (last ring)))
        (is (= 5 (count ring)))))
    (testing "capture-time is honest about being a composite"
      (is (= :static-composite (get-in m [:capture-time :type])))))
  (testing "ingested-at comes from the caller, not a hidden clock"
    (is (= "1970-01-01T00:00:00.000Z"
           (:ingested-at (ne/manifest asset {:retrieved-at "1970-01-01T00:00:00.000Z"
                                             :key "k" :bytes 1}))))))
