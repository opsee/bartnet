(ns bartnet.messaging
  (:require [manifold.deferred :as d]
            [manifold.stream :as s])
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

(defprotocol MessageBus
  (publish [this client-id ^Message msg]
    "Publishes a message to the bus for delivery. The client is checked for proper
    permissions before the message gets published.")
  (register [this ^MessageClient client customer_ids]
    "Registers a client to this bus and authorizes it to publish/subscribe to the
    specified ids. Upon successful registration a client-id is returned.")
  (subscribe [this client-id topics]
    "Subscribes a client to events on a given topic.  Topic names must be prefixed
    with the customer_id if the client is authorized for multiple ID's.")
  (unsubscribe [this client-id topics]
    "Unsubscribes the client.  This operation is idempotent: it has no effect if the
    client was not previously subscribed to a topic.")
  (closed [this ^MessageClient client]
    "Unregisters a client from receiving messages. This will be automatically called
    if client delivery throws an exception."))

