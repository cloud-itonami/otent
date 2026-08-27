(ns otent.feeds.core
  "The feed registry: what each public source is, and what it costs to read.

  Every entry is data, so `bin/otent.cljs` can report the whole roster --
  including the feeds it could **not** run and why -- rather than silently
  polling the subset that happens to be configured.

  ## `:access` is the field that matters

  | | |
  |---|---|
  | `:open` | no account, no key. Polled by default. |
  | `:free-key` | free registration, key required. Skipped with an explicit `:no-credential` result, never with silence. |
  | `:stream` | a persistent connection, not a poll. Needs a resident collector this repository does not run. |

  A feed that cannot be read is `UNMEASURED`. It is not zero rows, and a
  run that skipped four of five feeds must not exit like a run that read
  all five. `bin/otent.cljs` exits 2 for that -- not 0, not 1.

  ## `:scope` is required, and says what the request LEAVES OUT

  The quake feed was `2.5_day.geojson` from the first day. Nothing about
  the table, the receipt, the governor or `otent coverage` could show that
  every earthquake below M2.5 was outside what was ever asked for -- a
  world with no small earthquakes in it and a request that excludes them
  produce the same rows. **A scope decision made once inside a URL stops
  looking like a decision.**

  So every entry declares `:scope`, in prose, saying what the request
  excludes and what could be asked for instead. A test refuses a registry
  entry without one.

  This does not verify the scope is *right* -- nothing can, from here.
  It makes it impossible to narrow coverage without writing down that you
  did. `:firms` is the current example working as intended: it names three
  other sensors available under the same key that are not being asked for,
  so the ceiling is in the registry where it can be argued with, rather
  than in a path segment where it cannot."
  (:require [clojure.string :as str]))

