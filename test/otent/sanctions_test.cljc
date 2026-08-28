(ns otent.sanctions-test
  "The join that was run by hand six times, with the two mistakes the hand
  runs actually made turned into tests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [otent.sanctions :as sanc]))

(defn- vessels [n]
  (vec (for [i (range n)]
         {:name (str "SHIP" i) :imo (str (+ 9000000 i)) :mmsi (str (+ 273000000 i))})))

(def base
  {:vessels (vessels 40)
   :risk [{:imo "9000001" :risk "mare.shadow;sanction"}
          {:imo "9000002" :risk "sanction"}
          {:mmsi "273000003" :risk "mare.detained"}]
   :ownership [{:asset-imo "9000001" :org-id "o1" :org-name "SOVCOMFLOT"
                :org-jurisdiction "ru" :role "Owned or Controlled By"}
               {:asset-imo "9000002" :org-id "o1" :org-name "SOVCOMFLOT"
                :org-jurisdiction "ru" :role "Property in the interest of"}]
   :orgs [{:id "o1" :org-name "SOVCOMFLOT" :imo-company-no "IMO1234567"}]})

(deftest it-reports-the-fleet-and-the-three-counts
  (let [r (sanc/report base)]
    (is (= :ok (:verdict r)))
    (is (zero? (sanc/exit-code r)))
    (is (= 40 (:in-coverage r)))
    (is (= 3 (:listed r)))
    (is (= 1 (:shadow r)))
    (is (= 2 (:sanctioned r)))
    (is (= 2 (:with-controlling-org r)))
    (is (= [["SOVCOMFLOT" 2]] (:fleets r)))))

(deftest finding-sanctioned-vessels-is-not-an-error-exit
  (testing "the expected output of a working instrument. An exit code that
            treated it as a fault would make the normal state look like a
            failure, which is how an exit code stops being read."
    (is (zero? (sanc/exit-code (sanc/report base))))))

(deftest a-vessel-with-no-imo-is-unchecked-not-clean
  (testing "a ship that broadcasts no IMO cannot be looked up at all.
            Folding it into `not listed` would report a coverage gap as a
            clean bill."
    (let [r (sanc/report (assoc base :vessels
                                (conj (vessels 40) {:name "ANON" :mmsi "273999999"})))]
      (is (= 1 (:unchecked r)))
      (is (= 40 (:checked r)))
      (is (= 41 (:in-coverage r)))
      (is (some #(re-find #"NOT the same as not listed" %) (sanc/render r))))))

(deftest the-imo-prefix-mistake-the-first-hand-run-made
  (testing "the hand-run join left `IMO` on the sanctions side and returned
            zero rows, which reads exactly like `no ship here is listed`.
            The report must not present that as an answer."
    (let [r (sanc/report (assoc base :risk
                                [{:imo "IMO9000001" :risk "mare.shadow;sanction"}]))]
      (is (zero? (:listed r))
          "this is what the bug looked like")
      (is (= :ok (:verdict r))
          "and it cannot be distinguished from a genuinely clean fleet -- which
           is why the parser normalises the prefix at ingest, not here"))))

(deftest an-unreadable-table-is-not-a-clean-run
  (doseq [k [:vessels :risk :ownership :orgs]]
    (let [r (sanc/report (assoc base k nil))]
      (is (= :cannot-answer (:verdict r)) (str k " nil was reported as an answer"))
      (is (= 2 (sanc/exit-code r)))
      (is (some #(re-find #"REFUSING" %) (sanc/render r))))))

(deftest too-few-vessels-refuses-rather-than-dividing-small-numbers
  (let [r (sanc/report (assoc base :vessels (vessels 3)))]
    (is (= :cannot-answer (:verdict r)))
    (is (= 2 (sanc/exit-code r)))))

(deftest an-ownership-edge-with-no-organization-is-an-inconsistency
  (testing "both tables come from one payload, so a dangling org id is a real
            disagreement rather than a coverage gap -- and a blank name would
            hide it"
    (let [r (sanc/report (assoc base :orgs []))]
      (is (= :inconsistent (:verdict r)))
      (is (= 1 (sanc/exit-code r)))
      (is (= 2 (count (:dangling r))))
      (is (some #(str/includes? % "INCONSISTENT") (sanc/render r))))))

(deftest the-organization-imo-number-is-never-a-join-key
  (testing "IMO9036387 is both a Chinese vessel and a North Korean firm on the
            live data. Joining a vessel to an organization by that number
            links two unrelated things, in the direction that matters."
    (let [r (sanc/report (assoc base
                                :vessels (conj (vessels 40)
                                               {:name "COLLIDE" :imo "1234567" :mmsi "999"})
                                :orgs [{:id "o1" :org-name "SOVCOMFLOT"
                                        :imo-company-no "IMO1234567"}]))]
      (is (not-any? #(= "COLLIDE" (:name %)) (:rows r))
          "a vessel matched an ORGANIZATION's IMO company number"))))

(deftest a-long-fleet-name-does-not-run-into-its-count
  (testing "`.padEnd` does nothing to a name longer than the pad, and the
            count then joins it: `MARINE RESCUE SERVICE4` reads as one
            token. Second time this shape has shipped here -- the coverage
            table had `vessel-static2070` -- so the fix is a separator, not
            a wider pad, which only moves the length at which it breaks."
    (let [long-name "FEDERAL STATE BUDGETARY INSTITUTION MARINE RESCUE SERVICE"
          r (sanc/report (assoc base
                                :ownership [{:asset-imo "9000001" :org-id "o1"
                                             :org-name long-name}]
                                :orgs [{:id "o1" :org-name long-name}]))
          out (sanc/render r)]
      (is (some #(str/includes? % (str long-name "  1")) out)
          "the count is separated from the name")
      (is (not-any? #(re-find (re-pattern (str long-name "\\d")) %) out)
          "and never joined to it"))))
