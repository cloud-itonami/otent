(ns otent.feeds.parse
  "Payload bytes -> observations. Pure: every function here takes the text
  a feed returned plus the provenance of that fetch, and returns rows.

  No network. That is what makes the parsers testable against a captured
  payload, and it is why the fixtures under `test/otent/fixtures/` are
  real responses rather than invented ones.

  Each parser returns `{:ok [obs ...] :failed [{...}]}` for the same reason
  `sgp4.tle/parse-catalog` does: a feed with four unparsable records must
  not be shaped like a clean one."
  (:require [clojure.string :as str]
            [otent.observation :as obs]
            [sgp4.tle :as tle]))

(defn- prov [feed url fetched-at sha]
  {:source (:id feed) :source-url url :fetched-at fetched-at :payload-sha256 sha})

;; ------------------------------------------------------------ CelesTrak

(defn celestrak
  "TLE catalogue -> satellite observations.

  A satellite row carries **no position**. It carries the element set, and
  position is a function of element set and time -- computed by
  `kotoba-lang/sgp4` wherever it is needed, which for the globe is the
  browser. Writing a position here would freeze one instant into a table
  whose whole value is that it can be evaluated at any instant.

  `observed-at` is therefore the ELEMENT SET EPOCH, not the fetch time:
  it is the moment the elements describe."
  [text feed url fetched-at sha]
  (let [{:keys [ok failed]} (tle/parse-catalog text)
        p (prov feed url fetched-at sha)]
    {:ok (for [t ok]
           (obs/observation
            (merge p
                   {:kind :satellite
                    :object-id (:satnum t)
                    :observed-at (long (* 86400000.0
                                          (+ (- (:jdsatepoch t) 2440587.5)
                                             (:jdsatepoch-frac t))))
                    :lat nil :lon nil :alt-km nil
                    :attrs {:name (:name t)
                            :intl_designator (:intl-designator t)
                            :line1 (:line1 t)
                            :line2 (:line2 t)
                            :mean_motion_rev_day (:no-kozai-rev-day t)
                            :eccentricity (:ecco t)
                            :inclination_deg (* (:inclo t) (/ 180.0 Math/PI))
                            :bstar (:bstar t)
                            :element_set_number (:element-set-number t)}})))
     :failed (vec failed)}))

;; ------------------------------------------------------------ USGS

(defn usgs
  "USGS GeoJSON -> quake observations.

  GeoJSON coordinates are `[lon lat depth]` -- **longitude first**. Getting
  that backwards is the single most common geospatial bug and it produces
  valid-looking points for most of the populated world, which is why the
  governor's range rule exists as a second line rather than a first."
  [parsed feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        features (get parsed "features")]
    (reduce
     (fn [acc f]
       (let [props (get f "properties")
             [lon lat depth-km] (get-in f ["geometry" "coordinates"])
             id (get f "id")
             t (get props "time")]
         (if (or (nil? id) (nil? t) (nil? lat) (nil? lon))
           (update acc :failed conj
                   {:error :usgs/incomplete-feature
                    :detail (str "id=" (pr-str id) " time=" (pr-str t)
                                 " lat=" (pr-str lat) " lon=" (pr-str lon))})
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :quake
                            :object-id id
                            ;; USGS reports MILLISECONDS. Not converted.
                            :observed-at t
                            :lat lat :lon lon
                            ;; Depth is below the surface: a negative altitude.
                            :alt-km (when depth-km (- depth-km))
                            :attrs {:mag (get props "mag")
                                    :mag_type (get props "magType")
                                    :place (get props "place")
                                    :event_type (get props "type")
                                    :status (get props "status")
                                    :tsunami (get props "tsunami")
                                    :significance (get props "sig")
                                    :net (get props "net")
                                    :event_url (get props "url")}}))))))
     {:ok [] :failed []}
     features)))

;; ------------------------------------------------------------ OpenSky

