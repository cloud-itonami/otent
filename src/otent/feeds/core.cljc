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
  all five. `bin/otent.cljs` exits 2 for that -- not 0, not 1."
  (:require [clojure.string :as str]))

(def registry
  [{:id :celestrak
    :kind :satellite
    :access :open
    :label "CelesTrak GP element sets"
    :url "https://celestrak.org/NORAD/elements/gp.php"
    :default-params {"GROUP" "active" "FORMAT" "tle"}
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
    :label "USGS earthquake summary feed"
    :url "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson"
    :default-params {}
    :terms "https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits"
    :min-interval-ms 300000
    :notes "GeoJSON. Timestamps are UNIX MILLISECONDS -- unlike OpenSky's."}

   {:id :opensky
    :kind :aircraft
    :access :open
    :label "OpenSky Network state vectors (anonymous)"
    :url "https://opensky-network.org/api/states/all"
    :default-params {}
    :terms "https://opensky-network.org/about/terms-of-use"
    ;; The anonymous tier is rate-limited and the underlying data updates
    ;; every 10 s. 60 s is inside the limit and above the update period.
    :min-interval-ms 60000
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
    :terms "https://firms.modaps.eosdis.nasa.gov/usage/"
    :min-interval-ms 3600000
    :notes "CSV. A free MAP_KEY from firms.modaps.eosdis.nasa.gov is required;
            without it this feed reports :no-credential, not zero fires."}

   {:id :aisstream
    :kind :vessel
    :access :stream
    :credential-env "AISSTREAM_API_KEY"
    :label "AISStream vessel positions"
    :url "wss://stream.aisstream.io/v0/stream"
    :default-params {}
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
