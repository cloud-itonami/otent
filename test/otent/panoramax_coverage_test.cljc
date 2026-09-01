(ns otent.panoramax-coverage-test
  "Bounded tests for the Panoramax collections coverage manifest."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [otent.panoramax-coverage :as pc]))

(def ^:private fixture
  {:collections
   [{:id "f46df7b5-5b95-4781-a0d8-feb9a9af0f08"
     :type "Collection"
     :title "D9 - Décroissant - 2022-05-25 09-38-00"
     :license "etalab-2.0"
     :providers [{:name "cd30" :roles ["producer"]}]
     :extent {:spatial {:bbox [[4.475002305555556 44.13511638888889
                                4.475546305555556 44.1353345]]}
              :temporal {:interval [["2022-05-25T09:38:01+00:00"
                                     "2022-05-25T09:38:12+00:00"]]}}
     :stats:items {:count 12}
     :links [{:href "https://api.panoramax.xyz/api/collections?limit=5"
              :rel "next"}]}]})

(t/deftest normalize-keeps-only-curated-fields
  (let [row (pc/normalize-collection
             {:id "f46df7b5-5b95-4781-a0d8-feb9a9af0f08"
              :title "D9 - Décroissant - 2022-05-25 09-38-00"
              :license "etalab-2.0"
              :providers [{:name "cd30" :roles ["producer"]}]
              :extent {:spatial {:bbox [[4.47 44.13 4.48 44.14]]}
                       :temporal {:interval [["2022-05-25T09:38:01+00:00"
                                              "2022-05-25T09:38:12+00:00"]]}}
              :stats:items {:count 12}})]
    (t/is (= "f46df7b5-5b95-4781-a0d8-feb9a9af0f08" (:collection-id row)))
    (t/is (= "etalab-2.0" (:licence row)))
    (t/is (= [{:provider-name "cd30" :provider-roles ["producer"]}]
             (:providers row)))
    (t/is (= [4.47 44.13 4.48 44.14] (:spatial-bbox row)))
    (t/is (= "2022-05-25T09:38:01+00:00" (:capture-start row)))
    (t/is (= "2022-05-25T09:38:12+00:00" (:capture-end row)))
    (t/is (= 12 (:items-count row)))))

(t/deftest missing-bbox-is-refused-not-repaired
  (t/is (= {:refusal :missing-id} (pc/normalize-collection {:title "x"})))
  (let [r (pc/normalize-collection
           {:id "ok" :extent {:spatial {:bbox [4.48 44.13 4.47 44.14]}}})]
    (t/is (= :invalid-bbox (:refusal r)))))

(t/deftest analyze-counts-and-provenance
  (let [out (pc/analyze fixture {:retrieved-at "2026-09-02T00:00:00Z"
                                 :request-limit 20})
        row (first (:observations out))]
    (t/is (= 1 (get-in out [:counts :fetched])))
    (t/is (= 1 (get-in out [:counts :accepted])))
    (t/is (= 0 (get-in out [:counts :refused])))
    (t/is (= "f46df7b5-5b95-4781-a0d8-feb9a9af0f08" (:collection-id row)))
    (t/is (= 20 (get-in out [:provenance :request-limit])))
    (t/is (= 1 (get-in out [:provenance :requests-made])))
    (t/is (false? (get-in out [:provenance :pixel-url-requested])))
    (t/is (false? (get-in out [:provenance :raw-pixels-stored])))
    (t/is (false? (get-in out [:provenance :provider-blur-verified])))))

(t/deftest analyze-is-deterministic
  (let [a (pc/analyze fixture {:retrieved-at "2026-09-02T00:00:00Z"
                               :request-limit 20})]
    (t/is (= a (pc/analyze fixture {:retrieved-at "2026-09-02T00:00:00Z"
                                    :request-limit 20})))))

(t/deftest redaction-check-flags-uploader-identity
  (t/is (= :description-email (pc/redaction-hit {:description-email "x@y"})))
  (t/is (= :email (pc/redaction-hit {:email "a@b"})))
  (t/is (nil? (pc/redaction-hit {:title "no identity here"}))))
