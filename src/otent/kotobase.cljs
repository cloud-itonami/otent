(ns otent.kotobase
  "Publishing otent's catalog to the kotobase.net datom plane.

  The identity is otent's own: a 32-byte Ed25519 seed, kept at
  `.otent/identity.edn` and gitignored. The key IS the authority — the
  graph a write lands in is derived from it — so there is no token to
  request and no owner hand-off. See the workspace's `build-actor` skill.

  Reads and writes go through `kotoba-lang/kotobase-client`, which mints
  the CACAO. Nothing here re-implements signing: the workspace has one
  CACAO implementation and a second would drift from it silently."
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [kotobase.client :as client]
            [otent.deadline :as dl]
            [otent.catalog :as cat]))

(def endpoint "https://kotobase.net")
(def operator-did "did:web:kotobase.net")
(def db-name "otent-catalog")

(defn- identity-file []
  (path/join (js/process.cwd) ".otent" "identity.edn"))

(defn load-or-create-identity!
  "The seed at `.otent/identity.edn`, created on first use.

  Written 0600 and gitignored. A key that lands in a commit is a key that
  has to be rotated, and rotating this one moves the graph -- the published
  catalog would appear to start over."
  []
  (let [f (identity-file)]
    (if (.existsSync fs f)
      (let [m (reader/read-string (.readFileSync fs f "utf8"))]
        (js/Uint8Array.from (clj->js (:seed m))))
      (let [ed (.-ed25519 (js/require "@noble/curves/ed25519.js"))
            seed (.randomPrivateKey (.-utils ed))]
        (.mkdirSync fs (path/dirname f) #js {:recursive true})
        (.writeFileSync fs f (pr-str {:seed (vec (array-seq seed))
                                      :created-at (js/Date.now)
                                      :note "otent's kotobase.net write identity. Never commit."})
                        #js {:mode 0600})
        seed))))

(defn client-for
  "The client, with a deadline on its transport.

  `make-client` takes `:fetch-fn`, so the deadline goes in here rather than
  in the library: this actor's tolerance for a slow kotobase is its own
  business, and a library that picked one would be picking it for every
  caller.

  It matters because publishing happens INSIDE the cycle. Measured
  2026-08-27: a cycle sat for eighteen minutes holding two established
  connections to Cloudflare at 0% CPU, and launchd will not start the next
  job while one is running -- so a slow publish does not delay a receipt,
  it stops ingest. The receipt is already on disk by then, which makes it
  worse: the work was done and the schedule was blocked anyway."
  [seed]
  (client/make-client {:endpoint endpoint
                       :operator-did operator-did
                       :secret-key seed
                       :fetch-fn (fn [url opts]
                                   (js/fetch url
                                             (js/Object.assign
                                              #js {} (or opts #js {})
                                              #js {:signal (dl/signal dl/upload-ms)})))}))

(defn publish-tick!
  "Publish one tick receipt's catalog. Resolves a result map, never throws.

  `:retry? true` is safe here and is the reason identity is derived from
  values the receipt already carries: republishing the same receipt is an
  upsert, so a retry after a transient 5xx cannot duplicate a tick."
  [receipt]
  (let [tx (cat/tick->tx receipt)
        leaked (cat/leaked-keys tx)]
    (if (seq leaked)
      ;; Refuse rather than publish. An observation reaching this plane
      ;; would look like a richer catalog rather than like a leak, and it
      ;; would be published under otent's own key.
      (js/Promise.resolve
       {:ok? false :error :catalog/observation-leak
        :detail (str "refusing to publish: " (pr-str leaked)
                     " are observation-shaped and belong in Iceberg, not on "
                     "the datom plane")})
      (let [c (client-for (load-or-create-identity!))]
        (-> (client/transact c db-name (pr-str tx) {:retry? true})
            (.then (fn [r] {:ok? true :did (:did c)
                            :graph (some-> r .-graph)
                            :entities (count tx)}))
            (.catch (fn [e] {:ok? false :error :kotobase/transact-failed
                             :detail (str (.-message e))})))))))
