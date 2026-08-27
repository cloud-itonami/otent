#!/usr/bin/env python3
"""iceberg_retain.py -- delete observations older than a horizon, and expire
the snapshots that held them.

`bin/otent.cljs` owns the orchestration; this owns the Iceberg delete and
nothing else, the same split `iceberg_append.py` follows.

## Why this can exist now and could not before

Retention needs somewhere else for the observation to live. Until
2026-08-26 nothing stored the fetched payloads -- only their sha256 rode
along on the rows -- so the table was the only copy and deleting a row
destroyed the only record of it. `archive-payload!` now writes every
payload to `otent/payload/<sha256>.json.gz` before the rows are committed,
so a deleted row is recoverable from the bytes it came from.

**This script refuses to run if it is asked to delete rows whose payloads
are not in the bucket.** That check is the whole safety argument; without
it this is just deletion with a comment about rebuildability attached.

## The horizon is not the read window

The API folds each kind to one row per object inside a window measured in
minutes for aircraft. The horizon here is far longer, because the two
answer different questions: the window is *what should be drawn now*, the
horizon is *how much history is worth keeping online*. Anything older is
still in the bucket, addressed by hash.

## observed_at is text, and that is load-bearing

Every column in these tables is `large_string` (see `otent.observation`),
so the delete filter compares strings. Millisecond epochs are 13 digits
from 2001-09-09 until 2286-11-20, so lexicographic order equals numeric
order across every value these tables can hold -- and the script CHECKS
that the horizon it computed is 13 digits rather than assuming it. A
14-digit horizon in 2286 would silently compare as smaller than every row
and delete nothing, which is the failure that looks like success.

exit: 0 deleted (or nothing to delete) / 1 refused / 2 could not answer
"""

import argparse
import os
import sys

ACCOUNT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET = "cloud-itonami-datalake"

# How much history stays online, per kind. Far longer than the read window
# in app-otent.objects, deliberately: that one answers "what is current",
# this one answers "how much is worth keeping queryable".
HORIZON_MS = {
    "otent_aircraft": 24 * 3600 * 1000,        # 1 day
    "otent_vessel": 7 * 24 * 3600 * 1000,      # 1 week
    # Identity, not position. A ship keeps its name for years and its
    # destination for a voyage, so a week would throw away the answer to
    # "what was this called when we saw it" while keeping the sighting.
    "otent_vessel_static": 365 * 24 * 3600 * 1000,   # 1 year
    # A daily snapshot of what the lists said. Ninety days because
    # DELISTING is only visible in history -- a table holding the current
    # list alone cannot tell "never listed" from "listed and released" --
    # and because the whole list is ~23,000 rows every day.
    "otent_vessel_risk": 90 * 24 * 3600 * 1000,      # 3 months
    "otent_fire": 90 * 24 * 3600 * 1000,       # 3 months
    "otent_quake": 365 * 24 * 3600 * 1000,     # 1 year -- events, not fixes
    "otent_satellite": 90 * 24 * 3600 * 1000,  # 3 months of element sets
}


def catalog():
    tok = os.environ.get("CF_CATALOG_TOKEN", "").strip()
    if not tok:
        print("CF_CATALOG_TOKEN is empty -- this run cannot answer whether "
              "anything was deleted, which is not the same as nothing having "
              "been deleted", file=sys.stderr)
        sys.exit(2)
    from pyiceberg.catalog.rest import RestCatalog

    return RestCatalog(
        name="r2",
        uri=f"https://catalog.cloudflarestorage.com/{ACCOUNT}/{BUCKET}",
        warehouse=f"{ACCOUNT}_{BUCKET}",
        token=tok,
    )


