# otent

**お天道様 (otentosama) — the sun, as the thing that sees everything.**
Japanese keeps the sun as a witness: お天道様は見ている, *the sun is
watching*. That is what this is for.

This repository is the *ingest actor*: it polls open feeds for satellites,
earthquakes, aircraft, fires and vessels, checks every row against an
independent governor, and appends what survives to Apache Iceberg tables in
Cloudflare R2 Data Catalog.

The name does not describe the function — so, as this workspace requires,
it says so here in the first paragraph and is registered in
`manifest/concept-vocabulary.edn`.

> Renamed from `tenkyu` (天球, the celestial sphere) on 2026-08-26, owner's
> call. 天球 named the *surface things are drawn on*; お天道様 names the
> *watching*, which is what the project is about. GitHub redirects the old
> name; west carries only the new one.

Nothing renders here. `cloud-itonami/app-otent` draws the globe, and it
reads **only** what this actor has committed.

```bash
nbb --classpath src:../../kotoba-lang/sgp4/src bin/otent.cljs feeds
nbb --classpath src:../../kotoba-lang/sgp4/src bin/otent.cljs tick --dry-run
CF_CATALOG_TOKEN=... nbb --classpath src:../../kotoba-lang/sgp4/src bin/otent.cljs tick
```

## Live, measured 2026-08-26

| table | rows | source |
|---|---|---|
| `cloud_itonami.otent_quake` | 66 | USGS 2.5+ past day |
| `cloud_itonami.otent_satellite` | 21 | CelesTrak GP (stations) |
| `cloud_itonami.otent_aircraft` | 7,214 | OpenSky anonymous state vectors |
| `cloud_itonami.otent_vessel` | — | **UNMEASURED**, see below |
| `cloud_itonami.otent_fire` | — | **UNMEASURED**, see below |

Bucket `cloud-itonami-datalake`, catalog
`https://catalog.cloudflarestorage.com/<account>/cloud-itonami-datalake`.
The token needs **two** permissions — `R2 Data Catalog: Edit` *and*
`Workers R2 Storage: Edit`. A token with only the first passes
`GET /v1/config`, lists namespaces, and then fails on `create_table` with a
storage-side 401: **reaching the catalog is not the same as being able to
write to it.**

## The three feeds that ran, and the two that did not

`tick` exits **2** — not 0, not 1 — when a feed could not be read.

```
otent tick 2026-08-26T02:09:05.038Z
  committed 0  dry-run 3  nothing-new 0  refused 0  UNMEASURED 2  rows 0
  DRY-RUN celestrak -> otent_satellite  would append 21 rows
  DRY-RUN usgs      -> otent_quake      would append 33 rows
  DRY-RUN opensky   -> otent_aircraft   would append 7233 rows
  UNMEASURED firms      [no-credential] $FIRMS_MAP_KEY is not set
  UNMEASURED aisstream  [needs-resident-collector] ...
exit 2 -- 2 feed(s) were NOT READ. That is not an observation of nothing.
```

**Vessels and fires are unmeasured, not empty.** NASA FIRMS needs a free
MAP_KEY; AISStream is a WebSocket subscription and the resident collector
that holds the socket open is not in this repository — its message parser
is implemented and tested, so that collector has nothing to invent, but
until something runs it the vessel table does not exist. A scheduler that
treated exit 2 as success would record a month of missing vessels as a
month of empty oceans.

## The governor

Rows are proposed by the fetch/parse side and admitted by
`otent.governor`, which is pure — no network, no clock, no file handle,
so every verdict is reproducible from the batch and the `now` it was given.

| rule | holds |
|---|---|
| `:provenance-incomplete` | a row that cannot say where it came from |
| `:timestamp-not-plausible` | seconds read as milliseconds, or the reverse, or the future |
| `:coordinates-out-of-range` | latitude past ±90, longitude past ±180 |
| `:person-identifier` | an attribute whose name marks a person |
| `:duplicate-observation` | the same object at the same instant, twice in one batch |
| `:already-committed` | at or before the watermark from an earlier tick |

### These rules were watched firing

Not asserted — run, against the live feeds, 2026-08-26:

```
BREAK  drop the *1000 in the OpenSky parser (seconds read as milliseconds)
  opensky: 7224 parsed, 0 admitted, 7224 held
      held 7224 x timestamp-not-plausible
  REFUSED opensky [everything-held]                                  exit 1

BREAK  transpose lat/lon in the USGS parser
  usgs: 33 parsed, 9 admitted, 24 held
      held 24 x coordinates-out-of-range
  REFUSED usgs [held-fraction-too-high] 24 of 33 (73%), over the 50% ceiling
                                                                     exit 1
```

The second one is the interesting result. **Nine of thirty-three rows
still passed the per-row check** — a transposed pair where both values
happen to be valid latitudes is invisible to a range rule. That is why
there is also a batch-level ceiling, and why the per-row test in
`governor_test.cljc` asserts the limitation explicitly rather than
flattering the rule.

### `:person-identifier` keeps what the transponder itself broadcasts

`icao24`, `callsign`, `mmsi`, `ship_name`, `registration` are **kept**.
They identify an aircraft or a vessel, and they are broadcast in the clear
by the thing itself. `owner`, `pilot`, `crew`, `passenger_email` and
twenty-odd other markers are held. Removing the first group would not make
the data less identifying — it would make it useless while the same
broadcast stayed public. The line is vehicle versus person, and the test
asserts both sides.

