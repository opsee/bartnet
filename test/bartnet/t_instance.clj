(ns bartnet.t-instance
  (:require [midje.sweet :refer :all]
            [bartnet.instance :as instance]
            [taoensso.carmine :as car]
            [clojure.tools.logging :as log]))

(def fake-instance
  { :id "i-293856f"
    :name "anInstance" })

(def connection-info
  {:host "127.0.0.1"
   :port 6379
   :db 1})

(with-redefs [
              car/wcar (fn [_] true)
              car/get (fn [_] fake-instance)
              car/set (fn [_] fake-instance)
              car/expire (fn [_] true)
              ]
  (facts "about connect!"
    (fact "it returns an instace of InstanceStoreProtocol"
      (instance? instance/InstanceStoreProtocol (instance/connect! connection-info)) => true)))

