(ns otent.observation
  "The one row shape every feed lands in.

  Five public feeds -- orbital elements, seismic events, aircraft
  transponders, thermal anomalies, vessel positions -- describe entirely
  different things. What they have in common is that each is *somebody
  saying where something was, at a time, and where they said it*.

  That is the row:

  | column | |
  |---|---|
  | `kind` | `satellite` `quake` `aircraft` `fire` `vessel` |
  | `object-id` | the identifier the SOURCE uses, unchanged |
  | `observed-at` | UTC milliseconds, from the source |
  | `lat` `lon` `alt-km` | WGS-84, or nil where the feed gives none |
  | `attrs` | feed-specific, a map |
  | `source` `source-url` `fetched-at` `payload-sha256` | provenance |

  ## Why provenance is columns and not a side table

  `otent.governor` refuses a row that cannot say where it came from, and it
  can only do that if the answer travels with the row. A provenance table
  joined on batch id is one deletion away from a lake full of coordinates
  nobody can attribute -- and the deletion looks like a cleanup.

  ## `object-id` is the source's identifier, not ours

  Not normalised, not prefixed, not resolved across feeds. An OpenSky
  `icao24` and an AIS `mmsi` are different registries and must not be made
  to look like one namespace by this layer. Joining them is a question
  somebody asks later, with evidence; doing it here would bake an
  unrecorded guess into the only copy of the data."
  (:require [clojure.string :as str]))

(def kinds #{:satellite :quake :aircraft :fire :vessel})

(defn observation
  "Build a row. Present but unknown values stay nil -- a feed that does not
  report altitude must not be made to say zero."
  [{:keys [kind object-id observed-at lat lon alt-km attrs
           source source-url fetched-at payload-sha256]}]
  {:kind kind
   :object-id object-id
   :observed-at observed-at
   :lat lat
   :lon lon
   :alt-km alt-km
   :attrs (or attrs {})
   :source source
   :source-url source-url
   :fetched-at fetched-at
   :payload-sha256 payload-sha256})

(def ^{:doc "Column order for the Iceberg table. Fixed and explicit, because
  the writer pins the schema on the first batch and a map's iteration order
  is not a promise. `attrs` is carried as a JSON string: the feeds disagree
  about their own extra fields week to week, and a struct column would make
  every such change a schema migration on a table that is only a projection."}
  columns
  ["kind" "object_id" "observed_at" "lat" "lon" "alt_km" "attrs_json"
   "source" "source_url" "fetched_at" "payload_sha256"])

(defn ->row
  "Observation -> the flat map the NDJSON writer takes, keyed by `columns`.

  Everything is written as a string. Iceberg gets a table of
  `large_string`, and the reader casts. That is a deliberate narrowing: a
  numeric column would need this layer to decide, per feed, whether a
  missing altitude is null or zero and whether a magnitude is float or
  decimal -- decisions that belong to whoever queries, not to whoever
  ingests. The values are lossless as text; a cast at read time is not."
  [o]
  {"kind" (some-> (:kind o) name)
   "object_id" (:object-id o)
   "observed_at" (some-> (:observed-at o) str)
   "lat" (some-> (:lat o) str)
   "lon" (some-> (:lon o) str)
   "alt_km" (some-> (:alt-km o) str)
   "attrs_json" #?(:clj (pr-str (:attrs o))
                   :cljs (js/JSON.stringify (clj->js (:attrs o))))
   "source" (some-> (:source o) name)
   "source_url" (:source-url o)
   "fetched_at" (some-> (:fetched-at o) str)
   "payload_sha256" (:payload-sha256 o)})

(defn table-name
  "The Iceberg table a kind lands in: one table per kind, `otent_<kind>`.

  Not one table for everything. The kinds have wildly different row rates
  -- aircraft is thousands per poll, satellites is a daily catalogue -- and
  a shared table makes every aircraft poll rewrite the manifest that the
  satellite rows live in. Not one table per poll either: that is a metadata
  explosion, and commit cost is per-commit, not per-row."
  [kind]
  (str "otent_" (str/replace (name kind) "-" "_")))
