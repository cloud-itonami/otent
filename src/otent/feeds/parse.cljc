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
            [csv.core :as csv]
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

(defn opensanctions-maritime
  "OpenSanctions' maritime CSV -> what the sanctions lists SAY, as
  observations.

  ## Why the list is recorded rather than the matches

  The obvious thing is to join this against `otent_vessel_static` and store
  the vessels that matched. That is wrong here for the reason the vessel
  name was wrong yesterday: a matched row is a fact about TWO payloads, and
  it would carry a `payload_sha256` that cannot reproduce it.

  So the list lands as its own kind, from its own payload, and **the
  intersection is a query** -- which is the entire point of putting both in
  one catalog. `who in the Gulf of Finland is under sanction` is then a
  join anyone can write and re-run against any day's data, rather than a
  number somebody computed once.

  ## Why a daily snapshot series and not a diff

  Every poll commits the whole list again, and that is deliberate.
  **Delisting is invisible without history**: a vessel removed from a
  designation simply stops appearing, and a table holding only the current
  list cannot tell `never listed` from `listed and released`. The cost is
  ~23,000 rows a day; the byte-identical payload rule means a re-poll
  within the same publication commits nothing.

  ## The row identity is OpenSanctions', not the vessel's

  `object-id` is the OpenSanctions entity id, because 754 of 23,191 records
  carry neither IMO nor MMSI and keying on a vessel identifier would drop
  them -- an undeclared filter on exactly the entries whose identity is
  most obscured. IMO and MMSI ride as attributes, which is what the join
  uses.

  Data: OpenSanctions, CC-BY-NC 4.0. Attribution is on every row."
  [text feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        rows (csv/read-maps text)]
    (reduce
     (fn [acc r]
       (let [id (some-> (get r "id") str/trim not-empty)]
         (if (nil? id)
           (update acc :failed conj
                   {:error :opensanctions/no-entity-id
                    :detail (str "caption=" (pr-str (get r "caption")))})
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :vessel-risk
                            :object-id id
                            ;; The fetch time, and it has to be: the CSV
                            ;; carries no per-row date, and the export
                            ;; timestamp lives in a different file. Using
                            ;; the fetch time means a re-poll of the SAME
                            ;; publication would look new -- which the
                            ;; byte-identical payload rule catches before
                            ;; the watermark is ever consulted.
                            :observed-at fetched-at
                            :lat nil :lon nil :alt-km nil
                            :attrs {:entity_type (some-> (get r "type") str/trim not-empty)
                                    :ship_name (some-> (get r "caption") str/trim not-empty)
                                    ;; The column is `IMO9427366`; the bare
                                    ;; digits are what joins against an AIS
                                    ;; broadcast.
                                    :imo (some-> (get r "imo") str/trim
                                                 (str/replace #"^IMO" "") not-empty)
                                    :mmsi (some-> (get r "mmsi") str/trim not-empty)
                                    :flag (some-> (get r "flag") str/trim not-empty)
                                    :countries (some-> (get r "countries") str/trim not-empty)
                                    ;; Semicolon-separated in the source and
                                    ;; left that way: `mare.shadow` is the
                                    ;; shadow-fleet tag, `sanction` a
                                    ;; designation, `mare.detained` a port
                                    ;; state control detention. Three
                                    ;; different claims that must not be
                                    ;; flattened into one.
                                    :risk (some-> (get r "risk") str/trim not-empty)
                                    :lists (some-> (get r "datasets") str/trim not-empty)
                                    :url (some-> (get r "url") str/trim not-empty)
                                    :attribution "OpenSanctions, CC-BY-NC 4.0"}}))))))
     {:ok [] :failed []}
     rows)))

(defn- ftm-prop [e k]
  (get-in e ["properties" k]))

