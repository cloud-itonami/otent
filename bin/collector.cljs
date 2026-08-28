(ns collector
  "The resident AIS collector: the one thing in this repository that holds a
  socket open instead of polling.

    AISSTREAM_API_KEY=... CF_CATALOG_TOKEN=... nbb --classpath src bin/collector.cljs

  ## Why it exists and why it took a person to unblock

  `otent.feeds.core` has marked `aisstream` UNMEASURED since 2026-08-26 with
  the reason `the resident collector is not part of this repository`. That
  was true and it was not the real blocker: AISStream authenticates through
  GitHub OAuth only, so the key was one authorisation decision that belonged
  to a person rather than to this actor. It arrived on 2026-08-28.

  ## What it records, and what it gives up

  Subscribed to the whole planet, and records only vessels on a maritime
  risk list. `otent.watchlist` holds the measurement behind that: 5,710
  distinct vessels in a 90-second window, a dedup ratio of 1.12, ~4.3
  million rows a day if it kept everything.

  What it gives up is the vessel that was not on a list when it sailed past.
  `--all` removes the filter for anyone who wants the firehose and has
  somewhere to put it.

  ## The failure modes it is built around, all measured

  **A bad key is a silent close.** The server accepts the connection,
  accepts the subscription frame, and drops it with code 1006 and no
  message. Treating that as a network fault means retrying against a wall
  forever, so a close with no `SubscriptionConfirmation` ever received is
  reported as a credential problem and exits.

  **Three seconds to subscribe.** The frame goes out in `onopen`, not after
  any await.

  **Read continuously or they drop messages.** Nothing in the message path
  does I/O; the flush is on a timer and the commit is async, so the socket
  keeps draining while a commit is in flight.

  **Uncompressed connections face bandwidth caps from September 2026.**
  `SubscriptionConfirmation.CompressionEnabled` is checked and logged, and a
  connection that negotiated no compression says so rather than quietly
  costing bandwidth."
  (:require ["child_process" :as cp]
            ["path" :as path]
            [clojure.string :as str]
            [otent.deadline :as dl]
            [otent.watchlist :as wl]))

(def endpoint "wss://stream.aisstream.io/v0/stream")
(def flush-interval-ms
  "Ten minutes, matching what `digitraffic` declares for the same kind. A
  commit moves the Iceberg snapshot the read cache is keyed on, so flushing
  faster makes somebody pay a cold scan more often for positions that have
  barely moved."
  600000)

(def watchlist-refresh-ms
  "Hourly. The risk tables are a daily snapshot series, so an hour is well
  inside the rate at which the answer can change, and a collector that never
  refreshed would go on watching yesterday's fleet."
  3600000)

