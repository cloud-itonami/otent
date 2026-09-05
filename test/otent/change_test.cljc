(ns otent.change-test
  "Deterministic tests for the change-observation pipeline: the refusal
   ladder, threshold and gray-zone behaviour, unknown-side handling,
   both-dates provenance shape, failed rows, and summary arithmetic.
   No network, no JPEG -- per-date conditions come from synthetic RGBA
   fixtures through `otent.analysis`, the same way the bin script wires
   them."
  (:require [clojure.test :as t]
            [otent.analysis :as an]
            [otent.basemap :as bm]
            [otent.change :as ch]))

;; ------------------------------------------------------------------ fixtures

(def mo (bm/source-for "modis-terra-truecolor"))

(defn- tile-of [w h [r g b]]
  (let [data (js/Uint8Array. (* w h 4))]
    (doseq [i (range (* w h))]
      (aset data (* i 4) r) (aset data (inc (* i 4)) g)
      (aset data (+ 2 (* i 4)) b) (aset data (+ 3 (* i 4)) 255))
    {:width w :height h :data data}))

(defn- metrics-of [tile] (an/tile-metrics tile (get-in an/model [:params :sample-grid])))

;; a green land tile the per-date classifier reads confidently as
;; :vegetated-land, plus variants shifted +6 (deadband), +20 (gray zone)
;; and +40 brightness (a real transition)
(def land (tile-of 64 64 [90 150 70]))
(def land-brighter (tile-of 64 64 [140 190 130]))
(def land-slightly (tile-of 64 64 [96 156 76]))    ;; +6: inside the deadband
(def land-gray (tile-of 64 64 [110 170 90]))       ;; +20: gray zone

(defn- side [tile]
  (let [m (metrics-of tile)
        r (an/classify m)]
    {:metrics m :label (:label r) :confidence (:confidence r)}))

(defn- row [a b]
  (ch/change-observation
    {:source mo :tile [2 1 2] :date-from "2026-08-29" :date-to "2026-08-30"
     :key-a "k-a" :key-b "k-b" :sha-a "aa" :sha-b "bb"
     :metrics-a (:metrics a) :metrics-b (:metrics b)
     :res-a a :res-b b}
    "change-sha" "analysis-sha" "nbb test"))

(defn- prov [r] (:provenance r))
(defn- obs [r] (:observation r))

;; ------------------------------------------------------------------ refusals

(t/deftest change-refusals
  (t/is (= :change/capture-dates-required
           (:refusal (ch/change-refusal mo 4 "2026-08-29" nil))))
  (t/is (= :change/capture-dates-required
           (:refusal (ch/change-refusal mo 4 "2026-0829" "2026-08-30"))))
  (t/is (= :change/dates-must-differ
           (:refusal (ch/change-refusal mo 4 "2026-08-29" "2026-08-29"))))
  (t/is (= :change/dates-out-of-order
           (:refusal (ch/change-refusal mo 4 "2026-08-30" "2026-08-29"))))
  (t/is (= :change/past-ingest-bound
           (:refusal (ch/change-refusal mo 5 "2026-08-29" "2026-08-30"))))
  (t/is (= :licence/unknown-source
           (:refusal (ch/change-refusal (bm/source-for "nope") 4 "2026-08-29" "2026-08-30"))))
  (t/is (nil? (ch/change-refusal mo 4 "2026-08-29" "2026-08-30"))))

;; ------------------------------------------------------------------ classify

(t/deftest deadband-is-no-change
  (let [r (ch/classify-change (side land) (side land-slightly))]
    (t/is (= :no-change (:label r)))))

(t/deftest gray-zone-stays-inconclusive
  (let [r (ch/classify-change (side land) (side land-gray))]
    (t/is (= :inconclusive (:label r)))
    (t/is (string? (:detail r)) "the gray zone carries its reason")))

(t/deftest big-deltas-label-directionally
  (t/is (= :brightening (:label (ch/classify-change (side land) (side land-brighter)))))
  (t/is (= :darkening (:label (ch/classify-change (side land-brighter) (side land))))))

