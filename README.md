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
| `cloud_itonami.otent_vessel` | 68,378 | Digitraffic (the Baltic) **+ global AIS, filtered to listed vessels** |
| `cloud_itonami.otent_vessel_static` | 1,168 | who those vessels say they are |
| `cloud_itonami.otent_vessel_risk` | 23,173 | what the sanctions lists say about ships |
| `cloud_itonami.otent_ownership_link` | 3,473 | which organization controls which hull |
| `cloud_itonami.otent_org_identity` | 1,449 | what identifies those organizations |

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

### Global AIS, and the 124:1 that makes it affordable

`bin/collector.cljs` is the only resident process here — everything else is
a timer that runs and exits. It holds a WebSocket to AISStream, subscribes
to the whole planet, and keeps only vessels on a maritime risk list.

The filter is not a compromise, it is the measurement. Unfiltered, live on
2026-08-28:

| | |
|---|---|
| messages/second | 71 |
| distinct vessels in 90 s | 5,710 |
| dedup ratio | **1.12 messages per vessel** |
| implied volume | ~4.3 M rows/day |

A dedup ratio of 1.12 means keeping one fix per vessel per flush removes
almost nothing — nearly every message is a different ship. Against a vessel
table that held 56,000 rows in total and a read path that already fails at
21,000, the firehose is not a default anybody could serve.

Filtered: **4,212 messages seen in the first minute, 34 kept. 124:1.**

**This is the feed that answers the Black Sea and the Mediterranean.** The
question that started this — whether the attacked shadow-fleet tankers were
in the data — was answered *no*, because every attack was outside the
Finnish receiver network. `digitraffic` is one sea; this is every sea, for
the hulls somebody has designated. `--all` removes the filter for anyone
with somewhere to put 4.3 M rows a day.

What it gives up is the vessel that was not on a list when it sailed past.
That is a real loss, and it is why the scope is one flag rather than a
hard-coded set.

### Four failure modes it is built around, all measured

| | |
|---|---|
| **a bad key is a silent close** | the server accepts the connection, accepts the subscription, then drops it with code 1006 and no message. A close with no `SubscriptionConfirmation` exits 2 rather than retrying against a wall forever |
| **three seconds to subscribe** | the frame goes out in `onopen`, before any await |
| **read continuously or they drop messages** | nothing in the message path does I/O; the flush is on a timer and the commit is async |
| **uncompressed connections are bandwidth-capped from September 2026** | `CompressionEnabled` is checked and logged, and a connection without it says so |

The collector does not commit. It writes a batch and hands it to
`otent tick --ais-batch`, because the governor, the payload archive and the
receipt ledger all live on that path — a second writer that skipped them
would put rows in the table that no receipt explains.

`aisstream` stays in `expected-unmeasured`, and that is now correct rather
than stale: **the tick does not hold the socket.** It sees this feed only
when the collector hands it a flush.

### Two processes, one table

The collector and `digitraffic` both write `otent_vessel`, from separate
processes, and `otent.lock` only orders writers inside one. So `commit!`
now records **which check answered**: a delta that matches exactly is
`:verification :delta`; a table that grew by *more* than this commit wrote
means another writer appended between the two counts, and the result is
`:presence-only` with a note. Growth by *less* is still a refusal — rows are
missing however you count them.

Refusing on the middle case would report two commits that both succeeded as
a fault. Silently accepting it would let a weaker check wear the strong
one's name.

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

## What the lists say, and why that is a table rather than an answer

`otent_vessel_risk` holds the OpenSanctions maritime export: 23,173
records from OFAC, the EU official journal and sanctions map, UK FCDO,
Swiss SECO, Canada, Ukraine's NSDC and war-sanctions register, UN 1718,
and the Paris/Tokyo/Abuja/Black Sea MOU detention registers.

**The list is recorded, not the matches.** Joining it against
`otent_vessel_static` and storing the vessels that matched was the obvious
move and is wrong for the reason the ship name was wrong: a matched row is
a fact about *two* payloads and would carry a `payload_sha256` that cannot
reproduce it. So each side lands from its own payload, and the
intersection is a **query** — which is the entire point of both being in
one catalog:

```
otent_vessel_risk    23,173 rows
otent_vessel_static   1,168 vessels in Finnish AIS coverage

  matching a maritime risk record :  92
  tagged shadow fleet             :  42
  under sanction                  :  60
```

Run against the catalog alone, no external file. A number somebody
computed once in a terminal is not a finding; a join anyone can re-run
against any day's data is.

### One list is not the answer

The first version of that join used the OFAC SDN list only and reported
**20**. The multi-jurisdiction table reports **60**. A third. The proof
case is the tanker `QENDIL`, struck in the Mediterranean in December 2025:
EU- and UK-designated, and **not on the OFAC SDN list at all** — so a
check against one authority would have called it clean.

### Three risk tags that are three different claims

