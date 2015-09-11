(ns bartnet.t-protobuilder
  (:require [midje.sweet :refer :all]
            [bartnet.protobuilder :refer :all])
  (:import (co.opsee.proto TestCheckRequest Any Timestamp HttpCheck Header Check)
           (com.google.protobuf ByteString)))

(facts "Hash to proto"
       (fact "converts from flat hash"
             (let [proto (hash->proto Timestamp {:seconds 1 :nanos 2})]
               (.getNanos proto) => 2
               (.getSeconds proto) => 1))
       (fact "converts from nested hash"
             (let [proto (hash->proto TestCheckRequest {:max_hosts 2
                                                        :deadline {:seconds 1
                                                                   :nanos 3}})]
               (.getNanos (.getDeadline proto)) => 3
               (.getSeconds (.getDeadline proto)) => 1
               (.getMaxHosts proto) => 2))
       (fact "converts nested anyhash into Any"
             (let [proto (hash->proto Check {:check_spec {:type_url "HttpCheck"
                                                          :value {:name "check-1"}}})]
               (.getTypeUrl (.getCheckSpec proto)) => "HttpCheck"
               (let [checkspec (HttpCheck/parseFrom ^ByteString (.getValue (.getCheckSpec proto)))]
                 (.getName checkspec) => "check-1")))
       (fact "converts repeated message fields"
             (let [proto (hash->proto HttpCheck {:headers [{:name "Accept"
                                                            :values ["application/json"]}
                                                           {:name "Cache-Control"
                                                            :values ["today" "tomorrow"]}]})
                   headers (.getHeadersList proto)]
               (.getName (first headers)) => "Accept"
               (seq (.getValuesList (first headers))) => (contains ["application/json"])
               (.getName (last headers)) => "Cache-Control"
               (seq (.getValuesList (last headers))) => ["today" "tomorrow"])))

(facts "Proto to hash"
       (fact "converts flat proto to hash"
             (let [proto (-> (Timestamp/newBuilder)
                             (.setSeconds 1)
                             (.setNanos 2)
                             .build)]
               (proto->hash proto) => {:seconds 1 :nanos 2}))
       (fact "converts nested proto to hash"
             (let [proto (-> (TestCheckRequest/newBuilder)
                             (.setMaxHosts 3)
                             (.setDeadline (-> (Timestamp/newBuilder)
                                               (.setSeconds 1441234586)
                                               .build))
                             .build)]
               (proto->hash proto) => {:max_hosts 3 :deadline "2015-09-02T22:56:26Z"}))
       (let [check-proto (-> (HttpCheck/newBuilder)
                             (.setPath "/somewhere/beyond/the/highway")
                             (.addHeaders (-> (Header/newBuilder)
                                              (.setName "Accept")
                                              (.addValues "application/json")
                                              (.addValues "text/plain")
                                              .build))
                             (.addHeaders (-> (Header/newBuilder)
                                              (.setName "Cache-Control")
                                              (.addValues "whenever dude")
                                              .build))
                             .build)]
         (fact "converts repeated fields"
               (proto->hash check-proto) => {:path "/somewhere/beyond/the/highway"
                                             :headers [{:name "Accept"
                                                        :values ["application/json" "text/plain"]}
                                                       {:name "Cache-Control"
                                                        :values ["whenever dude"]}]})
         (fact "converts any fields"
               (let [proto (-> (Check/newBuilder)
                               (.setCheckSpec (-> (Any/newBuilder)
                                                  (.setTypeUrl "HttpCheck")
                                                  (.setValue (.toByteString check-proto))))
                               .build)]
                 (proto->hash proto) => {:check_spec {:type_url "HttpCheck"
                                                      :value {:path "/somewhere/beyond/the/highway"
                                                              :headers [{:name "Accept"
                                                                         :values ["application/json" "text/plain"]}
                                                                        {:name "Cache-Control"
                                                                         :values ["whenever dude"]}]}}}))))

