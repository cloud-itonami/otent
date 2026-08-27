(ns otent.parse-test
  "The parsers, against REAL captured payloads.

  The fixtures under `fixtures/` are byte-for-byte what CelesTrak, USGS and
  OpenSky returned on 2026-08-26. An invented payload tests the parser
  against the author's belief about the format, which is the belief that
  was wrong in the first place."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [otent.feeds.parse :as p]
            [otent.feeds.core :as feeds]
            [otent.governor :as gov]
            [otent.fixtures :as fx]))

(def now 1787700000000)
(def prov ["https://example.test/feed" now "sha256-of-the-payload"])

(defn- ingest-clock
  "The `now` a governor should be given for a CAPTURED payload.

  Not a hard-coded constant. A fixture is a recording of a moment, and the
  governor's future-slack rule compares against the ingest clock -- so a
  fixed `now` makes the test's verdict depend on whether the capture
  happened to precede it. It did: the first version of this file pinned
  now to 18:00Z and the OpenSky capture was taken at 20:41Z, so every row
  was correctly held as an hour in the future, and the test read that as
  the parser being broken."
  [rows]
  (+ 1000 (reduce max 0 (map :observed-at rows))))

(deftest fixtures-are-present
  ;; Evidence floor. Every assertion below iterates a parse result.
  (doseq [f ["celestrak-stations.tle" "usgs-2.5_day.geojson" "opensky-states.json"
             "digitraffic-ais-locations.json" "digitraffic-ais-vessels.json"
             "opensanctions-maritime.csv" "opensanctions-ownership.ndjson"]]
    (is (< 1000 (count (fx/slurp-fixture f))) (str f " is missing or truncated"))))

(deftest celestrak-carries-elements-and-no-position
  (let [r (apply p/celestrak (fx/slurp-fixture "celestrak-stations.tle")
                 (feeds/by-id :celestrak) prov)
        ok (vec (:ok r))]
    (is (<= 15 (count ok)))
    (is (empty? (:failed r)) (str "real feed had parse failures: " (pr-str (:failed r))))
    (testing "a satellite row has NO position: position is a function of
              elements and time, and freezing one instant into the table
              would destroy the only reason to keep elements at all"
      (doseq [o ok]
        (is (nil? (:lat o))) (is (nil? (:lon o))) (is (nil? (:alt-km o)))
        (is (string? (get-in o [:attrs :line1])))
        (is (string? (get-in o [:attrs :line2])))))
    (testing "observed-at is the ELEMENT SET EPOCH, not the fetch time"
      (doseq [o ok]
        (is (not= (:fetched-at o) (:observed-at o)))
        ;; Element sets are re-fitted continuously; none should be older
        ;; than a year or newer than the fetch.
        (is (< (- now 31536000000) (:observed-at o) (+ now 86400000))
            (str (:object-id o) " epoch " (:observed-at o) " is implausible"))))
    (testing "and the governor admits them"
      (is (= (count ok) (count (:admitted (gov/admit ok (ingest-clock ok)))))))))