`risk` is semicolon-separated and must not be flattened into `flagged`:

| tag | claim | who says it |
|---|---|---|
| `mare.shadow` | an assessment that the vessel is part of a shadow fleet | analysts |
| `sanction` | a designation | a named authority, with a legal instrument |
| `mare.detained` | the ship was held in port | a port state control inspection |

### A daily snapshot series, deliberately

Every poll commits the whole list again. **Delisting is invisible without
history**: a vessel removed from a designation simply stops appearing, and
a table holding only today's list cannot tell *never listed* from *listed
and released*. The cost is ~23,000 rows a day against a 90-day horizon; a
re-poll inside the same publication commits nothing, caught by the
byte-identical payload rule before the watermark is consulted.

### Identity is OpenSanctions', not the vessel's

`object_id` is the OpenSanctions entity id, because **754 of 23,191
records carry neither IMO nor MMSI** and keying on a vessel identifier
would drop exactly the entries whose identity is most obscured. IMO and
MMSI ride as attributes, and the IMO is stored as the bare seven digits an
AIS transponder broadcasts rather than the `IMO9427366` the CSV writes —
the join is the point, and it has to work without a string transform at
query time.

⚠ **Data: OpenSanctions, CC-BY-NC 4.0**, attribution on every row.
Non-commercial is a real condition rather than a formality: anything that
serves these rows onward inherits it. That is why this table is **not** in
`app-otent`'s `kinds` map — publishing it is a licensing decision, and an
ingest actor is not the place to make one.

## `otent sanctions`

    otent sanctions

Four tables, one join, inside the catalog:

```
otent sanctions  1,375 vessels in coverage
  checked        897   (broadcast an IMO number)
  unchecked      478   (no IMO -- NOT the same as not listed)
  on a list      101
    shadow fleet 45
    sanctioned   66
    with a named controlling organization 49

  ZODIAK        IMO 9513139  mare.shadow,poi,sanction
      -> Prominent Shipmanagement Ltd (hk) Property in the interest of;
         Glory Shipping HK Limited (hk)

fleets behind these vessels (hulls in this coverage):
  JOINT STOCK COMPANY SOVCOMFLOT                 4
  Prominent Shipmanagement Limited               2
  Sand Gemi Isletmeciligi AS                     2
```

**This was an ad-hoc script run by hand six times over two days**, and two of
those runs were wrong in ways nobody could see afterwards: one joined OFAC
alone (20 of 60 vessels), and one left the `IMO` prefix on and got zero rows,
which reads exactly like a clean fleet. Both mistakes are now structural
rather than remembered, and there is a test named after each.

Three things it refuses to do:

| | |
|---|---|
| conflate the two IMO registries | a vessel joins on its Ship Number; the organization's Company Number is never a key. On the live data `IMO9036387` is both a Chinese vessel and a North Korean firm |
| call an unchecked vessel clean | **478 of 1,375 broadcast no IMO** and cannot be looked up at all. They are `unchecked`, never folded into `not listed` |
| report on tables it could not read | any input missing is exit **2**, not a clean run with small numbers |

**Finding sanctioned vessels is exit 0.** That is the expected output of a
working instrument; an exit code that treated it as a fault would make the
normal state look like a failure, which is how an exit code stops being read.
Exit **1** is reserved for an inconsistency *between* the tables — an
ownership edge whose organization is missing, when both came from one payload.

`scripts/iceberg_read.py` had to exist first: the actor could count a table
and not read one, which is why every hand run went through a scratch script.
Its exit codes match the writer's — 2 could-not-ask, 3 asked-and-absent.

## Who is behind the hull

`otent_ownership_link` holds OpenSanctions' FollowTheMoney ownership edges
whose asset is a vessel — 1,496 of them — so the three planes join inside
the catalog:

```
where it is (otent_vessel) x what it says it is (otent_vessel_static)
                           x who controls it (otent_ownership_link)

  SALUT       -> Prominent Shipmanagement Limited   hk  Property in the interest of
  SOLARIS     -> JOINT STOCK COMPANY SOVCOMFLOT     ru  Property in the interest of
  AVANGARD    -> HS Atlantica Limited               lr  Property in the interest of
  AVANGARD    -> Hennesea Holdings Limited          ae  Property in the interest of
```

21 edges behind the vessels in Finnish AIS coverage. `AVANGARD` has two,
which is why this is **one row per edge**: a hull can be owned and
separately held in someone's interest, and folding to a `vessel -> owner`
column would drop the second and make the fleet-size question unanswerable.
SOVCOMFLOT is behind 4 hulls in the Gulf of Finland and **81 in the table**.

### The prefix that made the join silently empty

`imoNumber` is written `IMO9253325`; the bare digits are what an AIS
transponder broadcasts. Joining without stripping it returned **zero rows**,
which reads exactly like *no ship in these waters has a recorded owner* —
and was the first answer this join gave. With the prefix off, twenty of
twenty matched, every one with a named organization. The normalisation
happens once, in the parser, and a test fails on a prefixed value.

