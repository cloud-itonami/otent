(ns run
  "The suite, with an exit code the caller can see and a floor under the
  number of tests that ran."
  (:require [clojure.test :as t]
            [tenkyu.governor-test]
            [tenkyu.parse-test]
            [tenkyu.receipt-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println)
  (println (str "Ran " (:test m) " tests, " (:pass m) " assertions passed, "
                (:fail m) " failed, " (:error m) " errored."))
  (cond
    (< (:test m) 20)
    (do (println "REFUSING to report a pass:" (:test m) "tests ran.")
        (set! (.-exitCode js/process) 3))
    (t/successful? m) (println "OK")
    :else (set! (.-exitCode js/process) 1)))

(t/run-tests 'tenkyu.governor-test 'tenkyu.parse-test 'tenkyu.receipt-test)