(def ftm-relevant-schemas
  "The only entity kinds these two parsers reach for.

  The multi-jurisdiction export is 353 MB and 291,570 entities, of which
  160,413 are `Sanction` and 24,113 are `Address` -- neither is read here.
  Converting all of them into ClojureScript maps costs memory this process
  does not need to spend, so the filter happens on the raw line before
  `js->clj` ever sees it."
  #{"Vessel" "Ownership" "Organization" "Company" "LegalEntity" "Person"})

(defn- ftm-index
  "NDJSON -> id->entity, keeping only the schemas above.

  The prefilter runs on the RAW STRING, because `js->clj` on a 291,570-entity
  file is the expensive step and 75% of those entities are never read. It
  tests only for the quoted schema NAME, not for `\"schema\":\"Vessel\"`,
  because the exact spacing around the colon is the source's business and not
  a contract: the first version of this matched `\"schema\": \"` with a space,
  the export emits none, and it would have rejected every line in production
  while passing against a fixture written by `json.dumps`. A prefilter that
  is wrong returns an empty index, and an empty index looks exactly like a
  sanctions list with nobody on it.

  So the prefilter is deliberately loose -- a `Sanction` row mentioning the
  word Organization gets parsed and then dropped -- and the authoritative
  check is the one after `js->clj`."
  [text]
  (reduce (fn [m l]
            (if-not (some #(str/includes? l (str "\"" % "\"")) ftm-relevant-schemas)
              m
              (let [e (js->clj (js/JSON.parse l))]
                (if (contains? ftm-relevant-schemas (get e "schema"))
                  (assoc m (get e "id") e)
                  m))))
          {}
          (remove str/blank? (str/split-lines text))))

(defn opensanctions-ownership
  "OpenSanctions' FollowTheMoney entity graph -> who owns which ship.

  NDJSON, one entity per line. The interesting rows are `Ownership` edges
  whose `asset` is a `Vessel`: 1,545 of them in the OFAC export alone,
  carrying a role like `Owned or Controlled By` or `Property in the interest
  of`. That is the operating company behind a hull, which the AIS broadcast
  never says.

  ## The prefix that made the join silently empty

  `imoNumber` here is `IMO9253325`, not `9253325`. Joining on the bare digits
  an AIS transponder broadcasts returns ZERO rows, and zero rows looks exactly
  like `no ship in these waters has a recorded owner` -- which was the first
  answer this join gave. It was wrong: with the prefix stripped, twenty of
  twenty matched, every one with a named owner.

  So the IMO is normalised HERE, once, to the form the rest of this workspace
  already uses. `parse-test` asserts a prefixed value round-trips, because the
  failure mode is a plausible number rather than an error.

  ## One row per EDGE, not per vessel

  A ship can be owned and separately controlled, and the same company appears
  behind many hulls. Folding the edges into a `vessel -> owner` column would
  drop the second relationship and make the fleet-size question -- how many
  hulls does this operator control -- unanswerable without re-fetching.

  ## The governor refused all 1,545 rows, and it was right

  The first version wrote the owner as `owner_name`, and `otent.governor`
  held every row: `owner` is on its person-marker list, because on a position
  row it names a human. Measured on the OFAC export, that list was protecting
  something real -- 49 of 1,545 vessel-ownership edges name a NATURAL PERSON
  as the owner.

  The answer is not to rename the field until the rule stops noticing. It is
  to drop those 49 edges: this table carries organizations, and a vessel held
  by a named individual is personal data that has no business here. The
  remaining fields are `org_*` because after the filter they cannot name a
  person -- which is what the rule protects, enforced more strictly than the
  field name was doing. Person-owned edges land in `:failed` under
  `:ownership/natural-person-owner`, counted and named rather than vanishing.

  Data: OpenSanctions, CC-BY-NC 4.0. Attribution rides on every row, and the
  non-commercial condition is why this table is not on the CC0 wiki plane."
  [text feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        ents (ftm-index text)
        vessel? (fn [id] (= "Vessel" (get-in ents [id "schema"])))]
    (reduce
     (fn [acc [id e]]
       (if-not (= "Ownership" (get e "schema"))
         acc
         (let [assets (filter vessel? (ftm-prop e "asset"))
               owners (ftm-prop e "owner")]
           (if (or (empty? assets) (empty? owners))
             acc
             (reduce
              (fn [acc2 [a o]]
                (let [av (get ents a) ov (get ents o)
                      natural-person? (= "Person" (get ov "schema"))
                      ;; `IMO9253325` -> `9253325`. The whole reason this
                      ;; parser exists in one place.
                      imo (some-> (first (ftm-prop av "imoNumber"))
                                  str/trim (str/replace #"^IMO" "") not-empty)]
                  (if natural-person?
                    (update acc2 :failed conj
                            {:error :ownership/natural-person-owner
                             :detail (str "vessel " (pr-str (first (ftm-prop av "name")))
                                          " is owned by a named individual; this table"
                                          " carries organizations")})
                    (update acc2 :ok conj
                          (obs/observation
                           (merge p
                                  {:kind :ownership-link
                                   :object-id id
                                   :observed-at fetched-at
                                   :lat nil :lon nil :alt-km nil
                                   ;; The schema of what is owned, on the
                                   ;; row. Without it, `this table holds
                                   ;; vessel ownership` is an invariant only
                                   ;; the parser knows, and a test for it can
                                   ;; only check things a company-to-company
                                   ;; edge also has -- which is what the
                                   ;; first version of that test did.
                                   :attrs {:asset_schema (get av "schema")
                                           :asset_imo imo
                                           :asset_mmsi (some-> (first (ftm-prop av "mmsi")) str/trim not-empty)
                                           :asset_name (some-> (first (ftm-prop av "name")) str/trim not-empty)
                                           :org_id o
                                           :org_name (some-> (first (ftm-prop ov "name")) str/trim not-empty)
                                           :org_schema (get ov "schema")
                                           :org_jurisdiction (some-> (or (first (ftm-prop ov "jurisdiction"))
                                                                        (first (ftm-prop ov "country")))
                                                                     str/trim not-empty)
                                           :org_topics (some-> (seq (ftm-prop ov "topics")) (->> (str/join ";")))
                                           :role (some-> (or (first (ftm-prop e "role"))
                                                             (first (ftm-prop e "summary")))
                                                         str/trim not-empty)
                                           :attribution "OpenSanctions, CC-BY-NC 4.0"}}))))))
              acc
              (for [a assets o owners] [a o]))))))
     {:ok [] :failed []}
     ents)))

(defn- ftm-join [e k]
  (some-> (seq (ftm-prop e k)) (->> (str/join ";")) not-empty))

(defn opensanctions-organizations
  "The organizations behind the hulls, with the identifiers that make them
  joinable to something other than their own name.

  ## Why identifiers and not names

  The obvious way to enrich these companies is to look their names up in
  GLEIF and take the LEI. Measured 2026-08-27 on a sample of 40 of the 555
  controlling organizations: **four exact-name hits, and only ONE of those
  agreed on jurisdiction.** The other three were different companies wearing
  the same name -- GLEIF placed `Odyssey Marine Inc.` in Nevada and
  `Patriot Inc.` in Delaware where the sanctions record says Marshall
  Islands, and `EVER SHINING LIMITED` in Hong Kong against China.

  Recording those as identity would have asserted that a Nevada company owns
  a sanctioned tanker. The error direction on that is defamatory, which is
  why this parser records **only identifiers the source itself published**
  and does no matching at all.

  ## What the population actually carries

  Measured over the same 555: `leiCode` on **2**. The shadow fleet does not
  hold LEIs, so the GLEIF hierarchy route -- the obvious way to reach a
  parent company -- reaches almost none of it.

  What it does carry is `imoNumber` on **478**, the IMO Company Number, which
  is the identifier this industry actually uses and which joins to port state
  control and registry sources. Plus `registrationNumber` on 316 and
  `taxNumber`. Fifteen carry nothing at all, and that is recorded rather than
  quietly dropped: an organization with no identifier is one nobody can
  follow, which is a finding about it.

  `alias` (146) and `previousName` are kept whole and separated by `;`. A
  company that has traded under several names is the shape this table exists
  to make visible.

  Data: OpenSanctions, CC-BY-NC 4.0."
  [text feed url fetched-at sha]
  (let [p (prov feed url fetched-at sha)
        ents (ftm-index text)
        ;; Only organizations that actually control a vessel in this graph.
        ;; The export holds 9,819 organizations; the ones this table is about
        ;; are the ones on the far end of a vessel-ownership edge.
        controlling (reduce (fn [acc [_ e]]
                              (if-not (= "Ownership" (get e "schema"))
                                acc
                                (if (some #(= "Vessel" (get-in ents [% "schema"]))
                                          (ftm-prop e "asset"))
                                  (into acc (ftm-prop e "owner"))
                                  acc)))
                            #{} ents)]
    (reduce
     (fn [acc id]
       (let [e (get ents id)]
         (cond
           (nil? e) acc
           ;; A natural person controlling a vessel is personal data, held
           ;; out for the same reason it is held out of the ownership table.
           (= "Person" (get e "schema"))
           (update acc :failed conj
                   {:error :org/natural-person
                    :detail "a controlling party that is a named individual"})
           :else
           (update acc :ok conj
                   (obs/observation
                    (merge p
                           {:kind :org-identity
                            :object-id id
                            :observed-at fetched-at
                            :lat nil :lon nil :alt-km nil
                            :attrs {:org_name (some-> (first (ftm-prop e "name")) str/trim not-empty)
                                    :org_schema (get e "schema")
                                    :jurisdiction (some-> (or (first (ftm-prop e "jurisdiction"))
                                                              (first (ftm-prop e "country")))
                                                          str/trim not-empty)
                                    ;; The IMO Company Number -- 478 of 555
                                    ;; carry one, against 2 with an LEI.
                                    :imo_company_no (some-> (first (ftm-prop e "imoNumber")) str/trim not-empty)
                                    :lei_code (some-> (first (ftm-prop e "leiCode")) str/trim not-empty)
                                    :registration_no (some-> (first (ftm-prop e "registrationNumber")) str/trim not-empty)
                                    :tax_no (some-> (first (ftm-prop e "taxNumber")) str/trim not-empty)
                                    :incorporation_date (some-> (first (ftm-prop e "incorporationDate")) str/trim not-empty)
                                    :sector (some-> (first (ftm-prop e "sector")) str/trim not-empty)
                                    :legal_form (some-> (first (ftm-prop e "legalForm")) str/trim not-empty)
                                    :aliases (ftm-join e "alias")
                                    :previous_names (ftm-join e "previousName")
                                    :websites (ftm-join e "website")
                                    :topics (ftm-join e "topics")
                                    :programs (ftm-join e "programId")
                                    ;; Present and empty are different: an
                                    ;; org nobody can follow is a finding
                                    ;; about it, not a gap in this row.
                                    :has_identifier (str (boolean (or (seq (ftm-prop e "imoNumber"))
                                                                      (seq (ftm-prop e "leiCode"))
                                                                      (seq (ftm-prop e "registrationNumber")))))
                                    :attribution "OpenSanctions, CC-BY-NC 4.0"}}))))))
     {:ok [] :failed []}
     (sort controlling))))

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

(defn aisstream-batch
  "A file of AISStream messages, one JSON object per line, from the resident
  collector -> vessel observations.

  The collector already deduplicated by MMSI inside its flush window, so a
  line here is one vessel's latest fix. The per-message parser below is
  unchanged and still the thing that reads a message; this only walks the
  batch and keeps the failures rather than dropping them, so a shape change
  upstream shows as unparsable rows instead of a quietly smaller flush."
  [text feed url fetched-at sha]
  (reduce
   (fn [acc line]
     (let [r (aisstream-message (js->clj (js/JSON.parse line)) feed url fetched-at sha)]
       (if (:error r)
         (update acc :failed conj r)
         (update acc :ok conj r))))
   {:ok [] :failed []}
   (remove str/blank? (str/split-lines text))))

