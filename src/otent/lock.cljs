(ns otent.lock
  "One promise chain per key, so work on the same key stays in order.

  ## Why this exists at all

  `cp/spawnSync` used to serialise every Iceberg commit in this process, as a
  side effect of blocking the Node event loop. That side effect cost three
  separate symptoms -- false timeouts on healthy feeds, per-feed timings that
  could not attribute cost, and a cycle that outran its own timer -- so the
  writer became async.

  **An accident that was holding an invariant still has to be replaced by
  something that holds it on purpose.** Two feeds can share a kind and
  therefore a table: `digitraffic` and `aisstream` both write
  `otent_vessel`. Concurrent appends there would each read a `before` count
  that already includes the other's rows, and the delta check would report
  `count-mismatch` on two commits that both succeeded.

  Different keys still run concurrently -- that is the whole point of the
  change. Measured on the run that verified it: satellite and vessel commits
  overlapped, 49.6 s of wall clock for 82 s of per-feed work."
  (:require [clojure.string :as str]))

(defn make
  "A fresh lock table. Not a global: a test that shared one with the
  process would pass or fail depending on what ran before it."
  []
  (atom {}))

(defn with-lock
  "Run `f` -- which returns a promise -- after any pending work on `k`.

  A rejected predecessor does NOT wedge the chain. Without the `.catch`, one
  failed write would leave every later write on that key waiting forever on a
  promise that will never settle, which turns a single refusal into a dead
  table and looks from outside like the feed going quiet."
  [locks k f]
  (let [prev (get @locks k (js/Promise.resolve nil))
        next (-> prev (.catch (fn [_] nil)) (.then (fn [_] (f))))]
    (swap! locks assoc k next)
    next))
