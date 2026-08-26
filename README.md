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

Every row carries `payload_sha256`, `source_url` and `fetched_at`, and
**every fetched payload is stored in the bucket under its own hash** at
`otent/payload/<sha256>.json.gz`. So the Iceberg tables can be dropped and
rebuilt. The moment that stops being true they have become a *premise*, and
this workspace does not put a premise behind one vendor's SQL endpoint
(superproject ADR-2608039000). Provenance is columns rather than a joined
side table for the same reason: a provenance table is one deletion away
from a lake full of coordinates nobody can attribute, and the deletion
looks like a cleanup.

> ### This section was false until 2026-08-26
>
> It said the tables could be rebuilt from the payloads "because the raw
> payload of every fetch is content-addressed and its sha256 travels on
> every row". The sha did travel on every row. The payload was fetched,
> hashed, parsed and **dropped on the floor** — nothing wrote it anywhere.
> You cannot rebuild anything from a hash.
>
> So the tables were the only copy, which made them exactly the premise
> this section says they are not, and it made retention impossible to do
> honestly: deleting a row would have destroyed the only record of an
> observation, so the table could only grow.
>
> `archive-payload!` now puts the bytes in the bucket **before** the rows
> go to the table, and a failure to store refuses the commit — watched
> failing on 2026-08-26 by pointing the bucket at a name that does not
> exist: `exit 1`, the reason named, and the aircraft table unchanged at
> 32,006 rows rather than 32,006 + 6,139. Committing rows whose payload was
> not stored would put the table back to being the only copy, silently, and
> only for the ticks where the write happened to fail.
>
> The key is the sha256 of the *uncompressed* bytes — the same hash the
> rows carry, so a row points at its payload with no second identifier. The
> object is gzipped, which is transport and does not change identity;
> measured on a USGS payload, 32,064 bytes stored as 5,060. A payload
> already present is not written again: two objects with the same content
> and different write times would make the second one the answer to *when
> did we first see this*.

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

## How often each feed is asked

Every feed in the registry declares a `:min-interval-ms` — six hours for
CelesTrak element sets, five minutes for USGS, a minute for OpenSky. Those
numbers were written down with the registry and, until 2026-08-26, **nothing
read them.** The tick polled at whatever rate it was invoked at. A field that
looks like a control and controls nothing is worse than no field: it reads,
to the next person, as a decision already taken.

`feeds/due?` now gates the tick, and a feed inside its interval comes back as
`NOT-DUE` — a status of its own, never folded into `nothing-new`:

```
NOT-DUE celestrak -> otent_satellite  last contacted 9715s ago; this feed
  declares a minimum interval of 21600s, so it is due again in 11885s.
  NOT asked -- this is not an observation.
```

`nothing-new` means we asked and the feed had not changed. `not-due` means we
did not ask. Collapsing them would report a deliberate backoff as an
observation. `--force` overrides.

### The bug this uncovered

The first real tick after the interval landed **refused USGS**:

```
REFUSED usgs -> otent_quake  [held-fraction-too-high]
  42 of 46 rows held (91%), over the 50% ceiling: {:already-committed 42}
```

Two and a half hours after the previous poll, USGS returned 46 quakes: 42
already in the table, 4 genuinely new. The ceiling exists to catch a broken
parser wearing the shape of a quiet day — but it was counting deduplication
as a defect, so **four new earthquakes were dropped for arriving next to
forty-two old ones**, and the receipt said `held-fraction-too-high`, which
reads like a parser fault. Scheduling the tick would have made this the normal
outcome for every slow feed.

`:already-committed` now leaves both the numerator and the denominator. What
the ceiling measures is the fraction of rows *this poll could have
contributed* that were held for a reason about the row. A parser emitting the
same row a thousand times is still caught — that is `:duplicate-observation`,
which stays in.

### The launchd job, and what had to land before it

`ops/cloud.itonami.otent-tick.plist`, every **five minutes** — which is not
the poll rate. Each feed's `:min-interval-ms` decides that, and a feed inside
its interval comes back `NOT-DUE` without being asked. The timer only has to
be faster than the fastest feed wants.

```bash
cp ops/cloud.itonami.otent-tick.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/cloud.itonami.otent-tick.plist
tail -40 /tmp/otent-tick.log
```

Nothing was scheduled before 2026-08-26 and it could not honestly have been.
Three things had to land first, in this order, each because the one after it
would otherwise have been a lie:

1. **The payloads had to be stored.** Retention deletes rows; without the
   bytes somewhere else, deleting one destroys the only record of an
   observation.
2. **Retention had to exist.** Otherwise the table only grows.
3. **The reader had to prune files by manifest bounds.** Otherwise the read
   cost tracks the history rather than the answer — measured at 25,219 rows
   and 33.9 s before, 6,787 rows after, on the same question.

