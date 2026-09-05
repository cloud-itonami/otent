(ns otent.night-lights-test
  "The bounds and the provenance, tested without a network.

  Each refusal here is written against a specific way the ingest could
  quietly become unbounded, unlicensed or untraceable."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [otent.night-lights :as nl]))

(deftest plan-bounded-at-z4
  (let [p (nl/plan {:composite "2016-01-01" :max-zoom 4})]
    (is (:ok? p))
    ;; 1 + 4 + 16 + 64 + 256 -- the whole globe to z4, in one number the
    ;; caller prints before the first request goes out.
    (is (= 341 (:tile-count p)))
    (is (= 341 (count (:tiles p))))))

(deftest a-composite-source-requires-a-composite
  (testing "no --composite must not fall through to the service's `default`"
    (let [r (nl/plan {:max-zoom 4})]
      (is (not (:ok? r)))
      (is (= :plan/missing-composite (:refusal r)))
      (is (= (:composites nl/source) (:declared r))))))

(deftest an-undeclared-composite-is-refused
  (testing "a date the service does not declare would be ingesting a guess"
    (let [r (nl/plan {:composite "2026-08-31" :max-zoom 4})]
      (is (not (:ok? r)))
      (is (= :plan/unknown-composite (:refusal r)))
      (is (= ["2012-01-01" "2016-01-01"] (:declared r))))))

(deftest past-the-source-maximum-is-refused
  (let [r (nl/plan {:composite "2016-01-01" :max-zoom 9})]
    (is (not (:ok? r)))
    (is (= :plan/past-source-zoom (:refusal r)))
    ;; The refusal names the real ceiling, not just that there is one.
    (is (re-find #"8" (:detail r)))))

(deftest past-the-ingest-bound-is-refused-even-when-the-source-allows-it
  (let [r (nl/plan {:composite "2016-01-01" :max-zoom 8})]
    (is (not (:ok? r)))
    (is (= :plan/past-ingest-bound (:refusal r))
        "z8 is legal for the SERVICE (521,171 tiles at that level alone) and
         still illegal for this ingest")))

(deftest urls-and-keys-agree
  (let [tile [3 4 5]
        url (nl/tile-url tile "2016-01-01")
        key (nl/object-key tile "2016-01-01")]
    (is (str/starts-with? url "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/VIIRS_Black_Marble/default/2016-01-01/"))
    (is (str/ends-with? url "/3/5/4.png") "tile is [z x y] = [3 4 5]; the WMTS template is {z}/{y}/{x} -- row before column")
    (is (= "otent/night-lights/2016-01-01/3/4/5.png" key))
    (is (not (re-find #"2016-01-01" (nl/tile-url tile ""))) "no composite placeholder left behind")))

(deftest provenance-carries-every-required-field
  (let [tile [2 1 1]
        buf (js/Buffer.from "png-bytes")
        crypto (js/require "crypto")
        sha (-> (crypto.createHash "sha256") (.update buf) (.digest "hex"))
        p (nl/provenance {:tile tile
                          :composite "2016-01-01"
                          :url (nl/tile-url tile "2016-01-01")
                          :buf buf
                          :sha256-hex sha
                          :retrieved-at 1796200000000})
        required [:asset-id :object-key :source-url :composite :capture-time
                  :retrieved-at :content-sha256 :content-type :format :crs
                  :tile-matrix-set :scheme :tile-size :tile :sensor :bands
                  :resolution-degrees-at-z :licence :attribution]]
    (doseq [k required]
      (testing (name k) (is (some? (get p k)))))
    (is (= sha (:content-sha256 p)))
    (is (= "image/png" (:content-type p)))
    (is (= "EPSG:3857" (:crs p)))
    (is (= {:z 2 :x 1 :y 1} (:tile p)))
    (is (= "NASA -- public domain" (:licence p)))
    (is (= "otent/night-lights/2016-01-01/2/1/1.png" (:object-key p)))
    ;; resolution halves per zoom level; a wrong denominator here means a
    ;; consumer scaling the globe to the wrong size
    (is (= 90.0 (:resolution-degrees-at-z p)))))

(deftest manifest-states-exactly-what-exists
  (let [p (nl/plan {:composite "2012-01-01" :max-zoom 2})
        entries (mapv (fn [t] {:object-key (nl/object-key t "2012-01-01")
                               :content-sha256 "abc"})
                      (:tiles p))
        m (nl/manifest {:composite "2012-01-01"
                        :written-at 1796200000000
                        :measured-max-zoom 2
                        :entries entries})]
    (is (= 1 (:version m)))
    (is (= "viirs-black-marble" (get-in m [:source :id])))
    (is (= "2012-01-01" (:composite m)))
    (is (= 2 (:measured-max-zoom m))
        "the zoom the BUCKET measured, not the zoom that was requested")
    (is (= nl/ingest-bound-zoom (:ingest-bound-zoom m)))
    (is (= 21 (:tile-count m)) "1+4+16 at z2")
    (is (= 21 (count (:entries m))))
    ;; The manifest must not carry the template with placeholders: a
    ;; reader that followed it would fetch, not read what we hold.
    (is (not (contains? (:source m) :url-template)))
    (is (every? #(str/starts-with? (:object-key %) "otent/night-lights/2012-01-01/")
                (:entries m)))))

(deftest the-source-declaration-is-honest
  (testing "the licence line names the actual terms, and the ceilings are
            the ones measured from the live capabilities on 2026-09-01"
    (is (= "NASA -- public domain" (:licence nl/source)))
    (is (= 8 (:max-source-zoom nl/source)))
    (is (= ["2012-01-01" "2016-01-01"] (:composites nl/source)))
    (is (= 4 nl/ingest-bound-zoom))))
