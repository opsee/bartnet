(ns bartnet.t-bastion
  (:use midje.sweet)
  (:require [bartnet.bastion :as bastion]
            [bartnet.autobus :as msg]
            [bartnet.fixtures :refer :all]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [clojure.tools.logging :as log]
            [aleph.tcp :as tcp]
            [amazonica.aws.sns :as sns]
            [bartnet.autobus :as msg]))


(def bus (atom nil))
(def server (atom nil))

(def registration-event
  {:customer_id "cliff",
   :hostname "cliff.local",
   :command "connected",
   :id 1,
   :sent 0,
   :version 1,
   :instance_id "i25738ajfi"
   })

(defn publisher [customer-id]
  (let [client (msg/register @bus (msg/publishing-client) customer-id)]
    (fn [msg]
      (msg/publish @bus client msg))))

(defn echo [msg]
  (assoc msg :reply "ok"))

(defn send-reg [client]
  (s/put! client registration-event))

(defn client [host port]
  (let [c (-> (tcp/client {:host host, :port port})
              (d/chain
                (fn [c]
                  (log/info c)
                  c)
                #(bastion/wrap-duplex-stream bastion/protocol %))
              (d/catch (fn [ex]
                         (log/error (str "error " ex)))))]
    (log/info (str "client " c))
    c))

(defn setup-server [port cmds]
  (do
    (start-connection)
    (reset! bus (msg/message-bus))
    (reset! server (bastion/bastion-server @db @bus {:port port}))))

(defn teardown-server []
  (.close @server))

(facts "Bastion channel listens"
  (with-redefs [sns/create-topic (fn [& _] {:topic-arn "test topic"})
                sns/publish (fn [& _] true)]
       (with-state-changes
         [(before :facts (setup-server 4080 {"echo" echo}))
          (after :facts (teardown-server))]
         (fact "registers the client"
               (let [client @(client "localhost" 4080)]
                 @(send-reg client) => true
                 @(s/take! client) => (contains {:in_reply_to 1})
                 (.close client)))
         (with-state-changes
           [(before :facts (do
                             (login-fixtures @db)
                             (environment-fixtures @db)
                             (check-fixtures @db)))]
           (fact "bastions will get existing checks on registration"
                 (let [client @(client "localhost" 4080)]
                   @(send-reg client) => true
                   @(s/take! client) => (contains {:in_reply_to 1})
                   (let [msg @(s/take! client)]
                     msg => (contains {:command "healthcheck"})
                     (:attributes msg) => (contains {:name "A Nice Check"})))))
         (fact "can send commands to the client"
               (let [client @(client "localhost" 4080)
                     publish (publisher "cliff")
                     _ @(send-reg client)
                     _ @(s/take! client)
                     _ @(s/put! client (msg/map->Message {:command "subscribe"
                                                          :customer_id "cliff"
                                                          :attributes {:subscribe_to "cliff.echo1"}}))
                     _ @(s/take! client)
                     defer (publish (msg/map->Message {:command "echo1"
                                                       :customer_id "cliff"
                                                       :instance_id (:instance_id registration-event)
                                                       :attributes {:msg "hello"}}))
                     msg @(s/take! client)]
                 msg => (contains {:id 1, :attributes {:msg "hello"}, :version 1})
                 (.close client))))))
