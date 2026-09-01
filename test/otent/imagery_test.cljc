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
  (t/is (true? (imagery/licence-allowed? (:licence imagery/sample)))))