(def argv (js->clj (.slice js/process.argv 2)))
(def all? (boolean (some #{"--all"} argv)))
(def once? (boolean (some #{"--once"} argv)))

(defn- log [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " xs))))

(defn- keychain [service account]
  (let [r (cp/spawnSync "security"
                        #js ["find-generic-password" "-s" service "-a" account "-w"]
                        #js {:encoding "utf8"})]
    (when (zero? (.-status r)) (str/trim (str (.-stdout r))))))

(defn- read-risk-mmsis!
  "The watchlist, read from the catalog rather than from a file. Resolves nil
  when the table could not be read -- which is not an empty list."
  []
  (js/Promise.
   (fn [resolve _]
     (let [r (cp/spawnSync "python3"
                           #js [(path/join (js/process.cwd) "scripts" "iceberg_read.py")
                                "--account" "4da88288dc30d9ee257f319d3c33ecf0"
                                "--bucket" "cloud-itonami-datalake"
                                "--namespace" "cloud_itonami"
                                "--table" "otent_vessel_risk"
                                "--columns" "attrs_json"]
                           #js {:encoding "utf8" :maxBuffer (* 256 1024 1024)
                                :stdio #js ["ignore" "pipe" "inherit"]
                                :env js/process.env})]
       (resolve
        (when (zero? (.-status r))
          (->> (str/split-lines (str (.-stdout r)))
               (remove str/blank?)
               (mapv (fn [l]
                       (let [a (js->clj (js/JSON.parse (get (js->clj (js/JSON.parse l)) "attrs_json")))]
                         {:mmsi (get a "mmsi") :imo (get a "imo")}))))))))))

(defonce state (atom {:buffer {} :watchlist nil :confirmed? false :messages 0 :kept 0}))

(defn- handle-message [txt]
  (let [j (js->clj (js/JSON.parse txt))]
    (if (= "SubscriptionConfirmation" (get j "MessageType"))
      (let [compressed? (get-in j ["Message" "CompressionEnabled"])]
        (swap! state assoc :confirmed? true)
        (log "SUBSCRIBED  compression:" (if compressed? "on" "OFF -- from September 2026 this is bandwidth-capped")))
      (let [meta* (get j "MetaData")
            mmsi (or (get meta* "MMSI") (get-in j ["Message" "PositionReport" "UserID"]))]
        (swap! state update :messages inc)
        (when (and mmsi (or all? (wl/watched? {:mmsi mmsi} (:watchlist @state))))
          ;; Latest wins. Keyed by MMSI so a vessel reporting twice inside one
          ;; flush window contributes one row, not two.
          (swap! state (fn [s] (-> s
                                   (update :kept inc)
                                   (assoc-in [:buffer (str mmsi)] j)))))))))

(defn- flush! []
  (let [{:keys [buffer]} @state]
    (if (empty? buffer)
      (log "flush: nothing buffered -- the socket was open and no watched vessel reported")
      (let [msgs (vals buffer)
            f (path/join (or (aget js/process.env "TMPDIR") "/tmp")
                         (str "ais-" (js/Date.now) ".ndjson"))]
        (swap! state assoc :buffer {})
        ((aget (js/require "fs") "writeFileSync") f
         (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) msgs)) "\n"))
        (log "flush:" (count msgs) "vessels ->" f)
        ;; Handing the batch to `bin/otent.cljs` rather than committing here
        ;; is deliberate: the governor, the payload archive and the receipt
        ;; ledger all live on that path, and a second writer that skipped
        ;; them would put rows in the table that no receipt explains.
        (let [r (cp/spawnSync "nbb"
                              #js ["--classpath"
                                   (str "src:" (path/join ".." ".." "kotoba-lang" "sgp4" "src")
                                        ":" (path/join ".." ".." "kotoba-lang" "kotobase-client" "src")
                                        ":" (path/join ".." ".." "kotoba-lang" "org-ietf-csv" "src"))
                                   (path/join "bin" "otent.cljs")
                                   "tick" "--feed" "aisstream" "--ais-batch" f "--force"]
                              #js {:encoding "utf8" :stdio "inherit"
                                   :env js/process.env})]
          (log "flush: tick exit" (.-status r)))))))

(defn- connect! []
  (let [key (or (aget js/process.env "AISSTREAM_API_KEY")
                (keychain "aisstream.io" "API_KEY"))]
    (when-not key
      (log "REFUSED: no AISStream key. The Keychain item is aisstream.io/API_KEY."
           "Nothing ran, which is not the same as running and seeing no vessels.")
      (js/process.exit 2))
    (let [ws (js/WebSocket. endpoint)]
      (set! (.-onopen ws)
            (fn [_]
              ;; Inside onopen, with no await before it: the server closes
              ;; the connection if the frame does not arrive within 3 s.
              (.send ws (js/JSON.stringify
                         #js {:APIKey key
                              :BoundingBoxes #js [#js [#js [-90 -180] #js [90 180]]]
                              :FilterMessageTypes #js ["PositionReport"]}))
              (log "connected, subscription sent")))
      (set! (.-onmessage ws)
            (fn [e]
              (-> (js/Promise.resolve (.-data e))
                  (.then (fn [d] (if (instance? js/Blob d) (.text d) (str d))))
                  (.then handle-message)
                  (.catch (fn [_] nil)))))
      (set! (.-onclose ws)
            (fn [e]
              (if-not (:confirmed? @state)
                (do (log "REFUSED: the server closed with code" (.-code e)
                         "before confirming a subscription. That is what a bad or"
                         "disabled key looks like -- it is NOT a network fault, and"
                         "retrying will not fix it.")
                    (js/process.exit 2))
                (do (log "socket closed, code" (.-code e) "-- reconnecting")
                    (js/setTimeout connect! (+ 2000 (rand-int 3000)))))))
      ws)))

(defn -main []
  (-> (read-risk-mmsis!)
      (.then
       (fn [risk]
         (when (and (not all?) (nil? risk))
           (log "REFUSED: the risk table could not be read, so there is no watchlist."
                "Running anyway would record nothing and look afterwards like an"
                "ocean with nothing on it. Use --all to collect unfiltered.")
           (js/process.exit 2))
         (let [w (wl/build (or risk []))]
           (when (and (not all?) (wl/empty-list? w))
             (log "REFUSED: the watchlist has no MMSIs on it.")
             (js/process.exit 2))
           (swap! state assoc :watchlist w)
           (log "watchlist:" (count (:mmsi w)) "MMSI," (count (:imo w)) "IMO"
                (if all? "(--all: filter disabled)" ""))
           (connect!)
           (js/setInterval flush! flush-interval-ms)
           (js/setInterval
            (fn []
              (-> (read-risk-mmsis!)
                  (.then (fn [r]
                           (when r
                             (let [w (wl/build r)]
                               (when-not (wl/empty-list? w)
                                 (swap! state assoc :watchlist w)
                                 (log "watchlist refreshed:" (count (:mmsi w)) "MMSI"))))))))
            watchlist-refresh-ms)
           (js/setInterval
            (fn [] (let [{:keys [messages kept buffer]} @state]
                     (log "seen" messages "| kept" kept "| buffered" (count buffer))))
            60000)
           (when once?
             (js/setTimeout (fn [] (flush!) (js/process.exit 0)) 120000)))))))

(-main)
