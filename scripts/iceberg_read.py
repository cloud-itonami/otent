#!/usr/bin/env python3
"""iceberg_read.py -- dump one table's rows as NDJSON on stdout.

`bin/otent.cljs` owns every decision; this owns the Iceberg read and nothing
else, the same split `iceberg_append.py` and `iceberg_retain.py` follow.

## Why a reader had to exist before `otent sanctions` could

The join behind that command was run by hand six times over two days, each
time through an ad-hoc python script in a scratch directory. There was no
way to read a table from the actor itself, so every answer was a number in
a terminal that nobody could reproduce -- and two of those hand runs were
wrong in ways nobody could see afterwards.

## Exit codes match the writer's, deliberately

  0  rows on stdout
  2  could not ask -- no token, catalog unreachable
  3  asked, and the table is not there

2 and 3 are separate for the same reason they are in `iceberg_append.py`:
`could not look` and `looked, and it is not there` are different claims and
must not share a code. A caller that cannot tell them apart will report an
outage as an empty fleet.
"""
import argparse, json, os, sys

from pyiceberg.exceptions import NoSuchTableError

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from iceberg_append import catalog


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--account", required=True)
    p.add_argument("--bucket", required=True)
    p.add_argument("--namespace", required=True)
    p.add_argument("--table", required=True)
    p.add_argument("--columns", default="",
                   help="comma-separated; empty means every column")
    a = p.parse_args()

    tok = os.environ.get("CF_CATALOG_TOKEN", "").strip()
    if not tok:
        print("CF_CATALOG_TOKEN is empty -- this run cannot read the table, "
              "which is not the same as the table being empty", file=sys.stderr)
        return 2

    try:
        t = catalog(a.account, a.bucket).load_table((a.namespace, a.table))
    except NoSuchTableError:
        print(f"no such table: {a.namespace}.{a.table}", file=sys.stderr)
        return 3

    cols = [c for c in a.columns.split(",") if c]
    scan = t.scan(selected_fields=tuple(cols)) if cols else t.scan()
    out = sys.stdout
    n = 0
    for batch in scan.to_arrow().to_batches():
        for row in batch.to_pylist():
            out.write(json.dumps(row, default=str, ensure_ascii=False))
            out.write("\n")
            n += 1
    print(f"read {n} rows from {a.namespace}.{a.table}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
