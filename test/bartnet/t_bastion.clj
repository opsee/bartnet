(ns bartnet.t-bastion
  (:use midje.sweet)
  (:require [bartnet.pubsub :as pubsub]
            [bartnet.bastion :as bastion]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [clojure.tools.logging :as log]
            [aleph.tcp :as tcp]))

(def pubsub (atom nil))
(def server (atom nil))

(defn echo [msg]
  (assoc msg :reply "ok"))

(defn send-reg [client]
  (s/put! client {:command "connected",
                  :id 1,
                  :sent 0,
                  :version 1,
                  :message {:hostname "cliff.local",
                            :id "cliff",
                            :customer-id "customer"}}))

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
    (reset! pubsub (pubsub/create-pubsub))
    (reset! server (bastion/bastion-server @pubsub cmds {:port port}))))

(defn teardown-server []
  (.close @server))

(facts "Bastion channel listens"
       (with-state-changes
         [(before :facts (setup-server 10000 {"echo" echo}))
          (after :facts (teardown-server))]
         (fact "can echo the client"
               (let [client @(client "localhost" 10000)]
                 @(s/put! client {:command "echo", :id 1}) => true
                 @(s/take! client) => {:command "echo", :id 1, :reply "ok", :in_reply_to 1}))
         (fact "registers the client"
               (let [client @(client "localhost" 10000)]
                 @(send-reg client) => true
                 @(s/take! client) => (contains {:in_reply_to 1})
                 (.close client)))
         (fact "can send commands to the client"
               (let [client @(client "localhost" 10000)
                     _ @(send-reg client)
                     _ @(s/take! client)
                     defer (pubsub/send-msg @pubsub "cliff" "echo1" {:msg "hello"})
                     msg @(s/take! client)]
                 msg => (contains {:id 1, :message {:msg "hello"}, :version 1})
                 @(s/put! client {:id 2, :in_reply_to 1, :message {:msg "hey"}, :version 1, :sent 0})
                 @defer => (contains {:in_reply_to 1})))))