(def ^{:doc "OpenSky's state vector is a positional array, and the index of
  each field is the only documentation of what it holds. Named here once so
  no call site counts commas."}
  opensky-fields
  [:icao24 :callsign :origin-country :time-position :last-contact
   :longitude :latitude :baro-altitude :on-ground :velocity
   :true-track :vertical-rate :sensors :geo-altitude :squawk
   :spi :position-source])

(defn opensky
  "OpenSky `/states/all` -> aircraft observations.

  `time_position` is UNIX **SECONDS** and is multiplied by 1000 here. That
  multiplication is the entire reason `otent.governor` carries a timestamp
  plausibility window: without it, dropping this `* 1000` yields timestamps
  in January 1970 that are numerically valid and visually invisible.

  Aircraft with no position fix (`time_position` null, typically on the
  ground and out of coverage) are reported as failures rather than written
  with a null position -- a state vector without a position is not an
  observation of where something is."
  [parsed feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        states (get parsed "states")]
    (reduce
     (fn [acc st]
       (let [m (zipmap opensky-fields st)
             icao (some-> (:icao24 m) str/trim)
             tpos (:time-position m)
             lat (:latitude m) lon (:longitude m)]
         (if (or (str/blank? (str icao)) (nil? tpos) (nil? lat) (nil? lon))
           (update acc :failed conj
                   {:error :opensky/no-position-fix
                    :detail (str "icao24=" (pr-str icao)
                                 " time_position=" (pr-str tpos)
                                 " lat=" (pr-str lat) " lon=" (pr-str lon))})
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :aircraft
                            :object-id icao
                            ;; SECONDS -> milliseconds.
                            :observed-at (long (* 1000 tpos))
                            :lat lat :lon lon
                            ;; Geometric altitude if the aircraft reports it,
                            ;; barometric otherwise. Metres -> km.
                            :alt-km (some-> (or (:geo-altitude m) (:baro-altitude m))
                                            (/ 1000.0))
                            :attrs {:callsign (some-> (:callsign m) str/trim)
                                    :origin_country (:origin-country m)
                                    :on_ground (:on-ground m)
                                    :velocity_m_s (:velocity m)
                                    :true_track_deg (:true-track m)
                                    :vertical_rate_m_s (:vertical-rate m)
                                    :squawk (:squawk m)
                                    :position_source (:position-source m)}}))))))
     {:ok [] :failed []}
     states)))

;; ------------------------------------------------------------ NASA FIRMS

(defn- split-csv-line
  "Split one CSV line. FIRMS emits no quoted fields, but a parser that
  assumes so silently mis-columns the day someone adds a place name."
  [line]
  (loop [chars (seq line) cur [] out [] in-q? false]
    (if-not chars
      (conj out (apply str cur))
      (let [c (first chars)]
        (cond
          (= \" c) (recur (next chars) cur out (not in-q?))
          (and (= \, c) (not in-q?)) (recur (next chars) [] (conj out (apply str cur)) false)
          :else (recur (next chars) (conj cur c) out in-q?))))))

(defn- num* [s]
  (when-not (str/blank? s)
    #?(:clj (try (Double/parseDouble s) (catch Exception _ nil))
       :cljs (let [v (js/Number s)] (when-not (js/isNaN v) v)))))

