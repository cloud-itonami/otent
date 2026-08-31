(ns cloud-obs
  "One bounded analysis pass over the already-licensed Otent imagery asset.

    nbb --classpath src bin/cloud_obs.cljs --source viirs-noaa20-truecolor \\
        --date 2026-08-31 --max-zoom 4 --out ledger/cloud-obs

  The pure half lives in `otent.cloud-obs` (and `otent.basemap` for the
  plan); this file is only I/O: fetch the tiles the ingest bound allows,
  decode them, estimate cloud cover, and write the derived table.
  Failures become :failed rows -- they are never dropped, because a
  table that hides its holes is not a table, it is a claim."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            ["jpeg-js" :as jpeg]
            [clojure.string :as str]
            [otent.basemap :as bm]
            [otent.cloud-obs :as co]))

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- opt [name default]
  (let [args (js->clj (.-argv js/process))
        i (.indexOf args name)]
    (if (and (pos? i) (< (inc i) (count args)))
      (nth args (inc i))
      default)))

(defn- artifact-hash []
  ;; the analysis source IS the artifact: hash it, so the run manifest
  ;; pins exactly the code that produced the table
  (sha256 (fs/readFileSync (path/join "src" "otent" "cloud_obs.cljs"))))

;; ------------------------------------------------------------------ I/O

(defn- fetch-bytes [url]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-cloud-obs/0.1 (cloud-itonami)"}})
      (.then (fn [r]
               (if-not (.-ok r)
                 {:ok? false :error :source/http-error :detail (str (.-status r) " from " url)}
                 (.then (.arrayBuffer r)
                        (fn [ab] {:ok? true :buf (js/Buffer.from ab)})))))
      (.catch (fn [e] {:ok? false :error :source/unreachable :detail (str (.-message e))}))))

(defn- classify-one! [{:keys [tile url key]} source date]
  (-> (fetch-bytes url)
      (.then (fn [r]
               (if-not (:ok? r)
                 (co/failed-observation tile key (:error r) (:detail r))
                 (try
                   (let [img (.decode jpeg (:buf r) #js {:useTArray true})
                         marks (:marks (co/cell-marks {:width (.-width img) :height (.-height img)
                                                       :data (.-data img)}
                                                      (get-in co/model [:params :sample-grid])))
                         prov (co/provenance-record {:source source :date date :tile tile
                                                     :sha256 (sha256 (:buf r)) :key key}
                                                    (artifact-hash) "node/nbb (ClojureScript)")]
                     (co/observation prov marks))
                 (catch :default e
                   (co/failed-observation tile key :decode/jpeg (str (.-message e))))))))))

(def conc 8)

(defn- run! [{:keys [source date tiles] :as plan} out-dir max-z]
  (let [start (js/Date.now)
        _ (fs/mkdirSync out-dir #js {:recursive true})
        rows (atom [])
        queue (atom (vec tiles))
        done (atom 0)
        total (count tiles)]
    (log "cloud-obs:" (:id source) "capture date" date
         "tiles" (count tiles) "bound z" max-z)
    (letfn [(next-tile! []
              (let [[t] (swap-vals! queue #(if (seq %) (subvec % 1) %))]
                (first t)))
            (worker []
              (if-let [t (next-tile!)]
                (-> (classify-one! t source date)
                    (.then (fn [row]
                             (swap! rows conj row)
                             (let [n (swap! done inc)]
                               (when (zero? (mod n 25))
                                 (log "  " n "/" total (str " (" (count (filter #(= :failed (:observation-status %)) @rows)) " failed)"))))
                             (worker))))
                (js/Promise.resolve nil)))]
      (-> (js/Promise.all (clj->js (repeatedly conc worker)))
          (.then (fn [_]
                   (let [rows* (sort-by (comp (juxt :z :x :y) :tile :provenance) @rows)
                         elapsed (- (js/Date.now) start)
                         summary (co/run-summary {:source source :date date :max-z max-z}
                                                 (artifact-hash) (str "node " js/process.version) @rows elapsed)]
                     ;; readback: the table we are about to claim is the
                     ;; table on disk, byte for byte
                     (fs/writeFileSync (path/join out-dir "observations.jsonl")
                                       (str/join "\n" (map #(js/JSON.stringify (clj->js %)) rows*)))
                     (fs/writeFileSync (path/join out-dir "run.json")
                                       (js/JSON.stringify (clj->js summary) nil 2))
                     (let [back (->> (str/split (str (fs/readFileSync (path/join out-dir "observations.jsonl") "utf8")) "\n")
                                     (remove str/blank?)
                                     (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)))]
                       (log "readback:" (count back) "rows," (:tile-count summary) "in summary")
                       (if-not (= (count back) (:tile-count summary))
                         (do (log "REFUSING: the table on disk and the manifest disagree")
                             (js/process.exit 2))
                         (log "committed" (get-in summary [:counts :committed]) "observed,"
                              (get-in summary [:counts :failed]) "failed,"
                              (:unknown summary) "unknown"
                              "->" out-dir))))))))))

(defn- main []
  (let [source-id (opt "--source" "viirs-noaa20-truecolor")
        date (opt "--date" nil)
        max-z (js/parseInt (opt "--max-zoom" "4") 10)
        out (opt "--out" "ledger/cloud-obs")
        source (bm/source-for source-id)
        refusal (co/analysis-refusal source max-z date)]
    (if refusal
      (do (log "REFUSING:" (name (:refusal refusal)) "--" (:detail refusal))
          (js/process.exit 2))
      (let [plan (bm/ingest-plan source-id max-z date)]
        (if-not (:ok? plan)
          (do (log "REFUSING:" (name (:refusal plan)) "--" (:detail plan))
              (js/process.exit 2))
          (run! plan out max-z))))))

(main)
