(ns otent.sanctions
  "Who, of the ships this actor can currently see, is on a list -- and who
  is behind them.

  Pure. Four row sets in, a report out. The command that feeds it reads the
  catalog; nothing here opens a socket, so a verdict is reproducible from
  its inputs.

  ## Why this is a command and not a query somebody remembers

  This join was run by hand six times over two days, and each run was a
  number in a terminal that nobody could reproduce. Worse, the FIRST hand
  run got it wrong twice: once by joining only OFAC (20 of 60 vessels) and
  once by joining IMO numbers with the `IMO` prefix still attached, which
  returned zero rows and reads exactly like `no ship here has a recorded
  owner`.

  Both mistakes are now structural rather than remembered.

  ## The three things it refuses to do

  **It will not conflate the two IMO registries.** A vessel joins on its IMO
  Ship Number; an organization carries an IMO Company Number. Same format,
  same property name upstream, different registries -- and on the live data
  exactly one value is both (`IMO9036387` is a Chinese vessel and a North
  Korean firm). The org side is never used as a join key here.

  **It will not call an unchecked vessel clean.** A ship that broadcasts no
  IMO cannot be looked up at all. Those are counted and reported as
  `unchecked`, never folded into `not listed`.

  **It will not report on tables it could not read.** Any input missing is
  `:cannot-answer`, which is exit 2 -- not a clean run with small numbers."
  (:require [clojure.string :as str]))

(def min-vessels
  "Below this, there is no fleet to report on and the command says so rather
  than dividing small numbers."
  25)

(defn- imo-of [v] (some-> (:imo v) str str/trim not-empty))

(defn index-risk
  "IMO and MMSI -> risk records. Both keys, because 754 of 23,191 sanctions
  records carry neither and keying on one alone drops the entries whose
  identity is most obscured."
  [risk-rows]
  (reduce (fn [acc r]
            (cond-> acc
              (:imo r)  (update [:imo (:imo r)] (fnil conj []) r)
              (:mmsi r) (update [:mmsi (:mmsi r)] (fnil conj []) r)))
          {}
          risk-rows))

(defn index-ownership
  "IMO Ship Number -> ownership edges. Ship number only: the organization's
  IMO Company Number lives in a different registry and is never a key here."
  [own-rows]
  (reduce (fn [acc r]
            (if-let [i (:asset-imo r)] (update acc i (fnil conj []) r) acc))
          {}
          own-rows))