### The governor refused all 1,545 rows, and it was right

The first version wrote `owner_name`, and every row was held:
`:person-identifier`, because `owner` is on the governor's person-marker
list. That list was protecting something real — **49 of 1,545 edges name a
natural person** as the owner of a vessel.

The answer is not to rename the field until the rule stops noticing. It is
to drop those 49: this table carries organizations, and a hull held by a
named individual is personal data with no business here. The fields are
`org_*` because after the filter they cannot name a person — which is what
the rule protects, enforced more strictly than the field name was managing.
The dropped edges land in `:failed` under
`:ownership/natural-person-owner`, counted and named rather than vanishing.

### Two IMO registries, one property name, one live collision

An **IMO Ship Number** identifies a hull. An **IMO Company Number**
identifies a registered owner or ISM manager. Different registries, same
seven-digit format — and in FollowTheMoney the same property name,
`imoNumber`, carried on both `Vessel` and `Organization`.

Measured on the 2026-08-28 export: **one collision in 2,607 values.**

```
IMO9036387  Vessel        New Konk                       cn
IMO9036387  Organization  KOREA YUJONG SHIPPING CO LTD   kp
```

A join on the bare number links a North Korean shipping company to a
Chinese vessel it has nothing to do with. **One in 2,607 is the dangerous
frequency** — it will not show up in a hand-written test or a spot check,
and when it fires the error runs in the direction that matters.

So the two live in differently named columns (`asset_imo` on an ownership
row, `imo_company_no` on an organization row), `otent.observation/imo-namespaces`
gives the distinction a name instead of leaving it in whoever last read the
parser, and a test carries the real collision.

**The port state control join is not available.** The IMO Company Number is
the identifier this industry uses, and the obvious consumer is a PSC
inspection record — but Paris MOU serves its search as an application,
Tokyo MOU's database path 404s, EMSA THETIS has no open API, and Equasis
requires registration and forbids bulk extraction. Probed 2026-08-28, all
of them. The number is recorded and ready; the thing to join it to is not
openly published.

### The obvious enrichment, measured and refused

The next step after `who controls this hull` is `who controls them`, and the
obvious route is to look the company name up in GLEIF and take the LEI and
its parent. That route was measured before it was taken, on a sample of 40 of
the 555 controlling organizations:

| | |
|---|---|
| exact legal-name hit in GLEIF | **4 of 40** |
| of those, jurisdiction also agrees | **1** |

The other three are different companies wearing the same name. GLEIF places
`Odyssey Marine Inc.` in Nevada and `Patriot Inc.` in Delaware where the
sanctions record says Marshall Islands; `EVER SHINING LIMITED` is Hong Kong
against China. **Recording those as identity would assert that a Nevada
company owns a sanctioned tanker**, and the error direction on that one is
defamatory. So `otent_org_identity` records only identifiers the source
itself published and matches nothing by name.

What the population actually carries, measured across all 555:

| identifier | count | |
|---|---|---|
| `leiCode` | **2** | the shadow fleet does not hold LEIs, so the GLEIF hierarchy route reaches almost none of it |
| `imoNumber` (IMO Company Number) | **478** | the identifier this industry actually uses |
| `registrationNumber` | 316 | |
| none at all | **15** | recorded as `has_identifier=false`, because a blank reads like a gap in the ingest and this is a fact about the firm |

That inverts the plan. The route to a parent company is not GLEIF for this
population; it is the IMO Company Number, which joins to port state control
and registry sources rather than to a securities identifier.

### What is NOT in here

**Corporate hierarchy.** Measured 2026-08-27: not one of the four operators
behind the Finnish-coverage fleet has a parent, a director or a subsidiary
recorded in this graph. OFAC records who owns an asset, not who owns the
owner. GLEIF is the source for that and is partial too — it has
`SOVCOMFLOT` (LEI 89450003E1QO0BQ8WF75, CC0) and does not have the Hong
Kong manager.

### The OFAC ceiling came off, and the global number was the wrong one

Until 2026-08-28 both tables came from the US SDN export alone. They now
come from the multi-jurisdiction `sanctions` collection — OFAC, EU, UK,
Switzerland, Canada, Ukraine.

Globally that is worth **+31%** (1,545 → 2,026 vessel-ownership edges),
which is the number I would have quoted. For the fleet this actor actually
watches it is worth **3.2x**:

| | OFAC only | multi-jurisdiction |
|---|---|---|
| Finnish-AIS vessels appearing at all | 20 | **64** |
| …with a named controlling organization | 20 | **47** |
| ownership edges behind them | 21 | **57** |
| distinct controlling organizations (all) | 555 | **894** |

The two figures differ because EU and UK designations target the Baltic
shadow fleet specifically while OFAC's list is weighted elsewhere. **A
global average would have understated the gain by a factor of ten** — the
wrong measurement, honestly reported, which is its own failure mode.

