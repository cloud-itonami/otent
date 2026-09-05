(ns otent.solar-elevation
  "Deterministic solar-elevation computation — the pinned 'model' for the
  capture-daylight derived task (:derived-allow :locally-reproducible-
  model-run). Pure arithmetic over the provider's own published UTC STAC
  datetime and the observation's lon/lat; no timezone conversion, no
  network, no inference beyond the documented approximation.

  Accuracy statement: the NOAA-style low-precision algorithm below is
  good to roughly ±0.5° around the stated thresholds; the derived task
  therefore carries a declared ±2° elevation uncertainty, which dwarfs
  it and is always visible.

  This file is the model artifact: its own sha256 is recorded in the
  derived provenance by the bin script, so every table names the exact
  arithmetic that produced it."
  (:require [clojure.string :as str]))

;; ── task identity ────────────────────────────────────────────────────

(def model-id "otent-solar-elevation-noaa-lowprec")
(def model-version "v1")

;; class thresholds, in degrees of solar elevation — pinned parameters
(def daylight-threshold-deg 0.0)
(def civil-twilight-floor-deg -6.0)

;; declared run-bound uncertainty for the classifier output
(def elevation-uncertainty-deg 2.0)

;; ── published-datetime parsing (provider format, validated) ──────────

(def ^:private datetime-re
  #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?\+00:00$")

(defn parse-utc-datetime
  "The provider's fixed-offset UTC STAC format → {:ok? true :y :mo :d
  :h :mi :s}, or {:ok? false}. No conversion: +00:00 is UTC already."
  [s]
  (when (string? s)
    (if-some [[_ y mo d h mi s] (re-find datetime-re s)]
      {:ok? true
       :y (js/parseInt y 10) :mo (js/parseInt mo 10) :d (js/parseInt d 10)
       :h (js/parseInt h 10) :mi (js/parseInt mi 10)
       :s (js/parseInt s 10)}
      {:ok? false})))

(defn- day-of-year
  "Days since Jan 1 (0-based) for a proleptic-Gregorian date; leap years
  respected. No Date constructor (deterministic across engines)."
  [y mo d]
  (let [cum [0 31 59 90 120 151 181 212 243 273 304 334]
        leap? (or (and (zero? (mod y 4)) (pos? (mod y 100)))
                  (zero? (mod y 400)))
        base (+ (nth cum (dec mo)) (dec d))]
    (if (and leap? (> mo 2)) (inc base) base)))

(defn- to-deg [r] (* 180.0 (/ r js/Math.PI)))
(defn- to-rad [d] (/ (* d js/Math.PI) 180.0))

;; ── the model ────────────────────────────────────────────────────────

(defn solar-elevation-deg
  "Solar elevation in degrees at the published UTC instant and the
  observation's own (lon, lat) degrees. NOAA low-precision algorithm;
  fractional seconds are ignored (below the model's declared
  uncertainty). Returns a number."
  [{:keys [y mo d h mi s]} lon lat]
  (let [doy (day-of-year y mo d)
        frac-day (+ (/ (* 3600 (+ (* 1.0 h) (/ mi 60.0) (/ s 3600.0))) 86400.0))
        ;; fractional year, in radians
        gamma (* 2.0 js/Math.PI (/ (+ doy frac-day) 365.0))
        ;; equation of time (minutes) — NOAA low-precision
        eqtime (* 229.18
                  (+ (* 0.000075)
                     (* 0.001868 (Math/cos gamma))
                     (- (* 0.032077 (Math/sin gamma)))
                     (- (* 0.014615 (Math/cos (* 2 gamma))))
                     (- (* 0.040849 (Math/sin (* 2 gamma))))))
        ;; solar declination (radians)
        decl (+ 0.006918
                (- (* 0.399912 (Math/cos gamma)))
                (* 0.070257 (Math/sin gamma))
                (- (* 0.006758 (Math/cos (* 2 gamma))))
                (* 0.000907 (Math/sin (* 2 gamma)))
                (- (* 0.002697 (Math/cos (* 3 gamma))))
                (* 0.00148 (Math/sin (* 3 gamma))))
        ;; true solar time (minutes)
        tst (mod (+ (* 60.0 h) mi (/ s 60.0) eqtime (* 4.0 lon)) 1440.0)
        ;; hour angle (degrees, then radians) — tst minutes of time →
        ;; 15° per hour → tst/4 − 180
        ha (to-rad (- (/ tst 4.0) 180.0))
        latr (to-rad lat)
        cos-zen (+ (* (Math/sin latr) (Math/sin decl))
                   (* (Math/cos latr) (Math/cos decl) (Math/cos ha)))]
    (to-deg (js/Math.asin (max -1.0 (min 1.0 cos-zen))))))

(defn classify
  "Solar elevation degrees → one of :daylight, :civil-twilight, :night.
  Exactly the two pinned thresholds, nothing else."
  [elev]
  (cond
    (> elev daylight-threshold-deg) :daylight
    (>= elev civil-twilight-floor-deg) :civil-twilight
    :else :night))
