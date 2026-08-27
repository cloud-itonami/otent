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
CF_CATALOG_TOKEN=... nbb --classpath src:../../kotoba-lang/sgp4/src bin/otent.cljs coverage
```

## Live, measured 2026-08-27

| table | rows | source |
|---|---|---|
| `cloud_itonami.otent_quake` | 201 | USGS **all magnitudes**, past day |
| `cloud_itonami.otent_satellite` | 21,085 | CelesTrak GP (`GROUP=active`) |
| `cloud_itonami.otent_aircraft` | 882,554 | OpenSky anonymous state vectors |
| `cloud_itonami.otent_fire` | 27,833 | NASA FIRMS VIIRS NOAA-20, global, past day |
| `cloud_itonami.otent_vessel` | 1,282 | Digitraffic (Finnish AIS) — **the Baltic, not the world** |
| `cloud_itonami.otent_vessel_static` | 1,168 | who those vessels say they are |

Bucket `cloud-itonami-datalake`, catalog
`https://catalog.cloudflarestorage.com/<account>/cloud-itonami-datalake`.
The token needs **two** permissions — `R2 Data Catalog: Edit` *and*
`Workers R2 Storage: Edit`. A token with only the first passes
`GET /v1/config`, lists namespaces, and then fails on `create_table` with a
storage-side 401: **reaching the catalog is not the same as being able to
write to it.**

## `otent coverage`, and the two things it found on its first run

    otent coverage

Cadence from the tick ledger, row counts from the catalog, and a refusal
rather than a clean line whenever either could not be measured. It exists
because the question *what does this actually cover* was answered by hand
on 2026-08-27, and the hand answer found two things that every check in
this repository was structurally unable to see.

**Every feed was being polled at 1.5x its declared interval.** The plist
asks launchd for a cycle every 300 s. The cycle ran retention inline, which
takes ~3.4 minutes. launchd will not start a job that is still running. So
the real cadence was 447 s, aircraft was polled every 15 minutes against a
declared 10, and **nothing was red at any point** — `due?` was honouring
`:min-interval-ms` to the millisecond, on a clock it was being handed too
rarely. This is the same field this repository has already caught being
decorative once; it was not decorative this time, it was being defeated
one layer up. Retention now runs on its own hourly interval, against
horizons whose shortest is a day.

**The fire feed had a clearing condition nobody could act on.** `firms`
was exempt from being unmeasured because `$FIRMS_MAP_KEY` was not set, and
the exemption's `:clears-when` read *the key is entered on this machine* —
a sentence, checkable by nobody. The key is free. It was requested, it
arrived, and it is in the Keychain as `firms.nasa/MAP_KEY`; the cycle now
looks it up by name, and **a feed whose key it finds is no longer exempt**.
First commit: 27,833 global detections in the past 24 hours. `:clears-when`
is now a condition the code evaluates, because an exemption whose clearing
condition is prose is one that outlives its reason.

```
feed        kind       polls  declared  measured  ratio   reach
celestrak   satellite      7      6.0h      6.1h   1.01x   100%
usgs        quake        177      5.0m      7.5m   1.50x    99%  DRIFT
opensky     aircraft      90     10.0m     15.1m   1.51x    96%  DRIFT
firms       fire           0     60.0m        --      --     0%  UNMEASURED
aisstream   vessel         0        0s        --      --     0%  UNMEASURED
```

`--window 3h` measures a recent slice instead of the whole ledger, because
a 30-hour median cannot show a cadence that was repaired an hour ago — an
instrument that cannot show the schedule being fixed is useless at the one
moment it matters. The window is printed in the header: *what is the
cadence now* and *which window makes this look fine* are different
questions, and the reader can see which was asked. A window too narrow to
hold twelve ticks refuses rather than dividing small numbers.

Exit **0** clean · **1** a finding · **2** could not answer. The tolerance
is 1.25x, and a test asserts it is below 1.49 — the drift it was written
for — because a tolerance chosen after the fact to make the current state
pass is not a tolerance.

Three things it will not do, each with a test that fails when it does:

