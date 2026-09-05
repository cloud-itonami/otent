;; Live, bounded verification of the Natural Earth ingest path.
;;
;;   nbb --classpath src scripts/verify_ne1_sample.cljs
;;
;; Fetches the catalogued asset once, and checks the things a fetch
;; alone does not prove: the magic, the declared size band, the pinned
;; sha256, the zip's central directory (parsed by hand over zlib
;; inflateRaw -- no unzip dependency), and the TIFF magic of the
;; published GeoTIFF inside it. Read-only: writes nothing anywhere.
(ns verify-ne1-sample
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            ["zlib" :as zlib]
            [clojure.string :as str]
            [otent.natural-earth :as ne]))

(def ^:private asset (ne/get-asset "NE1_50M_SR_W"))

(defn- sha256 [^js buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

(defn- bytes->str
  "ASCII bytes 0..n of a Buffer as a string."
  [^js buf n]
  (let [u8 (js/Uint8Array. (.slice buf 0 n))]
    (apply str (map #(js/String.fromCharCode (aget u8 %)) (range (.-length u8))))))

;; -- hand-rolled zip reading -------------------------------------------------
;; The zip's local-file headers carry the compressed length; stored entries
;; (compression method 0) need no inflate, deflated ones (method 8) go
;; through zlib.inflateRawSync. Enough to prove the published entries are
;; present and the GeoTIFF really is a TIFF.

(defn- u16le [^js buf off] (.readUInt16LE buf off))
(defn- u32le [^js buf off] (.readUInt32LE buf off))

(defn- local-entries
  "Seq of {:name String :method n :compressed n} from the local file
  headers. Walks signatures rather than trusting the central directory,
  so a truncated download still reports what it actually contains."
  [^js buf]
  (loop [off 0 out []]
    (if (> (+ off 30) (.-length buf))
      out
      (if (not= "PK\u0003\u0004" (bytes->str (.slice buf off (+ off 4)) 4))
        out
        (let [method (u16le buf (+ off 8))
              csize (u32le buf (+ off 18))
              nlen (u16le buf (+ off 26))
              elen (u16le buf (+ off 28))
              name (.toString (.slice buf (+ off 30) (+ off 30 nlen)) "utf8")
              data-off (+ off 30 nlen elen)]
          (recur (+ data-off csize)
                 (conj out {:name name :method method :compressed csize
                            :data-off data-off})))))))

(defn- inflate-entry [^js buf e]
  (let [data (.slice buf (:data-off e) (+ (:data-off e) (:compressed e)))]
    (if (zero? (:method e))
      data
      (.inflateRawSync zlib data))))

;; -- the check ---------------------------------------------------------------

(defn- report [results]
  (doseq [[k v] results] (println (str k "=" v)))
  (let [failed (seq (filter (comp false? :ok?) (vals results)))]
    (println (if failed "VERIFY FAILED" "VERIFY OK"))
    (when failed (set! (.-exitCode js/process) 1))))

(-> (js/fetch (:url asset))
    (.then #(.arrayBuffer %))
    (.then (fn [ab]
             (let [buf (js/Buffer.from ab)
                   n (.-length buf)
                   size (ne/check-size asset n)
                   magic (ne/check-magic buf)
                   h (sha256 buf)
                   sha (ne/check-sha asset h)]
               (if (or (not (:ok? size)) (not (:ok? magic)) (not (:ok? sha)))
                 (report {:size-bound (:ok? size) :zip-magic (:ok? magic)
                          :sha-pinned (:ok? sha)})
                 (let [entries (local-entries buf)
                       names (set (map :name entries))
                       expected (set (:entries asset))
                       tif (first (filter #(str/ends-with? (:name %) ".tif") entries))
                       tif-head (when tif (bytes->str (inflate-entry buf tif) 4))]
                   (report
                    {:bytes n
                     :size-bound (:ok? size)
                     :zip-magic (:ok? magic)
                     :sha-pinned (:ok? sha)
                     :sha256 h
                     :entries-found (pr-str (vec (sort names)))
                     :entries-complete (= expected names)
                     :tiff-magic (= "II*\u0000" tif-head)
                     :tiff-uncompressed-bytes
                     (when tif (.-length (inflate-entry buf tif)))})))))))
