(ns otent.catalog
  "What otent publishes to the kotobase.net datom plane, and what it does not.

  ## The rule this namespace exists to hold

  **Bytes and bulk observations do not go on the datom plane**
  (superproject ADR-2608039970). Aircraft positions, element sets and
  earthquake magnitudes live in the Iceberg tables, addressed by the
  catalog; their raw payloads live in the bucket, addressed by hash. What
  goes to kotobase is the **catalog**: which feeds exist, what each tick
  did, which table it wrote to, and which payload backs it.

  That split is not a size optimisation. A datom plane is for things you
  join across datasets, and nobody joins an aircraft's latitude against
  anything -- but `otent_aircraft` as a table, `celestrak` as a source, and
  a tick's own provenance all join against `:source/dataset` neighbours in
  the same workspace plane.

  So `tick->tx` is a projection that DROPS almost everything, and the test
  suite asserts what it drops rather than only what it keeps: a coordinate
  reaching this plane would be a silent widening of scope, and would look
  like a richer catalog rather than like a leak.

  ## Identity

  A tick is `otent/tick/<at-ms>` and a feed result is
  `otent/tick/<at-ms>/<feed>` -- derived from values the receipt already
  carries, so republishing the same receipt is an upsert rather than a
  duplicate. The tick ledger is append-only on disk; this plane is
  idempotent."
  (:require [clojure.string :as str]))

(def dataset "otent")

(defn- feed-entity [at r]
  (cond-> {:db/id (str "otent/tick/" at "/" (name (:feed r)))
           :source/dataset dataset
           :otent.result/tick (str "otent/tick/" at)
           :otent.result/feed (name (:feed r))
           :otent.result/status (name (:status r))}
    (:table r) (assoc :otent.result/table (:table r))
    (:appended r) (assoc :otent.result/appended (:appended r))
    (:rows-after r) (assoc :otent.result/rows-after (:rows-after r))
    ;; The payload HASH and KEY, not the payload. This is the join from the
    ;; catalog to the bucket, and it is an identifier -- the bytes stay
    ;; where bytes go.
    (:payload-sha256 r) (assoc :otent.result/payload-sha256 (:payload-sha256 r))
    (:payload-key r) (assoc :otent.result/payload-key (:payload-key r))
    (:error r) (assoc :otent.result/error (name (:error r)))))

(defn tick->tx
  "One tick receipt -> catalog tx-data.

  Everything not named here is dropped, including every observation."
  [receipt]
  (let [at (:tick/at receipt)]
    (into [(cond-> {:db/id (str "otent/tick/" at)
                    :source/dataset dataset
                    :otent.tick/at at
                    :otent.tick/committed (:tick/committed receipt)
                    :otent.tick/nothing-new (:tick/nothing-new receipt)
                    :otent.tick/not-due (:tick/not-due receipt 0)
                    :otent.tick/refused (:tick/refused receipt)
                    :otent.tick/unmeasured (:tick/unmeasured receipt)
                    :otent.tick/rows-appended (:tick/rows-appended receipt)})]
          (map #(feed-entity at %))
          (:tick/results receipt))))

(def observation-shaped-keys
  "Keys that would mean an observation had reached this plane.

  Asserted against rather than merely absent: a coordinate arriving here
  would look like a richer catalog, not like a leak."
  #{:lat :lon :alt-km :lat-deg :lon-deg :observed-at :attrs :attrs_json
    :object-id :object_id :line1 :line2})

(defn leaked-keys
  "Any observation-shaped key present anywhere in tx-data."
  [tx]
  (->> tx
       (mapcat keys)
       (filter observation-shaped-keys)
       distinct
       vec))
