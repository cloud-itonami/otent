(ns tenkyu.fixtures
  "Read a captured payload from `test/tenkyu/fixtures/`.

  Throws on a missing or truncated file rather than returning \"\": every
  parser test walks a parse result, so an empty fixture would iterate
  nothing and report success."
  #?(:cljs (:require ["fs" :as fs] ["path" :as path])))

(defn slurp-fixture [name]
  (let [content #?(:clj (slurp (str "test/tenkyu/fixtures/" name))
                   :cljs (fs/readFileSync (path/join "test" "tenkyu" "fixtures" name) "utf8"))]
    (when (or (nil? content) (< (count content) 256))
      (throw (ex-info "fixture is missing or truncated"
                      {:fixture name :bytes (count content)})))
    content))