| | |
|---|---|
| call an unmeasured interval zero | a feed polled once has no gap; a feed polled never has no evidence. Both are `UNMEASURED`, which is not `ok` |
| read one blip as darkness | the first version called *unmeasured at any point in 30 hours* dark, which made two healthy feeds look as dark as the two that have never been readable — and a permanently red report is a permanently ignored one. Dark is now **as of the last tick**, and intermittent failure is a separate `reach` column |
| let a missing table pass | absent under a feed nobody can read is consistent; absent under a feed that has been committing rows is a finding |

The counter behind it learned the same distinction: `otent count --kind
fire` used to print `UNREADABLE` for a table whose absence was the most
certain fact in the system. **3** now means asked-and-absent, **2** means
could-not-ask.

## Five kinds, and the one that is a sea rather than an ocean

`tick` exits **2** — not 0, not 1 — when a feed could not be read. As of
2026-08-27 every kind has rows, which is new: fires and vessels had never
been observed at all.

**Vessels are the Baltic.** The global source, AISStream, is a WebSocket
subscription and the resident collector that would hold the socket open is
not in this repository. Rather than leave the kind at zero waiting for it,
the vessel table is fed by **Digitraffic** — the Finnish Transport
Infrastructure Agency's AIS, open, keyless, poll-shaped, and therefore a
fit for the machinery that already exists. Roughly 1,300 vessels in the
Gulf of Finland, the Gulf of Bothnia and the Baltic.

That is one sea instead of every sea, and the `:scope` says so. **One sea
measured is not the same as the ocean assumed empty**, and it is also not
the same as the ocean measured — which is why `aisstream` stays in the
registry as an exemption with a reason rather than being deleted. Its
absence used to be the difference between some vessels and none; it is now
the difference between the Baltic and the world.

**What global AIS is actually blocked on, measured 2026-08-27.** Every
other open vessel source was probed and none answered: BarentsWatch returns
401, `web.ais.dk` does not resolve, VT Explorer and MarineTraffic want a
paid key. AISStream is free and global, and its account is
**GitHub-OAuth-only** — `/api/login/github` is the sole login route in its
bundle, there is no email signup. So the blocker is not a collector and not
a protocol; it is one authorization decision that belongs to a person, not
to this actor.

The transport was verified as far as it can be without a key: the socket
opens, the subscription frame is accepted, and the server closes with 1006
and no message. **A silent drop after a successful subscribe is what a bad
key looks like** — worth writing down, because a collector that treated
that as a network fault would retry forever against a wall.

### Where a ship is, and who it says it is

Two tables, because the position payload contains none of the identity
fields. A ship's name, callsign, IMO, type, destination and draught are AIS
message type 5 — `ShipStaticData` — and they *change*: vessels are renamed,
re-registered, and report a new destination every voyage. A mutable side
table would answer *what is this vessel called* and destroy *what was it
called when we saw it there*, so identity lands as observations too:
object, instant, **no position**, on exactly the footing a satellite's
element set does.

Merging them into one row was the obvious thing and is wrong. The position
payload cannot reproduce the name, so the row's `payload_sha256` would
point at bytes it did not come from — and *the tables can be rebuilt from
the archived payloads* is the only reason retention is allowed to delete
anything at all. **One payload, one sha, one row.**

| | |
|---|---|
| 384 of 1,168 records carry **no IMO** | smaller vessels are not required to have one, so the field is absent rather than zero |
| `destination` and `eta` are typed by the crew | routinely stale, misspelled, or a port code nobody outside the bridge uses — kept verbatim, because a tidied version of what someone typed is a different fact from what they typed |

Two traps the position parser is tested against, both from the real payload:

| | |
|---|---|
| `timestampExternal` is epoch ms; the sibling `timestamp` is the **AIS second-of-minute** field (0–59, 60–63 reserved as status) | reading the wrong one puts every vessel in January 1970 |
| GeoJSON is `[lon lat]` | Finnish longitudes are 19–30 and latitudes 59–65, so a transposition **lands inside valid ranges for both** — the per-row rule cannot see it, and the test asserts that limitation rather than flattering the rule |

