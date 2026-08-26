(ns tenkyu.feeds.core
  "The feed registry: what each public source is, and what it costs to read.

  Every entry is data, so `bin/tenkyu.cljs` can report the whole roster --
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
  all five. `bin/tenkyu.cljs` exits 2 for that -- not 0, not 1."
  (:require [clojure.string :as str]))

(def registry
  [{:id :celestrak
    :kind :satellite
    :access :open
    :label "CelesTrak GP element sets"
    :url "https://celestrak.org/NORAD/elements/gp.php"
    :default-params {"GROUP" "stations" "FORMAT" "tle"}
    :terms "https://celestrak.org/publications/"
    ;; Element sets are re-fitted on the order of once a day. Polling faster
    ;; adds rows that are byte-identical to the last ones and are then held
    ;; as duplicates -- work done to produce a hold.
    :min-interval-ms 21600000
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
