(ns tenkyu.governor
  "The independent check between what a feed said and what gets written.

  This is the Governor half of the actor pattern: the fetch/parse side
  proposes rows, this side admits or holds them, and **nothing reaches the
  lake that the governor did not admit**. It is pure -- no network, no
  clock, no file handle -- so every decision is reproducible from the
  batch and the `now` it was given.

  ## The five rules, and what each one has actually caught

  1. `:provenance-incomplete` -- a row that cannot say where it came from.
     Without this, one parser bug produces coordinates that look like every
     other row and cannot be traced or withdrawn.

  2. `:timestamp-not-plausible` -- seconds handed to a field that means
     milliseconds. This is the rule that fires in practice: OpenSky reports
     UNIX **seconds**, USGS reports **milliseconds**, and reading OpenSky's
     as milliseconds puts every aircraft in January 1970. Reading USGS's as
     seconds puts every earthquake in the year 58000. Both produce numbers.

  3. `:coordinates-out-of-range` -- latitude outside +/-90, longitude
     outside +/-180. Catches a transposed lat/lon pair whenever the
     latitude would have been a valid longitude, which is most of the
     inhabited world.

  4. `:person-identifier` -- a field whose name marks it as identifying a
     person rather than a vehicle or an event. The upstream design this
     follows is explicit that it does not do named-person search; a rule
     is how that survives the next feed being added by someone who did not
     read the README.

  5. `:duplicate-observation` -- the same object at the same instant from
     the same source, twice in one batch.

  6. `:already-committed` -- an observation at or before the watermark this
     feed reached on a previous tick. Rule 5 only sees inside one batch,
     and that is not enough: a feed whose timestamps do NOT advance between
     polls re-offers the same rows every time. Measured 2026-08-26 -- the
     satellite table reached 42 rows from 21 element sets, because
     CelesTrak re-fits roughly daily and was polled twice within the hour.
     Aircraft are unaffected (every poll is a new fix), which is why this
     had to be a rule rather than a shorter poll interval.

     The watermark comes from the receipt ledger, not from a scan of the
     table: 7,000 aircraft rows a minute makes `SELECT max(observed_at)
     GROUP BY object_id` the most expensive thing in the tick, to answer a
     question the previous tick already wrote down.

  ## Held rows are counted and reported, never dropped quietly

  `admit` returns `{:admitted [...] :held [...]}` and **both keys are
  always present**. A batch where 4,000 of 4,100 rows were held must not
  be shaped like a clean one. `bin/tenkyu.cljs` refuses to commit when the
  held fraction crosses a threshold, because at that point the parser is
  wrong, not the feed."
  (:require [clojure.string :as str]))

(def ^{:doc "Field names that mark a person rather than a vehicle or event.

  Matched as substrings against the lower-cased key, so `pilot_name` and
  `ownerName` both hit. Deliberately broad: a false positive here is a
  held row and a log line, a false negative is personal data in a public
  lake.

  Not on the list, and this is the distinction the rule turns on:
  `icao24`, `callsign`, `mmsi`, `registration`. Those identify an aircraft
  or a vessel, and they are what the transponder itself broadcasts in the
  clear. Removing them would not make the data less identifying -- it
  would make it useless while the same broadcast stayed public."}
  person-field-markers
  #{"person" "name_of" "firstname" "first_name" "lastname" "last_name"
    "surname" "givenname" "given_name" "fullname" "full_name"
    "owner" "operator_name" "pilot" "crew" "captain" "passenger"
    "email" "phone" "address" "birth" "ssn" "passport" "license_no"
    "face" "photo_of"})

