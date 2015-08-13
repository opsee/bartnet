(ns bartnet.t-instance
  (:require [midje.sweet :refer :all]
            [bartnet.instance :as instance]
            [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [taoensso.carmine :as car :refer [wcar]]
            [bartnet.autobus :as msg]))

(def fake-instance
  {:id          "id"
   :name        "anInstance"
   :customer_id "customer_id"
   :groups      [{:group_id   "sg-123456"
                  :group_name "group"}]
   :meta        {:created "timestamp" :instanceSize "t2.micro"}})

(def connection-info
  {:pool {}
   :spec {:host (get (System/getenv) "REDIS_PORT_6379_TCP_ADDR" "127.0.0.1")
          :port (Integer/parseInt (get (System/getenv) "REDIS_PORT_6379_TCP_PORT" "6379"))
          :db 0}})

(defn reset-redis [conn-info]
  (car/wcar conn-info (car/flushall)))

(facts "with MemoryInstanceStore"
  (facts "get-instance!"
    (let [_ (instance/create-memory-store nil)]
      (fact "returns an instance"
        (instance/save-instance! fake-instance)
        (instance/get-instance! "customer_id" "id") => fake-instance)))
  (facts "save!"
    (let [_ (instance/create-memory-store nil)]
      (fact "saves an instance"
        (instance/get-instance! "customer_id" "id") => nil
        (instance/save-instance! fake-instance)
        (instance/get-instance! "customer_id" "id") => fake-instance)))
  (facts "get-group!"
    (let [_ (instance/create-memory-store nil)]
      (instance/save-instance! fake-instance)
      (fact "returns a valid existing group"
        (instance/get-group! "customer_id" "sg-123456") => {:group_name "group", :group_id "sg-123456", :instances '("id")}))))

(facts :integration "with RedisInstanceStore"
  (with-state-changes
    [(before :facts (reset-redis connection-info))]
      (let [_ (instance/create-redis-store nil connection-info)]
        (fact "getting a non-existent id returns nil"
          (instance/get-instance! "customer_id" "id") => nil)
        (fact "can save an instance"
          (instance/save-instance! fake-instance) => fake-instance
          (instance/get-instance! "customer_id" "id") => fake-instance)
        (facts "get-group!"
          (fact "returns a valid existing group"
            (instance/save-instance! fake-instance)
            (instance/get-group! "customer_id" "sg-123456") => {:group_name "group", :group_id "sg-123456", :instances '("id")}))
        (facts "list!"
          (fact "returns a list of instances"
            (instance/save-instance! fake-instance)
            (instance/list-instances! "customer_id") => '({:id "id", :name "anInstance"})))
        (facts "group-list!"
          (fact "returns a list of groups"
            (instance/save-instance! fake-instance)
            (instance/list-groups! "customer_id") => '({:id "sg-123456", :name "group"}))))))
