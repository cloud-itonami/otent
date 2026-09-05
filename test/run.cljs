(ns run
  "The suite, with an exit code the caller can see and two floors under it.

    npm test
    nbb --classpath src:test test/run.cljs

  Exit 0 pass · 1 failures · **2** the runner and the directory disagree ·
  **3** too few tests ran to report a pass. Three and two are separate on
  purpose: neither is a failing test, and neither may look like one.

  The namespaces below have to be written literally -- nbb resolves
  `require` at read time. The list is written down and then CHECKED against
  the directory, and a `_test.cljc` that is not in the list makes this
  exit 2. The list is the convenience; the scan is the control."
  (:require [clojure.test :as t]
            [clojure.set :as clojure.set]
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
            [otent.mapillary-image-test]
            [otent.mapillary-images-test]
            [otent.mapillary-mapfeature-detections-test]
            [otent.mapillary-mapfeatures-bbox-test]
            [otent.panorama-coverage-test]
            [otent.panorama-density-test]
            [otent.panoramax-coverage-test]
            [otent.panoramax-daylight-test]
            [otent.panoramax-image-test]
            [otent.panoramax-test]
            [otent.parse-test]
            [otent.parses-test]
            [otent.receipt-test]
            [otent.sanctions-test]
            [otent.street-heading-test]
            [otent.watchlist-test]
            [otent.basemap-test]))

(def declared
  '(otent.imagery-test
  otent.catalog-test
  otent.coverage-test
  otent.darkness-test
  otent.deadline-test
  otent.cli-test
  otent.feeds-due-test
  otent.governor-test
  otent.kartaview-image-test
  otent.lock-test
  otent.mapillary-image-detections-test
  otent.mapillary-image-test
  otent.mapillary-images-test
  otent.mapillary-mapfeature-detections-test
  otent.mapillary-mapfeatures-bbox-test
  otent.panorama-coverage-test
  otent.panorama-density-test
  otent.panoramax-coverage-test
  otent.panoramax-daylight-test
  otent.panoramax-image-test
  otent.panoramax-test
  otent.parse-test
  otent.parses-test
  otent.receipt-test
  otent.sanctions-test
  otent.street-heading-test
  otent.watchlist-test
  otent.basemap-test))

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

(def extra (clojure.set/difference (on-disk) (set declared)))
(def missing (clojure.set/difference (set declared) (on-disk)))

(when (seq (concat extra missing))
  (println "REFUSED: the runner and the directory disagree.")
  (when (seq missing)
    (println "  declared but not on disk:" (str/join " " (map name missing))))
  (when (seq extra)
    (println "  run but not on disk:" (str/join " " (map name extra))))
  (set! (.-exitCode js/process) 2))

(when-not (or (seq extra) (seq missing))
  (apply t/run-tests declared))