def payload_present(sha: str, token: str) -> bool:
    """One ranged GET, not HEAD: the Cloudflare REST object API answers HEAD
    with a non-2xx even for objects that are present."""
    import urllib.request
    import urllib.error

    url = (f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}"
           f"/r2/buckets/{BUCKET}/objects/otent/payload/{sha}.json.gz")
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}", "Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status in (200, 206)
    except urllib.error.HTTPError:
        return False
    except Exception as e:                       # noqa: BLE001
        print(f"could not probe {sha[:12]}...: {e}", file=sys.stderr)
        raise


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--table", required=True)
    ap.add_argument("--namespace", default="cloud_itonami")
    ap.add_argument("--now-ms", type=int, required=True,
                    help="the clock is passed in, so a run is reproducible "
                         "and a test can pin it")
    ap.add_argument("--pre-archive-ms", type=int, default=None,
                    help="rows observed strictly before this instant may be "
                         "deleted without a payload in the bucket, because "
                         "they were written before the archive existed. No "
                         "default: the waiver has to be stated by whoever "
                         "runs this, not inherited.")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    horizon = HORIZON_MS.get(args.table)
    if horizon is None:
        print(f"no horizon declared for {args.table}. Refusing: a table with "
              f"no declared horizon must not inherit another table's.",
              file=sys.stderr)
        return 1

    cutoff = args.now_ms - horizon
    cutoff_s = str(cutoff)
    if len(cutoff_s) != 13:
        print(f"the cutoff {cutoff_s} is {len(cutoff_s)} digits, not 13. "
              f"observed_at is stored as text, so string comparison only "
              f"equals numeric comparison while every value has the same "
              f"digit count. Refusing rather than deleting by an ordering "
              f"that no longer holds.", file=sys.stderr)
        return 1

    cat = catalog()
    try:
        tbl = cat.load_table(f"{args.namespace}.{args.table}")
    except Exception as e:                       # noqa: BLE001
        print(f"could not load {args.namespace}.{args.table}: {e}",
              file=sys.stderr)
        return 2

    from pyiceberg.expressions import And as AND
    from pyiceberg.expressions import GreaterThanOrEqual, LessThan

    expr = LessThan("observed_at", cutoff_s)
    doomed = tbl.scan(row_filter=expr,
                      selected_fields=("payload_sha256",)).to_arrow()
    n = doomed.num_rows
    if n == 0:
        print(f"{args.table} nothing-older-than {cutoff_s} 0")
        return 0

    # The safety argument, checked rather than asserted: every distinct
    # payload behind the rows about to go must be in the bucket.
    # Rows written before archive-payload! landed have no payload anywhere,
    # and no amount of checking will find one. Without a way to say so, this
    # script refuses forever on a fixed, bounded set of old rows and the
    # table can never shrink below them -- a control that is honest and
    # useless. The waiver is explicit, has no default, and its extent is
    # printed, so it cannot quietly widen to cover a write that failed
    # yesterday.
    waived = 0
    shas = sorted(set(doomed.column("payload_sha256").to_pylist()))
    token = os.environ.get("CF_CATALOG_TOKEN", "").strip()
    missing = [s for s in shas if s and not payload_present(s, token)]
    if missing and args.pre_archive_ms is not None:
        import pyarrow.compute as pc
        after = tbl.scan(
            row_filter=AND(expr, GreaterThanOrEqual(
                "observed_at", str(args.pre_archive_ms))),
            selected_fields=("payload_sha256",)).to_arrow()
        after_shas = sorted(set(after.column("payload_sha256").to_pylist()))
        still_missing = [s for s in after_shas if s and s in set(missing)]
        waived = n - after.num_rows
        if still_missing:
            print(f"REFUSING: {len(still_missing)} payload(s) are missing for "
                  f"rows observed AFTER {args.pre_archive_ms}, which the "
                  f"pre-archive waiver does not cover (first: "
                  f"{still_missing[0][:12]}...). Those rows were written when "
                  f"the archive already existed, so a missing payload there is "
                  f"a failure, not history.", file=sys.stderr)
            return 1
        missing = []
        print(f"waived {waived} row(s) observed before {args.pre_archive_ms}: "
              f"written before the payload archive existed, so no payload can "
              f"be found for them")
    if missing:
        print(f"REFUSING: {len(missing)} of {len(shas)} payload(s) behind the "
              f"{n} rows older than {cutoff_s} are not in the bucket "
              f"(first: {missing[0][:12]}...). Deleting them would destroy "
              f"the only record of those observations.", file=sys.stderr)
        return 1

    if args.dry_run:
        print(f"{args.table} would-delete {n} rows older than {cutoff_s}; "
              f"{len(shas) - 0} payload(s) behind them, "
              f"{waived} row(s) waived as pre-archive, "
              f"{n - waived} checked present")
        return 0

    tbl.delete(delete_filter=expr)
    after = tbl.scan(selected_fields=("observed_at",)).to_arrow().num_rows
    print(f"{args.table} deleted {n} rows older than {cutoff_s}; "
          f"{waived} waived as pre-archive, {n - waived} checked present; "
          f"{after} rows remain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
