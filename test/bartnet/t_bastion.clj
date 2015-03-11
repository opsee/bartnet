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

(def pubsub (pubsub/create-pubsub))
(def server (atom nil))

(defn echo [msg]
  (assoc msg :reply "ok"))

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
  (reset! server (bastion/bastion-server pubsub cmds {:port port})))

(defn teardown-server []
  (.close @server))

(facts "Bastion channel listens"
       (with-state-changes
         [(before :facts (setup-server 10000 {"echo" echo}))
          (after :facts (teardown-server))]
         (fact "and can echo the client"
               (let [client @(client "localhost" 10000)]
                 @(s/put! client {:command "echo", :id 1}) => true
                 @(s/take! client) => {:command "echo", :id 1, :reply "ok", :in_reply_to 1}))
         (fact "registers the client"
               (let [client @(client "localhost" 10000)]
                 @(s/put! client {:command "connected",
                                  :id 1,
                                  :sent 0,
                                  :version 1,
                                  :message {:hostname "cliff.local",
                                            :id "cliff",
                                            :customer-id "customer"}}) => true
                 @(s/take! client) => (contains {:in_reply_to 1})))))