The endpoint answers **406 without gzip**. Node's `fetch` negotiates it and
`curl` does not, so a hand-check fails where the actor succeeds.

## The whole active catalogue, and the 799 it refuses

The feed asked CelesTrak for `GROUP=stations` — twenty-one objects, the
crewed stations and what is docked to them. It now asks for `GROUP=active`:
**16,057 element sets, of which 16,054 are admitted** (three held for
implausible epochs) and **15,258 can actually be propagated**. The other 799
are deep-space — geostationary, Molniya, anything with a period past 225
minutes — and `kotoba-lang/sgp4` refuses them with
`:sgp4/deep-space-unsupported` rather than propagating them wrongly. SDP4 is
not implemented; a satellite that cannot be propagated is named, not drawn
in the wrong place.

Two things had to change to make that survivable, and both are measured
rather than assumed.

**The browser propagates a slice per frame.** 18.2 µs per propagation × 15,258
is 278 ms, which is 3.4 fps. `app-otent.propagate/slice-size` moves 512 per
advance and everything else holds its last position, so the sky refreshes in
half a second — 3.7 km of drift, about a fifth of a pixel at globe zoom.

**A 403 from CelesTrak is not an error.** They answer a repeat request with
`403` and a body reading `GP data has not updated since your last successful
download of GROUP=active at ... UTC. Data is updated once every 2 hours.` Not
304, and not a failure. Before this, the tick reported the feed UNMEASURED —
saying the sky could not be observed when it had been observed and had not
moved, which is precisely the confusion this repository exists to prevent.
`feeds/not-modified?` matches status **and** body, narrowly: a genuine 403,
blocked or rate-limited, carries a different body and stays UNMEASURED,
because that one really is a failure to observe.

## The magnitude floor that was written into a URL

The quake feed was `2.5_day.geojson` from the first day and never
revisited. That is a coverage ceiling hiding in a hostname: every
earthquake below M2.5 was not missing from the table, it was **outside
what was ever asked for** — which reads, from the table, exactly like a
world with no small earthquakes in it.

Measured 2026-08-27 on the same minute:

| feed | events in the past day | smallest |
|---|---|---|
| `2.5_day.geojson` | 29 | M2.5 |
| `all_day.geojson` | **241** | **M-0.4** |

Same GeoJSON, same parser, ~8x the rows at 170 KB a poll. `all_day` also
carries quarry blasts, mining explosions and ice quakes; they are kept,
with `event_type` on the row, because they are real observations of the
ground moving and dropping them would be a second undeclared filter of
exactly the kind this change removes.

**The lesson is not about USGS.** A URL is a place a scope decision can be
made once and then stop looking like a decision — a narrowed request and a
quiet world produce the same rows.

So every registry entry now declares **`:scope`**: what the request leaves
out, and what could be asked for instead. A test refuses an entry without
one. This does not verify a scope is *right* — nothing can, from here — it
makes it impossible to narrow coverage without writing down that you did.

`firms` is the example working as intended. It asks for VIIRS NOAA-20 and
names the three other sensors available under the same key that it is
**not** asking for, so roughly a doubling of detections sits in the
registry as an arguable line rather than in a path segment as a fact.

## Nothing had a timeout, and launchd will not overlap

Measured 2026-08-27, on the first cycle after the retention change: a tick
sat for **eighteen minutes at 0% CPU**, holding a dead OpenSky socket in
`CLOSE_WAIT` and then two established connections to Cloudflare. Node's
`fetch` has no default timeout and nothing here set one.

That is worse than a slow poll. **launchd will not start a job while the
previous one is running**, so one stalled remote does not delay one feed —
it stops ingest entirely, for as long as the socket stays open. And a cycle
that never finishes writes no receipt, so the ledger shows nothing at all,
which is exactly what a quiet period looks like. The failure is invisible in
the one place built to make failures visible.

