(ns street
  "Otent street-vision CLI: one bounded analysis pass over provider-published
  street detections (Mapillary `map_features`).

  Reproducible, offline — analyze a captured/fixture payload:
    nbb --classpath src bin/street.cljs --fixture <payload.json> --area-id <area>

  Live — one tile, token from the environment only (never in the URL):
    MAPILLARY_ACCESS_TOKEN=... nbb --classpath src bin/street.cljs --bbox W S E N --area-id <area>

  Without a token the live mode hard-fails: 空文字で叩いて 401 を『地物が無い』
  と誤読させないため. The report embeds provenance, observations, refusals and
  the counts that must stay visible. A detection here is an observation, not
  identity, ownership, inventory, availability, legal compliance or current
  existence."
  (:require [otent.street :as street]
            [clojure.string :as str]
            ["fs" :as fs]
            ["crypto" :as crypto]))

(defn- sha256 [s]
  (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn- iso-now []
  (.toISOString (js/Date.)))

(defn- fail [msg]
  (binding [*out* js/process.stderr]
    (println msg))
  (set! (.-exitCode js/process) 1))

(defn- analyze-string [raw {:keys [area-id] :as opts}]
  (let [json (js->clj (js/JSON.parse raw))
        report (street/analyze json (merge {:input-sha256 (sha256 raw)} opts))]
    (-> (clj->js report)
        (js/JSON.stringify nil 2))))

(defn- fetch-map-features [bbox object-values token]
  (let [{:keys [west south east north]} bbox
        qs (->> [["bbox" (str west "," south "," east "," north)]
                 ["object_values" (str/join "," object-values)]
                 ["fields" (str/join "," ["id" "object_value" "geometry"
                                          "first_seen_at" "last_seen_at"])]
                 ["limit" "2000"]]
                (map (fn [[k v]] (str (js/encodeURIComponent k) "=" (js/encodeURIComponent v))))
                (str/join "&"))]
    (-> (js/fetch (str "https://graph.mapillary.com/map_features?" qs)
                  #js {:method "GET"
                       :headers #js {"Authorization" (str "OAuth " token)}})
        (.then (fn [res]
                 (if (.-ok res)
                   (.text res)
                   (throw (ex-info (str "mapillary request failed with status "
                                        (.-status res)
                                        " — a refusal is not an empty tile")
                                   {:status (.-status res)}))))))))

(defn -main [& args]
  (let [[mode rest] (case (first args)
                      "--fixture" [:fixture (rest args)]
                      "--bbox" [:live (rest args)]
                      [nil args])]
    (cond
      (not mode)
      (fail "usage: street.cljs --fixture <file> [--area-id ID] | --bbox W S E N --area-id ID")

      (= :fixture mode)
      (let [path (first rest)
            area-id (or (second (drop-while #(not= "--area-id" %) rest)) "fixture")]
        (when-not (and path (fs/existsSync path))
          (fail (str "fixture file not found: " path)))
        (println (analyze-string (fs/readFileSync path "utf8")
                                 {:area-id area-id :retrieved-at (iso-now)})))

      :else
      (let [token (some-> js/process .-env .-MAPILLARY_ACCESS_TOKEN)]
        (when (or (nil? token) (= "" token))
          (fail "MAPILLARY_ACCESS_TOKEN is not set — live mode refused (no credential, no invented data)")
          (js/process.exit 1))
        (let [[w s e n] (map js/parseFloat (take 4 rest))
              area-id (or (second (drop-while #(not= "--area-id" %) rest)) "unspecified")]
          (when-not (and (number? w) (number? s) (number? e) (number? n)
                         (< w e) (< s n)
                         (< (- e w) 0.01) (< (- n s) 0.01))
            (fail "bbox must satisfy W<E, S<N and stay under Mapillary's 0.01° tile limit"))
          (-> (fetch-map-features {:west w :south s :east e :north n}
                                  (vec (sort street/taxonomy)) token)
              (.then (fn [raw]
                       (println (analyze-string raw {:area-id area-id
                                                     :retrieved-at (iso-now)}))))
              (.catch (fn [err]
                        (fail (str "refused: " (.-message err)))
                        (js/process.exit 1)))))))))

(defn- script-args []
  (let [argv (js->clj js/process.argv)
        i (first (keep-indexed (fn [idx a] (when (str/ends-with? a "street.cljs") idx)) argv))]
    (if i (drop (inc i) argv) (drop 2 argv))))

(apply -main (script-args))
