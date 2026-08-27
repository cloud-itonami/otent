(ns otent.deadline
  "Every network call in this actor gets a deadline, and a call that runs
  out of one is UNMEASURED with a reason.

  ## Why this exists

  Measured 2026-08-27, on the first cycle after the scheduler change: a
  tick sat for eleven minutes at 0% CPU with a dead OpenSky socket in
  CLOSE_WAIT. Nothing in this repository set a timeout on any `fetch`, and
  Node's default is none -- so a remote that accepts a connection and then
  says nothing holds the cycle open indefinitely.

  **launchd will not start a job while the previous one is running.** So
  one stalled feed does not delay one poll; it stops ingest entirely, for
  as long as the socket stays open. And a cycle that never finishes writes
  no receipt, so the ledger shows nothing at all -- which is exactly what a
  quiet period looks like. The failure is invisible in the one place built
  to make failures visible.

  This is the same defect as the cadence drift, one layer down: the tick
  reports honestly on every poll it completes, and says nothing about the
  poll it is still inside.

  ## The deadline is per call, not per cycle

  A cycle-wide budget would let one slow feed eat the others' time and
  report them as failures. Each call carries its own, and the reason names
  which call and how long it waited, so `feed did not answer in 60s` and
  `feed answered 500` stay different sentences."
  (:require [clojure.string :as str]))

(def default-ms
  "60 s. Long enough for a 2.3 MB FIRMS payload over a slow link -- measured
  at 2.4 s, so this is 25x headroom -- and short enough that a stall costs
  one poll rather than a day of them."
  60000)

(def upload-ms
  "180 s for writes. An R2 payload archive is megabytes and a catalog
  commit talks to two services; a write cut off halfway is worse than a
  read cut off halfway, so it gets more room before we give up on it."
  180000)

(defn signal
  "An `AbortSignal` that fires after `ms`.

  `AbortSignal.timeout` rather than a hand-rolled `setTimeout` + `abort`:
  the hand-rolled version keeps a timer alive after the fetch resolves,
  which holds the Node event loop open and makes a fast cycle exit slowly."
  ([] (signal default-ms))
  ([ms] (js/AbortSignal.timeout ms)))

(defn timeout-error?
  "Did this rejection come from the deadline rather than from the network?

  Checked by name, because the two need different words in the receipt: a
  timeout means we do not know what the feed would have said, and a
  connection refused means we do."
  [e]
  (let [n (some-> e .-name str)
        m (some-> e .-message str)]
    (boolean (or (= "TimeoutError" n)
                 (= "AbortError" n)
                 (and m (str/includes? (str/lower-case m) "timed out"))))))

(defn describe
  "The sentence that goes in the receipt when a call ran out of time."
  [what ms]
  (str what " did not answer within " (Math/round (/ ms 1000.0)) "s. "
       "The call was abandoned, so this is UNMEASURED -- we do not know what "
       "it would have said, which is not the same as it having said nothing."))