`otent.deadline` puts a deadline on every network call — 60 s for reads,
180 s for writes, on the feed fetch, the R2 archive and the kotobase
publish (through `make-client`'s `:fetch-fn`, so the tolerance stays this
actor's choice rather than the library's). A call that runs out is
`UNMEASURED` with **`[timeout]`, not `[unreachable]`**: one says the feed
answered nothing, the other says we stopped waiting and do not know what it
would have said. Watched firing by setting the deadline to 1 ms against the
live USGS feed.

### The suite was green over a file that did not parse

Fixing the above, a one-paren edit left `bin/otent.cljs` unparseable and
`npm test` reported **70 tests, 740 assertions, 0 failures**. Every
namespace the tests require was fine. `bin/otent.cljs` is required by
nothing — it is the entry point, it ends in `(-main)`, and requiring it
from a test would run a tick against the live feeds. So the one file that
touches the network, holds the commit logic and is what launchd executes
was the one file no test could see.

`parses_test.cljc` runs each CLI with no arguments. That path prints usage
and exits 2 without opening a socket, but it is a real load: every
`require` resolves and every top-level form evaluates. Reading the files
with `cljs.reader` was tried first and is wrong — the reader has no `#js`,
no `#?` and no regex literal, so it calls healthy ClojureScript
unreadable, and a checker that is wrong about good files is worse than
none. **`bin/scheduled.cljs` is still not covered**, because a bare
invocation of it reads the Keychain and runs a cycle; that gap is named in
the namespace docstring rather than left to be assumed away.

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

**Eighteen metro areas, 450 z14 tiles.** The first four were Tokyo,
Manhattan, London and Singapore — a reasonable place to start and an
unreasonable place to stop, because a globe with buildings in exactly those
four is making a claim about whose cities are worth drawing. The additions
are chosen for spread across continents and hemispheres rather than for
size: Osaka, Seoul, San Francisco, Mexico City, São Paulo, Paris, Berlin,
Istanbul, Cairo, Lagos, Nairobi, Mumbai, Jakarta, Sydney.

**The counts measure OpenStreetMap, not the city.** Lagos and Cairo return
far fewer buildings than Manhattan, and that is a true measurement of what
OSM contributors have mapped rather than a failure of the ingest. The
manifest records the count per area, so the difference stays visible
instead of being averaged into a total.

### A one-area run used to erase the other seventeen

`write-manifest!` rebuilt the whole manifest from the areas the current run
touched, so `ingest --area osaka` wrote a manifest in which the other
seventeen had **no building count at all** — not zero, not stale, absent.
Every one of them still held tiles in the bucket. The result was
indistinguishable from a fleet of areas nobody had ever ingested.

It now reads the previous manifest and carries forward what this run did
not measure, with the `measured-at` it was measured at. Watched working: an
Osaka-only run left Tokyo, Manhattan, London and Singapore holding
yesterday's counts and yesterday's timestamps.

**A listed range is a promise that the tiles behind it exist.**
`globe/scene.cljc` walks every range in `areas` and asks for the tiles
inside it, so an area that is declared but not ingested has to be kept out
of that list or the manifest stops being a coverage map and becomes a 404
generator — the exact failure the "named ranges, not everywhere" design
exists to prevent. Declared-and-not-ingested areas go in
`declared-not-ingested` instead: named, so the gap is visible; without a
range, so nothing can ask for them. Every entry lands in exactly one of the
two lists, which is what stops a third, silent state from existing.

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

## Three planes, and what is allowed on each

| plane | holds | addressed by |
|---|---|---|
| **bucket** `otent/payload/<sha>.json.gz` | the bytes exactly as the feed served them | their own sha256 |
| **Iceberg** `cloud_itonami.otent_*` | governed observation rows | table + snapshot |
| **kotobase.net** `otent-catalog` | the catalog: what ran, what it wrote, which payload backs it | otent's own DID |

**Bytes and bulk observations do not go on the datom plane** (superproject
ADR-2608039970). Not as a size optimisation: a datom plane is for things you
join across datasets, and nobody joins an aircraft's latitude against
anything — but `otent_aircraft` as a table, `celestrak` as a source, and a
tick's provenance all join against `:source/dataset` neighbours in the same
workspace plane.

