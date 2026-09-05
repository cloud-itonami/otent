#!/usr/bin/env nbb
;; Derived heading/panorama coverage table over Mapillary /images
;; metadata — one source, one area, one derived task per run.
;;
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src:../../kotoba-lang/com-mapillary-graph-api/src \
;;     bin/street_heading.cljs --bbox W S E N
;;   nbb --classpath src:../../kotoba-lang/com-mapillary-graph-api/src \
;;     bin/street_heading.cljs --fixture payload.json --bbox W S E N
;;
;; The upstream normalization (privacy gate: curated metadata fields,
;; redaction check, lon/lat order, unknowns visible) belongs to
;; otent.mapillary-images and is reused unchanged. This script derives
;; the heading histogram + panorama count and stores BOTH the
;; observations and the derived table with full provenance. Without
;; $CF_CATALOG_TOKEN nothing is written (exit 2): a refusal to write is
;; reported, never faked.
;;
;; Exit 0 ingested · 1 refused (bounds violated / upstream changed) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.street-heading
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.mapillary-images :as mimg]
            [com-mapillary-graph-api.core :as mi]
            [otent.r2 :as r2]
            [otent.street-heading :as sh]))

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
          "--fixture" (recur (nnext a) (assoc m :fixture (second a)))
          nil))
      m)))

;; -- step 1: fetch (or load fixture) and hash -------------------------

(defn- fetch-payload!
  [{:keys [bbox]}]
  (let [{:keys [ok? error detail]} (mimg/check-bbox bbox)]
    (if-not ok?
      (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
      (let [token (.-MAPILLARY_ACCESS_TOKEN js/process.env)]
        (if-not token
          (js/Promise.resolve
           {:ok? false :code 2 :error :mapillary/no-credential
            :detail "MAPILLARY_ACCESS_TOKEN is not set: a 401 must never be misread as an empty tile, and no data is invented"})
          (let [{:keys [ok? error detail request]} (mimg/build-request bbox)]
            (if-not ok?
              (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
              (do (println (str "GET (token in Authorization header, never the URL)"))
                  (-> (js/fetch (str (:url request))
                                #js {:method "GET"
                                     :signal (dl/signal)
                                     :headers #js {"Authorization" (mi/authorization-header token)}})
                      (.then (fn [r]
                               (if-not (.-ok r)
                                 (js/Promise.resolve
                                  {:ok? false :code 2 :error :mapillary/fetch-failed
                                   :detail (str "status " (.-status r) " from the Graph API")})
                                 (.then (.text r)
                                        (fn [txt]
                                          (js/Promise.resolve
                                           {:ok? true
                                            :buf (js/Buffer.from txt "utf8")
                                            :payload (js/JSON.parse txt)}))))))
                      (.catch (fn [e]
                                (js/Promise.resolve {:ok? false :code 2
                                                     :error :mapillary/unreachable
                                                     :detail (str (.-message e))}))))))))))))

(defn- load-fixture!
  [{:keys [fixture bbox]}]
  (if (or (not fixture) (not bbox))
    (js/Promise.resolve
     {:ok? false :code 2 :error :mapillary/missing-args
      :detail "usage: --bbox W S E N [--fixture payload.json]"})
    (let [{:keys [ok? error detail]} (mimg/check-bbox bbox)]
      (if-not ok?
        (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
        (try
          (let [txt (str (fs/readFileSync fixture "utf8"))]
            (js/Promise.resolve
             {:ok? true :buf (js/Buffer.from txt "utf8")
              :payload (js/JSON.parse txt)}))
          (catch :default e
            (js/Promise.resolve {:ok? false :code 2 :error :mapillary/fixture-unreadable
                                 :detail (str (.-message e))})))))))

;; -- step 2: normalize (upstream gates) + derive ----------------------

(defn- normalize-and-derive!
  [payload bbox retrieved-at input-sha256 request-url]
  (let [result (mimg/normalize-payload (js->clj payload) {:bbox bbox :retrieved-at retrieved-at})]
    (if-not (:ok? result)
      (js/Promise.resolve {:ok? false :code 1 :error (:error result) :detail (:detail result)})
      (do (println (str "counts: fetched=" (:fetched (:counts result))
                        " accepted=" (:accepted (:counts result))
                        " refused=" (:refused (:counts result))
                        " outside-bbox=" (:returned-outside-bbox (:counts result))
                        " links-next=" (:links-next (:counts result))))
          (doseq [r (:refusals result)]
            (println (str "  REFUSED " (name (:error r)) ": " (:detail r))))
          (let [prov (mimg/provenance {:area-id (:area-id (mimg/check-bbox bbox))
                                       :bbox bbox
                                       :retrieved-at retrieved-at
                                       :input-sha256 input-sha256
                                       :request-url request-url})
                table (sh/heading-table {:observations (:observations result)
                                         :counts (:counts result)
                                         :provenance prov})
                derived-prov (sh/provenance prov {:run-at retrieved-at})
                checks (sh/provenance-checks table)]
            (if-not (:ok? checks)
              (js/Promise.resolve {:ok? false :code 1 :error (:error checks) :detail (:detail checks)})
              (do (println (str "heading: " (pr-str (:table/heading-histogram table))
                                " heading-unknown=" (:heading-unknown (:table/images table))
                                " panorama=" (:panorama (:table/images table))))
                  (js/Promise.resolve
                   {:ok? true
                    :area-id (:area-id (mimg/check-bbox bbox))
                    :result result
                    :table table
                    :derived-prov derived-prov
                    :prov prov}))))))))

;; -- step 3: store (only with a credential) ---------------------------

(defn- object-key [area-id]
  (str "otent/street-heading/" area-id "/heading-"
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
  [{:keys [area-id result table derived-prov prov]}]
  (let [doc (clj->js {:provenance prov
                      :derived-provenance derived-prov
                      :counts (:counts result)
                      :refusals (:refusals result)
                      :observations (:observations result)
                      :derived-table table})
        body (js/JSON.stringify doc nil 2)
        key (object-key area-id)]
    (-> (r2/put! key body "application/json")
        (.then (fn [p]
                 (if-not (:ok? p)
                   (js/Promise.resolve {:ok? false :code 2 :error (:error p) :detail (:detail p)})
                   (readback! key "{\"prov")))))))

;; -- entry point --------------------------------------------------------

(defn- go [args]
  (let [retrieved-at (iso-now)]
    (-> ((if (:fixture args) load-fixture! fetch-payload!) args)
        (.then (fn [{:keys [ok? code error detail buf payload]}]
                 (if-not ok?
                   (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                   (let [sha (sha256 buf)
                         {:keys [ok? error detail]} (mimg/check-bbox (:bbox args))]
                     (if-not ok?
                       (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
                       (do (println (str "input sha256=" sha))
                           (-> (normalize-and-derive! payload (:bbox args) retrieved-at sha nil)
                               (.then (fn [res]
                                        (if-not (:ok? res)
                                          res
                                          (store! res))))))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args] — find
;; this script by name and take what follows.
(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a]
                                 (when (str/ends-with? a "street_heading.cljs") idx))
                               argv))]
    (if i (drop (inc i) argv) (drop 3 argv))))

(.then (go (parse-args (vec (script-args))))
       (fn [{:keys [ok? code error detail]}]
         (if ok?
           (println "OK")
           (refuse! (or code 2) {:error error :detail detail}))))
