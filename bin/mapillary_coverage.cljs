#!/usr/bin/env nbb
;; Derived temporal-coverage (vintage) table over Mapillary street
;; imagery METADATA — one source, one area, one derived task per run.
;;
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/mapillary_coverage.cljs \
;;     --bbox W S E N
;;   nbb --classpath src bin/mapillary_coverage.cljs --fixture payload.json \
;;     --bbox W S E N
;;
;; The upstream normalization (metadata only, no pixel; thumbnails
;; never requested; redaction check; bbox filter; unknowns visible)
;; belongs to otent.mapillary-images and is reused unchanged. This
;; script derives the coverage table and stores BOTH the observations
;; and the derived table with full provenance. Without $CF_CATALOG_TOKEN
;; nothing is written (exit 2): a refusal to write is reported, never
;; faked.
;;
;; Exit 0 ingested · 1 refused (bounds violated / upstream changed) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.mapillary-coverage
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.mapillary-images :as mimg]
            [otent.mapillary-coverage :as mc]
            [otent.r2 :as r2]
            [com-mapillary-graph-api.core :as mi]))

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
          "--area-id" (recur (nnext a) (assoc m :area-id (second a)))
          "--fixture" (recur (nnext a) (assoc m :fixture (second a)))
          nil))
      m)))

(defn- token! []
  (some-> js/process .-env .-MAPILLARY_ACCESS_TOKEN str/trim not-empty))

;; -- step 1: fetch (or load fixture) and hash -------------------------

(defn- fetch-payload!
  "GET /images once through the registered client's request. The token
  goes in the Authorization header -- never the query string. The URL
  printed carries only coordinates and the field list."
  [{:keys [bbox]}]
  (let [{:keys [ok? error detail request]} (mimg/build-request bbox)]
    (if-not ok?
      (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
      (let [token (token!)]
        (if (str/blank? token)
          (js/Promise.resolve
           {:ok? false :code 2 :error :mapillary/no-credential
            :detail "MAPILLARY_ACCESS_TOKEN is not set -- a 401 must never be misread as an empty tile, and no data is invented"})
          (let [url (str (:url request) "?" (->> (:query-params request)
                                                 (map (fn [[k v]] (str k "=" v)))
                                                 (str/join "&")))]
            (println (str "GET " (:url request)
                          " (token in Authorization header, not the URL)"))
            (-> (js/fetch url #js {:method "GET"
                                   :signal (dl/signal)
                                   :headers (clj->js (mi/authorization-header token))})
                (.then (fn [r]
                         (if-not (.-ok r)
                           (.then (.text r)
                                  (fn [txt]
                                    (let [body (subs txt 0 (min 200 (count txt)))]
                                      (js/Promise.resolve
                                       {:ok? false :code 2 :error :mapillary/fetch-failed
                                        :detail (str "status " (.-status r)
                                                     " body: " body)}))))
                           (.then (.text r)
                                  (fn [txt]
                                    (js/Promise.resolve
                                     {:ok? true
                                      :buf (js/Buffer.from txt "utf8")
                                      :payload (js/JSON.parse txt)})))))
                 (.catch (fn [e]
                          (js/Promise.resolve {:ok? false :code 2
                                               :error :mapillary/unreachable
                                               :detail (str (.-message e))})))))))))))

(defn- load-fixture!
  [{:keys [fixture bbox]}]
  (if (or (not fixture) (not bbox))
    (js/Promise.resolve
     {:ok? false :code 2 :error :mapillary/missing-args
      :detail "usage: --bbox W S E N [--fixture payload.json] [--area-id area]"})
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
  (let [result (mimg/normalize-payload (js->clj payload :keywordize-keys false)
                                       {:bbox bbox :retrieved-at retrieved-at})]
    (if-not (:ok? result)
      (js/Promise.resolve {:ok? false :code 1 :error (:error result) :detail (:detail result)})
      (do (println (str "counts: fetched=" (get-in result [:counts :fetched])
                        " accepted=" (get-in result [:counts :accepted])
                        " refused=" (get-in result [:counts :refused])
                        " outside-bbox=" (get-in result [:counts :returned-outside-bbox])
                        " next=" (boolean (get-in result [:counts :links-next]))))
          (doseq [r (:refusals result)]
            (println (str "  REFUSED " (name (:error r)) ": " (:detail r))))
          (let [checked (mimg/check-bbox bbox)
                prov (mimg/provenance {:area-id (:area-id checked)
                                       :bbox bbox
                                       :retrieved-at retrieved-at
                                       :input-sha256 input-sha256
                                       :request-url request-url})
                table (mc/vintage-table {:observations (:observations result)
                                         :counts (:counts result)
                                         :provenance prov})
                derived-prov (mc/provenance prov {:run-at retrieved-at})]
            (println (str "vintage: " (pr-str (:table/capture-span table))
                          " capture-unknown=" (:capture-unknown (:table/images table))))
            (js/Promise.resolve
             {:ok? true
              :area-id (:area-id checked)
              :bbox bbox
              :retrieved-at retrieved-at
              :result result
              :table table
              :derived-prov derived-prov}))))))

;; -- step 3: store (only with a credential) and read back -------------

(defn- object-key [area-id]
  (str "otent/mapillary-coverage/" area-id "/vintage-"
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
  [{:keys [area-id bbox retrieved-at result table derived-prov input-sha256 request-url]}]
  (let [prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at retrieved-at
                               :input-sha256 input-sha256
                               :request-url request-url})
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

(defn- main [args]
  (let [retrieved-at (iso-now)]
    (-> ((if (:fixture args) load-fixture! fetch-payload!) args)
        (.then (fn [{:keys [ok? code error detail buf payload]}]
                 (if-not ok?
                   (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                   (let [sha (sha256 buf)
                         checked (mimg/check-bbox (:bbox args))]
                     (if-not (:ok? checked)
                       (js/Promise.resolve {:ok? false :code 1 :error (:error checked) :detail (:detail checked)})
                       (let [req (:request (mimg/build-request (:bbox args)))]
                         (println (str "input sha256=" sha))
                         (-> (normalize-and-derive! payload (:bbox args) retrieved-at sha (:url req))
                             (.then (fn [res]
                                      (if-not (:ok? res)
                                        res
                                        (store! (assoc res
                                                       :input-sha256 sha
                                                       :request-url (:url req))))))))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args] -- find
;; this script by name and take what follows.
(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a]
                                 (when (str/ends-with? a "mapillary_coverage.cljs") idx))
                               argv))]
    (if i (drop (inc i) argv) (drop 3 argv))))

(.then (main (parse-args (vec (script-args))))
       (fn [{:keys [ok? code error detail]}]
         (if ok?
           (println "OK")
           (refuse! (or code 2) {:error error :detail detail}))))