(t/deftest unmeasured-side-is-inconclusive-never-no-change
  (let [r (ch/classify-change (side land) {:metrics nil :label :dim :confidence 0.0})]
    (t/is (= :inconclusive (:label r)))
    (t/is (re-find #"no measurable pixels" (str (:detail r))))))

(t/deftest unknown-side-is-inconclusive-never-coerced
  ;; a mostly-nodata tile reads :unknown on one date; the comparison
  ;; must refuse to invent a transition
  (let [sparse {:width 64 :height 64
                :data (doto (js/Uint8Array. (* 64 64 4))
                        (aset 0 60) (aset 1 60) (aset 2 60))}
        m (metrics-of sparse)
        r (an/classify m)
        unknown-side {:metrics m :label (:label r) :confidence (:confidence r)}]
    (t/is (= :unknown (:label unknown-side)) "fixture precondition")
    (t/is (= :inconclusive (:label (ch/classify-change unknown-side (side land)))))))

;; ------------------------------------------------------------------ rows

(t/deftest row-carries-both-dates-provenance
  (let [r (row (side land) (side land-brighter))]
    (t/is (= :committed (:observation-status r)))
    (t/is (= "modis-terra-truecolor/2/1/2/2026-08-29..2026-08-30"
             (get-in (prov r) [:asset-id])))
    (t/is (= "aa" (get-in (prov r) [:from :content-sha256])))
    (t/is (= "bb" (get-in (prov r) [:to :content-sha256])))
    (t/is (= "2026-08-29T00:00:00Z" (get-in (prov r) [:from :capture-time])))
    (t/is (= "2026-08-30T00:00:00Z" (get-in (prov r) [:to :capture-time])))
    (t/is (= "otent/tile-change-observer" (get-in (prov r) [:model :id])))
    (t/is (= "change-sha" (get-in (prov r) [:model :artifact-hash])))
    (t/is (= "analysis-sha" (get-in (prov r) [:model :consumes :artifact-hash])
             )
          "the consumed per-date classifier is pinned by its own hash")
    (t/is (= "NASA -- public domain" (:licence (prov r))))
    ;; whole-tile footprint, same as the single-date analysis
    (let [bbox (get-in (prov r) [:footprint-epsg3857])]
      (t/is (= bbox (an/tile-bbox 2 1 2))))))

(t/deftest row-raw-and-normalized-stay-distinguishable
  (let [r (row (side land) (side land-brighter))]
    (t/is (contains? (:raw r) :metrics-from))
    (t/is (contains? (:raw r) :metrics-to))
    (t/is (contains? (:raw r) :raw-scores))
    (t/is (= :brightening (:label (obs r))))
    (t/is (= "tile-change/1" (:taxonomy-version (obs r))))
    (t/is (nil? (:cause (obs r))) "no cause is ever attached")
    (t/is (= (- 1.0 (double (:confidence (obs r)))) (double (:uncertainty (obs r)))))))

(t/deftest failed-row-shape
  (let [r (ch/failed-change-observation [1 0 0] "k-a" "k-b" :source/http-error "404")]
    (t/is (= :failed (:observation-status r)))
    (t/is (= {:z 1 :x 0 :y 0} (get-in r [:provenance :tile])))
    (t/is (= :source/http-error (get-in r [:failure :reason])))
    (t/is (nil? (:observation r)) "a failed row makes no observation claim")))

;; ------------------------------------------------------------------ summary

(t/deftest summary-counts-add-up
  (let [rows [(row (side land) (side land-brighter))
              (row (side land) (side land-slightly))
              (ch/failed-change-observation [1 0 0] "k-a" "k-b" :source/http-error "404")
              (ch/failed-change-observation [1 0 1] "k-a" "k-b" :decode/jpeg "boom")]
        s (ch/run-summary {:source mo :date-from "2026-08-29" :date-to "2026-08-30" :max-z 2}
                          "change-sha" rows 123)]
    (t/is (= 4 (:tile-count s)))
    (t/is (= {:committed 2 :failed 2} (:counts s)))
    (t/is (= (:tile-count s) (+ (get-in s [:counts :committed]) (get-in s [:counts :failed]))
             ) "committed + failed = tile count, nothing dropped")
    (t/is (= 0 (:inconclusive s)))
    (t/is (= 1 (get (:labels s) :brightening 0)))
    (t/is (= 1 (get (:labels s) :no-change 0)))
    (t/is (= "2026-08-29" (:date-from s)))
    (t/is (= "2026-08-30" (:date-to s)))
    (t/is (= 4 (:ingest-bound s)))
    (t/is (= 123 (:run-ms s)))
    (t/is (= "otent/tile-change-observer" (get-in s [:model :id])))))

(t/deftest taxonomy-is-versioned-with-inconclusive
  (t/is (= "tile-change/1" (:taxonomy-version ch/taxonomy)))
  (t/is (contains? (set (:labels ch/taxonomy)) :inconclusive))
  (t/is (contains? (set (:labels ch/taxonomy)) :no-change))
  (t/is (contains? (set (:labels ch/taxonomy)) :brightening))
  (t/is (contains? (set (:labels ch/taxonomy)) :darkening)))