What arrived with it is the shape you would expect: Turkish managers
(`Sand Gemi Isletmeciligi`, `Tokyo Gemi Isletmeciligi`), Vietnamese
(`Hung Phat`), Chinese (`WU HU SHIPMANAGEMENT`, `She Shan`), and a long
tail of jurisdiction-less shells — `Blossom Bridge Corp`,
`Seafaring Savants LLC`, `Voyage Craft Inc`, `Oasis Bloom Corp`.

Cost, measured: 353 MB, 291,570 entities, **37 s and 1.88 GB peak RSS** per
parse. `ftm-index` drops the 160,413 `Sanction` and 24,113 `Address` rows on
the raw line before `js->clj` ever sees them, which is what makes it fit.

⚠ The prefilter tests for the quoted schema **name**, not for
`"schema":"Vessel"`. The first version matched `"schema": "` with a space;
the export emits none, so it would have rejected every line in production
while passing against a fixture written by `json.dumps`. **An empty index
looks exactly like a sanctions list with nobody on it.** A test now runs
both spacings.

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

⚠ **And within a day the deadline was blaming the wrong end.** `firms`
timed out on three consecutive cycles while the same request answered in
**2.3 s from `curl` on the same machine, at the same minute**.
`AbortSignal.timeout` is a wall-clock timer, `tick` runs the feeds
concurrently, and the commit is `cp/spawnSync` — which blocks the whole
event loop. The deadline kept counting while this process was frozen
inside a sibling's `python3`, and fired against a healthy server. The
receipt said *the feed did not answer*, which is a claim about NASA that
belonged to us. It now says the clock is ours, names the mechanism, and
names the check that settles it. Telling the two apart for real means not
blocking the loop.

**A dark feed no longer stops retention.** The refusal used to `exit 2`
before the retain block. Once a feed could stay dark for hours instead of
one cycle that stopped being theoretical: retention did not run for 51
minutes because a *different* feed could not be read. Whether a feed is
readable and whether committed rows are past their horizon are unrelated
questions, and letting the first answer the second is how one fault
becomes two.

### Fixed 2026-08-28: the commit no longer blocks the loop

`run-writer!` uses `cp/spawn` and resolves. One change, and all three of
the symptoms above stop being symptoms:

| before | after |
|---|---|
| `firms` reported `did not answer within 60s` while curl got 2.3 s | the deadline measures the remote again |
| `opensky` reported 175 s on a poll that gave up at 60 s | per-feed timings are the feed's own work |
| cycles outran the 300 s timer and launchd skipped fires | commits overlap fetches |

Measured on the verifying run: `usgs` finished **NOTHING-NEW in 1 s while a
sibling was 26 s into a commit**. Under `spawnSync` its clock ran through
every sibling's python. Three feeds, 82 s of per-feed work, **49.6 s of wall
clock** — the difference is the overlap.

⚠ **`spawnSync` was also serialising every commit, by accident.** An
accident holding an invariant still has to be replaced by something that
holds it deliberately: `otent.lock` gives each TABLE its own promise chain,
so two feeds sharing a kind (`digitraffic` and `aisstream` both write
`otent_vessel`) cannot each read a `before` count that already contains the
other's rows. Different tables still overlap, which is the point. A rejected
write does not wedge the chain — without that, one refusal turns a table
dead and looks from outside like the feed going quiet.

⚠ **And the refactor shipped a bug the suite could not see.**
`(.then p :rows)` reads correctly and is wrong: a ClojureScript keyword is a
function to Clojure and an opaque object to `Promise.prototype.then`, so it
handed back the whole status map, the delta computed `NaN`, and two commits
that had **actually landed** were reported `count-mismatch`. Nothing in the
suite exercises `commit!` against a live catalog, so it took a real tick to
find. The failure was in the safe direction; that it was safe is luck, not
design.

`otent.deadline` puts a deadline on every network call — 60 s for reads,
180 s for writes, on the feed fetch, the R2 archive and the kotobase
publish (through `make-client`'s `:fetch-fn`, so the tolerance stays this
actor's choice rather than the library's). A call that runs out is
`UNMEASURED` with **`[timeout]`, not `[unreachable]`**: one says the feed
answered nothing, the other says we stopped waiting and do not know what it
would have said. Watched firing by setting the deadline to 1 ms against the
live USGS feed.

### A blip is not an outage, and the exit code has to know

The deadline fired in production within the hour: a burst of local load
made the FIRMS and OpenSky fetches exceed 60 s, both came back
`[timeout]`, and the cycle **REFUSED**. Strictly true — and the beginning
of the failure `expected-unmeasured` exists to prevent. Supplying the FIRMS
key had correctly removed its exemption, so any darkness was now news; a
job that goes red for a transient reason teaches its reader that red means
nothing, and then the real outage arrives looking identical to the last
four false ones.