(defn firms
  "NASA FIRMS active-fire CSV -> fire observations.

  FIRMS reports `acq_date` (YYYY-MM-DD) and `acq_time` (HHMM, UTC) in two
  columns. They are combined here; a parser that keeps only the date puts
  every detection of a day at midnight, which looks like data.

  The header row is used to find columns by NAME. FIRMS has added columns
  between product versions, and a fixed index would then read the wrong one
  and keep returning numbers."
  [text feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        lines (->> (str/split-lines (or text "")) (remove str/blank?))
        header (some-> (first lines) split-csv-line)
        idx (zipmap (map str/trim (or header [])) (range))
        col (fn [row k] (some-> (get idx k) (#(nth row % nil)) str/trim))]
    (if (or (empty? lines) (nil? (get idx "latitude")) (nil? (get idx "acq_date")))
      {:ok []
       :failed [{:error :firms/unexpected-header
                 :detail (str "header was " (pr-str header)
                              " -- expected latitude/longitude/acq_date/acq_time")}]}
      (reduce
       (fn [acc line]
         (let [row (split-csv-line line)
               lat (num* (col row "latitude"))
               lon (num* (col row "longitude"))
               date (col row "acq_date")
               hhmm (col row "acq_time")]
           (if (or (nil? lat) (nil? lon) (str/blank? date) (str/blank? hhmm))
             (update acc :failed conj
                     {:error :firms/incomplete-row :detail (pr-str row)})
             (let [hhmm (str/replace (str "0000" hhmm) #"^.*(\d{4})$" "$1")
                   iso (str date "T" (subs hhmm 0 2) ":" (subs hhmm 2 4) ":00Z")
                   ms #?(:clj (.toEpochMilli (java.time.Instant/parse iso))
                         :cljs (.getTime (js/Date. iso)))]
               (update acc :ok conj
                       (obs/observation
                        (merge p
                               {:kind :fire
                                ;; FIRMS has no stable detection id: the same
                                ;; pixel re-detected is a new row. The id is
                                ;; therefore the pixel and instant, which is
                                ;; exactly what the governor de-duplicates on.
                                :object-id (str (col row "satellite") ":"
                                                lat "," lon "@" iso)
                                :observed-at ms
                                :lat lat :lon lon :alt-km nil
                                :attrs {:brightness (num* (col row "bright_ti4"))
                                        :frp_mw (num* (col row "frp"))
                                        :confidence (col row "confidence")
                                        :satellite (col row "satellite")
                                        :instrument (col row "instrument")
                                        :daynight (col row "daynight")
                                        :scan (num* (col row "scan"))
                                        :track (num* (col row "track"))}})))))))
       {:ok [] :failed []}
       (rest lines)))))

;; ------------------------------------------------------------ AISStream

(defn digitraffic
  "Finnish Transport Infrastructure Agency AIS GeoJSON -> vessel
  observations.

  A POLL, where the other vessel source is a subscription. That is the
  whole reason this exists: `aisstream` is global and needs a process that
  holds a socket open, which this repository does not run, so the vessel
  table did not exist at all. Digitraffic is one sea instead of every sea,
  and one sea measured is not the same as the ocean assumed empty.

  GeoJSON coordinates are `[lon lat]` -- longitude first, same trap as
  USGS.

  `timestampExternal` is UNIX MILLISECONDS. The sibling field `timestamp`
  is NOT a time: it is the AIS second-of-minute field, 0-59 with 60-63
  reserved as status codes, and reading it as an epoch would put every
  vessel in January 1970. It is carried as `ais_second` rather than
  dropped, because it is what the transponder actually said."
  [parsed feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        features (get parsed "features")]
    (reduce
     (fn [acc f]
       (let [props (get f "properties")
             [lon lat] (get-in f ["geometry" "coordinates"])
             mmsi (or (get f "mmsi") (get props "mmsi"))
             t (get props "timestampExternal")]
         (if (or (nil? mmsi) (nil? t) (nil? lat) (nil? lon))
           (update acc :failed conj
                   {:error :digitraffic/incomplete-feature
                    :detail (str "mmsi=" (pr-str mmsi) " t=" (pr-str t)
                                 " lat=" (pr-str lat) " lon=" (pr-str lon))})
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :vessel
                            :object-id (str mmsi)
                            :observed-at t
                            :lat lat :lon lon :alt-km nil
                            ;; No ship name: this endpoint does not carry one.
                            ;; `/api/ais/v1/vessels` does, and joining it would
                            ;; be a second request per poll for a field nothing
                            ;; draws yet. Absent rather than invented.
                            :attrs {:ship_name nil
                                    :sog_knots (get props "sog")
                                    :cog_deg (get props "cog")
                                    :true_heading (get props "heading")
                                    :nav_status (get props "navStat")
                                    :ais_second (get props "timestamp")
                                    :position_accurate (get props "posAcc")}}))))))
     {:ok [] :failed []}
     features)))

