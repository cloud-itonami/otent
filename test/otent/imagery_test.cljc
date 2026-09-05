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

;; ---- the fourth bounded sample: MODIS Terra Bands367, dated, level 0

(def bands367-fixture-name "modis-terra-bands367-20260901-z0.jpeg")

(defn bands367-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures" bands367-fixture-name))

(t/deftest bands367-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/modis-terra-bands367-sample)))
  (t/testing "a record missing the capture-time claim is not a record"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/modis-terra-bands367-sample
                           :capture-time))))))

(t/deftest bands367-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/modis-terra-bands367-sample)]
    (t/is (= (:asset-id imagery/modis-terra-bands367-sample) (:asset-id m)))
    (t/is (re-find #"MODIS_Terra_CorrectedReflectance_Bands367" (:what-exists m)))
    (t/is (re-find #"declared capture date 2026-09-01" (:what-exists m)))
    (t/is (true? (:level-0-only m)))
    (t/is (= "2026-09-01" (:capture-time m)))
    (t/testing "the dated global tile still states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest bands367-object-readback-test
  (t/testing "the bands367 fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (bands367-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/modis-terra-bands367-sample) sha256)))))

(t/deftest bands367-verify-sample-test
  (let [bytes (fs/readFileSync (bands367-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/modis-terra-bands367-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest bands367-licence-allowed-test
  (t/testing "the same allowlist gate applies to the false-colour source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/modis-terra-bands367-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/modis-terra-bands367-sample))))))

;; ---- the fifth bounded sample: VIIRS City Lights 2012, static, level 0

(def citylights-fixture-name "viirs-citylights-2012-z0.jpeg")

(defn citylights-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures" citylights-fixture-name))

(t/deftest citylights-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-citylights-2012-sample)))
  (t/testing "a static record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-citylights-2012-sample
                           :retrieved-at))))))

(t/deftest citylights-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-citylights-2012-sample)]
    (t/is (= (:asset-id imagery/viirs-citylights-2012-sample) (:asset-id m)))
    (t/is (re-find #"VIIRS_CityLights_2012" (:what-exists m)))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the static global tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest citylights-object-readback-test
  (t/testing "the city-lights fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (citylights-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/viirs-citylights-2012-sample) sha256)))))

(t/deftest citylights-verify-sample-test
  (let [bytes (fs/readFileSync (citylights-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/viirs-citylights-2012-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest citylights-licence-allowed-test
  (t/testing "the same allowlist gate applies to the night-lights source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-citylights-2012-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-citylights-2012-sample))))))

;; ---- the sixth bounded sample: VIIRS SNPP true colour, dated, level 0

(def snpp-truecolor-fixture-name "viirs-snpp-truecolor-20260901-z0.jpeg")

(defn snpp-truecolor-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             snpp-truecolor-fixture-name))

(t/deftest snpp-truecolor-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-snpp-truecolor-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-snpp-truecolor-sample
                           :capture-time))))))

(t/deftest snpp-truecolor-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-snpp-truecolor-sample)]
    (t/is (= (:asset-id imagery/viirs-snpp-truecolor-sample) (:asset-id m)))
    (t/is (re-find #"VIIRS_SNPP_CorrectedReflectance_TrueColor"
                   (:what-exists m)))
    (t/testing "the declared capture date is stated verbatim"
      (t/is (re-find #"2026-09-01" (:what-exists m)))
      (t/is (= "2026-09-01" (:capture-time m))))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the level-0 tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest snpp-truecolor-object-readback-test
  (t/testing "the SNPP true-colour fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (snpp-truecolor-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/viirs-snpp-truecolor-sample) sha256)))))

(t/deftest snpp-truecolor-verify-sample-test
  (let [bytes (fs/readFileSync (snpp-truecolor-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/viirs-snpp-truecolor-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest snpp-truecolor-licence-allowed-test
  (t/testing "the same allowlist gate applies to the VIIRS daytime source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-snpp-truecolor-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-snpp-truecolor-sample))))))
