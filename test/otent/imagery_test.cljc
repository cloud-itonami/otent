(ns otent.imagery-test
  "Provenance, licence and refusal controls for the earth-imagery slice."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]
            [otent.imagery :as imagery]))

(def fixture-name "gibs-bluemarble-500m.jpg")

(defn fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures" fixture-name))

(t/deftest licence-allowlist-test
  (t/is (true? (imagery/licence-allowed? :nasa-public-domain)))
  (t/is (true? (imagery/licence-allowed? :cc0)))
  (t/is (false? (imagery/licence-allowed? :unknown-licence)))
  (t/is (false? (imagery/licence-allowed? nil))))

(t/deftest refusal-test
  (t/testing "an off-allowlist licence is refused with a reason"
    (t/is (true? (:refused (imagery/refusal :all-rights-reserved)))))
  (t/testing "an allowlisted licence is not refused"
    (t/is (nil? (imagery/refusal :nasa-public-domain)))))

(t/deftest provenance-complete-test
  (t/is (true? (imagery/provenance-complete? imagery/sample)))
  (t/testing "a record missing any required field is a rumour, not a record"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/sample :payload-sha256))))
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/sample :crs))))))

(t/deftest manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/sample)]
    (t/is (= (:asset-id imagery/sample) (:asset-id m)))
    (t/is (= (:payload-sha256 imagery/sample) (:payload-sha256 m)))
    (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m)))
    (t/is (true? (:level-0-only m))
          "the manifest claims level 0 only -- one tile, nothing wider")))

(t/deftest object-readback-test
  (t/testing "the fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/sample) sha256)))))

(t/deftest sample-licences-test
  (t/is (true? (imagery/licence-allowed? (:licence imagery/sample))))
  (t/is (true? (imagery/licence-allowed? (:licence imagery/modis-terra-truecolor-sample)))))

;; ---- the second bounded sample: MODIS Terra true colour, dated capture

(def modis-fixture-name "modis-terra-truecolor-20260901-z0.jpeg")

(defn modis-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures" modis-fixture-name))

(t/deftest modis-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/modis-terra-truecolor-sample)))
  (t/testing "a dated record without its capture time is a rumour"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/modis-terra-truecolor-sample :capture-time))))))

(t/deftest modis-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/modis-terra-truecolor-sample)]
    (t/is (= (:asset-id imagery/modis-terra-truecolor-sample) (:asset-id m)))
    (t/is (= "2026-09-01" (:capture-time m))
          "the manifest states the one declared capture date, verbatim")
    (t/is (re-find #"MODIS_Terra_CorrectedReflectance_TrueColor"
                   (:what-exists m)))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))
          "the manifest's bounds are the record's footprint, not more")))

(t/deftest modis-object-readback-test
  (t/testing "the modis fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (modis-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/modis-terra-truecolor-sample) sha256)))))

(t/deftest modis-verify-sample-test
  (let [bytes (fs/readFileSync (modis-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/modis-terra-truecolor-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest modis-licence-refusal-test
  (t/testing "the same refusal gate applies to the dated source"
    (t/is (nil? (imagery/refusal (:licence imagery/modis-terra-truecolor-sample))))
    (t/is (true? (:refused (imagery/refusal "Map data (c) unknown terms"))))))

;; ---- the third bounded sample: ASTER GDEM colour index, half-globe tile

(def aster-fixture-name "aster-gdem-color-31m-z0.png")

(defn aster-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures" aster-fixture-name))

(t/deftest aster-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/aster-gdem-color-sample)))
  (t/testing "a static record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/aster-gdem-color-sample :sensor))))))

(t/deftest aster-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/aster-gdem-color-sample)]
    (t/is (= (:asset-id imagery/aster-gdem-color-sample) (:asset-id m)))
    (t/is (re-find #"ASTER_GDEM_Color_Index" (:what-exists m)))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the bounds are the half-globe tile, never the planet"
      (t/is (= [-180.0 0.0 0.0 90.0] (:bounds-epg4326-deg m)))
      (t/is (re-find #"north-west half\s+of the globe" (:what-exists m))))))

(t/deftest aster-object-readback-test
  (t/testing "the aster fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (aster-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/aster-gdem-color-sample) sha256)))))

(t/deftest aster-verify-sample-test
  (let [bytes (fs/readFileSync (aster-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/aster-gdem-color-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest aster-licence-allowed-test
  (t/testing "the same allowlist gate applies to the elevation source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/aster-gdem-color-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/aster-gdem-color-sample))))))