## Two deduplication rules, because one was not enough

The satellite table reached **42 rows from 21 element sets** on the first
day. The in-batch duplicate rule cannot see across ticks, and CelesTrak
re-fits roughly daily while being polled more often, so every poll
re-offered the same rows.

| rule | exact? | catches |
|---|---|---|
| payload sha256 identical to last tick's | **yes** | polling faster than a feed republishes |
| `observed-at` at or before the feed's watermark | no | a partially-changed payload |

The hash rule has no false negatives; the watermark does — it is a single
max per feed, not per object, so an element set republished with an epoch
older than some *other* satellite's newest epoch would be held. That is a
known limitation, written down here rather than discovered later.

The watermark is read from the receipt ledger, not from a table scan: the
previous tick already wrote the answer down, and `max(observed_at) GROUP BY
object_id` over seven thousand aircraft rows a minute would be the most
expensive thing in the tick.

**The 21 duplicate rows were not left in place.** `otent_satellite` was
dropped and rebuilt from one clean poll once the watermark existed — the
table was twenty minutes old, created by the same session, and every
duplicate in it came from a stray re-run. That is recorded because a
silently-cleaned table and a table that never had the problem look the same
afterwards.

## Buildings, and the ground under them

`bin/buildings.cljs` pulls OpenStreetMap building footprints — via
**OpenFreeMap**, keyless, ODbL 1.0 — plus water, landcover and parks from
the same tiles, and stores them as flat coordinate arrays in R2.

```bash
nbb --classpath src:../../kotoba-lang/map/src bin/buildings.cljs areas
nbb --classpath src:../../kotoba-lang/map/src bin/buildings.cljs ingest --area tokyo
```

Measured 2026-08-26: **19,016 buildings across 100 tiles, four metro
areas, 0 failed.**

| | tiles | buildings | fetched | stored |
|---|---|---|---|---|
| Tokyo | 25 | 2,199 | 15.2 MB | 2.8 MB |
| New York (Manhattan) | 25 | 10,129 | 8.8 MB | 6.4 MB |
| London | 25 | 3,895 | 10.1 MB | 3.6 MB |
| Singapore | 25 | 2,793 | 6.1 MB | 3.1 MB |

**The MVT is decoded here, once.** A z14 tile is 730 KB of protobuf
carrying sixteen layers, of which this wants four; shipping it to the
browser would make every viewer pay to reach the same answer.
`kotoba.map.mvt` does the decoding, so nothing downstream parses protobuf.

**Coverage is bounded and named.** OpenFreeMap serves buildings at z14
only, and the planet is 268 million z14 tiles. The manifest records the
exact tile block per area, so the renderer asks only where something
exists — a globe that requested buildings everywhere and 404'd would be
the same picture plus a request storm.

⚠ `manifest` MEASURES the deepest raster zoom present rather than taking
it from a flag, and the presence probe is a **one-byte ranged GET, not
HEAD**: Cloudflare's REST object API answers HEAD with a non-2xx, so a
HEAD probe reported every object as absent and concluded there was no
basemap at all in a bucket holding 1,365 tiles.

## The tables are a projection, not the source of truth

Every row carries `payload_sha256`, `source_url` and `fetched_at`, so the
Iceberg tables can be dropped and rebuilt from the payloads. The moment
that stops being true they have become a *premise*, and this workspace does
not put a premise behind one vendor's SQL endpoint
(superproject ADR-2608039000). Provenance is columns rather than a joined
side table for the same reason: a provenance table is one deletion away
from a lake full of coordinates nobody can attribute, and the deletion
looks like a cleanup.

**Satellite rows carry no position.** They carry the element set, and
position is a function of elements and time — evaluated by
[`kotoba-lang/sgp4`](https://github.com/kotoba-lang/sgp4) wherever it is
needed, which for the globe is the browser. Writing a position here would
freeze one instant into a table whose whole value is that it can be
evaluated at any instant.

## Layout

```
src/otent/observation.cljc   the one row shape every feed lands in
src/otent/governor.cljc      pure admit/hold
src/otent/receipt.cljc       pure: run report and exit code
src/otent/feeds/core.cljc    the registry, including what cannot be read
src/otent/feeds/parse.cljc   payload -> observations, per feed. Pure.
bin/otent.cljs               the only namespace that touches the network
scripts/iceberg_append.py     the only part nbb cannot do
```

Python appears once, for the Iceberg commit, because nbb has no Iceberg
writer — the same split `com-junkawasaki/org-gleif-projections` already
uses in this workspace. The boundary is NDJSON on disk; that script knows
nothing about feeds, governors or EDN.

## Tests

`npm test` — 23 tests, 583 assertions, against **captured real payloads**
rather than invented ones. Four deliberate breakages, run 2026-08-26:

| break | result |
|---|---|
| drop the `*1000` in the OpenSky parser | exit 1, 139 failures |
| make `person-identifier?` always false | exit 1, in `an-attribute-that-names-a-person` |
| make an unmeasured feed exit 0 | exit 1, in `unmeasured-does-not-collapse-into-its-neighbours` |
| replace a fixture with `{}` | exit 1, 2 errors — a truncated fixture does not read as a pass |

One test bug worth keeping: the parse suite originally pinned the ingest
clock to a constant that **predated the captured OpenSky payload**, so
every row was correctly held as an hour in the future and the suite read
that as the parser being broken. The clock is now derived from the fixture.
A recording is a moment; a test that compares it against a fixed present
is testing the calendar.