(deftest usgs-reads-geojson-longitude-first
  (let [r (apply p/usgs (js->clj (js/JSON.parse (fx/slurp-fixture "usgs-2.5_day.geojson")))
                 (feeds/by-id :usgs) prov)
        ok (:ok r)]
    (is (<= 10 (count ok)))
    (is (empty? (:failed r)))
    (testing "GeoJSON is [lon lat depth]. If this were read the other way
              round, most rows would still have a valid-looking latitude --
              so the assertion is on the RANGE of both, over every row"
      (doseq [o ok]
        (is (<= -90.0 (:lat o) 90.0) (str (:object-id o) " lat " (:lat o)))
        (is (<= -180.0 (:lon o) 180.0) (str (:object-id o) " lon " (:lon o)))))
    (testing "depth becomes a NEGATIVE altitude: an earthquake is below the
              surface, and a positive number here would put it in the air"
      (is (some #(neg? (:alt-km %)) ok)))
    (testing "timestamps are already milliseconds and are NOT rescaled"
      (doseq [o ok]
        (is (< 1000000000000 (:observed-at o) 9999999999999))))
    (is (= (count ok) (count (:admitted (gov/admit ok (ingest-clock ok))))))))

(deftest opensky-converts-seconds-to-milliseconds
  (let [r (apply p/opensky (js->clj (js/JSON.parse (fx/slurp-fixture "opensky-states.json")))
                 (feeds/by-id :opensky) prov)
        ok (:ok r)]
    (is (<= 5 (count ok)))
    (testing "the *1000 is present -- without it every row lands in 1970
              and the governor holds the entire batch"
      (doseq [o ok]
        (is (< 1000000000000 (:observed-at o) 9999999999999)
            (str (:object-id o) " observed-at " (:observed-at o)))))
    (testing "an aircraft with no position fix is a FAILURE, not a row with
              null coordinates"
      (is (every? #(and (some? (:lat %)) (some? (:lon %))) ok))
      (doseq [f (:failed r)]
        (is (= :opensky/no-position-fix (:error f)))))
    (testing "altitude is metres in the feed and kilometres in the row"
      (doseq [o (filter :alt-km ok)]
        (is (< -1.0 (:alt-km o) 30.0)
            (str (:object-id o) " alt " (:alt-km o) " km -- metres not converted?"))))
    (is (= (count ok) (count (:admitted (gov/admit ok (ingest-clock ok))))))))

(deftest firms-refuses-an-unexpected-header
  ;; FIRMS has added columns between product versions. A fixed column index
  ;; would read the wrong one and keep returning numbers.
  (let [r (apply p/firms "a,b,c\n1,2,3\n" (feeds/by-id :firms) prov)]
    (is (empty? (:ok r)))
    (is (= :firms/unexpected-header (:error (first (:failed r)))))))

(deftest firms-combines-date-and-time
  (let [csv (str "latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,"
                 "satellite,instrument,confidence,version,bright_ti5,frp,daynight\n"
                 "-12.345,130.456,320.1,0.4,0.36,2026-08-25,1423,N20,VIIRS,n,2.0NRT,290.0,1.5,D\n")
        r (apply p/firms csv (feeds/by-id :firms) prov)
        o (first (:ok r))]
    (is (= 1 (count (:ok r))) (str (pr-str (:failed r))))
    (testing "acq_time is HHMM and must reach the timestamp -- keeping only
              the date puts every detection of a day at midnight"
      (is (= (.getTime (js/Date. "2026-08-25T14:23:00Z")) (:observed-at o))))
    (is (= -12.345 (:lat o)))
    (is (= 130.456 (:lon o)))))

(deftest aisstream-parses-a-position-report
  (let [msg (js->clj (js/JSON.parse
                      (str "{\"MetaData\":{\"MMSI\":636019825,\"ShipName\":\"EVER GIVEN  \","
                           "\"latitude\":31.2,\"longitude\":32.35,"
                           "\"time_utc\":\"2026-08-25 14:23:01.5 +0000 UTC\"},"
                           "\"Message\":{\"PositionReport\":{\"Sog\":12.4,\"Cog\":41.2,"
                           "\"TrueHeading\":40,\"NavigationalStatus\":0}}}")))
        o (apply p/aisstream-message msg (feeds/by-id :aisstream) prov)]
    (is (= :vessel (:kind o)))
    (is (= "636019825" (:object-id o)))
    (is (= "EVER GIVEN" (get-in o [:attrs :ship_name])) "trailing pad not trimmed")
    (is (= 31.2 (:lat o)))
    (is (nil? (gov/check-row o (ingest-clock [o])))
        "the governor should admit a vessel row"))
  (testing "and refuses what it cannot place"
    (is (= :ais/no-mmsi (:error (apply p/aisstream-message {} (feeds/by-id :aisstream) prov))))))

(deftest digitraffic-does-not-read-the-ais-second-as-a-time
  (let [r (apply p/digitraffic (js->clj (js/JSON.parse
                                         (fx/slurp-fixture "digitraffic-ais-locations.json")))
                 (feeds/by-id :digitraffic) prov)
        ok (vec (:ok r))]
    (is (<= 20 (count ok)))
    (is (empty? (:failed r)) (str "real feed had parse failures: " (pr-str (:failed r))))
    (testing "`timestampExternal` is epoch milliseconds; the sibling
              `timestamp` is the AIS second-of-minute field, 0-59 with 60-63
              reserved as status. Reading the wrong one puts every vessel in
              January 1970 -- and the governor WOULD catch that, which is
              exactly why the test has to check the value and not only the
              verdict"
      (doseq [o ok]
        (is (< 1700000000000 (:observed-at o) 1900000000000)
            (str (:object-id o) " observed-at " (:observed-at o)
                 " is not a plausible epoch-millisecond value"))
        (let [sec (get-in o [:attrs :ais_second])]
          (is (or (nil? sec) (<= 0 sec 63))
              "the AIS second field is kept, in its own attribute"))))
    (testing "GeoJSON is [lon lat], and these are Baltic coordinates"
      (doseq [o ok]
        (is (< 50 (:lat o) 70) (str "latitude " (:lat o) " is not Finnish AIS coverage"))
        (is (< 15 (:lon o) 32) (str "longitude " (:lon o) " is not Finnish AIS coverage"))))
    (testing "no altitude, and no invented ship name"
      (doseq [o ok]
        (is (nil? (:alt-km o)))
        (is (nil? (get-in o [:attrs :ship_name]))
            "this endpoint does not carry a name; absent beats invented")))
    (testing "and the governor admits them"
      (let [v (gov/admit ok (ingest-clock ok))]
        (is (= (count ok) (count (:admitted v)))
            (str "held: " (pr-str (:counts v))))))))

(deftest digitraffic-transposed-coordinates-would-not-be-silent
  (testing "the [lon lat] trap: Finnish AIS longitudes are 19-30 and
            latitudes 59-65, so a transposition lands inside valid ranges
            for BOTH -- the per-row range rule cannot see it. This asserts
            the limitation rather than flattering the rule, the same way
            the USGS test does."
    (let [parsed (js->clj (js/JSON.parse (fx/slurp-fixture "digitraffic-ais-locations.json")))
          swapped (update parsed "features"
                          (fn [fs] (mapv #(update-in % ["geometry" "coordinates"] reverse) fs)))
          r (apply p/digitraffic swapped (feeds/by-id :digitraffic) prov)
          ok (vec (:ok r))
          v (gov/admit ok (ingest-clock ok))]
      (is (pos? (count ok)))
      (is (= (count ok) (count (:admitted v)))
          "every transposed row passes the range rule -- which is the point:
           the coordinate check is a second line, not a first")
      (is (every? #(< 15 (:lat %) 32) ok)
          "and they are now sitting off the coast of Africa, silently"))))

(deftest digitraffic-refuses-a-feature-it-cannot-place-or-time
  (let [r (p/digitraffic {"features" [{"mmsi" 1 "geometry" {"coordinates" [21.5 60.1]}
                                       "properties" {"mmsi" 1}}
                                      {"mmsi" 2 "geometry" {"coordinates" [nil nil]}
                                       "properties" {"mmsi" 2 "timestampExternal" 1787756229677}}]}
                         (feeds/by-id :digitraffic) "https://example.test" now "sha")]
    (is (empty? (:ok r)))
    (is (= 2 (count (:failed r))))
    (is (every? #(= :digitraffic/incomplete-feature (:error %)) (:failed r)))))

(deftest digitraffic-static-is-identity-and-carries-no-position
  (let [r (apply p/digitraffic-static (js->clj (js/JSON.parse
                                                (fx/slurp-fixture "digitraffic-ais-vessels.json")))
                 (feeds/by-id :digitraffic-static) prov)
        ok (vec (:ok r))]
    (is (<= 20 (count ok)))
    (is (empty? (:failed r)) (str "real feed had parse failures: " (pr-str (:failed r))))
    (testing "identity has no position, for the reason element sets do not:
              putting one here would freeze an instant into a row whose
              whole value is that it is not about one"
      (doseq [o ok]
        (is (nil? (:lat o))) (is (nil? (:lon o))) (is (nil? (:alt-km o)))))
    (testing "every row can say who it is and when"
      (doseq [o ok]
        (is (string? (:object-id o)))
        (is (< 1700000000000 (:observed-at o) 1900000000000))
        (is (string? (get-in o [:attrs :ship_name])))))
    (testing "a missing IMO is absent, not zero -- 384 of 1,168 real records
              have none, and smaller vessels are not required to carry one"
      (doseq [o ok]
        (let [imo (get-in o [:attrs :imo])]
          (is (or (nil? imo) (pos? imo))
              (str (:object-id o) " has imo " (pr-str imo))))))
    (testing "and the governor admits them"
      (let [v (gov/admit ok (ingest-clock ok))]
        (is (= (count ok) (count (:admitted v))) (str "held: " (pr-str (:counts v))))))))

(deftest digitraffic-static-does-not-tidy-what-the-crew-typed
  (testing "`destination` is a free-text field a human types on the bridge.
            A cleaned-up version of what someone typed is a different fact
            from what they typed."
    (let [raw (js->clj (js/JSON.parse (fx/slurp-fixture "digitraffic-ais-vessels.json")))
          r (apply p/digitraffic-static raw (feeds/by-id :digitraffic-static) prov)
          by-mmsi (into {} (for [o (:ok r)] [(:object-id o) o]))]
      (doseq [v raw
              :let [o (get by-mmsi (str (get v "mmsi")))
                    d (some-> (get v "destination") clojure.string/trim not-empty)]
              :when (and o d)]
        (is (= d (get-in o [:attrs :destination]))
            (str "destination for " (:object-id o) " was rewritten"))))))

(deftest digitraffic-static-refuses-a-record-with-no-identity-or-no-time
  (let [r (p/digitraffic-static [{"name" "GHOST"}
                                 {"mmsi" 123456789 "name" "NO TIME"}]
                                (feeds/by-id :digitraffic-static)
                                "https://example.test" now "sha")]
    (is (empty? (:ok r)))
    (is (= 2 (count (:failed r))))
    (is (every? #(= :digitraffic/incomplete-vessel (:error %)) (:failed r)))))

(deftest opensanctions-keeps-the-three-claims-apart
  (let [r (apply p/opensanctions-maritime (fx/slurp-fixture "opensanctions-maritime.csv")
                 (feeds/by-id :opensanctions-maritime) prov)
        ok (vec (:ok r))]
    (is (<= 20 (count ok)))
    (is (empty? (:failed r)) (str "real payload had parse failures: " (pr-str (:failed r))))
    (testing "`risk` is semicolon-separated and its values are DIFFERENT
              claims -- `mare.shadow` is a shadow-fleet assessment,
              `sanction` a designation by a named authority,
              `mare.detained` a port state control detention. Flattening
              them into one `flagged` boolean loses the distinction that is
              the entire reason to hold this data."
      (let [risks (set (mapcat #(str/split (or (get-in % [:attrs :risk]) "") #";") ok))]
        (is (contains? risks "mare.detained"))
        (is (some #(str/starts-with? % "reg.") risks))))
    (testing "identity is OpenSanctions', not the vessel's -- 754 of 23,191
              real records carry neither IMO nor MMSI, and keying on a
              vessel identifier would silently drop exactly the entries
              whose identity is most obscured"
      (doseq [o ok]
        (is (string? (:object-id o)))
        (is (seq (:object-id o)))))
    (testing "the IMO is stored as the bare digits that an AIS broadcast
              carries, not as the `IMO9427366` the CSV writes -- the join
              is the whole point and it has to be possible without a
              string transform at query time"
      (doseq [o ok]
        (let [imo (get-in o [:attrs :imo])]
          (is (or (nil? imo) (re-matches #"\d{7}" imo))
              (str (:object-id o) " imo " (pr-str imo))))))
    (testing "no position: a sanctions designation is not a sighting"
      (doseq [o ok] (is (nil? (:lat o))) (is (nil? (:lon o)))))
    (testing "attribution rides on every row, because CC-BY-NC requires it"
      (doseq [o ok]
        ;; `str` first: a nil attribution should FAIL this test, not
        ;; error it. An error says the test broke; a failure says the code
        ;; did, and the two must not look the same.
        (is (str/includes? (str (get-in o [:attrs :attribution])) "OpenSanctions"))
        (is (str/includes? (str (get-in o [:attrs :attribution])) "CC-BY-NC"))))
    (testing "and the governor admits them"
      (let [v (gov/admit ok (ingest-clock ok))]
        (is (= (count ok) (count (:admitted v))) (str "held: " (pr-str (:counts v))))))))

(deftest opensanctions-reads-quoted-commas-rather-than-splitting-on-them
  (testing "vessel names contain commas. A naive split would shift every
            column after the name and put a risk tag in the flag field --
            silently, because the result is still a string."
    (let [csv (str "\"type\",\"caption\",\"imo\",\"risk\",\"countries\",\"flag\",\"mmsi\",\"id\",\"url\",\"datasets\",\"aliases\"\n"
                   "\"VESSEL\",\"OCEAN TRADER, LTD\",\"IMO1234567\",\"mare.shadow;sanction\",\"ru\",\"pa\",\"273000001\",\"os-1\",\"u\",\"eu_fsf\",\"\"\n")
          r (p/opensanctions-maritime csv (feeds/by-id :opensanctions-maritime)
                                      "https://example.test" now "sha")
          o (first (:ok r))]
      (is (= 1 (count (:ok r))))
      (is (= "OCEAN TRADER, LTD" (get-in o [:attrs :ship_name])))
      (is (= "pa" (get-in o [:attrs :flag])) "the flag column did not shift")
      (is (= "1234567" (get-in o [:attrs :imo])))
      (is (= "273000001" (get-in o [:attrs :mmsi]))))))

(deftest opensanctions-refuses-a-record-with-no-entity-id
  (let [csv (str "\"type\",\"caption\",\"imo\",\"risk\",\"countries\",\"flag\",\"mmsi\",\"id\",\"url\",\"datasets\",\"aliases\"\n"
                 "\"VESSEL\",\"NO ID\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\n")
        r (p/opensanctions-maritime csv (feeds/by-id :opensanctions-maritime)
                                    "https://example.test" now "sha")]
    (is (empty? (:ok r)))
    (is (= [:opensanctions/no-entity-id] (map :error (:failed r))))))

(deftest ownership-strips-the-imo-prefix-the-join-needs-gone
  (testing "`imoNumber` is written `IMO9253325` here and the bare digits are
            what an AIS transponder broadcasts. Joining without stripping it
            returns ZERO rows -- which reads exactly like `no ship in these
            waters has a recorded owner`, and was the first answer this join
            gave. Twenty of twenty matched once the prefix came off."
    (let [r (apply p/opensanctions-ownership (fx/slurp-fixture "opensanctions-ownership.ndjson")
                   (feeds/by-id :opensanctions-ownership) prov)
          ok (vec (:ok r))]
      (is (<= 5 (count ok)))
      (doseq [o ok]
        (let [imo (get-in o [:attrs :asset_imo])]
          (is (or (nil? imo) (re-matches #"\d{7}" imo))
              (str (:object-id o) " asset_imo " (pr-str imo)
                   " -- a prefixed value here makes every downstream join empty")))))))

(deftest ownership-keeps-only-edges-whose-asset-is-a-vessel
  (testing "the same Ownership schema links company to company. Those rows are
            real and belong to a different question; letting them into a table
            called vessel-ownership would answer it wrongly."
    (let [r (apply p/opensanctions-ownership (fx/slurp-fixture "opensanctions-ownership.ndjson")
                   (feeds/by-id :opensanctions-ownership) prov)]
      (is (pos? (count (:ok r))) "an empty result would pass every check below")
      (doseq [o (:ok r)]
        ;; The first version of this asserted that asset_name and owner_name
        ;; were present -- which a company-to-company edge also satisfies, so
        ;; it passed when the filter was removed. Watched not discriminating,
        ;; then fixed by putting the schema on the row.
        (is (= "Vessel" (get-in o [:attrs :asset_schema]))
            (str (:object-id o) " owns a "
                 (get-in o [:attrs :asset_schema]) ", not a vessel"))
        (is (some? (get-in o [:attrs :org_name]))
            "an edge with no named organization got through")
        (is (not= "Person" (get-in o [:attrs :org_schema]))
            "a vessel owned by a named individual is personal data")))))

(deftest ownership-drops-vessels-owned-by-a-named-individual
  (testing "49 of 1,545 edges in the OFAC export name a natural person as the
            owner. The governor caught the first version of this parser by
            holding all 1,545 rows on the `owner` field name -- and it was
            protecting something real. The answer is the filter, not a rename
            that stops the rule noticing."
    (let [csv (str "{\"id\":\"p1\",\"schema\":\"Person\",\"properties\":{\"name\":[\"A Person\"]}}\n"
                   "{\"id\":\"v1\",\"schema\":\"Vessel\",\"properties\":{\"name\":[\"SHIP\"],\"imoNumber\":[\"IMO9253325\"]}}\n"
                   "{\"id\":\"o1\",\"schema\":\"Ownership\",\"properties\":{\"asset\":[\"v1\"],\"owner\":[\"p1\"]}}\n")
          r (p/opensanctions-ownership csv (feeds/by-id :opensanctions-ownership)
                                       "https://example.test" now "sha")]
      (is (empty? (:ok r)))
      (is (= [:ownership/natural-person-owner] (map :error (:failed r)))
          "dropped, counted and named -- not vanished"))))

(deftest ownership-is-one-row-per-edge-and-carries-the-role
  (testing "a ship can be owned and separately controlled, and one company
            sits behind many hulls -- folding to `vessel -> owner` would drop
            the second relationship and make fleet size unanswerable"
    (let [r (apply p/opensanctions-ownership (fx/slurp-fixture "opensanctions-ownership.ndjson")
                   (feeds/by-id :opensanctions-ownership) prov)
          ok (vec (:ok r))]
      (is (= (count ok) (count (distinct (map :object-id ok))))
          "the edge id is the row identity")
      (is (some #(get-in % [:attrs :role]) ok)
          "the role -- owned vs controlled vs held in the interest of -- is kept")
      (is (every? #(str/includes? (str (get-in % [:attrs :attribution])) "CC-BY-NC") ok)))))

(deftest ownership-carries-no-position
  (let [r (apply p/opensanctions-ownership (fx/slurp-fixture "opensanctions-ownership.ndjson")
                 (feeds/by-id :opensanctions-ownership) prov)]
    (doseq [o (:ok r)] (is (nil? (:lat o))) (is (nil? (:lon o))))))
