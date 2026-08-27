(ns otent.deadline-test
  "A deadline that cannot be told apart from a refusal is half a deadline."
  (:require [clojure.test :refer [deftest is testing]]
            [otent.deadline :as dl]))

(deftest a-timeout-is-not-a-refusal
  (testing "both are UNMEASURED, and the receipt has to say which, or the
            next reader debugs the wrong end of the wire"
    (is (dl/timeout-error? #js {:name "TimeoutError" :message "The operation was aborted due to timeout"}))
    (is (dl/timeout-error? #js {:name "AbortError" :message "aborted"}))
    (is (not (dl/timeout-error? #js {:name "TypeError" :message "fetch failed"})))
    (is (not (dl/timeout-error? #js {:name "Error" :message "ECONNREFUSED"})))))

(deftest the-sentence-says-we-do-not-know
  (let [d (dl/describe "the feed at https://example.test" 60000)]
    (is (re-find #"60s" d))
    (is (re-find #"UNMEASURED" d))
    (is (re-find #"not the same as it having said nothing" d)
        "the whole point of this repository is that the two are different")))

(deftest a-signal-is-produced-and-is-not-already-aborted
  (let [s (dl/signal 5000)]
    (is (some? s))
    (is (false? (.-aborted s))
        "a signal that starts aborted would make every call fail instantly
         while looking like a network problem")))

(deftest writes-get-more-room-than-reads
  (is (> dl/upload-ms dl/default-ms)
      "a write cut off halfway is worse than a read cut off halfway"))
