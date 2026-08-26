(ns tenkyu.receipt
  "The append-only audit ledger, and the run report a human reads.

  Every tick appends one entry recording what each feed did -- including
  the feeds that did nothing, and why. This is the third leg of the actor
  pattern (contained proposer, independent governor, immutable ledger), and
  it is the only durable record of *what was not observed*, which the
  Iceberg tables cannot hold: a table has no row for a poll that failed.

  ## Three statuses, three exit codes, no collapsing

  | status | means | exit |
  |---|---|---|
  | `:committed` / `:dry-run` | looked, and the rows landed | 0 |
  | `:nothing-new` | looked; the feed has published nothing since last tick | 0 |
  | `:refused` | looked, and the governor or the writer said no | 1 |
  | `:unmeasured` | **did not look** -- no credential, unreachable, no collector | 2 |

  The whole point is that `:unmeasured` does not collapse into either
  neighbour. A tick that read one feed of five is not a successful tick,
  and it is not a failed one either: it is a tick that mostly did not
  happen, and the exit code has to say so or a scheduler will treat a
  month of missing vessels as a month of empty oceans."
  (:require [clojure.string :as str]))

(defn build [results at-ms]
  (let [by (group-by :status results)]
    {:tick/at at-ms
     :tick/results (vec results)
     :tick/committed (count (:committed by))
     :tick/dry-run (count (:dry-run by))
     :tick/nothing-new (count (:nothing-new by))
     :tick/refused (count (:refused by))
     :tick/unmeasured (count (:unmeasured by))
     :tick/rows-appended (reduce + 0 (keep :appended (:committed by)))}))

(defn exit-code
  "0 / 1 / 2. Ordered so the most serious wins: a tick that both refused
  one feed and could not read another exits 2, because `could not answer`
  is the fact that most changes what a reader should conclude."
  [r]
  (cond
    (pos? (:tick/unmeasured r)) 2
    (pos? (:tick/refused r)) 1
    (zero? (+ (:tick/committed r) (:tick/dry-run r) (:tick/nothing-new r))) 2
    :else 0))

(defn render
  "The run report. `iso` is passed in rather than formatted here: this
  namespace is `.cljc` and has no business owning a calendar."
  [r iso]
  (str/join
   "\n"
   (concat
    [(str "tenkyu tick " iso)
     (str "  committed " (:tick/committed r)
          "  dry-run " (:tick/dry-run r)
          "  nothing-new " (:tick/nothing-new r)
          "  refused " (:tick/refused r)
          "  UNMEASURED " (:tick/unmeasured r)
          "  rows " (:tick/rows-appended r))]
    (for [x (:tick/results r)]
      (str "  " (str/upper-case (name (:status x)))
           " " (name (:feed x))
           (when (:table x) (str " -> " (:table x)))
           (when (:appended x) (str "  +" (:appended x) " rows"
                                    (when (:rows-after x)
                                      (str " (" (:rows-before x) " -> " (:rows-after x) ")"))))
           (when (:would-append x) (str "  would append " (:would-append x) " rows"))
           (when (:error x) (str "  [" (name (:error x)) "] " (:detail x)))
           ;; :nothing-new carries a reason too, and WHICH rule answered
           ;; matters: the payload hash is exact, the timestamp watermark
           ;; is not. A reader must be able to tell them apart.
           (when (and (nil? (:error x)) (:detail x)) (str "  " (:detail x)))
           (when (:note x) (str "  note: " (:note x)))))
    [(str "exit " (exit-code r)
          (when (pos? (:tick/unmeasured r))
            (str " -- " (:tick/unmeasured r)
                 " feed(s) were NOT READ. That is not an observation of nothing.")))])))

;; `append!` deliberately does NOT live here. Writing the ledger is I/O, and
;; this namespace is the pure half -- `build`, `exit-code` and `render` are
;; all functions of their arguments, so a test can assert the exit code for
;; a given set of feed results without a filesystem. The append lives in
;; `bin/tenkyu.cljs`, which already holds the only `fs` handle in the repo.
