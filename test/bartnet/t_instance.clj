(ns bartnet.t-instance
  (:require [midje.sweet :refer :all]
            [bartnet.instance :as instance]
            [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]]))

(def fake-instance
  { :id "id"
    :name "anInstance" })

(def connection-info
  {:host "127.0.0.1"
   :port 6379
   :db 1})

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
      (satisfies? instance/InstanceStoreProtocol (instance/connect! connection-info)) => true))
  (facts "about get-instance!"
    (let [_ (reset! instance/instance-store (instance/->MemoryInstanceStore {"id" fake-instance}))]
      (fact "it returns an instance"
        (instance/get-instance! (:id fake-instance)) => fake-instance)))
  (facts "about save!"
    (let [_ (reset! instance/instance-store (instance/->MemoryInstanceStore {}))]
      (fact "it saves an instance"
        (instance/get-instance! "id") => nil
        (instance/save-instance! fake-instance)
        (instance/get-instance! "id") => fake-instance))))

(facts :integration "about integration with redis"
  (with-state-changes
    [(before :facts (reset-redis connection-info))]
      (let [_ (instance/connect! connection-info)]
        (fact "getting a non-existent id returns nil"
          (instance/get-instance! "id") => nil)
        (fact "setting an instance"
          (instance/save-instance! fake-instance) => fake-instance
          (instance/get-instance! "id") => fake-instance))))