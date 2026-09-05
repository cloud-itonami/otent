#!/usr/bin/env nbb
;; Ingest one bounded area of Mapillary street-imagery METADATA (Graph API
;; v4 /images, via the registered client com-mapillary-graph-api) into R2.
;;
;;   MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/mapillary_images.cljs \
;;     --bbox W S E N --area-id <area>
;;   nbb --classpath src bin/mapillary_images.cljs --fixture payload.json \
;;     --bbox W S E N
;;
;; One source, one area, one request per invocation. Metadata only: no
;; pixel is fetched or stored, and the thumbnail URL is never requested.
;; The token is read from the environment and sent in the Authorization
;; header only -- never in a URL, never in a log line, never in a stored
;; observation. The response bytes are hashed before anything else, so
;; provenance survives even a refusal.
;;
;; Exit 0 ingested · 1 refused (bounds violated / upstream changed) ·
;; 2 could not act (no credential, network unreachable, write failed).

(ns bin.mapillary-images
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.mapillary-images :as mimg]
            [otent.r2 :as r2]
            [com-mapillary-graph-api.core :as mi]))

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

;; -- step 1: fetch (or load fixture) and hash ---------------------------------

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

;; -- step 2: normalize ---------------------------------------------------------

(defn- normalize!
  [payload bbox retrieved-at]
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
          (js/Promise.resolve {:ok? true :result result})))))

;; -- step 3: store and read back -------------------------------------------------

(defn- object-key [area-id]
  (str "otent/mapillary-images/" area-id "/observations-"
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
  [area-id bbox retrieved-at input-sha256 request result]
  (let [prov (mimg/provenance {:area-id area-id :bbox bbox
                               :retrieved-at retrieved-at
                               :input-sha256 input-sha256
                               :request-url (:url request)})
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

;; -- entry point ------------------------------------------------------------------

(defn- go [args]
  (let [retrieved-at (iso-now)]
    (-> ((if (:fixture args) load-fixture! fetch-payload!) args)
        (.then (fn [{:keys [ok? code error detail buf payload]}]
                 (if-not ok?
                   (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                   (let [sha (-> (crypto/createHash "sha256") (.update buf) (.digest "hex"))
                         {:keys [ok? error detail]} (mimg/check-bbox (:bbox args))
                         req (when ok? (:request (mimg/build-request (:bbox args))))
                         area-id (or (:area-id args)
                                     (:area-id (mimg/check-bbox (:bbox args))))]
                     (if-not ok?
                       (js/Promise.resolve {:ok? false :code 1 :error error :detail detail})
                       (do (println (str "input sha256=" sha))
                           (-> (normalize! payload (:bbox args) retrieved-at)
                               (.then (fn [{:keys [ok? code error detail result]}]
                                        (if-not ok?
                                          (js/Promise.resolve {:ok? false :code code :error error :detail detail})
                                          (store! area-id (:bbox args) retrieved-at sha req result))))))))))))))

;; Under nbb, process.argv is [node, nbb, script, ...user-args] -- find
;; this script by name and take what follows, like `bin/panoramax.cljs`
;; does.
(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a]
                                 (when (str/ends-with? a "mapillary_images.cljs") idx))
                               argv))]
    (if i (drop (inc i) argv) (drop 3 argv))))

(.then (go (parse-args (vec (script-args))))
       (fn [{:keys [ok? code error detail]}]
         (if ok?
           (println "OK")
           (refuse! (or code 2) {:error error :detail detail}))))