`otent.darkness` counts **consecutive** unreadable cycles per feed and
refuses at three — fifteen minutes at a five-minute timer. A single timeout
is still in the receipt, because it is a real gap in coverage, but it does
not make the run a failure. The rising count is printed while it is still
recoverable (`watching: firms 1/3, opensky 1/3`), because a threshold whose
approach is invisible always arrives as a surprise.

Three things it gets right that the obvious version does not:

| | |
|---|---|
| a feed that recovers resets to zero | otherwise every feed accumulates its way to the threshold and the refusal eventually fires for one that has been healthy for a week |
| a feed that was **not asked** neither grows nor resets | `not-due` and `unmeasured` are different, and resetting on a skip would let a broken feed launder its record by being inside its interval |
| a declared exemption never refuses, however long it lasts | `aisstream` is at 500 and counting; its darkness is the documented state, not news, and it must not crowd out the feeds whose silence is |

This is the same blip-versus-pattern distinction `otent coverage` draws,
moved to where the exit code is decided. It had to be learned twice.

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
src/otent/darkness.cljc      consecutive dark cycles, not the first one
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

## Street imagery sources (bounded, one subject per run)

`src/otent/mapillary_mapfeature_detections.cljc` + `bin/mapillary_mapfeature_detections.cljs`:
ONE `/:map_feature_id/detections` request through the registered client
`com-mapillary-graph-api` (`detections-request` with `kind :map-feature`),
metadata only — no pixel field is ever requested. `paging.next` is counted,
never followed. Detection taxonomy values naming a person, face or licence
plate are refused before any record exists (counted, not stored). Token:
`MAPILLARY_ACCESS_TOKEN` from the environment only, in the `Authorization`
header; without it live mode exits 2 — a 401 must never be misread as an
empty subject. A map feature is a provider-published observation, not a
current fact about the world.

## One Mapillary map-features bbox source — one tile, metadata only

`src/otent/mapillary_mapfeatures_bbox.cljc` + `bin/mapillary_mapfeatures_bbox.cljs`
covers the last client endpoint no otent run had touched: the registered
client `com-mapillary-graph-api`'s `map-features-request`
(`GET /map_features`).

- **one tile, one request**: one `[minx,miny,maxx,maxy]` vector,
  validated numerically and then against the client's own
  `bbox-within-limit?` (strictly under 0.01 degree) — a larger tile is
  refused, **not split**: this source is one tile and `paging.next` is
  counted, printed, never followed (:run-bounds). No neighbouring
  tile, no second request.
- **metadata only**: the field list (`id,object_value,geometry,
  first_seen_at,last_seen_at`) carries no thumbnail field, so no pixel
  was requested and none had to be defended.
- **a map feature is a triangulated object, not a current fact**:
  `first_seen_at`/`last_seen_at` are carried verbatim as provider
  claims; nothing here asserts the object stands there now.
- **privacy boundary before normalization**: any `object_value` naming
  a person, face or licence plate is refused before any record exists
  (counted, never stored); the redaction check also refuses any record
  carrying an `@` or an exif/email key; only a plausible GeoJSON Point
  lon/lat is admissible — anything else is refused, not repaired.
- **provenance**: provider map-feature id, `object_value` verbatim,
  Point lon/lat, first/last-seen (ms since epoch), tile bbox string,
  retrieval time, permalink-style evidence URL, licence named where it
  actually lives (the payload carries no per-feature licence field),
  attribution, `requests-made 1`, `:unknown` spatial uncertainty.
- **token**: `MAPILLARY_ACCESS_TOKEN` from the environment only, in
  the client's `Authorization` header; without it live mode exits 2 —
  a 401 must never be misread as an empty subject.

**Measured this run (offline, synthetic fixture)**: 4 raw features →
2 recorded, 1 privacy-refused (`object--human--person`), 1
geometry-refused (LineString), counted not stored; `paging.next`
present, not followed. Refusal paths verified live: malformed bbox →
exit 1, over-limit tile → exit 1, no credential → exit 2
`no-credential` — **no token exists** in this environment (env,
keychain — re-verified), so nothing was fetched from Mapillary and
none is invented. `npm test` — 230 tests, 1,841 assertions, 0 failures.

## One Mapillary per-image detections source — metadata only

`bin/mapillary_image_detections.cljs` covers the client endpoint no
other otent run had touched: the registered client
`com-mapillary-graph-api`'s `detections-request` with `kind :image`
(`GET /:image_id/detections`). The bbox metadata source, the pixel
sample and the map_features analysis pass exist; this is the
per-image detection pass over them.

- **one image, one request**: a single provider image id (validated
  numeric, refused before any request is built), one HTTP GET.
  `paging.next` is counted and printed, **never followed**
  (:run-bounds). No bbox, no siblings.
- **metadata only**: the field list (`id,value,geometry,created_at`)
  carries no thumbnail field, so no pixel was requested and none had
  to be defended. The pixel exception belongs to the pixel sample,
  not here.
