(ns compare
  "One bounded change-observation pass between two capture dates of the
   already-licensed Otent imagery asset.

    nbb --classpath src bin/compare.cljs --source modis-terra-truecolor \\\\
        --from 2026-08-29 --to 2026-08-30 --max-zoom 4 --out ledger/change

  The pure half lives in `otent.change` (per-date conditions come from
  `otent.analysis`); this file is only I/O: fetch each tile on BOTH
  dates, decode, measure, and write the derived table. A date whose
  fetch or decode fails becomes a :failed row -- it is never read as
  no-change, because a table that fills its holes with `nothing
  happened` is not a table, it is a cover story."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            ["jpeg-js" :as jpeg]
            [clojure.string :as str]
            [otent.basemap :as bm]
            [otent.analysis :as an]
            [otent.change :as ch]))

(defn- log [& xs] (binding [*print-fn* *print-err-fn*] (apply println xs)))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- artifact-hash [f]
  (sha256 (fs/readFileSync f)))

(defn- fetch-bytes [url]
  (-> (js/fetch url #js {:headers #js {"user-agent" "otent-change/0.1 (cloud-itonami)"}})
      (.then (fn [r]
               (if-not (.-ok r)
                 {:ok? false :error :source/http-error :detail (str (.-status r) " from " url)}
                 (.then (.arrayBuffer r)
                        (fn [ab] {:ok? true :buf (js/Buffer.from ab)})))))
      (.catch (fn [e] {:ok? false :error :source/unreachable :detail (str (.-message e))}))))

(defn- fetch-and-measure! [source date tile]
  (let [url (bm/tile-url source tile date)
        key (bm/tile-key source tile date)]
    (-> (fetch-bytes url)
        (.then (fn [r]
                 (if-not (:ok? r)
                   {:date date :key key :error (:error r) :detail (:detail r)}
                   (try
                     (let [img (.decode jpeg (:buf r) #js {:useTArray true})
                           metrics (an/tile-metrics {:width (.-width img) :height (.-height img)
                                                     :data (.-data img)}
                                                    (get-in an/model [:params :sample-grid]))]
                       {:date date :key key :sha256 (sha256 (:buf r)) :metrics metrics})
                     (catch :default e
                       {:date date :key key :error :decode/jpeg :detail (str (.-message e))}))))))))

(defn- compare-one! [source date-from date-to tile]
  (let [key-a (bm/tile-key source tile date-from)
        key-b (bm/tile-key source tile date-to)]
    (-> (js/Promise.all (clj->js [(fetch-and-measure! source date-from tile)
                                  (fetch-and-measure! source date-to tile)]))
        (.then (fn [arr]
                 (let [a (aget arr 0), b (aget arr 1)]
                   (if (or (:error a) (:error b))
                     (ch/failed-change-observation tile (:key a) (:key b)
                                                   (or (:error a) (:error b))
                                                   (str (or (:detail a) (:detail b))))
                     (ch/change-observation
                       {:source source :tile tile
                        :date-from date-from :date-to date-to
                        :key-a (:key a) :key-b (:key b)
                        :sha-a (:sha256 a) :sha-b (:sha256 b)
                        :metrics-a (:metrics a) :metrics-b (:metrics b)
                        :res-a (an/classify (:metrics a))
                        :res-b (an/classify (:metrics b))}
                       (artifact-hash (path/join "src" "otent" "change.cljs"))
                       (artifact-hash (path/join "src" "otent" "analysis.cljs"))
                       (str "node " js/process.version " (nbb)")))))))))

(def conc 8)

(defn- run! [{:keys [source date-from date-to tiles] :as plan} out-dir max-z]
  (let [start (js/Date.now)
        _ (fs/mkdirSync out-dir #js {:recursive true})
        rows (atom [])
        queue (atom (vec tiles))
        done (atom 0)
        total (count tiles)]
    (log "change:" (:id source) date-from ".." date-to
         "tiles" total "bound z" max-z)
    (letfn [(next-tile! []
              (let [[t] (swap-vals! queue #(if (seq %) (subvec % 1) %))]
                (first t)))
            (worker []
              (if-let [t (next-tile!)]
                (-> (compare-one! source date-from date-to t)
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
                         summary (ch/run-summary {:source source :date-from date-from
                                                  :date-to date-to :max-z max-z}
                                                 (artifact-hash (path/join "src" "otent" "change.cljs"))
                                                 rows* elapsed)]
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
                              (:inconclusive summary) "inconclusive ->" out-dir))))))))))

(defn- main []
  (let [args (js->clj (.-argv js/process))
        getopt (fn [name default] (let [i (.indexOf args name)]
                                   (if (and (pos? i) (< (inc i) (count args)))
                                     (nth args (inc i)) default)))
        source-id (getopt "--source" "modis-terra-truecolor")
        date-from (getopt "--from" nil)
        date-to (getopt "--to" nil)
        max-z (js/parseInt (getopt "--max-zoom" "4") 10)
        out (getopt "--out" "ledger/change")
        source (bm/source-for source-id)
        refusal (ch/change-refusal source max-z date-from date-to)]
    (if refusal
      (do (log "REFUSING:" (name (:refusal refusal)) "--" (:detail refusal))
          (js/process.exit 2))
      (let [plan (bm/ingest-plan source-id max-z date-from)]
        (if-not (:ok? plan)
          (do (log "REFUSING:" (name (:refusal plan)) "--" (:detail plan))
              (js/process.exit 2))
          (run! {:source source
                 :date-from date-from :date-to date-to
                 :tiles (map :tile (:tiles plan))}
                out max-z))))))

(main)
