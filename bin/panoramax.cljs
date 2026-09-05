#!/usr/bin/env nbb
;; Ingest one bounded area of Panoramax open street imagery (STAC items)
;; into R2, with the checks otent.panoramax declares.
;;
;;   nbb --classpath src bin/panoramax.cljs --bbox 139.765 35.678 139.77 35.682
;;   nbb --classpath src bin/panoramax.cljs --fixture payload.json --bbox W S E N
;;
;; One source, one area per invocation. Metadata only: no pixel is
;; fetched or stored (:no-raw-image-republication-without-rights); the
;; canonical provider URLs are recorded instead. The response bytes are
;; hashed before anything else, so provenance survives even a refusal.
;;
;; Exit 0 ingested · 1 refused (bounds violated / upstream changed) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.panoramax
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.panoramax :as px]
            [otent.r2 :as r2]))

(defn- sha256 [^js buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- iso-now []
  (.toISOString (js/Date.)))

(defn- refuse! [code m]
  (println (str "REFUSED " (name (:error m)) ": " (:detail m)))
  (js/process.exit code))

(defn- parse-args [args]
  (loop [a args m {}]
    (if (seq a)
      (let [x (first a)]
        (case x
          "--bbox" (recur (drop 5 a) (assoc m :bbox (mapv js/parseFloat (take 4 (rest a)))))
          "--limit" (recur (nnext a) (assoc m :limit (js/parseInt (second a) 10)))
          "--fixture" (recur (nnext a) (assoc m :fixture (second a)))
          nil))
      m)))

;; -- step 1: fetch (or load fixture) and hash ---------------------------------

(defn- fetch-payload!
  "GET the provider search once. The URL carries only coordinates and a
  limit — no credential, no secret (this API is anonymous). Resolves
  {:ok? true :buf buf :payload js-object} or a refusal."
  [{:keys [bbox limit]}]
  (let [{:keys [ok? error detail]} (px/check-bbox bbox)]
    (if-not ok?
      (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
      (let [[w s e n] bbox
            url (str px/api-url "?bbox=" w "," s "," e "," n
                     "&limit=" (or limit px/max-results))]
        (println (str "GET " url " (anonymous open data; no credential involved)"))
        (-> (js/fetch url #js {:method "GET" :signal (dl/signal)})
            (.then (fn [r]
                     (if-not (.-ok r)
                       (js/Promise.resolve
                        {:ok? false :code 2 :error :panoramax/fetch-failed
                         :detail (str "status " (.-status r) " from " px/api-url)})
                       (.then (.text r)
                              (fn [txt]
                                (js/Promise.resolve
                                 {:ok? true
                                  :buf (js/Buffer.from txt "utf8")
                                  :payload (js/JSON.parse txt)}))))))
            (.catch (fn [e]
                      (js/Promise.resolve {:ok? false :code 2
                                           :error :panoramax/unreachable
                                           :detail (str (.-message e))}))))))))

(defn- load-fixture!
  [{:keys [fixture bbox]}]
  (if (or (not fixture) (not bbox))
    (js/Promise.resolve
     {:ok? false :code 2 :error :panoramax/missing-args
      :detail "usage: --bbox W S E N [--fixture payload.json] [--limit N]"})
    (let [{:keys [ok? error detail]} (px/check-bbox bbox)]
      (if-not ok?
        (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
        (try
          (let [txt (str (fs/readFileSync fixture "utf8"))]
            (js/Promise.resolve
             {:ok? true :buf (js/Buffer.from txt "utf8")
              :payload (js/JSON.parse txt)}))
          (catch :default e
            (js/Promise.resolve {:ok? false :code 2 :error :panoramax/fixture-unreadable
                                 :detail (str (.-message e))})))))))

;; -- step 2: normalize ---------------------------------------------------------

(defn- normalize!
  "Normalize the payload against the declared bounds. A bad envelope is a
  refusal; per-item refusals are counted, never dropped."
  [payload bbox retrieved-at]
  (let [result (px/normalize-payload (js->clj payload) {:bbox bbox :retrieved-at retrieved-at})]
    (if-not (:ok? result)
      (js/Promise.resolve {:ok? false :code 1 :error (:error result) :detail (:detail result)})
      (do (println (str "counts: fetched=" (get-in result [:counts :fetched])
                        " accepted=" (get-in result [:counts :accepted])
                        " refused=" (get-in result [:counts :refused])
                        " outside-bbox=" (get-in result [:counts :returned-outside-bbox])
                        " next=" (boolean (get-in result [:counts :links-next]))))
          (doseq [r (:refusals result)]
            (println (str "  REFUSED " (name (:error r)) ": " (:detail r))))
          (js/Promise.resolve {:ok? true :result result})))))

;; -- step 3: store (only with a credential) ------------------------------------

(defn- object-key [area-id]
  (str "otent/panoramax/" area-id "/observations-"
       (.replace (subs (iso-now) 0 19) ":" "") ".json"))

(defn- readback! [key expected-prefix]
  (if-let [t (r2/token)]
    (-> (js/fetch (str "https://api.cloudflare.com/client/v4/accounts/" r2/account
                       "/r2/buckets/" r2/bucket "/objects/" key)
                  #js {:method "GET"
                       :signal (dl/signal)
                       :headers #js {"Authorization" (str "Bearer " t)
                                     "Range" "bytes=0-7"}})
        (.then (fn [r]
                 (if (.-ok r)
                   (.then (.text r)
                          (fn [got]
                            (if (= got expected-prefix)
                              {:ok? true}
                              {:ok? false :code 2 :error :readback/mismatch
                               :detail (str "readback prefix " (pr-str got))})))
                   {:ok? false :code 2 :error :readback/failed
                    :detail (str "readback status " (.-status r))})))
        (.catch (fn [e] {:ok? false :code 2 :error :readback/failed
                         :detail (str (.-message e))})))
    (js/Promise.resolve
     {:ok? false :code 2 :error :r2/no-credential
      :detail "$CF_CATALOG_TOKEN is not set: nothing was written, which is not the same as writing nothing"})))

(defn- store!
  [area-id bbox retrieved-at input-sha256 result]
  (let [prov (px/provenance {:area-id area-id :bbox bbox
                             :retrieved-at retrieved-at
                             :input-sha256 input-sha256})
        doc (clj->js {:provenance prov
                      :counts (:counts result)
                      :refusals (:refusals result)
                      :observations (:observations result)})
        body (js/JSON.stringify doc nil 2)
        key (object-key area-id)]
    (-> (r2/put! key body "application/json")
        (.then (fn [p]
                 (if-not (:ok? p)
                   (js/Promise.resolve {:ok? false :code 2 :error (:error p) :detail (:detail p)})
                   (readback! key "{\"prov")))))))

;; -- entry point ----------------------------------------------------------------

(defn- go [args]
  (let [retrieved-at (iso-now)]
    (-> ((if (:fixture args) load-fixture! fetch-payload!) args)
        (.then (fn [{:keys [ok? code error detail buf payload]}]
                 (if-not ok?
                   (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                   (let [sha (sha256 buf)
                         {:keys [ok? error detail]} (px/check-bbox (:bbox args))]
                     (if-not ok?
                       (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
                       (do (println (str "input sha256=" sha))
                           (-> (normalize! payload (:bbox args) retrieved-at)
                               (.then (fn [{:keys [ok? code error detail result]}]
                                        (if-not ok?
                                          (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                                          (store! (:area-id (px/check-bbox (:bbox args)))
                                                  (:bbox args) retrieved-at sha result))))))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args] — find
;; this script by name and take what follows, like `bin/kartaview.cljs`
;; does.
(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a]
                                 (when (str/ends-with? a "panoramax.cljs") idx))
                               argv))]
    (if i (drop (inc i) argv) (drop 3 argv))))

(.then (go (parse-args (vec (script-args))))
       (fn [{:keys [ok? code error detail]}]
         (if ok?
           (println "OK")
           (refuse! (or code 2) {:error error :detail detail}))))
