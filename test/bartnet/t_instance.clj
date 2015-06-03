(ns bartnet.t-instance
  (:require [midje.sweet :refer :all]
            [bartnet.instance :as instance]
            [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]])
  (:import (bartnet.instance RedisInstanceStore MemoryInstanceStore)
           (org.cliffc.high_scale_lib NonBlockingHashMap)))

(def fake-instance
  { :id "id"
    :name "anInstance"
    :customer_id "customer_id" })

(def connection-info
  {:host "127.0.0.1"
   :port 6379
   :db 1})

(defn primed-map [coll]
  (let [new-map (NonBlockingHashMap.)]
    (doseq [instance (seq coll)]
      (.put new-map (str (:customer_id instance) ":" (:id instance)) instance))
    new-map))

(defn reset-redis [conn-info]
  (car/wcar conn-info (car/flushall)))

(with-redefs [
              car/wcar (fn [_] true)
              car/get (fn [_] fake-instance)
              car/set (fn [_] fake-instance)
              car/expire (fn [_] true)
              ]
  (facts "about connect!"
    (fact "it returns an instace of InstanceStoreProtocol"
      (satisfies? instance/InstanceStoreProtocol (instance/create-redis-store connection-info)) => true))
  (facts "about get-instance!"
    (let [_ (instance/create-memory-store (primed-map [fake-instance]))]
      (fact "it returns an instance"
        (instance/get-instance! "customer_id" "id") => fake-instance)))
  (facts "about save!"
    (let [_ (instance/create-memory-store (NonBlockingHashMap.))]
      (fact "it saves an instance"
        (instance/get-instance! "customer_id" "id") => nil
        (instance/save-instance! fake-instance)
        (instance/get-instance! "customer_id" "id") => fake-instance))))

(facts :integration "about integration with redis"
  (with-state-changes
    [(before :facts (reset-redis connection-info))]
      (let [_ (instance/create-redis-store connection-info)]
        (fact "getting a non-existent id returns nil"
          (instance/get-instance! "customer_id" "id") => nil)
        (fact "setting an instance"
          (instance/save-instance! fake-instance) => fake-instance
          (instance/get-instance! "customer_id" "id") => fake-instance))))