- **privacy boundary before normalization**: detection `value` is the
  provider's taxonomy string; any value naming a person, face or
  licence plate is **refused before any record exists** — counted,
  never stored, never an entity. The redaction check also refuses any
  record carrying an `@` or an exif/email key.
- **provenance**: provider image id + detection id, taxonomy value
  verbatim, Point geometry (refused, not repaired), `created_at` (ms
  since epoch), retrieval time, image permalink, licence named where
  it actually lives (the payload carries no per-detection licence
  field), attribution, `requests-made 1`, `:unknown` spatial
  uncertainty.

**Measured this run (offline, synthetic fixture)**: 3 raw detections
→ 2 recorded, 1 privacy-refused (`object--human--person`), counted
not stored; `paging.next` present, not followed. `npm test` — 129
tests, 1,533 assertions, 0 failures. Live mode without
`MAPILLARY_ACCESS_TOKEN` hard-fails exit 2 `no-credential` — **no
token exists** in this environment (env, keychain — re-verified), so
nothing was fetched from Mapillary and none is invented.

## One open street source, anonymously: Panoramax

`bin/panoramax.cljs` ingests street-imagery **metadata** (no pixel is
fetched or stored) from Panoramax — the IGN / OSM-FR street-imagery
federation publishing CC-BY-SA-4.0 pictures over a STAC API. The
anonymous aggregate endpoint `api.panoramax.xyz/api/search` answers 200
with no credential; per-item licence links are carried through, so what
an item is licensed as is what the observation records.

The bound is one bbox per invocation (`--bbox W S E N`), ≤ 0.01° a side,
**filtered back down to the bbox** — the API's aggregate answers may
reach further than the declared area, so everything returned outside it
stays visible in the counts. `--fixture` replays a captured payload
offline with identical checks.

Gates before any item becomes an observation:

- **privacy, curated fields**: the raw EXIF block contains
  uploader-identifying metadata (a `MAPSettingsEmail`,
  upload hashes) — EXIF is never copied into an observation, only a
  curated allow-list passes, and a redaction check refuses the whole run
  if an `@` or a forbidden key ever reaches an emitted observation.
- **processing and visibility**: unprocessed items (`geovisio:status` ≠
  `ready`) and non-public items (`geovisio:visibility` ≠ `anyone`) are
  refused and counted. The provider's automatic face/plate blurring is
  platform-level and the item API publishes no per-item blur flag, so
  the observation carries `provider-blur-verified false` with the
  limitation stated rather than a claim that was never checked.
- **geometry**: GeoJSON lon/lat only; a point that is only plausible if
  swapped is refused, not repaired.
- **uncertainty**: the provider's `quality:horizontal_accuracy` (metres,
  95% interval) is carried as spatial uncertainty; where the item omits
  it, `:unknown` stays visible.

Capture time is the item's `properties.datetime`, never confused with
ingest time; every refusal is counted by name; the response bytes are
hashed (`input sha256=…`) before anything else runs, so provenance
survives even a refusal. A picture is an observation at capture time,
not current existence.

## One derived task over the Panoramax observations: spatial density (per-cell grid)

`bin/panorama_density.cljs` runs **one** derived task —
`panoramax-street-density-v1` — over the Panoramax observations the
upstream pass normalized: the one ≤ 0.01° area is binned into a fixed
deterministic grid (target cell 0.0025°, so at most 4×4 cells derived
from the declared bbox, never from the data) and admissible pictures
are counted per cell. No model, no inference: `model-id` is `:none`,
stated rather than hidden.

What it keeps honest:

- **The grid is a pure function of the declared bbox.** The same bbox
  always produces the same cell edges, whatever the observations are;
  a picture outside the declared bbox would be counted `unplaceable`,
  never folded into a neighbouring cell.
- **Unknowns stay visible.** A picture whose published `view:azimuth`
  is not a number is counted `heading-unknown` in its cell; a picture
  with no collection id is simply absent from `sequence-known` —
  counted, never dropped. Cells with zero admissible pictures stay as
  explicit zeros.
- **A lower bound, said out loud.** The provider pages results; the
  table records `coverage-bound: lower-bound` with the presence of a
  `next` link in the note — an empty cell says nothing about the
  provider's actual coverage there.
- **The privacy gate is upstream and stated.** Only `status=ready`,
  public, licence-carrying items with uploader EXIF redacted (admitted
  by `otent.panoramax`) reach the table; the derived provenance
  re-asserts that no face, plate, person or vehicle entity exists in
  this task, and that no pixel was fetched or stored.
- **The stored document self-checks.** `provenance-checks` verifies
  that placed + unplaceable equals the observation count and that the
  per-cell counts sum to placed — a tampered count refuses, not passes.

