;; Panoramax collections coverage manifest -- one bounded run.
;;
;;   nbb --classpath src:test bin/panoramax_coverage.cljs --fixture <payload.json>
;;   nbb --classpath src:test bin/panoramax_coverage.cljs --live --limit 20
;;
;; Exit 0 manifest produced · 1 refused · 2 could-not-act (no write
;; credential, or bad payload). One request; a `rel=next` link is counted,
;; never followed. No pixel URL is requested, no pixel stored.
(ns panoramax-coverage
  (:require ["fs" :as fs]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [otent.panoramax-coverage :as pc]))

(def source-url "https://api.panoramax.xyz/api/collections")

(defn- hash-bytes [buf]
  (-> (crypto/createHash "sha256")
      (.update buf)
      (.digest "hex")))

(defn- fail [code msg]
  (println (str "REFUSED " msg))
  (js/process.exit code))

(defn- read-args []
  (let [v (js->clj (.slice js/process.argv 2))]
    (loop [i 0
           acc {:live false :limit 20}]
      (if (>= i (count v))
        acc
        (let [k (nth v i)]
          (cond
            (= k "--fixture") (recur (+ i 2) (assoc acc :fixture (nth v (inc i))))
            (= k "--live") (recur (inc i) (assoc acc :live true))
            (= k "--limit") (recur (+ i 2)
                                   (assoc acc :limit
                                          (js/parseInt (nth v (inc i)) 10)))
            :else (recur (inc i) acc)))))))

(defn- fixture-run [path]
  (let [buf (fs/readFileSync path)
        payload (js->clj (js/JSON.parse (.toString buf "utf8"))
                         :keywordize-keys true)
        res (pc/analyze payload
                        {:retrieved-at (.toISOString (js/Date.))
                         :request-limit (:limit (read-args))})]
    (println (clj->js res))
    (if (empty? (str (.-CF_CATALOG_TOKEN js/process.env)))
      (do (println "nothing written: no CF_CATALOG_TOKEN")
          (js/process.exit 2))
      (js/process.exit 0))))

(defn- fetch-live [limit]
  (let [url (str source-url "?limit=" (min (max limit 1) 100))]
    (-> (js/fetch url)
        (.then (fn [r]
                 (if (.-ok r)
                   (.text r)
                   (throw (js/Error. (str "http " (.-status r)))))))
        (.then (fn [body]
                 (println (str "input sha256="
                               (hash-bytes (js/Buffer.from body "utf8"))))
                 (let [res (pc/analyze (js->clj (js/JSON.parse body)
                                                :keywordize-keys true)
                                       {:retrieved-at (.toISOString (js/Date.))
                                        :request-limit limit})]
                   (println (clj->js res))
                   (set! (.-exitCode js/process) 0))))
        (.catch (fn [e]
                  (println (str "REFUSED fetch-failed " e))
                  (set! (.-exitCode js/process) 2))))))

(defn -main []
  (let [{:keys [fixture live limit]} (read-args)]
    (cond
      fixture
      (try
        (fixture-run fixture)
        (catch :default e (fail 1 (str "bad-payload " e))))

      live
      (do (fetch-live limit) nil)

      :else (fail 1 "pass --fixture <payload.json> or --live --limit N"))))

(-main)
