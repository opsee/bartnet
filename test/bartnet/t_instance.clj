(ns bartnet.t-instance
  (:require [midje.sweet :refer :all]
            [bartnet.instance :as instance]
            [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]]
            [bartnet.autobus :as msg]))

; Either test via the bus or mock the bus for unit testing.
(def bus (msg/message-bus))

(def fake-instance
  {:id          "id"
   :name        "anInstance"
   :customer_id "customer_id"
   :groups      [{:group_id   "sg-123456"
                  :group_name "group"}]
   :meta        {:created "timestamp" :instanceSize "t2.micro"}})

(def connection-info
  {:host "127.0.0.1"
   :port 6379
   :db 1})

(defn reset-redis [conn-info]
  (car/wcar conn-info (car/flushall)))

(facts "about MemoryInstanceStore"
  (facts "about get-instance!"
    (let [_ (instance/create-memory-store bus)]
      (fact "it returns an instance"
        (instance/save-instance! fake-instance)
        (instance/get-instance! "customer_id" "id") => fake-instance)))
  (facts "about save!"
    (let [_ (instance/create-memory-store bus)]
      (fact "it saves an instance"
        (instance/get-instance! "customer_id" "id") => nil
        (instance/save-instance! fake-instance)
        (instance/get-instance! "customer_id" "id") => fake-instance)))
  (facts "about get-group!"
    (let [_ (instance/create-memory-store bus)]
      (instance/save-instance! fake-instance)
      (fact "it gets an existing group"
        (instance/get-group! "customer_id" "sg-123456") => {:group_name "group", :group_id "sg-123456", :instances '("id")}))))

(facts :integration "about integration with redis"
  (with-state-changes
    [(before :facts (reset-redis connection-info))]
      (let [_ (instance/create-redis-store bus connection-info)]
        (fact "getting a non-existent id returns nil"
          (instance/get-instance! "customer_id" "id") => nil)
        (fact "setting an instance"
          (instance/save-instance! fake-instance) => fake-instance
          (instance/get-instance! "customer_id" "id") => fake-instance))))