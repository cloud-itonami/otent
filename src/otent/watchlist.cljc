(ns otent.watchlist
  "Which vessels the global AIS collector records, and why it is not all of
  them.

  ## The measurement that decided this

  Measured 2026-08-28 against the live AISStream feed, subscribed to the
  whole planet with `FilterMessageTypes: [\"PositionReport\"]`:

  | | |
  |---|---|
  | messages in a 90 s window | 6,386 |
  | DISTINCT vessels in that window | 5,710 |
  | dedup ratio | **1.12 messages per vessel** |
  | rate | 71 messages/second |

  A dedup ratio of 1.12 is the number that matters: keeping one fix per
  vessel per flush removes almost nothing, because nearly every message is a
  different ship. Ten-minute flushes would commit on the order of 30,000
  rows each -- roughly **4.3 million rows a day**, against the 56,000 the
  vessel table held in total when this was written, on a read path that
  already fails at 21,000.

  So the collector records vessels that appear on a maritime risk list, and
  says so. That is not a smaller version of global coverage; it is a
  different claim, and `:scope` on the feed states it.

  ## Why this is the useful default rather than a compromise

  The question this actor was asked on 2026-08-27 was whether the shadow
  fleet tankers that had been attacked were in its data. They were not, and
  could not be: every attack was in the Black Sea or the Mediterranean and
  the vessel feed was the Finnish receiver network. A global collector
  filtered to listed vessels covers exactly those hulls -- QENDIL, JAMES II,
  ALTURA, VELORA, KAIROS, VIRAT -- and costs a fraction of the firehose.

  What it gives up is the ability to ask later about a vessel that was not
  on a list at the time it sailed past. That is a real loss and it is the
  reason the scope is one parameter rather than a hard-coded set."
  (:require [clojure.string :as str]))

(defn build
  "Risk rows -> `{:mmsi #{...} :imo #{...}}`.

  Both keys, because 754 of 23,191 risk records carry neither and the ones
  that carry only an MMSI are exactly the vessels whose identity is most
  obscured -- the population this is for."
  [risk-rows]
  (reduce (fn [acc r]
            (cond-> acc
              (not (str/blank? (str (:mmsi r)))) (update :mmsi conj (str (:mmsi r)))
              (not (str/blank? (str (:imo r))))  (update :imo conj (str (:imo r)))))
          {:mmsi #{} :imo #{}}
          risk-rows))

(defn watched?
  "Is this AIS message about a vessel on the list?

  Matched on MMSI, which is what a position report carries. An IMO number is
  not in a PositionReport at all, so the `:imo` set is kept for the static
  messages and for callers that have one -- not because this function can
  use it."
  [{:keys [mmsi]} watchlist]
  (boolean (and mmsi (contains? (:mmsi watchlist) (str mmsi)))))

(defn empty-list?
  "A watchlist with no MMSIs on it. The collector refuses to run against one:
  it would sit on a global stream recording nothing, and `recorded nothing`
  is indistinguishable from `nothing sailed past` in the table afterwards."
  [watchlist]
  (empty? (:mmsi watchlist)))
