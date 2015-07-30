(ns bartnet.bus
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [clojure.string :as str])
  (:import (clojure.lang IPersistentMap)))



(defrecord BusMessage [^Integer id
                       ^Integer reply_to
                       ^String type
                       ^String body])

(defmulti make-msg (fn [_ body] (class body)))
(defmethod make-msg IPersistentMap [type body]
  (map->BusMessage {:type type
                    :body (generate-string body)}))
(defmethod make-msg String [type body]
  (map->BusMessage {:type type
                    :body body}))


; Message clients right now are either websockets or bastions, but will be other things
; too, eventually.  Any particular client will need to have its own implementation
; of message delivery.

(defprotocol MessageClient
  (deliver-to [this topic ^BusMessage msg] "Completes delivery of a message to this client.")
  (session-id [this] "Calculates a session id that is unique to this client."))

(defprotocol MessageBus
  (publish [this ^ClientAdapter client customer_id topic msg]
    "Publishes a message to the bus for delivery. The client is checked for proper
    permissions before the message gets published.")
  (register [this ^MessageClient client customer_ids]
    "Registers a client to this bus and authorizes it to publish/subscribe to the
    specified ids. Upon successful registration a client adapter is returned.")
  (subscribe [this ^ClientAdapter client customer_id topics]
    "Subscribes a client to events on a given topic.  Topic names must be prefixed
    with the customer_id if the client is authorized for multiple ID's.")
  (unsubscribe [this ^ClientAdapter client customer_id topics]
    "Unsubscribes the client.  This operation is idempotent: it has no effect if the
    client was not previously subscribed to a topic.")
  (close [this ^ClientAdapter client]
    "Unregisters a client from receiving messages. This will be automatically called
    if client delivery throws an exception."))

(defprotocol Consumer
  "The consumer is meant to be supplied by a message bus implementation, and encapsulates
  a subscription.  The bus implementation is meant to start the consumer when it is created"
  (stop! [this]))

(defprotocol InternalBus
  "The internal bus is the interface that any message bus implementation must conform to.
  All the bookkeeping of permissions, closing consumers and the like will be handled by the
  messagebus reified through this namespace."
  (publish! [this topic msg])
  (subscribe! [this topic ^MessageClient client]))

(defrecord DefaultClient []
  MessageClient
  (deliver-to [_ topic msg] (log/info "msg undeliverable" msg)))

(defrecord ClientAdapter [client topic->consumer counter permissions])

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

(defn- consumers-for-client
  ([^ClientAdapter client]
   (seq @(:topic->consumer client)))
  ([^ClientAdapter client topics]
   (let [consumers @(:topic->consumer client)]
     (map
       (fn [topic-name]
         [topic-name ((keyword topic-name) consumers)])
       topics))))

(defn- add-consumer [^ClientAdapter client topic-name consumer]
  (swap! (:topic->consumer client) assoc topic-name consumer))

(defn- rm-consumer [^ClientAdapter client topic-name]
  (swap! (:topic->consumer client) dissoc topic-name))

(defn publishing-client [] (DefaultClient.))

(defn- topics-for-client [^ClientAdapter client]
  (keys (consumers-for-client client)))

(defn- split-list [string]
  (if string
    (str/split string #",")
    []))

(defn id-inc [client]
  (swap! (:counter client) inc))

(defn message-bus [ bus]
  (reify MessageBus

    (publish [this client customer_id topic msg-noid]
      (let [msg (merge msg-noid {:id (id-inc client)
                                 :version 1})]
        (if (= "subscribe" (:type msg))
          (let [id (:id msg)
                body (parse-string (:body msg) true)
                subscribe-to (split-list (get-in body [:subscribe_to]))
                unsubscribe-from (split-list (get-in msg [:unsubscribe_from]))]
            (log/info "subscribing to" subscribe-to unsubscribe-from)
            (subscribe this client customer_id (flatten [subscribe-to]))
            (unsubscribe this client customer_id (flatten [unsubscribe-from]))
            (let [subscriptions (str/join "," (topics-for-client client))]
              (deliver-to (:client client) topic (map->BusMessage {:id (id-inc client)
                                                                   :in_reply_to id
                                                                   :type "subscribe"
                                                                   :body (generate-string {:subscriptions subscriptions})}))))
          (let [topic (str/join "." [customer_id topic])
                firehose (str "*." (:command msg))]
            (log/info "trying to publish to" topic)
            (when (has-permissions? client topic)
              (log/info "publishing to" topic)
              (log/info "publishing to" firehose)
              (log/info msg)
              (publish! bus topic msg)
              (publish! bus firehose msg))))))

    (register [_ client customer-ids]
      (client-adapter client (flatten [customer-ids])))

    (subscribe [_ client customer_id t]
      (log/info "subscribe" t)
      (let [topics (map #(str/join "." [customer_id %]) (flatten [t]))]
        (log/info "topics" topics)
        (when-not (empty? topics)
          (doseq [topic topics]
            (log/info "sub for" topic)
            (when (has-permissions? client topic)

              (let [consumer (subscribe! bus topic (:client client))]
                (log/info "consuming from" topic)
                (add-consumer client topic consumer)))))))

    (unsubscribe [_ client customer_id t]
      (let [topics (map #(str/join "." [customer_id %]) (flatten [t]))]
        (when-not (empty? topics)
          (log/info topics)
          (doseq [[topic-name consumer] (consumers-for-client client topics)]
            (log/info topic-name consumer)
            (rm-consumer client topic-name)
            (stop! consumer)))))

    (close [_ client]
      (doseq [[topic-name consumer] (consumers-for-client client)]
        (rm-consumer client topic-name)
        (stop! consumer)))))