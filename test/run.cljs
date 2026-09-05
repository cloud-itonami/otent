(ns run
  "The suite, with an exit code the caller can see and two floors under it.

    npm test
    nbb --classpath src:test:../../kotoba-lang/sgp4/src test/run.cljs

  Exit 0 pass · 1 failures · **2** the runner and the directory disagree ·
  **3** too few tests ran to report a pass. Three and two are separate on
  purpose: neither is a failing test, and neither may look like one.

  ## Two different ways a green runner means nothing

  **A test file lands and nobody adds the require.** The namespaces below
  have to be written literally -- nbb resolves `require` at read time, and
  a runtime symbol loads nothing while looking like it did. So the list is
  written down and then CHECKED against the directory, and a `_test.cljc`
  that is not in the list makes this exit 2. The list is the convenience;
  the scan is the control. This is what `verify-cljs-runner-completeness`
  looks for across the workspace.

  **The suite fails to load and reports nothing.** A run with fewer than 20
  tests, or with no assertions at all, refuses to report a pass. That floor
  came from the original version of this file and is kept here deliberately:
  it is the difference between `I looked and it was fine` and `I could not
  look`, and those must not share an exit code.

  `clojure -M:test` does NOT work here and is not a second way to run this:
  the tests are `.cljc` but exercise ClojureScript behaviour, and
  `parse_test.cljc` calls `js->clj`, which the JVM cannot resolve."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            [otent.imagery-test]
            [otent.catalog-test]
            [otent.coverage-test]
            [otent.darkness-test]
            [otent.deadline-test]
    [otent.cli-test]
    [otent.feeds-due-test]
            [otent.governor-test]
            [otent.kartaview-image-test]
            [otent.lock-test]
[otent.mapillary-image-detections-test]
[otent.mapillary-image-test]            [otent.parse-test]
[otent.panoramax-coverage-test]
[otent.panoramax-test]
            [otent.panorama-density-test]
            [otent.panorama-coverage-test]            [otent.parses-test]
[otent.mapillary-images-test]
            [otent.mapillary-coverage-test]
            [otent.parse-test]
            [otent.parses-test]            [otent.receipt-test]
            [otent.sanctions-test]
            [otent.watchlist-test]
            [otent.mapillary-mapfeature-detections-test]
            [otent.mapillary-mapfeatures-bbox-test]))

(def declared
  '[otent.imagery-test
  otent.catalog-test
    otent.coverage-test
    otent.darkness-test
    otent.deadline-test
    otent.cli-test
    otent.feeds-due-test
    otent.kartaview-image-test
    otent.governor-test
    otent.lock-test
otent.mapillary-image-detections-test
otent.mapillary-image-test    otent.parse-test
otent.panoramax-coverage-test
otent.panoramax-test
    otent.panorama-density-test
    otent.panorama-coverage-test    otent.parses-test
otent.mapillary-images-test
    otent.mapillary-coverage-test
    otent.parse-test
    otent.parses-test    otent.receipt-test
    otent.sanctions-test
    otent.watchlist-test
    otent.mapillary-mapfeature-detections-test
    otent.mapillary-mapfeatures-bbox-test])

(def min-tests 20)

(def test-dir (path/join (js/process.cwd) "test" "otent"))

(defn on-disk []
  (->> (fs/readdirSync test-dir)
       (filter #(str/ends-with? % "_test.cljc"))
       (map #(symbol (str "otent." (str/replace (subs % 0 (- (count %) 5)) "_" "-"))))
       set))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (let [assertions (+ (:pass m) (:fail m) (:error m))]
    (println)
    (println (str "Ran " (:test m) " tests, " (:pass m) " assertions passed, "
                  (:fail m) " failed, " (:error m) " errored, in "
                  (count declared) " namespace(s)."))
    (cond
      (or (< (:test m) min-tests) (zero? assertions))
      (do (println "REFUSING to report a pass:" (:test m) "tests and"
                   assertions "assertions ran.")
          (set! (.-exitCode js/process) 3))
      (t/successful? m) (println "OK")
      :else (set! (.-exitCode js/process) 1))))

(let [disk (on-disk)
      decl (set declared)
      missing (sort (remove decl disk))
      extra (sort (remove disk decl))]
  (if (or (seq missing) (seq extra))
    (do (println "REFUSED: the runner and the directory disagree.")
        (when (seq missing)
          (println "  on disk but not run:" (str/join " " (map name missing))))
        (when (seq extra)
          (println "  run but not on disk:" (str/join " " (map name extra))))
        (set! (.-exitCode js/process) 2))
    (apply t/run-tests declared)))
