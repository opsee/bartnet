(ns bartnet.pubsub
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [manifold.bus :as b])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMap]))

(defn- bastion-topic [id]
  (str id "-bastion"))

(defn- command-topic [id]
  (str id "-commands"))

(defprotocol SendableConn
  (send-and-recv [this cmd msg]))

(defprotocol PubSub
  (register-bastion [this connection msg])
  (register-ws-client [this connection])
  (publish-command [this id msg])
  (publish-bastion [this id msg])
  (send-msg [this id cmd msg]))

(defrecord PubSubBus [bus bastions ws-clients]
  PubSub
  (register-bastion [_ connection msg]
    (let [id (:customer-id connection)
          stream (b/subscribe bus (command-topic id))]
      (log/info connection)
      (.put bastions (:id connection) {:connection connection, :stream stream})
      (b/publish! bus (bastion-topic id) msg)
      stream))
  (register-ws-client [_ connection]
    (let [id (:customer-id connection)
          stream (b/subscribe bus (bastion-topic id))]
      (.put ws-clients (:id connection) {:connection connection, :stream stream})
      stream))
  (publish-command [_ id msg]
    (b/publish! bus (command-topic id) msg))
  (publish-bastion [_ id msg]
    (b/publish! bus (bastion-topic id) msg))
  (send-msg [_ id cmd msg]
    (log/info "bastions " bastions)
    (if-let [connrec (.get bastions id)]
      (send-and-recv (:connection connrec) cmd msg))))

(defn create-pubsub []
  (PubSubBus. (b/event-bus) (NonBlockingHashMap.) (NonBlockingHashMap.)))