Verified live (one area, central Tokyo, 2026-09-03): `fetched=100
accepted=100 refused=0 outside-bbox=0 next-link=false`, grid 2×2,
`pictures-placed=100 unplaceable=0`. R2 write stopped at the
no-credential gate. `--fixture` replays a labeled SYNTHETIC payload
offline with identical checks.

`bin/panorama_coverage.cljs` runs **one** derived task —
`panoramax-street-vintage-v1` — over the same normalized Panoramax
observations: a temporal-coverage (vintage) table for the one
≤ 0.01° area, counting admissible pictures and the span of their
published capture times. No model, no inference: `model-id` is
`:none`, stated rather than hidden.

What it keeps honest:

- **The span endpoints are the provider's bytes.** Published
  `datetime` strings are validated against the provider's ISO-8601
  UTC form and compared lexicographically (which sorts
  chronologically); no timezone conversion, no date math, so
  `earliest-published` / `latest-published` are byte-identical to
  what the provider published.
- **Unknowns stay visible.** A picture whose published datetime does
  not match the validated form is counted `capture-unknown`, never
  dropped, never folded into the span; if nothing parses, the span is
  an explicit `:unknown`, not an empty result that looks like a pass.
- **A lower bound, said out loud.** The table records
  `coverage-bound: lower-bound` with the presence of a `next` link in
  the note — a span proves nothing outside the fetched area or page.
- **The privacy gate is upstream and stated.** Only `status=ready`,
  public, licence-carrying items with uploader EXIF redacted (admitted
  by `otent.panoramax`) reach the table; the derived provenance
  re-asserts that no pixel was fetched or stored and that no face,
  plate, person or vehicle entity exists in this task.
- **The stored document self-checks.** `provenance-checks` verifies
  that `capture-known + capture-unknown` equals the observation count
  and that the span endpoints are members of the capture-known set —
  a tampered count or span refuses, not passes.
- **The epistemic boundary is written in the table.** A
  temporal-coverage observation is not road condition, accessibility,
  ownership, inventory, availability, legal compliance, or current
  existence.

Verified live (same area, central Tokyo, 2026-09-03): `fetched=100
accepted=100 refused=0 outside-bbox=0 next-link=false`,
`capture-known=100 capture-unknown=0`, span `2016-10-13T13:13:07.592061+00:00`
→ `2026-06-09T02:19:58+00:00`. R2 write stopped at the no-credential
gate. `--fixture` replays a labeled SYNTHETIC payload offline with
identical checks (span `2017-09-09T08:28:31.000000+00:00` →
`2021-04-02T12:00:00Z`, `capture-unknown=1`).

## One Mapillary image pixel sample — the exception, earned

`bin/mapillary_image.cljs` is the **pixel** counterpart of the Mapillary
metadata source (`bin/mapillary_images.cljs`, which deliberately never
requested `thumb_1024_url` — a field never read was a field never had
to defend). This one earns the exception rather than assuming it:

- **built through the registered client `com-mapillary-graph-api`**:
  the `/images` request is built by the client (bbox strictly under
  0.01° a side, token in the `Authorization` header, never the URL).
- **one image, one pixel request**: the first admissible image's own
  `thumb_1024_url`, fetched once. Siblings and `paging.next` are
  counted and printed, **never followed** (:run-bounds).
- **permission basis stated on the record**: Mapillary contributor
  imagery is published CC-BY-SA under Mapillary's Terms of Service;
  the payload carries no per-image licence field, so the licence is
  named where it actually lives and the record carries the basis
  rather than an invented flag.
- **privacy**: Mapillary publishes no per-image blur-result flag, so
  `provider-blur-verified` is `false` with the limitation carried on
  the record — the same story the Panoramax pixel source states.
  Faces and plates stay outside the observation space. A redaction
  check refuses any record carrying an `@` or an exif/email key.
- **provenance**: provider image id, viewer permalink, `captured_at`
  (ms since epoch), geometry (refused, not repaired), heading,
  panorama flag, sequence id as published, licence, attribution,
  retrieval time, sha256 + byte-size of the exact bytes, `:unknown`
  spatial uncertainty.
- **gates**: id, geometry, numeric `captured_at`, published
  `thumb_1024_url` — an image without a published pixel URL keeps its
  metadata observability and is refused the byte fetch.

Storage is gated: bytes go to R2 only behind `$CF_CATALOG_TOKEN`;
without it the run reports `nothing written` and exits 2.

**Measured this run (offline, synthetic bytes)**: the full
gate → record → check path runs deterministically on the fixture;
live mode hard-fails exit 2 with `:mapillary-image/no-credential` —
**no `MAPILLARY_ACCESS_TOKEN` exists** in this environment (env,
keychain — re-verified), so no pixel has been fetched from Mapillary
and none is invented. A 401 must never be misread as an empty tile.

## One derived capture-daylight task over the Panoramax observations

