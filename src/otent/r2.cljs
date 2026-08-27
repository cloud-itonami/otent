(ns otent.r2
  "Writing objects to the R2 bucket, over the Cloudflare REST object API.

  The REST API rather than the S3 one because it takes the same Bearer
  token everything else here uses: no second credential to store, and no
  signing to get wrong.

  This lived inside `bin/basemap.cljs` until the ingest tick needed it too.
  Two copies of a credentialed PUT is how one of them quietly grows a
  different retry rule."
  (:require ["crypto" :as crypto]
            ["zlib" :as zlib]
            [clojure.string :as str]
            [otent.deadline :as dl]))

(def account "4da88288dc30d9ee257f319d3c33ecf0")
(def bucket "cloud-itonami-datalake")

(defn token
  "The catalog/R2 bearer token, or nil.

  Returns nil rather than exiting: a caller that can still do useful work
  without writing should be allowed to say so. Callers that cannot must
  exit 2 themselves -- `could not write` is not `wrote nothing`."
  []
  (some-> (aget js/process.env "CF_CATALOG_TOKEN") str/trim not-empty))

(defn sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn gzip [^js buf]
  (.gzipSync zlib buf))

(defn put!
  "PUT one object. Resolves `{:ok? true :key ...}` or a refusal that names
  the status -- never a silent failure."
  [key body content-type]
  (if-let [t (token)]
    (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" account
                       "/r2/buckets/" bucket "/objects/" key)
                  #js {:method "PUT"
                       :signal (dl/signal dl/upload-ms)
                       :headers #js {"Authorization" (str "Bearer " t)
                                     "Content-Type" content-type}
                       :body body})
        (.then (fn [r]
                 (if (.-ok r)
                   {:ok? true :key key}
                   (.then (.text r)
                          (fn [txt] {:ok? false :error :r2/put-failed
                                     :detail (str (.-status r) " " (subs txt 0 (min 200 (count txt))))})))))
        (.catch (fn [e] {:ok? false :error :r2/unreachable :detail (str (.-message e))})))
    (js/Promise.resolve
     {:ok? false :error :r2/no-credential
      :detail "$CF_CATALOG_TOKEN is not set: nothing was written, which is not the same as writing nothing"})))

(defn head
  "Does this key already exist? `{:ok? true :present? bool}` or a refusal.

  A one-byte ranged GET, not HEAD: measured 2026-08-26, the Cloudflare REST
  object API answers HEAD with a non-2xx even for objects that are present,
  so a HEAD probe reports an empty bucket."
  [key]
  (if-let [t (token)]
    (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" account
                       "/r2/buckets/" bucket "/objects/" key)
                  #js {:method "GET"
                       :signal (dl/signal)
                       :headers #js {"Authorization" (str "Bearer " t)
                                     "Range" "bytes=0-0"}})
        (.then (fn [r] {:ok? true :present? (or (.-ok r) (= 206 (.-status r)))}))
        (.catch (fn [e] {:ok? false :error :r2/unreachable :detail (str (.-message e))})))
    (js/Promise.resolve {:ok? false :error :r2/no-credential})))
