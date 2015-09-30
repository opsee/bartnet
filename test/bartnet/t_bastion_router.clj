(ns bartnet.t-bastion-router
  (:use midje.sweet)
  (:require [bartnet.bastion-router :as router]
            [clojure.test :refer :all]
            [cheshire.core :refer :all]
            [clj-disco.core :as disco]
            [verschlimmbesserung.core :as etcd]
            [clojure.string :refer [split]]
            [clojure.tools.logging :as log]))

(def portmap-json (generate-string
                    {
                     :checker {:hostname "host" :port "4000" :name "checker"}
                     :monitor {:hostname "host" :port "4001" :name "monitor"}
                     }))

(facts "router can return services"
       (with-redefs [etcd/connect (fn [_] true)
                     etcd/get (fn [_ key]
                                (let [[customer-id instance-id] (take-last 2 (split key #"/"))]
                                  (if (and
                                        (= customer-id "1")
                                        (= instance-id "1"))
                                    portmap-json)))]
         (fact "returns nil if customer not found"
               (let [response (router/get-service 2 2 "foo")]
                 response => nil))
         (fact "returns nil if instance id not found"
               (let [response (router/get-service 1 2 "foo")]
                 response => nil))
         (fact "returns nil if the service does not exist"
               (let [response (router/get-service 1 1 "foo")]
                 response => nil))
         (fact "returns a map containing host and port if service found"
               (let [response (router/get-service 1 1 "checker")]
                 (:host response) => "host"
                 (:port response) => 4000))))