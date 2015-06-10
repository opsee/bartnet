(ns bartnet.autobus
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [manifold.bus :as bus]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.util Date)
           (clojure.lang PersistentHashMap Sequential)))

(defrecord Message [^Integer id
                    ^Integer version
                    ^String command
                    ^Date sent
                    ^PersistentHashMap attributes
                    ^Integer in_reply_to
                    ^String customer_id
                    ^String instance_id
                    ^String host
                    ^String service
                    ^String state
                    ^Date time
                    ^String description
                    ^Sequential tags
                    ^Number metric
                    ^Float ttl])

; Message clients right now are either websockets or bastions, but will be other things
; too, eventually.  Any particular client will need to have its own implementation
; of message delivery.

; Message TTL's are respected: a message will be deliverable from a given topic while its
; TTL is still valid.

(defprotocol MessageClient
  (deliver-to [this ^Message msg] "Completes delivery of a message to this client."))

(defrecord DefaultClient []
  MessageClient
  (deliver-to [_ msg] (log/info "msg undeliverable" msg)))

(defrecord TestingClient [stream]
  MessageClient
  (deliver-to [_ msg]
    (log/info "deliver test" msg)
    (s/put! stream msg)))

(defrecord ClientAdapter [client topic->stream counter permissions])

(defprotocol MessageBus
  (publish [this ^ClientAdapter client ^Message msg]
    "Publishes a message to the bus for delivery. The client is checked for proper
    permissions before the message gets published.")
  (register [this ^MessageClient client customer_ids]
    "Registers a client to this bus and authorizes it to publish/subscribe to the
    specified ids. Upon successful registration a client adapter is returned.")
  (subscribe [this ^ClientAdapter client topics]
    "Subscribes a client to events on a given topic.  Topic names must be prefixed
    with the customer_id if the client is authorized for multiple ID's.")
  (unsubscribe [this ^ClientAdapter client topics]
    "Unsubscribes the client.  This operation is idempotent: it has no effect if the
    client was not previously subscribed to a topic.")
  (close [this ^ClientAdapter client]
    "Unregisters a client from receiving messages. This will be automatically called
    if client delivery throws an exception."))

(defn- msg->topic [^Message msg]
  (str (:customer_id msg) "." (:command msg)))

(defn- client-adapter [client perms]
  (ClientAdapter. client (atom {}) (atom 0) (atom perms)))

(defn- has-permissions? [^ClientAdapter client topic-name]
  (when-not (instance? ClientAdapter client) false)
  (let [[customer_id _] (str/split topic-name #"\." 2)
        permissions @(:permissions client)]
    (log/info customer_id permissions)
    (loop [perm (first permissions)
           tail (rest permissions)]
      (if (or (= "*" perm)
              (= customer_id perm))
        true
        (recur (first tail) (rest tail))))))

(defn- streams-for-client
  ([^ClientAdapter client]
   (seq @(:topic->stream client)))
  ([^ClientAdapter client topics]
   (let [streams @(:topic->stream client)]
     (map
       (fn [topic-name]
         [topic-name ((keyword topic-name) streams)])
       topics))))

(defn- add-stream [^ClientAdapter client topic-name stream]
  (swap! (:topic->stream client) assoc topic-name stream))

(defn- rm-stream [^ClientAdapter client topic-name]
  (swap! (:topic->stream client) dissoc topic-name))

(defn publishing-client [] (DefaultClient.))

(defn testing-client []
  (TestingClient. (s/stream)))

(defn- topics-for-client [^ClientAdapter client]
  (keys (streams-for-client client)))

(defn- split-list [string]
  (if string
    (str/split string #",")
    []))

(defn message-bus []
  (let [bus (bus/event-bus)]
    (reify MessageBus

      (publish [this client msg-noid]
        (let [msg (merge msg-noid {:id (swap! (:counter client) inc)
                                   :version 1})]
          (if (= "subscribe" (:command msg))
            (let [id (:id msg)
                  subscribe-to (split-list (get-in msg [:attributes :subscribe_to]))
                  unsubscribe-from (split-list (get-in msg [:attributes :unsubscribe_from]))]
              (log/info "subscribing to" subscribe-to unsubscribe-from)
              (subscribe this client (flatten [subscribe-to]))
              (unsubscribe this client (flatten [unsubscribe-from]))
              (let [subscriptions (str/join "," (topics-for-client client))]
                (deliver-to (:client client) (map->Message {:id (+ 1 id)
                                                            :in_reply_to id
                                                            :command "subscribe"
                                                            :attributes {:subscriptions subscriptions}
                                                            :state "ok"}))))
            (let [topic (msg->topic msg)]
              (log/info "trying to publish to" topic)
              (when (has-permissions? client topic)
                (log/info "publishing to" topic)
                (log/info msg)
                (bus/publish! bus topic msg))))))

      (register [_ client customer-ids]
        (client-adapter client (flatten [customer-ids])))

      (subscribe [_ client t]
        (log/info "subscribe" t)
        (let [topics (flatten [t])]
          (log/info "topics" topics)
          (when-not (empty? topics)
            (doseq [topic topics]
              (log/info "sub for" topic)
              (when (has-permissions? client topic)
                (let [stream (bus/subscribe bus topic)]
                  (log/info "consuming from" topic)
                  (s/consume
                    (fn [msg]
                      (deliver-to (:client client) msg))
                    stream)
                  (add-stream client topic stream)))))))

      (unsubscribe [_ client t]
        (let [topics (flatten [t])]
          (when-not (empty? topics)
            (log/info topics)
            (doseq [[topic-name stream] (streams-for-client client topics)]
              (log/info topic-name stream)
              (rm-stream client topic-name)
              (s/close! stream)))))

      (close [_ client]
        (doseq [[topic-name stream] (streams-for-client client)]
          (rm-stream client topic-name)
          (s/close! stream))))))