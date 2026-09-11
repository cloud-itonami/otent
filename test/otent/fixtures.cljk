(ns otent.fixtures
  "Read a captured payload from `test/otent/fixtures/`.

  Throws on a missing or truncated file rather than returning \"\": every
  parser test walks a parse result, so an empty fixture would iterate
  nothing and report success."
  #?(:cljs (:require ["fs" :as fs] ["path" :as path])))

(defn slurp-fixture [name]
  (let [content #?(:clj (slurp (str "test/otent/fixtures/" name))
                   :cljs (fs/readFileSync (path/join "test" "otent" "fixtures" name) "utf8"))]
    (when (or (nil? content) (< (count content) 256))
      (throw (ex-info "fixture is missing or truncated"
                      {:fixture name :bytes (count content)})))
    content))
