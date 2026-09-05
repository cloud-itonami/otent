#!/usr/bin/env nbb
;; Derived temporal-coverage (vintage) table over Panoramax open
;; street imagery metadata — one source, one area, one derived task
;; per run.
;;
;;   nbb --classpath src bin/panorama_coverage.cljs --bbox 139.765 35.678 139.77 35.682
;;   nbb --classpath src bin/panorama_coverage.cljs --fixture payload.json --bbox W S E N
;;
;; The upstream normalization (privacy gate: status=ready,
;; visibility=anyone, per-item licence, uploader EXIF redacted; GeoJSON
;; lon/lat; unknowns visible) belongs to otent.panoramax and is reused
;; unchanged. This script derives the vintage table and stores BOTH
;; the observations and the derived table with full provenance.
;; Without $CF_CATALOG_TOKEN nothing is written (exit 2): a refusal to
;; write is reported, never faked.
;;
;; Exit 0 ingested · 1 refused (bounds violated / upstream changed) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.panorama-coverage
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.panoramax :as px]
            [otent.panorama-coverage :as pxc]
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

;; -- step 1: fetch (or load fixture) and hash -------------------------

(defn- fetch-payload!
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

;; -- step 2: normalize (upstream gates) + derive ----------------------

(defn- normalize-and-derive!
  [payload bbox retrieved-at input-sha256]
  (let [result (px/normalize-payload (js->clj payload) {:bbox bbox :retrieved-at retrieved-at})]
    (if-not (:ok? result)
      (js/Promise.resolve {:ok? false :code 1 :error (:error result) :detail (:detail result)})
      (do (println (str "counts: fetched=" (:fetched (:counts result))
                        " accepted=" (:accepted (:counts result))
                        " refused=" (:refused (:counts result))
                        " outside-bbox=" (:returned-outside-bbox (:counts result))
                        " next-link=" (some? (:links-next (:counts result)))))
          (doseq [r (:refusals result)]
            (println (str "  REFUSED " (name (:error r)) ": " (:detail r))))
          (let [prov (px/provenance {:area-id (:area-id (px/check-bbox bbox))
                                     :bbox bbox
                                     :retrieved-at retrieved-at
                                     :input-sha256 input-sha256})
                table (pxc/vintage-table {:observations (:observations result)
                                          :counts (:counts result)
                                          :provenance prov})
                derived-prov (pxc/provenance prov {:run-at retrieved-at})
                span (:table/capture-span table)]
            (println (str "vintage: accepted=" (get-in table [:table/pictures :accepted])
                          " capture-known=" (get-in table [:table/pictures :capture-known])
                          " capture-unknown=" (get-in table [:table/pictures :capture-unknown])
                          (when (map? span)
                            (str " earliest=" (:earliest-published span)
                                 " latest=" (:latest-published span)))))
            (js/Promise.resolve
             {:ok? true
              :sha input-sha256
              :area-id (:area-id (px/check-bbox bbox))
              :bbox bbox
              :retrieved-at retrieved-at
              :result result
              :table table
              :derived-prov derived-prov}))))))

;; -- step 3: store (only with a credential) ---------------------------

(defn- object-key [area-id]
  (str "otent/street-px-vintage/" area-id "/vintage-"
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
  [{:keys [area-id bbox retrieved-at result table derived-prov]}]
  (let [prov (px/provenance {:area-id area-id :bbox bbox
                             :retrieved-at retrieved-at
                             :input-sha256 (:sha result)})
        doc (clj->js {:provenance prov
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
                         {:keys [ok? error detail]} (px/check-bbox (:bbox args))]
                     (if-not ok?
                       (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
                       (do (println (str "input sha256=" sha))
                           (-> (normalize-and-derive! payload (:bbox args) retrieved-at sha)
                               (.then (fn [res]
                                        (if-not (:ok? res)
                                          res
                                          (store! res))))))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args] — find
;; this script by name and take what follows.
(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a]
                                 (when (str/ends-with? a "panorama_coverage.cljs") idx))
                               argv))]
    (if i (drop (inc i) argv) (drop 3 argv))))

(.then (go (parse-args (vec (script-args))))
       (fn [{:keys [ok? code error detail]}]
         (if ok?
           (println "OK")
           (refuse! (or code 2) {:error error :detail detail}))))
