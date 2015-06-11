(ns bartnet.instance
  (:require [taoensso.carmine :as car]
            [clojure.tools.logging :as log])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMap)))

(defprotocol InstanceStoreProtocol
  "External storage for AWS instance data"
  (get! [this customer_id id] "Get an instance by customer and instance ID")
  (save! [this instance] [this instance ttl] "Save an instance to the instance store w/ optional TTL"))

; TODO: Make redis connection information configurable
(def ^{:private true} redis-conn (atom nil))
(def ^{:private true} instance-store (atom nil))

(defn carmine-connection-details []
  {:pool {} :spec @redis-conn})

(defmacro ^{:private true} with-redis [& body]
  `(try
     (car/wcar (carmine-connection-details) ~@body)
     (catch Exception e#
       (log/info "Error communicating with redis: " e# (.printStackTrace e#)))))

(defn- ikey [customer_id id]
  (str customer_id ":" id))

(defn- instance->ikey [instance]
  (ikey (:customer_id instance) (:id instance)))

(defrecord MemoryInstanceStore [instances]
  InstanceStoreProtocol
  (get! [_ customer_id id]
    (.get instances (ikey customer_id id)))
  (save! [_ instance]
    (.put instances  (instance->ikey instance) instance))
  (save! [_ instance _]
    (log/warn "TTL unsupported in MemoryInstanceStore")
    (.put instances (instance->ikey instance) instance)))

;The redis datastore looks roughly like so:
;`type:id`
;types:
;  - instance

(defn- rkey [ikey]
  (str "instance:" ikey))

(defn- instance->rkey [instance]
  (rkey (instance->ikey instance)))

(defrecord RedisInstanceStore []
  InstanceStoreProtocol
  (get! [_ customer_id id]
    (with-redis
      (car/get (rkey (ikey customer_id id)))))
  (save! [_ instance]                                 ; persistent
    (with-redis
      (car/set (instance->rkey instance) instance))
    instance)
  (save! [_ instance ttl]
    (with-redis
      (car/set (instance->rkey instance) instance)
      (car/expire (instance->rkey instance) (or 60000 ttl)))
    instance))

(defn create-memory-store
  ([]
    (reset! instance-store (MemoryInstanceStore. (NonBlockingHashMap.))))
  ([coll]
    (reset! instance-store (MemoryInstanceStore. coll))))

(defn create-redis-store [r & {:keys [host port db],
                               :or   {
                                      :host "127.0.0.1",
                                      :port 6379,
                                      :db   1
                                      }
                               :as   connection-details}]
  "Given a map of connection information, return an instance store"
  :pre (and (string? host) (number? port) (number? db))
  (do
    (reset! redis-conn connection-details)
    (reset! instance-store (RedisInstanceStore.))))

(defn get-instance! [customer_id id]
  (get! @instance-store customer_id id))

(def default-instance-ttl 60000)

(defn save-instance!
  ([instance ttl]
    (save! @instance-store instance ttl))
  ([instance]
   (save! @instance-store instance default-instance-ttl)))

