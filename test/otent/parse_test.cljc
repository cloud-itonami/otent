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
             "digitraffic-ais-locations.json"]]
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
