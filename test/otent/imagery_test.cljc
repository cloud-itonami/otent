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

;; ---- the seventh bounded sample: MODIS Aqua true colour, dated, level 0

(def aqua-truecolor-fixture-name "modis-aqua-truecolor-20260902-z0.jpeg")

(defn aqua-truecolor-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             aqua-truecolor-fixture-name))

(t/deftest aqua-truecolor-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/modis-aqua-truecolor-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/modis-aqua-truecolor-sample
                           :capture-time))))))

(t/deftest aqua-truecolor-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/modis-aqua-truecolor-sample)]
    (t/is (= (:asset-id imagery/modis-aqua-truecolor-sample) (:asset-id m)))
    (t/is (re-find #"MODIS_Aqua_CorrectedReflectance_TrueColor"
                   (:what-exists m)))
    (t/testing "the declared capture date is stated verbatim"
      (t/is (re-find #"2026-09-02" (:what-exists m)))
      (t/is (= "2026-09-02" (:capture-time m))))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the level-0 tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest aqua-truecolor-object-readback-test
  (t/testing "the Aqua true-colour fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (aqua-truecolor-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256 imagery/modis-aqua-truecolor-sample) sha256)))))

(t/deftest aqua-truecolor-verify-sample-test
  (let [bytes (fs/readFileSync (aqua-truecolor-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample imagery/modis-aqua-truecolor-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest aqua-truecolor-licence-allowed-test
  (t/testing "the same allowlist gate applies to the Aqua daytime source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/modis-aqua-truecolor-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/modis-aqua-truecolor-sample))))))

;; ---- the eighth bounded sample: Landsat WELD true colour, annual, level 0

(def weld-truecolor-fixture-name "landsat-weld-truecolor-1985-z0.jpeg")

(defn weld-truecolor-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             weld-truecolor-fixture-name))

(t/deftest weld-truecolor-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/landsat-weld-truecolor-1985-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/landsat-weld-truecolor-1985-sample
                           :capture-time))))))

(t/deftest weld-truecolor-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/landsat-weld-truecolor-1985-sample)]
    (t/is (= (:asset-id imagery/landsat-weld-truecolor-1985-sample)
             (:asset-id m)))
    (t/is (re-find #"Landsat_WELD_CorrectedReflectance_TrueColor_Global_Annual"
                   (:what-exists m)))
    (t/testing "the declared composite period is stated verbatim"
      (t/is (re-find #"1985-12-01" (:what-exists m)))
      (t/is (= "1985-12-01" (:capture-time m))))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the half-globe tile states its bounds, never the planet"
      (t/is (= [-180.0 0.0 0.0 90.0] (:bounds-epg4326-deg m)))
      (t/is (re-find #"north-west half\s+of the globe" (:what-exists m))))))

(t/deftest weld-truecolor-object-readback-test
  (t/testing "the WELD fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (weld-truecolor-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/landsat-weld-truecolor-1985-sample) sha256)))))

(t/deftest weld-truecolor-verify-sample-test
  (let [bytes (fs/readFileSync (weld-truecolor-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/landsat-weld-truecolor-1985-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest weld-truecolor-licence-allowed-test
  (t/testing "the same allowlist gate applies to the Landsat source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/landsat-weld-truecolor-1985-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/landsat-weld-truecolor-1985-sample))))))

;; ---- the ninth bounded sample: MODIS Terra Bands721 false colour, level 0

(def bands721-fixture-name "modis-terra-bands721-20260901-z0.jpeg")

(defn bands721-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             bands721-fixture-name))

(t/deftest bands721-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/modis-terra-bands721-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/modis-terra-bands721-sample
                           :capture-time))))))

(t/deftest bands721-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/modis-terra-bands721-sample)]
    (t/is (= (:asset-id imagery/modis-terra-bands721-sample)
             (:asset-id m)))
    (t/is (re-find #"MODIS_Terra_CorrectedReflectance_Bands721"
                   (:what-exists m)))
    (t/testing "the declared capture date is stated verbatim"
      (t/is (re-find #"2026-09-01" (:what-exists m)))
      (t/is (= "2026-09-01" (:capture-time m))))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))
          "the manifest's bounds are the record's footprint, not more")))

(t/deftest bands721-object-readback-test
  (t/testing "the Bands721 fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (bands721-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/modis-terra-bands721-sample) sha256)))))

(t/deftest bands721-verify-sample-test
  (let [bytes (fs/readFileSync (bands721-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/modis-terra-bands721-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest bands721-licence-allowed-test
  (t/testing "the same allowlist gate applies to the Bands721 source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/modis-terra-bands721-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/modis-terra-bands721-sample))))))

;; ---- the tenth bounded sample: VIIRS NOAA-20 true colour, dated, level 0

(def noaa20-truecolor-fixture-name "viirs-noaa20-truecolor-20260901-z0.jpeg")

(defn noaa20-truecolor-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             noaa20-truecolor-fixture-name))

(t/deftest noaa20-truecolor-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-noaa20-truecolor-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-noaa20-truecolor-sample
                           :capture-time))))))

(t/deftest noaa20-truecolor-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-noaa20-truecolor-sample)]
    (t/is (= (:asset-id imagery/viirs-noaa20-truecolor-sample)
             (:asset-id m)))
    (t/is (re-find #"VIIRS_NOAA20_CorrectedReflectance_TrueColor"
                   (:what-exists m)))
    (t/testing "the declared capture date is stated verbatim"
      (t/is (re-find #"2026-09-01" (:what-exists m)))
      (t/is (= "2026-09-01" (:capture-time m))))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the level-0 tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest noaa20-truecolor-object-readback-test
  (t/testing "the NOAA-20 fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (noaa20-truecolor-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/viirs-noaa20-truecolor-sample) sha256)))))

(t/deftest noaa20-truecolor-verify-sample-test
  (let [bytes (fs/readFileSync (noaa20-truecolor-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/viirs-noaa20-truecolor-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest noaa20-truecolor-licence-allowed-test
  (t/testing "the same allowlist gate applies to the NOAA-20 daytime source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-noaa20-truecolor-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-noaa20-truecolor-sample))))))

;; ---- the eleventh bounded sample: Black Marble 2016, static, level 0

(def black-marble-fixture-name "viirs-black-marble-2016-z0.png")

(defn black-marble-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             black-marble-fixture-name))

(t/deftest black-marble-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-black-marble-2016-sample)))
  (t/testing "a static record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-black-marble-2016-sample
                           :sensor))))))

(t/deftest black-marble-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-black-marble-2016-sample)]
    (t/is (= (:asset-id imagery/viirs-black-marble-2016-sample)
             (:asset-id m)))
    (t/is (re-find #"VIIRS_Black_Marble" (:what-exists m)))
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the static global tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest black-marble-object-readback-test
  (t/testing "the black-marble fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (black-marble-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/viirs-black-marble-2016-sample) sha256)))))

(t/deftest black-marble-verify-sample-test
  (let [bytes (fs/readFileSync (black-marble-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/viirs-black-marble-2016-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest black-marble-licence-allowed-test
  (t/testing "the same allowlist gate applies to the Black Marble source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-black-marble-2016-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-black-marble-2016-sample))))))

;; ---- the twelfth bounded sample: MODIS Terra 8-day surface
;; ---- reflectance bands 1-4-3, one declared period start, level 0

(def bands143-fixture-name "modis-terra-bands143-8day-20260202-z0.jpeg")

(defn bands143-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             bands143-fixture-name))

(t/deftest bands143-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/modis-terra-bands143-8day-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/modis-terra-bands143-8day-sample
                           :capture-time))))))

(t/deftest bands143-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/modis-terra-bands143-8day-sample)]
    (t/is (= (:asset-id imagery/modis-terra-bands143-8day-sample)
             (:asset-id m)))
    (t/is (re-find #"MODIS_Terra_L3_SurfaceReflectance_Bands143_8Day"
                   (:what-exists m)))
    (t/is (= "2026-02-02" (:capture-time m))
          "the declared 8-day period start is carried verbatim")
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the global tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest bands143-object-readback-test
  (t/testing "the bands143 fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (bands143-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/modis-terra-bands143-8day-sample) sha256)))))

(t/deftest bands143-verify-sample-test
  (let [bytes (fs/readFileSync (bands143-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/modis-terra-bands143-8day-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest bands143-licence-allowed-test
  (t/testing "the same allowlist gate applies to the bands143 source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/modis-terra-bands143-8day-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/modis-terra-bands143-8day-sample))))))

;; ---- the thirteenth bounded sample: VIIRS SNPP day/night band
;; ---- enhanced near-constant contrast, one declared capture date, level 0

(def dnb-encc-fixture-name "viirs-snpp-dnb-encc-20230707-z0.png")

(defn dnb-encc-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             dnb-encc-fixture-name))

(t/deftest dnb-encc-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-snpp-dnb-encc-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-snpp-dnb-encc-sample
                           :capture-time))))))

(t/deftest dnb-encc-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-snpp-dnb-encc-sample)]
    (t/is (= (:asset-id imagery/viirs-snpp-dnb-encc-sample)
             (:asset-id m)))
    (t/is (re-find #"VIIRS_SNPP_DayNightBand_ENCC" (:what-exists m)))
    (t/is (= "2023-07-07" (:capture-time m))
          "the declared capture date is carried verbatim")
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the global tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest dnb-encc-object-readback-test
  (t/testing "the dnb-encc fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (dnb-encc-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/viirs-snpp-dnb-encc-sample) sha256)))))

(t/deftest dnb-encc-verify-sample-test
  (let [bytes (fs/readFileSync (dnb-encc-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/viirs-snpp-dnb-encc-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest dnb-encc-licence-allowed-test
  (t/testing "the same allowlist gate applies to the dnb-encc source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-snpp-dnb-encc-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-snpp-dnb-encc-sample))))))

;; ---- the fourteenth bounded sample: VIIRS NOAA-20 BandsM11-I2-I1
;; ---- false colour, one declared capture date, level 0

(def n20-m11-fixture-name "viirs-noaa20-bandsm11-i2-i1-20260901-z0.jpeg")

(defn n20-m11-fixture-path []
  (path/join (js/process.cwd) "test" "otent" "fixtures"
             n20-m11-fixture-name))

(t/deftest n20-m11-provenance-complete-test
  (t/is (true? (imagery/provenance-complete?
                imagery/viirs-noaa20-bandsm11-i2-i1-sample)))
  (t/testing "a dated record still carries every provenance key"
    (t/is (false? (imagery/provenance-complete?
                   (dissoc imagery/viirs-noaa20-bandsm11-i2-i1-sample
                           :capture-time))))))

(t/deftest n20-m11-manifest-states-exactly-what-exists-test
  (let [m (imagery/manifest imagery/viirs-noaa20-bandsm11-i2-i1-sample)]
    (t/is (= (:asset-id imagery/viirs-noaa20-bandsm11-i2-i1-sample)
             (:asset-id m)))
    (t/is (re-find #"VIIRS_NOAA20_CorrectedReflectance_BandsM11-I2-I1"
                   (:what-exists m)))
    (t/is (= "2026-09-01" (:capture-time m))
          "the declared capture date is carried verbatim")
    (t/is (true? (:level-0-only m))
          "level 0 only -- one tile, nothing wider")
    (t/testing "the global tile states the planet it covers"
      (t/is (= [-180.0 180.0 -90.0 90.0] (:bounds-epg4326-deg m))))))

(t/deftest n20-m11-object-readback-test
  (t/testing "the n20-m11 fixture bytes on disk hash to what the record claims"
    (let [bytes (fs/readFileSync (n20-m11-fixture-path))
          sha256 (-> (crypto/createHash "sha256")
                     (.update bytes)
                     (.digest "hex"))]
      (t/is (= (:payload-sha256
                imagery/viirs-noaa20-bandsm11-i2-i1-sample) sha256)))))

(t/deftest n20-m11-verify-sample-test
  (let [bytes (fs/readFileSync (n20-m11-fixture-path))
        sha256 (-> (crypto/createHash "sha256")
                   (.update bytes)
                   (.digest "hex"))
        v (imagery/verify-sample
           imagery/viirs-noaa20-bandsm11-i2-i1-sample sha256)]
    (t/is (true? (:provenance-complete v)))
    (t/is (true? (:sha256-matches v)))))

(t/deftest n20-m11-licence-allowed-test
  (t/testing "the same allowlist gate applies to the n20-m11 source"
    (t/is (true? (imagery/licence-allowed?
                  (:licence imagery/viirs-noaa20-bandsm11-i2-i1-sample))))
    (t/is (nil? (imagery/refusal
                 (:licence imagery/viirs-noaa20-bandsm11-i2-i1-sample))))))