So `otent.catalog/tick->tx` is a projection that **drops almost everything**,
and the suite asserts what it drops rather than only what it keeps — a
coordinate arriving there would look like a richer catalog rather than like a
leak. `publish-tick!` re-checks before writing and **refuses** rather than
publishing one under otent's key.

```clojure
;; what a tick leaves behind, queryable from anywhere
{:find [?feed ?status ?table]
 :where [[?e :otent.result/feed ?feed]
         [?e :otent.result/status ?status]
         [?e :otent.result/table ?table]]}
;; => [["usgs" "nothing-new" "otent_quake"]]
```

Every status reaches the plane, including `not-due` and `unmeasured`. A
catalog that recorded only successful polls could not answer why a table
stopped growing.

**The identity is otent's own** — a 32-byte Ed25519 seed at
`.otent/identity.edn`, gitignored, mode 0600. The key *is* the authority: the
graph a write lands in is derived from it, so there is no token to request
and no owner hand-off. CACAO minting is
[`kotoba-lang/kotobase-client`](https://github.com/kotoba-lang/kotobase-client);
nothing here re-implements signing, because a second implementation drifts
from the first silently.

Identity of a published entity is derived from values the receipt already
carries (`otent/tick/<at>` and `otent/tick/<at>/<feed>`), so republishing is
an upsert. That is what makes `:retry?` safe on a transient 5xx: the ledger
on disk is append-only, this plane is not.

Publishing happens **after** the receipt is on disk. The ledger is the
record; this is a projection of it, so a failed publish costs queryability
rather than history — and it is reported, never swallowed, because a catalog
that has silently stopped being written looks exactly like a workspace where
nothing happened. `--no-publish` skips it.

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
src/otent/coverage.cljc      pure: declared cadence vs measured cadence
src/otent/deadline.cljc      every network call gets one, and names it
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

**Each feed carries the wall time it took.** The cycle went over the
timer's period again once five feeds were committing, and nothing in the
receipt could say which one was responsible — a cycle total says there is a
problem, a per-feed number says where, and guessing from the feed list is
how you tune the one that was already fast. Printed for every status: a
feed that spent ninety seconds discovering it had nothing new is as much of
a finding as one that spent it committing.

**Retention runs after ingest, and on its own hourly interval.** After,
because deleting rows whose replacement then failed to commit is the one
ordering that loses an observation. Hourly, because running it every cycle
is what stretched every feed's poll rate by half again: it takes ~3.4
minutes, launchd will not start a job that is still running, and the
horizons it enforces are a day at the shortest, so five-minutely was never
what they needed. State in `~/.gftd/otent/retain.state.edn`;
`$OTENT_RETAIN_INTERVAL_MS` overrides. A skipped run logs `retain NOT-DUE`
and never `retain exit 0` — those are different claims.

**The cycle times itself.** If it runs longer than the timer's period it
says so, naming the consequence: launchd will skip fires and every feed's
effective interval is longer than the registry declares. That warning is
the thing whose absence made this invisible for a day.

### The tick ledger is not in the checkout

`~/.gftd/otent/tick.ledger.edn` (or `$OTENT_LEDGER_DIR`). It was
`./ledger/tick.ledger.edn`, tracked in git, which was fine while a person
ran the tick by hand and wrong the moment launchd started running it every
ten minutes: **a scheduled job that appends to a tracked file leaves the
shared checkout permanently dirty**, and every other session's `main` sync
then tries to preserve somebody's WIP. CLAUDE.md records what that costs in
piled-up stashes; the detector tick next door holds the same invariant for
the same reason.

The committed `ledger/` stays as the record of the hand-run era. It is no
longer written to.

### Retention

`otent.cljs retain`, per kind, horizons in `scripts/iceberg_retain.py`:
aircraft 1 day, vessel 1 week, fire and satellite 3 months, quake 1 year.
Run hourly by the cycle rather than every five minutes — see the launchd
section for what running it inline cost.
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

`npm test` — 83 tests, 1,132 assertions, against **captured real payloads**
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