(defn person-identifier?
  "Does this attribute key name a person?"
  [k]
  (let [s (str/lower-case (name k))]
    (some #(str/includes? s %) person-field-markers)))

;; Milliseconds. Timestamps outside [2001-09-09, 2286-11-20] are refused.
;; The bounds are not calendar choices: a UNIX-SECONDS value read as
;; milliseconds lands below the first (1.79e9 < 1e12), and a milliseconds
;; value read as seconds and re-scaled lands above the second. The unit
;; error is exactly what the window is shaped to catch.
(def min-plausible-ms 1000000000000)   ; 2001-09-09
(def max-plausible-ms 9999999999999)   ; 2286-11-20

(defn- hold [row reason detail]
  {:row row :reason reason :detail detail})

(defn check-row
  "nil if the row is admissible, otherwise a hold.

  `now-ms` bounds the future: a row observed more than `future-slack-ms`
  ahead of the ingest clock is held. Feeds do run slightly ahead (clock
  skew at the sensor), so the slack is real rather than zero, but a row an
  hour in the future is a unit error or a corrupt payload.

  `watermark-ms` is the newest `observed-at` this feed has already
  committed; rows at or before it are held. nil means no previous tick is
  on record, which admits everything -- correct for a first run, and the
  reason the CLI distinguishes 'no watermark' from 'watermark of 0'."
  ([row now-ms] (check-row row now-ms 3600000 nil))
  ([row now-ms future-slack-ms] (check-row row now-ms future-slack-ms nil))
  ([{:keys [kind object-id observed-at lat lon attrs source source-url
            fetched-at payload-sha256] :as row}
    now-ms future-slack-ms watermark-ms]
   (cond
     (not (contains? #{:satellite :quake :aircraft :fire :vessel} kind))
     (hold row :unknown-kind (str "kind " (pr-str kind)))

     (str/blank? (str object-id))
     (hold row :provenance-incomplete "object-id is blank")

     (or (str/blank? (str source-url)) (nil? source)
         (str/blank? (str payload-sha256)) (nil? fetched-at))
     (hold row :provenance-incomplete
           (str "missing "
                (str/join ", "
                          (cond-> []
                            (str/blank? (str source-url)) (conj "source-url")
                            (nil? source) (conj "source")
                            (str/blank? (str payload-sha256)) (conj "payload-sha256")
                            (nil? fetched-at) (conj "fetched-at")))))

     (not (number? observed-at))
     (hold row :timestamp-not-plausible (str "observed-at " (pr-str observed-at)))

     (< observed-at min-plausible-ms)
     (hold row :timestamp-not-plausible
           (str observed-at " is before 2001 as milliseconds -- a UNIX "
                "SECONDS value read as milliseconds looks exactly like this"))

     (> observed-at max-plausible-ms)
     (hold row :timestamp-not-plausible
           (str observed-at " is after 2286 as milliseconds"))

     (> observed-at (+ now-ms future-slack-ms))
     (hold row :timestamp-not-plausible
           (str observed-at " is " (long (/ (- observed-at now-ms) 60000))
                " minutes ahead of the ingest clock"))

     (and (some? watermark-ms) (<= observed-at watermark-ms))
     (hold row :already-committed
           (str "observed-at " observed-at " is at or before the watermark "
                watermark-ms " this feed already committed"))

     ;; A feed may legitimately omit position (a satellite row carries
     ;; elements, not a fix). What it may not do is report an impossible one.
     (and (some? lat) (not (<= -90.0 lat 90.0)))
     (hold row :coordinates-out-of-range (str "lat " lat))

     (and (some? lon) (not (<= -180.0 lon 180.0)))
     (hold row :coordinates-out-of-range (str "lon " lon))

     (and (some? lat) (nil? lon))
     (hold row :coordinates-out-of-range "latitude without longitude")

     :else
     (when-let [bad (seq (filter person-identifier? (keys (or attrs {}))))]
       (hold row :person-identifier
             (str "attribute(s) name a person: " (str/join ", " (map name bad))))))))

(defn- dedupe-key [{:keys [kind object-id observed-at source]}]
  [kind object-id observed-at source])

(defn admit
  "Check a batch. Returns `{:admitted [...] :held [...] :counts {...}}`.

  Both `:admitted` and `:held` are always present, and `:counts` carries
  the tally by reason so a caller can report what it dropped without
  walking the held rows."
  ([batch now-ms] (admit batch now-ms {}))
  ([batch now-ms opts]
   (let [slack (get opts :future-slack-ms 3600000)
         watermark (get opts :watermark-ms)]
     (loop [rows (seq batch)
            seen #{}
            admitted (transient [])
            held (transient [])]
       (if-not rows
         (let [held* (persistent! held)
               admitted* (persistent! admitted)]
           {:admitted admitted*
            :held held*
            :counts (merge {:proposed (count batch)
                            :admitted (count admitted*)
                            :held (count held*)}
                           (frequencies (map :reason held*)))})
         (let [row (first rows)
               k (dedupe-key row)]
           (cond
             (contains? seen k)
             (recur (next rows) seen admitted
                    (conj! held (hold row :duplicate-observation
                                      (str "already in this batch: " (pr-str k)))))

             :else
             (if-let [h (check-row row now-ms slack watermark)]
               (recur (next rows) seen admitted (conj! held h))
               (recur (next rows) (conj seen k) (conj! admitted row) held)))))))))

(defn commit-decision
  "The batch-level verdict, given a governor result.

  A held row is normal. A batch that is *mostly* held is a broken parser
  wearing the shape of a quiet day, so it is refused rather than committed
  as a small batch. `:max-held-fraction` defaults to 0.5.

  Also refuses an empty admitted set: committing zero rows creates an
  Iceberg snapshot that says a poll happened and found nothing, which is
  indistinguishable from a poll that failed."
  ([result] (commit-decision result {}))
  ([{:keys [admitted held counts]} opts]
   (let [max-frac (get opts :max-held-fraction 0.5)
         proposed (:proposed counts)
         frac (if (pos? proposed) (/ (double (count held)) proposed) 0.0)]
     (cond
       (zero? proposed)
       {:commit? false :reason :nothing-proposed
        :detail "the feed returned no rows: this is not a clean poll, it is an unanswered one"}

       ;; A batch held ENTIRELY because it was already committed is the
       ;; normal outcome of polling a slow feed, not a failure. It is
       ;; distinguished from :everything-held so the tick does not exit 1
       ;; every six hours for doing exactly the right thing.
       (and (empty? admitted)
            (= (count held) (:already-committed counts)))
       {:commit? false :reason :nothing-new
        :detail (str "all " proposed " rows were already committed by an "
                     "earlier tick; the feed has not published anything new")}

       (empty? admitted)
       {:commit? false :reason :everything-held
        :detail (str "all " proposed " rows were held: " (pr-str (dissoc counts :proposed :admitted :held)))}

       (> frac max-frac)
       {:commit? false :reason :held-fraction-too-high
        :detail (str (count held) " of " proposed " rows held ("
                     (Math/round (* 100.0 frac)) "%), over the "
                     (Math/round (* 100.0 max-frac)) "% ceiling: "
                     (pr-str (dissoc counts :proposed :admitted :held)))}

       :else
       {:commit? true :rows (count admitted) :held (count held)}))))
