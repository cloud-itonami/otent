(ns otent.cli-test
  "The parser, and specifically the switch that did nothing.

  `--force` was added to the tick on 2026-08-26 and was not on the boolean
  whitelist, so it fell through to the `--key value` branch: it became an
  option nobody reads, and it swallowed the argument after it. The run that
  looked like it honoured `--force` was a feed that happened to be due
  anyway."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.cli :as cli]))

(deftest force-is-a-switch-not-an-option
  (let [p (cli/parse-args ["tick" "--feed" "usgs" "--force"])]
    (is (nil? (:error p)))
    (is (contains? (:flags p) "force"))
    (testing "and it does not consume the argument after it"
      (let [q (cli/parse-args ["tick" "--force" "--feed" "usgs"])]
        (is (= "usgs" (:feed (:opts q)))
            (str "--force ate the next argument: " (pr-str q)))))))

(deftest every-boolean-flag-round-trips
  (doseq [f cli/boolean-flags]
    (let [p (cli/parse-args ["tick" f])]
      (is (nil? (:error p)) (str f " was not accepted"))
      (is (contains? (:flags p) (subs f 2)) (str f " did not reach :flags")))))

(deftest every-value-option-takes-its-value
  (doseq [o cli/value-options]
    (let [p (cli/parse-args ["tick" o "x"])]
      (is (nil? (:error p)))
      (is (= "x" (get (:opts p) (keyword (subs o 2))))))))

(deftest an-unknown-switch-refuses-rather-than-being-interpreted
  (testing "the whole point: silently dropping an option is
            indistinguishable from honouring it"
    (let [p (cli/parse-args ["tick" "--nonsense"])]
      (is (= :cli/unknown-option (:error p)))
      (is (= "--nonsense" (:option p)))))
  (testing "and it does not quietly become an option with a value"
    (let [p (cli/parse-args ["tick" "--nonsense" "value"])]
      (is (= :cli/unknown-option (:error p)))
      (is (nil? (:opts p))))))

(deftest a-typo-of-a-real-switch-is-caught
  (testing "the case that motivated this -- a switch one character off from
            a real one used to be accepted and ignored"
    (doseq [typo ["--forcee" "--dryrun" "--Force"]]
      (is (= :cli/unknown-option (:error (cli/parse-args ["tick" typo])))
          (str typo " was silently accepted")))))

(deftest the-command-still-parses-around-all-of-it
  (let [p (cli/parse-args ["tick" "--feed" "usgs" "--dry-run" "--force"])]
    (is (= "tick" (:cmd p)))
    (is (= #{"dry-run" "force"} (:flags p)))
    (is (= "usgs" (:feed (:opts p))))))