Scheduling before those would have grown a table behind a page that timed
out, and called it automation.

`bin/scheduled.cljs` is the cycle, not the plist: launchctl needs
`bootout`/`bootstrap` on every edit, so a plist carrying decisions is a plist
nobody fixes. It does three things a plist cannot express.

**The credential comes from the Keychain, by name.** `security
find-generic-password -s gftd.cf -a API_TOKEN` — one item, targeted. Never an
enumeration: a dump exposes unrelated credentials' metadata and raises a
prompt per item. Absent, the cycle exits 2 and says nothing ran, rather than
running a tick that reports every feed UNMEASURED and reads like a quiet
planet.

**UNMEASURED feeds are expected here, by name.** `tick` exits 2 whenever a
feed could not be read, which is right for a person and wrong for a timer:
`firms` needs a key nobody has entered and `aisstream` needs a resident
collector this repository does not run, so an unwrapped tick would exit 2 on
every run forever — and a job that is permanently red is one nobody can tell
from a broken one. The expected set is declared in `expected-unmeasured` with
a date, a reason and a clearing condition, and **a third feed going dark is a
failure**: watched on 2026-08-26 by removing `aisstream` from the set, which
exits 2 naming it.

**Retention runs after ingest.** Deleting rows the tick just wrote is fine —
they are past the horizon or they are not. Doing it first would delete rows
whose replacement then failed to commit.

### Retention

`otent.cljs retain`, per kind, horizons in `scripts/iceberg_retain.py`:
aircraft 1 day, vessel 1 week, fire and satellite 3 months, quake 1 year.
Longer than the read window in every case, because the two answer different
questions — the window is *what should be drawn now*, the horizon is *how
much history is worth keeping queryable*. Anything older is still in the
bucket, addressed by its hash.

Four refusals, each watched on 2026-08-26:

| | |
|---|---|
| a table with no declared horizon | refuses rather than inheriting another table's |
| a cutoff that is not 13 digits | refuses — `observed_at` is text, so string order equals numeric order only while every value has the same digit count (2001–2286) |
| rows whose payloads are not in the bucket | refuses, naming the count: deleting them would destroy the only record |
| every table unreadable | exits 2 — that is what an outage looks like from here, and it must not read as a clean run |

Rows written before the payload archive existed can never have a payload, so
they would block retention forever. `--pre-archive-ms` waives exactly those,
has no default, and prints how many it waived. Set to the epoch — covering
nothing — the same three rows refuse again, naming them as observed *after*
the archive existed, which is a failure rather than history.

## Tests

`npm test` — 33 tests, 614 assertions, against **captured real payloads**
rather than invented ones.

The runner has two floors and four exit codes, each watched on 2026-08-26:
**0** pass · **1** a test failed · **2** the runner and the `test/otent`
directory disagree · **3** too few tests ran to report a pass. Two and three
are not failing tests and must not look like ones. The directory check exists
because the namespace list has to be written literally — nbb resolves
`require` at read time, so a runtime symbol loads nothing while looking like
it did — and a written list is the shape where a test file lands, the line is
forgotten, and the runner goes green over a suite it never loaded.

Seven deliberate breakages, run 2026-08-26:

| break | result |
|---|---|
| drop the `*1000` in the OpenSky parser | exit 1, 139 failures |
| make `person-identifier?` always false | exit 1, in `an-attribute-that-names-a-person` |
| make an unmeasured feed exit 0 | exit 1, in `unmeasured-does-not-collapse-into-its-neighbours` |
| replace a fixture with `{}` | exit 1, 2 errors — a truncated fixture does not read as a pass |
| make `due?` always admit (the state before this change) | exit 1, 2 failures |
| count `:already-committed` in the held fraction again | exit 1, 2 failures |
| remove the held-fraction ceiling entirely | exit 1, 4 failures, 2 errors |
| add a `_test.cljc` the runner does not list | **exit 2**, `REFUSED: the runner and the directory disagree` |
| raise the minimum test count above the suite | **exit 3**, `REFUSING to report a pass` |

One of those breakages was mine to begin with: the first version of the
`already-committed` test failed for the wrong reason — its "new" rows carried
the fixture's own timestamp, which was older than the watermark, so all 46
were held and the batch came back `:nothing-new` rather than committing four.
The code was right and the fixture was wrong. A test that fails is not
automatically a test that discriminates.

One test bug worth keeping: the parse suite originally pinned the ingest
clock to a constant that **predated the captured OpenSky payload**, so
every row was correctly held as an hour in the future and the suite read
that as the parser being broken. The clock is now derived from the fixture.
A recording is a moment; a test that compares it against a fixed present
is testing the calendar.
