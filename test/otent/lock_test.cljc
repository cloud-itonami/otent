(ns otent.lock-test
  (:require [cljs.test :refer [deftest is testing async]]
            [otent.lock :as lock]))

(defn- deferred []
  (let [res (atom nil)
        p (js/Promise. (fn [resolve _] (reset! res resolve)))]
    {:promise p :resolve @res}))

(deftest same-key-work-does-not-overlap
  (testing "two commits to the same table would each read a `before` count
            that already includes the other's rows, and the delta check would
            refuse two writes that both succeeded"
    (async done
      (let [locks (lock/make)
            order (atom [])
            a (deferred)
            started-b (atom false)]
        (lock/with-lock locks "t"
          (fn [] (swap! order conj :a-start) (:promise a)))
        (lock/with-lock locks "t"
          (fn [] (reset! started-b true) (swap! order conj :b-start)
            (js/Promise.resolve :b)))
        ;; B must NOT have started while A is unresolved.
        (js/setTimeout
         (fn []
           (is (false? @started-b) "the second call started before the first finished")
           ((:resolve a) :a)
           (js/setTimeout
            (fn []
              (is (true? @started-b) "the second call never ran")
              (is (= [:a-start :b-start] @order))
              (done))
            10))
         10)))))

(deftest different-keys-do-overlap
  (testing "different tables running concurrently is the point of the change"
    (async done
      (let [locks (lock/make)
            started (atom #{})
            a (deferred)]
        (lock/with-lock locks "t1" (fn [] (swap! started conj :t1) (:promise a)))
        (lock/with-lock locks "t2" (fn [] (swap! started conj :t2) (js/Promise.resolve nil)))
        (js/setTimeout
         (fn []
           (is (= #{:t1 :t2} @started)
               "the second key waited on the first -- that would serialise everything again")
           ((:resolve a) nil)
           (done))
         10)))))

(deftest a-failed-write-does-not-wedge-the-key-forever
  (testing "without the `.catch`, one refusal leaves every later write on that
            table waiting on a promise that will never settle -- a single
            failure becomes a dead table, and from outside it looks like the
            feed going quiet"
    (async done
      (let [locks (lock/make)
            ran (atom false)]
        (-> (lock/with-lock locks "t" (fn [] (js/Promise.reject (js/Error. "boom"))))
            (.catch (fn [_] nil)))
        (-> (lock/with-lock locks "t" (fn [] (reset! ran true) (js/Promise.resolve :ok)))
            (.then (fn [v]
                     (is (true? @ran) "the follow-up never ran")
                     (is (= :ok v))
                     (done))))))))
