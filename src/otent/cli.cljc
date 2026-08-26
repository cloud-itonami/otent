(ns otent.cli
  "Argument parsing, split out of `bin/otent.cljs` so it can be tested.

  Both option sets are closed, and an unrecognised `--switch` comes back as
  a refusal value rather than being interpreted. The caller exits; this
  namespace does not, so the refusal is something a test can hold.

  The parser used to fall through: anything not in the boolean whitelist
  was read as `--key value`, so an unknown switch became an option nobody
  reads AND silently swallowed the argument after it. That is not a missing
  typo-catcher. It is a control that reports success while doing nothing --
  `--force` was added to this tool on 2026-08-26, was not on the whitelist,
  and the run that appeared to honour it was a feed that happened to be due
  anyway. Same class as `:min-interval-ms` sitting in the feed registry
  unread, in the same file, on the same day."
  (:require [clojure.string :as str]))

(def boolean-flags
  "Switches that take no value."
  #{"--dry-run" "--create" "--verbose" "--force"})

(def value-options
  "Options that consume the next argument."
  #{"--feed" "--kind" "--params"})

(defn parse-args
  "argv -> `{:cmd :flags :opts}`, or `{:error :cli/unknown-option ...}`."
  [argv]
  (loop [a (seq argv) out {:flags #{} :opts {}}]
    (if-not a
      out
      (let [x (first a)]
        (cond
          (boolean-flags x)
          (recur (next a) (update out :flags conj (subs x 2)))

          (value-options x)
          (recur (nnext a) (assoc-in out [:opts (keyword (subs x 2))] (second a)))

          (str/starts-with? x "--")
          {:error :cli/unknown-option
           :option x
           :detail (str "unknown option " x ". Known switches: "
                        (str/join " " (sort boolean-flags))
                        "; known options: " (str/join " " (sort value-options))
                        ". Refusing rather than ignoring it -- an option that is"
                        " silently dropped is indistinguishable from one that worked.")}

          (nil? (:cmd out)) (recur (next a) (assoc out :cmd x))
          :else (recur (next a) out))))))
