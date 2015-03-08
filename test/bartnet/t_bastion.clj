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

(defn do-setup []
  (do
    ))

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

(with-state-changes
  []
  (facts "Bastion channel listens"
         (fact "can echo"
               (let [pubsub (pubsub/create-pubsub)
                     server (bastion/bastion-server pubsub {"echo" echo} {:port 10000})
                     client @(client "localhost" 10000)]
                 @(s/put! client {:cmd "echo", :seq 1}) => true
                 @(s/take! client) => {:cmd "echo", :reply "ok", :seq 1}))))