(defn digitraffic-static
  "Digitraffic AIS vessel records -> vessel IDENTITY observations.

  ## Why this is an observation and not a lookup table

  A ship's name, callsign, IMO number, type, destination and draught are
  what the transponder broadcasts in AIS message type 5 -- `ShipStaticData`
  -- and they change: a vessel is renamed, re-registered, reports a new
  destination on every voyage. Storing them as a mutable side table would
  answer `what is this vessel called` and destroy `what was it called when
  we saw it there`.

  So they land the same way satellite element sets do: an object, an
  instant, no position, and the attributes that were true at that instant.
  `otent_vessel` holds where a ship was; this holds who it said it was.

  ## Why not merged into the position rows

  Because the position payload does not contain any of it. Joining a second
  payload into the same row would put a `payload_sha256` on it that cannot
  reproduce it, and the claim that the tables can be rebuilt from the
  archived bytes is the reason retention is allowed to delete anything at
  all. One payload, one sha, one row.

  **`destination` and `eta` are what the crew typed.** They are routinely
  stale, misspelled, or a port code nobody outside the bridge uses. Kept
  verbatim, because a cleaned-up version of what someone typed is a
  different fact from what they typed."
  [parsed feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)]
    (reduce
     (fn [acc v]
       (let [mmsi (get v "mmsi")
             t (get v "timestamp")]
         (if (or (nil? mmsi) (nil? t))
           (update acc :failed conj
                   {:error :digitraffic/incomplete-vessel
                    :detail (str "mmsi=" (pr-str mmsi) " timestamp=" (pr-str t))})
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :vessel-static
                            :object-id (str mmsi)
                            :observed-at t
                            ;; Identity has no position. The same rule the
                            ;; satellite rows follow, for the same reason:
                            ;; putting one here would freeze an instant into
                            ;; a row whose value is that it is not about one.
                            :lat nil :lon nil :alt-km nil
                            :attrs {:ship_name (some-> (get v "name") str/trim not-empty)
                                    :call_sign (some-> (get v "callSign") str/trim not-empty)
                                    ;; 384 of 1,168 records carry no IMO --
                                    ;; smaller vessels are not required to
                                    ;; have one. Absent, not zero.
                                    :imo (let [i (get v "imo")] (when (and i (pos? i)) i))
                                    :ship_type (let [x (get v "shipType")]
                                                 (when (and x (pos? x)) x))
                                    :destination (some-> (get v "destination") str/trim not-empty)
                                    :eta (get v "eta")
                                    ;; Decimetres in AIS, and left in them.
                                    :draught_dm (get v "draught")}}))))))
     {:ok [] :failed []}
     parsed)))

(defn aisstream-message
  "One AISStream JSON message -> a vessel observation, or a failure.

  **The collector that holds this socket open is not in this repository.**
  This parser exists and is tested against a captured message shape so that
  the resident collector, when written, has nothing to invent -- but the
  vessel table is UNMEASURED until something runs it. `otent.feeds.core`
  marks the feed `:access :stream` for that reason, and the CLI reports it
  as unmeasured rather than as zero vessels."
  [parsed feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        meta* (get parsed "MetaData")
        pos (get-in parsed ["Message" "PositionReport"])
        mmsi (or (get meta* "MMSI") (get pos "UserID"))
        lat (or (get meta* "latitude") (get pos "Latitude"))
        lon (or (get meta* "longitude") (get pos "Longitude"))
        t (get meta* "time_utc")]
    (cond
      (nil? mmsi) {:error :ais/no-mmsi :detail (pr-str (keys parsed))}
      (or (nil? lat) (nil? lon)) {:error :ais/no-position :detail (str "mmsi " mmsi)}
      (str/blank? (str t)) {:error :ais/no-timestamp :detail (str "mmsi " mmsi)}
      :else
      (obs/observation
       (merge p
              {:kind :vessel
               :object-id (str mmsi)
               :observed-at #?(:clj (.toEpochMilli (java.time.Instant/parse
                                                    (str/replace t #" \+0000 UTC$" "Z")))
                               :cljs (.getTime (js/Date. (str/replace t #" \+0000 UTC$" "Z"))))
               :lat lat :lon lon :alt-km nil
               ;; ShipName is the vessel's broadcast name, not a person's.
               :attrs {:ship_name (some-> (get meta* "ShipName") str/trim)
                       :sog_knots (get pos "Sog")
                       :cog_deg (get pos "Cog")
                       :true_heading (get pos "TrueHeading")
                       :nav_status (get pos "NavigationalStatus")}})))))
