(ns otent.darkness
  "How many cycles a feed has to stay unreadable before the cycle refuses.

  ## Why not the first one

  Until 2026-08-27 `firms` was exempt from being unmeasured because nobody
  had entered its key. Supplying the key correctly removed the exemption --
  a feed whose credential is present is expected to work. Twenty minutes
  later a burst of local load made the FIRMS and OpenSky fetches exceed
  their 60-second deadline, both came back UNMEASURED, and the cycle
  REFUSED.

  Which was, strictly, true. It was also the beginning of the failure that
  `expected-unmeasured` exists to prevent: a job that goes red for a
  transient reason teaches its reader that red means nothing, and then the
  real outage arrives and looks identical to the last four false ones.

  So the threshold is CONSECUTIVE cycles, not one. A single timeout is
  reported in the receipt -- it is a real gap in coverage and it is written
  down -- but it does not make the run a failure. Three in a row does.

  This is the same distinction `otent.coverage` draws between a blip and a
  pattern, moved to where the exit code is decided. It was learned twice:
  the first version of `dark-now` called a feed that had blipped once in
  thirty hours as dark as one that had never been readable.

  ## The counter has to reset

  A feed that failed twice and then worked is at zero, not two. Without
  that, every feed eventually accumulates its way to the threshold and the
  refusal fires for a feed that has been healthy for a week. Pure, so the
  reset is something a test can hold."
  (:require [clojure.string :as str]))

(def default-threshold
  "Three consecutive cycles. At a five-minute timer that is fifteen minutes
  of a feed being unreadable, which is longer than any burst of local load
  has lasted and far shorter than an outage anyone would want to sit
  through."
  3)

(defn advance
  "Previous streaks + this cycle's unmeasured set -> new streaks.

  Every feed that was asked appears in the result: those that answered are
  explicitly zero rather than dropped, so `worked this time` and `was not
  in this run` stay different states."
  [prev-streaks asked unmeasured]
  (reduce (fn [acc f]
            (assoc acc f (if (contains? (set unmeasured) f)
                           (inc (get prev-streaks f 0))
                           0)))
          {}
          asked))

(defn verdict
  "Should this cycle refuse, and what should it say?

  `exempt` are the feeds declared unreadable for a standing reason -- they
  never count towards a refusal, however long they stay dark, because their
  darkness is the documented state rather than news."
  [{:keys [streaks exempt threshold] :or {threshold default-threshold}}]
  (let [exempt (set exempt)
        over (sort (for [[f n] streaks
                         :when (and (not (exempt f)) (>= n threshold))]
                     f))
        rising (sort (for [[f n] streaks
                           :when (and (not (exempt f)) (pos? n) (< n threshold))]
                       [f n]))]
    {:refuse? (boolean (seq over))
     :over over
     :rising rising
     :detail (cond
               (seq over)
               (str (str/join "," over) " could not be read for "
                    threshold " consecutive cycles. Not a blip.")

               (seq rising)
               ;; Printed BEFORE it refuses, so a rising count is visible
               ;; while it is still recoverable. A threshold whose approach
               ;; is invisible is one that always arrives as a surprise.
               (str "watching: "
                    (str/join ", " (for [[f n] rising]
                                     (str (name f) " " n "/" threshold)))
                    " -- unreadable this cycle, not yet a pattern")

               :else nil)}))
