#!/usr/bin/env nbb
;; Ingest one bounded Natural Earth raster asset into R2, with the
;; integrity checks the pure module declares.
;;
;;   nbb --classpath src bin/natural_earth.cljs NE1_50M_SR_W
;;
;; One asset per invocation, from the catalogue in `otent.natural-earth`.
;; Nothing is unzipped or repacked: the zip is stored as published, so a
;; later reader can verify it against the pinned sha256 without trusting
;; this script.
;;
;; Exit 0 ingested · 1 refused (upstream changed / bounds violated) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.natural-earth
  (:require ["crypto" :as crypto]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.natural-earth :as ne]
            [otent.r2 :as r2]))

(defn- sha256 [^js buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- iso-now []
  (.toISOString (js/Date.)))

(defn- refuse! [code m]
  (println (str "REFUSED " (name (:error m)) ": " (:detail m)))
  (set! (.-exitCode js/process) code)
  (js/process.exit code))

(defn- bytes->str
  "The first n bytes of a Buffer as a string."
  [^js buf n]
  (let [u8 (js/Uint8Array. (.slice buf 0 n))]
    (apply str (map #(js/String.fromCharCode (aget u8 %)) (range (.-length u8))))))

;; -- step 1: download and check -----------------------------------------------

(defn- fetch-and-check!
  "Download the asset, refusing at every gate the module declares: the
  declared Content-Length band, the zip magic, the pinned sha256. All of
  these run BEFORE anything is written.

  Resolves {:ok? true :buf buf :bytes n} or {:ok? false :error ...}."
  [asset]
  (.then (js/fetch (:url asset) #js {:method "GET" :signal (dl/signal)})
         (fn [r]
           (let [cl (js/parseInt (or (.get (.-headers r) "content-length") "") 10)
                 size (ne/check-size asset cl)]
             (if-not (.-ok r)
               (js/Promise.resolve
                {:ok? false :error :natural-earth/fetch-failed
                 :detail (str "status " (.-status r) " from " (:url asset))})
               (if-not (:ok? size)
                 (js/Promise.resolve size)
                 (.then (.arrayBuffer r)
                        (fn [ab]
                          (let [buf (js/Buffer.from ab)
                                magic (ne/check-magic buf)]
                            (if-not (:ok? magic)
                              (js/Promise.resolve magic)
                              (.then (js/Promise.resolve nil)
                                     (fn [_]
                                       (let [sha (ne/check-sha asset (sha256 buf))]
                                         (if-not (:ok? sha)
                                           sha
                                           {:ok? true :buf buf :bytes (.-length buf)}))))))))))))
         (fn [e]
           (js/Promise.resolve {:ok? false :error :natural-earth/fetch-failed
                                :detail (str (.-message e))}))))

;; -- step 2: readback ----------------------------------------------------------

(defn- readback!
  "Ranged GET over the object we claim to have written, compared to the
  first bytes we hashed. A 200 from the PUT is not evidence the object
  is readable; this is."
  [key expected-prefix]
  (if-let [t (r2/token)]
    (.then (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" r2/account
                          "/r2/buckets/" r2/bucket "/objects/" key)
                     #js {:method "GET"
                          :signal (dl/signal)
                          :headers #js {"Authorization" (str "Bearer " t)
                                        "Range" "bytes=0-3"}})
           (fn [r]
             (if (.-ok r)
               (.then (.arrayBuffer r)
                      (fn [ab]
                        (let [got (bytes->str (js/Buffer.from ab) 4)]
                          (if (= got expected-prefix)
                            {:ok? true}
                            {:ok? false :error :readback/mismatch
                             :detail (str "readback prefix " (pr-str got)
                                          " != " (pr-str expected-prefix))}))))
               {:ok? false :error :readback/failed
                :detail (str "readback status " (.-status r))})))
    (js/Promise.resolve {:ok? false :error :r2/no-credential})))

;; -- step 3: store -------------------------------------------------------------

(defn- object-key [asset]
  (str "otent/natural-earth/" (:id asset) "/"
       (subs (:sha256 asset) 0 12) "/" (:id asset) ".zip"))

(defn- manifest-key [asset]
  (str "otent/natural-earth/" (:id asset)
       "/manifest-" (.replace (subs (iso-now) 0 19) ":" "") ".json"))

(defn- then-check
  "Run `next-step` only when `p` resolves {:ok? true}; otherwise pass the
  refusal through with its exit code."
  [p next-step]
  (.then p (fn [result]
             (if (:ok? result)
               (next-step)
               (js/Promise.resolve {:ok? false :code 2
                                    :error (:error result)
                                    :detail (:detail result)})))))

(defn- store!
  "PUT the zip, read it back, then PUT the manifest. Each step reports
  its own refusal; nothing downstream runs after one fails.

  Resolves {:ok? true} or {:ok? false :code n :error ...}."
  [asset buf bytes]
  (let [key (object-key asset)
        mkey (manifest-key asset)
        m (ne/manifest asset {:key key :retrieved-at (iso-now) :bytes bytes})
        manifest-step (fn []
                        (then-check
                         (.then (.stringify js/JSON (clj->js m) nil 2)
                                (fn [s] (r2/put! mkey s "application/json")))
                         (fn [mp]
                           (if (:ok? mp)
                             (do (println (str "OK object=" key
                                               " bytes=" bytes
                                               " sha256=" (:sha256 asset)
                                               " manifest=" mkey))
                                 (js/Promise.resolve {:ok? true}))
                             (js/Promise.resolve {:ok? false :code 2
                                                  :error (:error mp) :detail (:detail mp)})))))
        readback-step (fn [] (then-check (readback! key ne/zip-magic)
                                         (fn [] (manifest-step))))]
    (then-check
     (r2/put! key buf (:object-format asset))
     readback-step)))

;; -- entry point ----------------------------------------------------------------

(defn- go [id]
  (let [{:keys [ok? asset error detail]} (ne/plan id)]
    (if-not ok?
      (refuse! 1 {:error error :detail detail})
      (.then (fetch-and-check! asset)
             (fn [res]
               (if-not (:ok? res)
                 (refuse! 1 res)
                 (.then (store! asset (:buf res) (:bytes res))
                        (fn [done]
                          (when-not (:ok? done)
                            (refuse! (or (:code done) 2) done))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args]; the
;; asset id is the last argument.
(let [arg (last (js->clj js/process.argv))]
  (if (str/blank? arg)
    (do (println "usage: nbb --classpath src bin/natural_earth.cljs ASSET-ID")
        (println (str "  catalogued assets: " (str/join " " (sort (keys ne/assets)))))
        (set! (.-exitCode js/process) 1))
    (go arg)))
