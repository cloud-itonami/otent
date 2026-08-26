#!/usr/bin/env python3
"""Copy every R2 object under one prefix to another, then verify.

Written for the tenkyu -> otent rename. Cloudflare's REST object API has no
server-side copy, so this is GET + PUT per object -- but it does NOT refetch
from NASA or OpenFreeMap. Re-running the ingests would have been simpler and
would have put ~1,500 needless requests on two public services.

Deletes nothing. The old prefix is removed by a separate, deliberate call
once the new one is verified: a copy that also deletes has no state in which
you can compare the two.
"""
import os, sys, json, urllib.request, urllib.error

ACCOUNT = "4da88288dc30d9ee257f319d3c33ecf0"
BUCKET = "cloud-itonami-datalake"
BASE = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/r2/buckets/{BUCKET}"
TOK = os.environ["CF_CATALOG_TOKEN"].strip()


def req(method, url, data=None, ctype=None):
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", f"Bearer {TOK}")
    if ctype:
        r.add_header("Content-Type", ctype)
    return urllib.request.urlopen(r, timeout=60)


def list_keys(prefix):
    keys, cursor = [], None
    while True:
        u = f"{BASE}/objects?prefix={prefix}&per_page=1000"
        if cursor:
            u += f"&cursor={cursor}"
        d = json.loads(req("GET", u).read())
        keys += [o["key"] for o in d["result"]]
        cursor = (d.get("result_info") or {}).get("cursor")
        if not cursor:
            return keys


def ctype_for(k):
    if k.endswith(".jpg"):
        return "image/jpeg"
    if k.endswith(".json"):
        return "application/json"
    return "application/octet-stream"


def main():
    old, new = sys.argv[1], sys.argv[2]
    keys = list_keys(old)
    print(f"{len(keys)} objects under {old}", flush=True)
    if not keys:
        print("nothing to copy -- refusing to report a successful rename", file=sys.stderr)
        return 2
    done = failed = 0
    for i, k in enumerate(keys):
        nk = new + k[len(old):]
        try:
            body = req("GET", f"{BASE}/objects/{k}").read()
            req("PUT", f"{BASE}/objects/{nk}", body, ctype_for(k)).read()
            done += 1
        except Exception as e:
            failed += 1
            print(f"  FAILED {k}: {e}", file=sys.stderr)
        if (i + 1) % 200 == 0:
            print(f"  {i+1}/{len(keys)}", flush=True)
    after = list_keys(new)
    print(f"copied {done}, failed {failed}; {len(after)} objects now under {new}")
    # The count is checked, not assumed: a copy loop that silently skipped
    # would otherwise report the same success as one that copied everything.
    ok = failed == 0 and len(after) == len(keys)
    print("VERIFIED" if ok else "MISMATCH")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
