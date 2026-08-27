(ns otent.parses-test
  "The entry points load.

  ## Why this is a test

  On 2026-08-27 a one-paren edit left `bin/otent.cljs` unparseable and the
  suite went green: 70 tests, 740 assertions, 0 failures. Every namespace
  the tests require was fine. `bin/otent.cljs` is required by nothing -- it
  is the entry point, it ends in `(-main)`, and requiring it from a test
  would run a tick against the live feeds.

  So the one file that touches the network, holds the commit logic and is
  what launchd executes was the one file no test could see. It would have
  surfaced at the next scheduled cycle, in a log nobody reads.

  ## How, given that loading it runs it

  Each CLI here answers a bare invocation with its usage line and exit 2.
  That path allocates nothing, opens no socket and writes nothing -- but it
  is a real load: the file is read, every `require` resolves, and every
  top-level form evaluates. A parse error, a missing namespace or a typo'd
  var all fail here.

  Reading the files with `cljs.reader` was tried first and does not work:
  the reader has no `#js`, no `#?` and no regex literal, so it calls a
  perfectly good ClojureScript file unreadable. A checker that is wrong
  about healthy files is worse than no checker.

  ## What is NOT covered, by name

  **`bin/scheduled.cljs`.** It has no usage branch -- a bare invocation
  reads the Keychain and runs a full cycle -- so nothing here loads it. It
  is checked by hand with a `security` that fails on `PATH`, which is not
  the same as being checked. That gap is real and is written here rather
  than left for someone to assume otherwise."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            ["child_process" :as cp]
            ["path" :as path]))

(def classpath
  "`$OTENT_TEST_CLASSPATH`, or the layout `npm test` runs in.

  The sibling paths are where west puts the dependencies; a worktree
  outside `orgs/` has to say where they are."
  (or (some-> (aget js/process.env "OTENT_TEST_CLASSPATH") not-empty)
      (str "src:" (path/join ".." ".." "kotoba-lang" "sgp4" "src")
           ":" (path/join ".." ".." "kotoba-lang" "kotobase-client" "src")
           ;; `org-ietf-csv` reached this list the way everything does: the
           ;; check above failed. A parser was added, its dependency went
           ;; into package.json and the scheduler's subprocess classpath,
           ;; and this default was the third copy nobody remembered. That
           ;; is the point of loading the entry points rather than reading
           ;; them.
           ":" (path/join ".." ".." "kotoba-lang" "org-ietf-csv" "src")
           ;; `map` is here for `buildings.cljs` only. It is not in the
           ;; suite's own classpath, which is why the load check nearly
           ;; shipped with that entry point quietly excluded -- the same
           ;; shape as the defect the whole namespace is about.
           ":" (path/join ".." ".." "kotoba-lang" "map" "src"))))

(defn- bare-run [script]
  (let [r (cp/spawnSync "nbb" #js ["--classpath" classpath (path/join "bin" script)]
                        #js {:encoding "utf8" :timeout 120000})]
    {:code (.-status r) :out (str (.-stdout r)) :err (str (.-stderr r))}))

(deftest the-entry-points-load
  (doseq [[script marker] [["otent.cljs" "usage: otent.cljs"]
                           ["basemap.cljs" "usage: basemap.cljs"]
                           ["buildings.cljs" "usage:"]]]
    (testing script
      (let [{:keys [code out err]} (bare-run script)]
        (is (= 2 code)
            (str script " did not answer a bare invocation with exit 2. stderr: "
                 (str/trim (subs err 0 (min 400 (count err))))))
        (is (str/includes? out marker)
            (str script " loaded but printed no usage line"))))))

(deftest a-broken-entry-point-would-not-be-silent
  (testing "the check above is only worth anything if a damaged file fails
            it -- and the failure this was written for is a parse error,
            which nbb reports on stderr with a non-2 exit"
    (let [{:keys [code]} (bare-run "does-not-exist.cljs")]
      (is (not= 2 code)
          "a script that cannot load must not produce the same exit code as
           one that loaded and printed its usage"))))