(def registry
  [{:id :celestrak
    :kind :satellite
    :access :open
    :label "CelesTrak GP element sets"
    :url "https://celestrak.org/NORAD/elements/gp.php"
    :default-params {"GROUP" "active" "FORMAT" "tle"}
        :scope "GROUP=active: objects CelesTrak still tracks as active. Excludes
            decayed and inactive objects, and analyst-only element sets. Of
            what arrives, deep-space element sets (period past 225 min) are
            admitted but cannot be propagated -- SDP4 is not implemented in
            kotoba-lang/sgp4, so they are named rather than drawn wrongly."
    :terms "https://celestrak.org/publications/"
    ;; Element sets are re-fitted on the order of once a day. Polling faster
    ;; adds rows that are byte-identical to the last ones and are then held
    ;; as duplicates -- work done to produce a hold.
    :min-interval-ms 21600000
    ;; CelesTrak answers a repeat request with **HTTP 403** and a body
    ;; saying the data has not changed -- not 304, and not an error. Without
    ;; this the tick reports the feed UNMEASURED, which is the one thing
    ;; this repository is built to avoid: it would say the sky could not be
    ;; observed when in fact it was observed and had not moved. Measured
    ;; 2026-08-26: `GP data has not updated since your last successful
    ;; download of GROUP=active at 2026-08-26 07:15:15 UTC.`
    ;;
    ;; Matched on the body, deliberately narrowly. A genuine 403 -- blocked,
    ;; rate-limited, banned -- carries a different body and must stay
    ;; UNMEASURED, because that one really is a failure to observe.
    :not-modified {:status 403
                   :body-contains "has not updated since your last successful"}
    :notes "Two-line element sets. Parsed by kotoba-lang/sgp4, which refuses
            deep-space element sets rather than propagating them wrongly."}

   {:id :usgs
    :kind :quake
    :access :open
    :label "USGS earthquake summary feed (all magnitudes, past day)"
    ;; `all_day`, not `2.5_day`. The M2.5 feed was chosen on the first day
    ;; and never revisited, and it is a coverage ceiling written into a URL:
    ;; every quake below M2.5 was not missing from the table, it was outside
    ;; what we ever asked for -- which reads, from the table, exactly like a
    ;; world with no small earthquakes in it. Measured 2026-08-27 on the same
    ;; minute: `2.5_day` 29 events, `all_day` 241, smallest M-0.4. Same
    ;; GeoJSON shape, same parser, ~8x the rows at 170 KB a poll.
    ;;
    ;; `all_day` also carries non-tectonic events -- quarry blasts, mining
    ;; explosions, ice quakes. They are kept, with `event_type` on the row,
    ;; because they are real observations of the ground moving and dropping
    ;; them would be a second undeclared filter of the same kind as the one
    ;; this change removes.
    :url "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"
    :default-params {}
        :scope "all_day: every magnitude USGS publishes, past 24 hours. No
            magnitude floor -- `2.5_day` was the URL until 2026-08-27 and
            that floor was invisible from the table. Excludes events older
            than a day, which retention would drop anyway, and events USGS
            has not yet reviewed into the summary feed."
    :terms "https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits"
    :min-interval-ms 300000
    :notes "GeoJSON. Timestamps are UNIX MILLISECONDS -- unlike OpenSky's.
            `mag` can be null for some event types; the row carries the null
            rather than inventing a magnitude."}

   {:id :opensky
    :kind :aircraft
    :access :open
    :label "OpenSky Network state vectors (anonymous)"
    :url "https://opensky-network.org/api/states/all"
    :default-params {}
        :scope "/states/all on the anonymous tier: global in principle, and in
            practice bounded by OpenSky's volunteer ADS-B receiver network
            -- dense over Europe and North America, thin over oceans,
            Africa and central Asia. Aircraft not transmitting ADS-B are
            absent. The anonymous tier is also rate-limited, which is what
            the 10-minute interval is for. This is the one feed whose
            coverage cannot be widened by asking differently."
    :terms "https://opensky-network.org/about/terms-of-use"
    ;; The anonymous tier is rate-limited and the underlying data updates
    ;; every 10 s. 60 s is inside the limit and above the update period.
    ;; Ten minutes, not one. The registry's job is to say how fast the
    ;; feed changes, but a poll also COMMITS -- and a commit moves the
    ;; Iceberg snapshot, which is the read cache's key. Measured 2026-08-26
    ;; with the scheduler live: polling every five minutes invalidated the
    ;; cache every five minutes, so somebody always paid the cold scan, and
    ;; the Worker returned `error code: 1102` -- exceeded CPU -- before
    ;; succeeding on retry at 51 s. Ten minutes is also the considerate rate
    ;; for a free anonymous tier.
    :min-interval-ms 600000
    :notes "Positional arrays, not objects. Timestamps are UNIX SECONDS --
            unlike USGS's. This pair is why the governor has a plausibility
            window on timestamps."}

   {:id :firms
    :kind :fire
    :access :free-key
    :credential-env "FIRMS_MAP_KEY"
    :label "NASA FIRMS active fire detections"
    :url "https://firms.modaps.eosdis.nasa.gov/api/area/csv"
    :default-params {"source" "VIIRS_NOAA20_NRT" "area" "world" "day_range" "1"}
        :scope "VIIRS NOAA-20 near-real-time, world, past 1 day. ONE sensor of
            several FIRMS serves: VIIRS_SNPP_NRT, VIIRS_NOAA21_NRT and
            MODIS_NRT are also available under the same key and are NOT
            being asked for. That is a live, named ceiling -- roughly a
            doubling of detections is one parameter away -- and it is
            written here rather than left in the URL, which is the mistake
            `2.5_day` was. Excludes: anything the satellite did not pass
            over, anything under cloud, and fires below the sensor's
            detection threshold."
    :terms "https://firms.modaps.eosdis.nasa.gov/usage/"
    :min-interval-ms 3600000
    :notes "CSV. A free MAP_KEY from firms.modaps.eosdis.nasa.gov is required;
            without it this feed reports :no-credential, not zero fires."}

   {:id :digitraffic
    :kind :vessel
    :access :open
    :label "Finnish Transport Infrastructure Agency AIS (Digitraffic)"
    :url "https://meri.digitraffic.fi/api/ais/v1/locations"
    :default-params {}
    ;; Their terms ask callers to identify themselves in this header rather
    ;; than only in the user-agent, so that a misbehaving client can be
    ;; contacted instead of blocked.
    :headers {"Digitraffic-User" "cloud-itonami/otent"}
    :terms "https://www.digitraffic.fi/en/terms-of-service/"
    ;; The response says `cache-control: max-age=60`, so sixty seconds is
    ;; the source's own refresh rate. Ten minutes anyway, for the reason
    ;; the aircraft feed learned: a poll COMMITS, and a commit moves the
    ;; Iceberg snapshot that the read cache is keyed on, so polling at the
    ;; source's rate makes somebody pay a cold scan every minute.
    :min-interval-ms 600000
    :scope "Finnish AIS coverage -- the Baltic, the Gulf of Finland and the
            Gulf of Bothnia, roughly 1,300 vessels. NOT global: this is one
            sea. It exists because the global source needs a resident
            collector this repository does not run, and one sea measured is
            not the same as the ocean assumed empty. Excludes vessels not
            transmitting AIS, vessels outside the Finnish receiver network,
            and anything the 24-hour default `from` window has dropped.
            Carries no ship name -- `/api/ais/v1/vessels` has it and is not
            joined."
    :notes "GeoJSON, [lon lat]. `timestampExternal` is UNIX MILLISECONDS;
            the sibling `timestamp` is the AIS second-of-minute field (0-59,
            60-63 reserved) and is NOT a time. Requires gzip -- the endpoint
            answers 406 without it, which Node's fetch negotiates on its own
            and curl does not."}

   {:id :digitraffic-static
    :kind :vessel-static
    :access :open
    :label "Digitraffic AIS vessel identity (ShipStaticData)"
    :url "https://meri.digitraffic.fi/api/ais/v1/vessels"
    :default-params {}
    :headers {"Digitraffic-User" "cloud-itonami/otent"}
    :terms "https://www.digitraffic.fi/en/terms-of-service/"
    ;; Identity changes on the order of voyages, not minutes. Hourly is
    ;; already far faster than the thing being observed.
    :min-interval-ms 3600000
    :scope "Who the vessels in Finnish AIS coverage say they are: name,
            callsign, IMO, ship type, destination, ETA, draught. Same
            receiver network as the position feed and therefore the same
            sea. Excludes vessels not currently in coverage. 384 of 1,168
            records carry no IMO -- smaller vessels are not required to have
            one, so that field is absent rather than zero. `destination` and
            `eta` are typed by the crew and are routinely stale or
            misspelled; they are kept verbatim, because a tidied version of
            what someone typed is a different fact from what they typed."
    :notes "Lands in `otent_vessel_static`, NOT in `otent_vessel`. The
            position payload contains none of these fields, so merging them
            into a position row would give it a `payload_sha256` that cannot
            reproduce it -- and `the tables can be rebuilt from the archived
            bytes` is the only reason retention is allowed to delete
            anything. One payload, one sha, one row."}

   {:id :opensanctions-maritime
    :kind :vessel-risk
    :access :open
    :label "OpenSanctions maritime -- sanctions, shadow-fleet and detention records"
    :url "https://data.opensanctions.org/datasets/latest/maritime/maritime.csv"
    :default-params {}
    :terms "https://www.opensanctions.org/licensing/"
    ;; The source publishes daily. Polling faster gets the same bytes and
    ;; is caught by the payload hash, so this is politeness rather than
    ;; correctness -- but it is also 5 MB a poll.
    :min-interval-ms 86400000
    :scope "What the maritime sanctions lists SAY -- not who is currently
            sailing. 23,191 records from OFAC, the EU official journal and
            sanctions map, UK FCDO, Swiss SECO, Canada, Ukraine's NSDC and
            war-sanctions register, UN 1718, and the Paris/Tokyo/Abuja/Black
            Sea MOU detention registers. Excludes any jurisdiction not in
            that set, and anything OpenSanctions has not yet ingested.
            **Recording the list is not recording the vessels**: the
            intersection with `otent_vessel_static` is a join, and lives in
            whatever query asks for it. 754 of 23,191 records carry neither
            IMO nor MMSI and are kept anyway, keyed by OpenSanctions entity
            id -- dropping them would be an undeclared filter on exactly the
            entries whose identity is most obscured."
    :notes "CSV, RFC 4180, read by `kotoba-lang/org-ietf-csv`. `risk` is
            semicolon-separated and its values are DIFFERENT CLAIMS:
            `mare.shadow` is a shadow-fleet assessment, `sanction` a
            designation by a named authority, `mare.detained` a port state
            control detention. Flattening them into `flagged` would lose
            the distinction that matters.

            **Data: OpenSanctions, CC-BY-NC 4.0.** Attribution rides on
            every row. Non-commercial is a real condition, not a formality:
            anything that serves these rows onward inherits it, and this
            actor is not the place to decide that a downstream use
            qualifies."}

   {:id :opensanctions-ownership
    :kind :ownership-link
    :access :open
    :label "OpenSanctions FollowTheMoney graph -- who owns which ship"
    :url "https://data.opensanctions.org/datasets/latest/sanctions/entities.ftm.json"
    :default-params {}
    :terms "https://www.opensanctions.org/licensing/"
    :min-interval-ms 86400000
    :scope "Ownership edges whose asset is a vessel, from the
            multi-jurisdiction `sanctions` collection -- OFAC, the EU, the UK,
            Switzerland, Canada and Ukraine. 2,026 edges against 1,998
            vessels.

            It was the OFAC export alone until 2026-08-28 (1,545 edges), and
            the switch was measured rather than assumed. Globally it is worth
            +31%. **For the fleet this actor watches it is worth 3.2x**:
            vessels in Finnish AIS coverage that appear at all went 20 -> 64,
            those with a named controlling organization 20 -> 47, and edges
            21 -> 57. The two numbers differ because EU and UK designations
            target the Baltic shadow fleet specifically while OFAC's list is
            weighted elsewhere -- so a global average would have understated
            the gain by a factor of ten, and quoting it would have been the
            wrong measurement honestly reported.

            Corporate hierarchy is mostly ABSENT: measured 2026-08-27, none of
            the four operators behind the Finnish-coverage fleet had a parent,
            a director or an owned subsidiary recorded in this graph. OFAC
            records who owns an asset, not who owns the owner. For that,
            GLEIF is the source -- and it has SOVCOMFLOT and does not have the
            Hong Kong manager, so that route is partial too."
    :notes "NDJSON, one FollowTheMoney entity per line, ~353 MB, 291,570
            entities of which 160,413 are `Sanction` and are never read --
            `ftm-index` drops them on the raw line before `js->clj`. `imoNumber` is
            written `IMO9253325`; the bare digits an AIS transponder
            broadcasts are what everything else here joins on, so the prefix
            is stripped in the parser. Joining without stripping it returns
            ZERO rows, which reads exactly like `no ship here has a recorded
            owner` -- the first answer this join actually gave.

            One row per EDGE. A ship can be owned and separately controlled,
            and one company sits behind many hulls (SOVCOMFLOT: 81).

            **Data: OpenSanctions, CC-BY-NC 4.0.** Non-commercial is why this
            is not on the CC0 wiki plane."}

   {:id :opensanctions-organizations
    :kind :org-identity
    :access :open
    :label "The organizations that control vessels, and what identifies them"
    :url "https://data.opensanctions.org/datasets/latest/sanctions/entities.ftm.json"
    :default-params {}
    :terms "https://www.opensanctions.org/licensing/"
    :min-interval-ms 86400000
    :scope "The 555 organizations on the far end of a vessel-ownership edge in
            the OFAC export -- not all 9,819 organizations in it. Identifiers
            are taken AS PUBLISHED and nothing is matched by name.

            Measured on a sample of 40: exact-name lookup in GLEIF hit 4, and
            only 1 of those 4 agreed on jurisdiction. GLEIF placed
            `Odyssey Marine Inc.` in Nevada and `Patriot Inc.` in Delaware
            where the sanctions record says Marshall Islands. Recording those
            as identity would assert that a Nevada company owns a sanctioned
            tanker, and the error direction on that is defamatory.

            Measured over all 555: LEI on 2. IMO Company Number on 478.
            Registration number on 316. Fifteen carry no identifier at all,
            which `has_identifier` records rather than leaving as a blank
            that reads like a gap in the ingest.

            From the multi-jurisdiction `sanctions` collection since
            2026-08-28, not OFAC alone. The identifier counts above were
            measured on the OFAC population of 555 and are restated on each
            ingest rather than pinned here."
    :notes "Same ~353 MB payload as `opensanctions-ownership`, fetched twice a
            day because a feed maps to one kind and one table. The archive is
            content-addressed so it is stored once; the fetch is not. That is
            a real cost and it is written down rather than hidden.

            **Data: OpenSanctions, CC-BY-NC 4.0.** Not on the CC0 wiki plane."}

   {:id :aisstream
    :kind :vessel
    :access :stream
    :credential-env "AISSTREAM_API_KEY"
    :label "AISStream vessel positions"
    :url "wss://stream.aisstream.io/v0/stream"
    :default-params {}
        :scope "Global, and asking for nothing, because the collector that would
            hold the socket open does not exist. The vessel table is now fed
            by `digitraffic` instead -- one sea rather than every sea -- so
            this feed's absence is no longer the difference between some
            vessels and none. It is the difference between the Baltic and
            the world, which is why it stays in the registry as an
            exemption with a reason rather than being deleted."
    :terms "https://aisstream.io/terms"
    :min-interval-ms 0
    :notes "A WebSocket subscription, not a poll: positions arrive when
            vessels broadcast them. The message PARSER is implemented and
            tested here; the resident collector that holds the socket open
            is not part of this repository. This feed is UNMEASURED."}])

(def by-id (into {} (map (juxt :id identity)) registry))

(defn not-modified?
  "Did this feed answer `you already have this` rather than fail?

  Some feeds say it with a non-2xx and a body. CelesTrak uses **403** with
  `GP data has not updated since your last successful download of
  GROUP=... at ... UTC.` -- not 304, and not an error. Reading that as a
  failure to observe would report a sky that had not moved as a sky nobody
  could see, which is the one confusion this whole repository is built to
  avoid.

  Matched on status AND body, deliberately narrowly. A genuine 403 --
  blocked, rate-limited, banned -- carries a different body and must stay
  UNMEASURED, because that one really is a failure to observe. A feed with
  no `:not-modified` declaration never takes this path."
  [feed status body]
  (boolean
   (when-let [nm (:not-modified feed)]
     (and (= (:status nm) status)
          (string? body)
          (str/includes? body (:body-contains nm))))))

(defn due?
  "Has enough time passed since this feed was last CONTACTED to be worth
  contacting again?

  `:min-interval-ms` sat in this registry, one line per feed, from the day
  the registry was written -- and nothing read it. Every entry carried a
  comment explaining the cost of polling faster, and the tick polled at
  whatever rate it was invoked at. A field that looks like a control and
  controls nothing is worse than no field: it reads, to the next person, as
  a decision already taken.

  `last-contact-ms` is nil for a feed that has never been reached, and nil
  admits -- a first run must look. It is deliberately NOT the watermark:
  the watermark says when the newest observation happened, which for a
  quiet feed can be days before the last time we asked. Backing off on that
  would poll a quiet feed hardest."
  [feed now-ms last-contact-ms]
  (or (nil? last-contact-ms)
      (>= (- now-ms last-contact-ms) (or (:min-interval-ms feed) 0))))

(defn next-due-in-ms
  "How long until `due?` turns true. Zero when it already is.

  Reported rather than computed at the call site so the run report can say
  *when* rather than only *not yet* -- `not yet` alone is the shape that
  reads the same whether the backoff is a minute or six hours."
  [feed now-ms last-contact-ms]
  (if (nil? last-contact-ms)
    0
    (max 0 (- (or (:min-interval-ms feed) 0) (- now-ms last-contact-ms)))))

(def expected-unmeasured
  "Feeds that cannot be read today, with the reason and what would clear it.

  Declared here rather than in the scheduler because two things now check
  it -- the cycle wrapper, so a permanently red timer is not indistinct
  from a broken one, and `otent coverage`, so a report cannot call a dark
  feed expected on its own authority. Two copies of a declaration is how
  one of them quietly stops matching the other.

  A THIRD feed going dark is a failure: this is checked by name, never as
  a count."
  {"firms"     {:since "2026-08-26"
                :why "$FIRMS_MAP_KEY is not set; NASA FIRMS needs a free key"
                ;; Checkable, and checked: the cycle looks this item up in
                ;; the Keychain and drops the exemption when it finds it.
                ;; An exemption whose clearing condition is prose is one
                ;; that outlives the reason for it.
                :clears-when "the Keychain has firms.nasa/MAP_KEY"}
   "aisstream" {:since "2026-08-26"
                :why "AIS is a WebSocket subscription and the resident collector is not in this repository"
                ;; Structural, not a missing key -- so unlike `firms` there
                ;; is nothing this cycle can look up to clear it. It stays a
                ;; sentence, and it stays true.
                :clears-when "a collector runs somewhere and writes vessel rows"}})

(defn open-feeds [] (filter #(= :open (:access %)) registry))

(defn describe
  "One line per feed, for the run report. Includes the ones that will not
  run -- that is the whole point of printing it."
  []
  (for [{:keys [id kind access label credential-env]} registry]
    (str (str/upper-case (name id))
         "  kind=" (name kind)
         "  access=" (name access)
         (when credential-env (str "  needs=$" credential-env))
         "  -- " label)))