`bin/panoramax_daylight.cljs` runs **one** derived task —
`panoramax-capture-daylight-v1` — over the observations normalized by
`otent.panoramax`: a deterministic, locally-reproducible solar-elevation
classifier (`otent.solar-elevation`, NOAA low-precision algorithm) that
labels each observation's published UTC capture time and own lon/lat as
`:daylight` (> 0°), `:civil-twilight` (−6°…0°) or `:night` (< −6°),
thresholds pinned as model parameters.

- the **model artifact is pinned**: the bin script hashes the
  classifier source file at run time (`sha256`) and records it in the
  derived provenance — every table names the exact arithmetic that
  produced it. When the hash cannot be read it is stated `:unknown`,
  never invented.
- **no pixels are touched**: classification is astronomy over published
  metadata, not scene analysis — overcast noon and clear noon are both
  `:daylight`, and the table says so in its epistemic boundary.
- **uncertainty is declared, not implied**: the approximate model
  carries a stated ±2° elevation uncertainty (classes near a threshold
  are provisional), and each observation's provider positional
  accuracy rides alongside.
- **unknowns stay visible**: a non-conforming published datetime is
  counted `capture-unknown`, a malformed footprint `geometry-unknown` —
  counted, never dropped, never classified.
- the table is a **lower bound** over the fetched page (`links-next`
  restated in the coverage-bound note).

```
nbb --classpath src bin/panoramax_daylight.cljs --bbox 139.765 35.675 139.77 35.68
nbb --classpath src bin/panoramax_daylight.cljs --fixture payload.json --bbox W S E N
```

As with the other derived tasks, `$CF_CATALOG_TOKEN` absence stops at
the write gate (exit 2): nothing is written, and that refusal is
reported rather than faked.

## Tests

`npm test` — 143 tests, 1,611 assertions, against **captured real payloads**
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

## The Panoramax collections coverage manifest

`otent.panoramax-coverage` turns one request to the federation's
`GET /api/collections` into a **coverage manifest**: which open
street-imagery collections exist, where their providers *declare* they span,
which capture window they state, under which licence, by whom. Metadata only
— no image is requested, no pixel URL is requested, no pixel is stored, and
the run bound is real: a `rel=next` link is counted in the provenance
(`paging-next`), never followed.

The extent is the collection's declared bbox, kept as published; a bbox that
does not hold together is refused, not repaired. A declared extent is not a
verified observation (`coverage-declared-not-verified`), and a collection
existing is not a road being covered or current. Producer attribution
(names + roles) is kept because licence terms require it; a redaction check
refuses the run if an `@` or an exif/email-shaped key reaches an observation
anyway. No face or plate can appear here: no image was fetched.

    npx nbb --classpath src:test bin/panoramax_coverage.cljs --fixture test/otent/fixtures/panoramax-collections-live.json
    npx nbb --classpath src:test bin/panoramax_coverage.cljs --live --limit 20

Exit 0 manifest produced · 1 refused · 2 could-not-act (no write
credential, or bad payload). The R2 write stays behind
`$CF_CATALOG_TOKEN`; without it the run reports `nothing written` and
exits 2 rather than pretending.

## One MODIS Aqua land surface temperature (day) sample

`otent.imagery/modis-aqua-lst-day-sample` is the nineteenth bounded
sample: **one** `MODIS_Aqua_Land_Surface_Temp_Day` EPSG:4326 level-0
tile (1km tile matrix) for ONE declared capture date, 2026-09-04. The
Aqua sibling of the Terra LST (day) slice -- the same MOD11-family
daily thermal product from Aqua's afternoon overpass, where the
afternoon surface-temperature maximum is visible; a different
observation, not a re-render. The 1km matrix is 2x1 tiles at level 0,
so the single level-0 tile is the north-west half of the globe and the
record states that footprint exactly rather than claiming the planet.
A dated daily acquisition; the declared capture date selects the
layer's time dimension, stated verbatim, never guessed from the wall
clock. NASA GIBS, public domain, one tile, level 0. Provenance,
licence-allowlist and object-readback tests assert the record: fixture
bytes hash to the record's `payload-sha256`. `npm test` -- 245 tests,
1,889 assertions, 0 failures.

## One KartaView image pixel sample (2026-09-02)

KartaView metadata (PR #12) and a derived density task (PR #34) were ingested
with no pixel ever fetched. This run adds the KartaView counterpart of the
Panoramax pixel sample (`otent.kartaview-image`): one anonymous search in a
bbox of at most 0.01 degrees per side, the first in-bbox photo that passes
every gate — public, active, provider-processed `BLURRED`, plausible lon/lat
geometry, capture time, published processed-image URL — then ONE pixel GET of
that photo's own URL. Bytes are hashed and stored only behind
`$CF_CATALOG_TOKEN`; without the credential the run reports nothing written
and exits 2. An unblurred, unknown-flag, withdrawn or non-public photo keeps
its metadata eligibility but is refused the fetch; a record carrying an `@`
or an exif-shaped key refuses the whole run.
