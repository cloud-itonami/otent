(ns otent.catalog-test
  "What reaches the kotobase datom plane, and what must not."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.catalog :as cat]))

(def receipt
  {:tick/at 1787726417000
   :tick/committed 1 :tick/dry-run 0 :tick/nothing-new 1 :tick/not-due 1
   :tick/refused 0 :tick/unmeasured 2 :tick/rows-appended 6399
   :tick/results
   [{:feed :opensky :status :committed :table "otent_aircraft"
     :appended 6399 :rows-before 18820 :rows-after 25219
     :max-observed-at 1787726417000
     :payload-sha256 "b55880d282eec0f9" :payload-key "otent/payload/b55880d282eec0f9.json.gz"}
    {:feed :celestrak :status :nothing-new :table "otent_satellite"
     :detail "403 with the feed's own not-modified body"}
    {:feed :usgs :status :not-due :table "otent_quake" :detail "due again in 131s"}
    {:feed :firms :status :unmeasured :error :feed/no-credential
     :detail "$FIRMS_MAP_KEY is not set"}]})

(deftest a-tick-becomes-one-entity-plus-one-per-feed
  (let [tx (cat/tick->tx receipt)]
    (is (= 5 (count tx)))
    (is (= "otent/tick/1787726417000" (:db/id (first tx))))
    (is (every? #(= "otent" (:source/dataset %)) tx)
        "every entity must carry :source/dataset or it cannot be told apart
         from the other datasets sharing this plane")))

(deftest no-observation-ever-reaches-the-datom-plane
  (testing "bytes and bulk observations belong in Iceberg and the bucket
            (ADR-2608039970). A coordinate arriving here would look like a
            richer catalog rather than like a leak, so it is asserted
            against rather than merely absent."
    (is (= [] (cat/leaked-keys (cat/tick->tx receipt))))))

(deftest a-receipt-carrying-observations-is-still-projected-clean
  (testing "the projection has to DROP, not merely decline to add -- a
            receipt that grew an :objects key must not carry it through"
    (let [dirty (assoc-in receipt [:tick/results 0 :objects]
                          [{:lat 1.0 :lon 2.0 :object-id "abc"}])
          tx (cat/tick->tx dirty)]
      (is (= [] (cat/leaked-keys tx)))
      (is (not-any? #(contains? % :objects) tx)))))

(deftest the-payload-hash-travels-but-the-payload-does-not
  (let [tx (cat/tick->tx receipt)
        ac (first (filter #(= "opensky" (:otent.result/feed %)) tx))]
    (is (= "b55880d282eec0f9" (:otent.result/payload-sha256 ac)))
    (is (= "otent/payload/b55880d282eec0f9.json.gz" (:otent.result/payload-key ac))
        "the join from the catalog to the bucket is an identifier, and it
         has to be there or the tables stop being rebuildable in practice")))

(deftest identity-is-derived-so-republishing-is-an-upsert
  (testing "the tick ledger is append-only on disk; this plane is not"
    (is (= (cat/tick->tx receipt) (cat/tick->tx receipt)))
    (is (= 5 (count (distinct (map :db/id (cat/tick->tx receipt))))))))

(deftest every-status-survives-including-the-ones-that-did-nothing
  (let [tx (cat/tick->tx receipt)
        by (into {} (map (juxt :otent.result/feed :otent.result/status))
                 (remove #(= "otent/tick/1787726417000" (:db/id %)) tx))]
    (is (= {"opensky" "committed" "celestrak" "nothing-new"
            "usgs" "not-due" "firms" "unmeasured"} by)
        "not-due and unmeasured must reach the plane too -- a catalog that
         records only the successful polls cannot answer why a table
         stopped growing")))