(defn report
  "`vessels` are the identities currently in coverage; the rest are the
  catalog tables. Any of them nil means that table could not be read."
  [{:keys [vessels risk ownership orgs min-vessels]
    :or {min-vessels min-vessels}}]
  (if (or (nil? vessels) (nil? risk) (nil? ownership) (nil? orgs)
          (< (count vessels) min-vessels))
    {:verdict :cannot-answer
     :detail (cond
               (nil? vessels)   "the vessel identity table could not be read"
               (nil? risk)      "the risk table could not be read"
               (nil? ownership) "the ownership table could not be read"
               (nil? orgs)      "the organization table could not be read"
               :else (str "only " (count vessels) " vessels in coverage -- too few"
                          " to report on. Refusing rather than dividing small numbers."))}
    (let [ridx (index-risk risk)
          oidx (index-ownership ownership)
          org-by-id (into {} (map (juxt :id identity)) orgs)
          checked (filter imo-of vessels)
          unchecked (remove imo-of vessels)
          rows (for [v checked
                     :let [i (imo-of v)
                           hits (concat (get ridx [:imo i])
                                        (get ridx [:mmsi (:mmsi v)]))
                           tags (set (mapcat #(str/split (or (:risk %) "") #";") hits))
                           edges (get oidx i)]
                     :when (seq hits)]
                 {:name (:name v) :imo i
                  :tags (disj tags "")
                  :shadow? (boolean (some #(str/includes? % "shadow") tags))
                  :sanctioned? (contains? tags "sanction")
                  :orgs (vec (for [e edges]
                               {:name (:org-name e)
                                :jurisdiction (:org-jurisdiction e)
                                :role (:role e)
                                ;; nil rather than a guess: an edge whose
                                ;; organization is not in the org table is a
                                ;; gap in the ingest, and a blank name would
                                ;; hide it.
                                :identity (get org-by-id (:org-id e))}))})
          listed (vec rows)
          shadow (filterv :shadow? listed)
          sanctioned (filterv :sanctioned? listed)
          with-org (filterv #(seq (:orgs %)) listed)
          ;; An ownership edge pointing at an organization nobody recorded.
          ;; Both tables come from one payload, so this should be zero, and
          ;; a non-zero is a real inconsistency rather than a coverage gap.
          dangling (for [r listed o (:orgs r) :when (nil? (:identity o))]
                     [(:name r) (:name o)])
          fleets (->> (for [r listed o (:orgs r)] (:name o))
                      (remove nil?) frequencies
                      (sort-by (comp - val)))]
      {:verdict (cond (seq dangling) :inconsistent :else :ok)
       :in-coverage (count vessels)
       :checked (count checked)
       :unchecked (count unchecked)
       :listed (count listed)
       :shadow (count shadow)
       :sanctioned (count sanctioned)
       :with-controlling-org (count with-org)
       :dangling (vec dangling)
       :fleets (vec (take 8 fleets))
       :rows (vec (sort-by :name listed))})))

(defn exit-code
  "0 reported · 1 an inconsistency between two tables · 2 could not answer.

  Finding sanctioned vessels is NOT exit 1. That is the expected output of a
  working instrument, and an exit code that treated it as a fault would make
  the normal state look like a failure -- which is how an exit code stops
  being read."
  [{:keys [verdict]}]
  (case verdict :ok 0 :cannot-answer 2 1))

(defn- org-line [o]
  (str (:name o)
       (when (:jurisdiction o) (str " (" (:jurisdiction o) ")"))
       (when (:role o) (str " " (:role o)))))

(defn- vessel-lines [r]
  (cons (str "  " (.padEnd (str (:name r)) 22) " IMO " (:imo r) "  "
             (str/join "," (sort (:tags r))))
        (when (seq (:orgs r))
          [(str "      -> " (str/join "; " (map org-line (:orgs r))))])))

(defn render [{:keys [verdict detail in-coverage checked unchecked listed shadow
                      sanctioned with-controlling-org dangling fleets rows]}]
  (if (= :cannot-answer verdict)
    [(str "REFUSING to report: " detail)]
    (concat
     [(str "otent sanctions  " in-coverage " vessels in coverage")
      (str "  checked        " checked "   (broadcast an IMO number)")
      (str "  unchecked      " unchecked "   (no IMO -- NOT the same as not listed)")
      (str "  on a list      " listed)
      (str "    shadow fleet " shadow)
      (str "    sanctioned   " sanctioned)
      (str "    with a named controlling organization " with-controlling-org)
      ""]
     (mapcat vessel-lines rows)
     (when (seq fleets)
       (concat ["" "fleets behind these vessels (hulls in this coverage):"]
               ;; A separator, not padding alone. `.padEnd` does nothing to a
               ;; name longer than the pad, and the count then runs into it:
               ;; `MARINE RESCUE SERVICE4` reads as one token. This is the
               ;; second time that shape has shipped in this repository -- the
               ;; coverage table had it with `vessel-static2070` -- so the
               ;; separator is the fix rather than a wider pad, which only
               ;; moves the length at which it breaks.
               (map (fn [[org n]] (str "  " (.padEnd (str org) 44) "  " n)) fleets)))
     (when (seq dangling)
       (cons "" (map (fn [[v o]]
                       (str "INCONSISTENT " v " -> " o
                            " : an ownership edge whose organization is not in the org table"))
                     dangling)))
     [""
      (case verdict
        :ok "sanctions: reported"
        :inconsistent "sanctions: INCONSISTENT -- two tables from one payload disagree"
        (str "sanctions: " (name verdict)))])))
