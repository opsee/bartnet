(ns bartnet.instance
  (:require [taoensso.carmine :as car]
            [clojure.tools.logging :as log]))

(defprotocol InstanceStoreProtocol
  "Exteranl storage for AWS instance data"
  (get! [this id] "Get an instance by instance ID")
  (save! [this instance] [store instance ttl] "Save an instance to the instance store w/ optional TTL"))

; TODO: Make redis connection information configurable
(def ^{:private true} redis-conn (atom nil))

 ;The redis datastore looks roughly like so:
 ;`type:id`
 ;types:
 ;  - instance

(defn- instance-key [id]
  (str "instance:" id))

(defn carmine-connection-details []
  {:pool {} :spec @redis-conn})

(defmacro ^{:private true} with-redis [& body]
  `(try
     (car/wcar (carmine-connection-details) ~@body)
     (catch Exception e#
       (log/info "Error communicating with redis: " e# (.printStackTrace e#)))))

; MemoryInstanceStore is horribly unsafe and is used only for testing.
(deftype MemoryInstanceStore [^{:volatile-mutable true} map]
  InstanceStoreProtocol
  (get! [_ id]
    (get map id))
  (save! [_ instance]
    (set! map (assoc map "id" instance)))
  (save! [this instance ttl]
    (set! map (assoc map "id" instance))))

(defrecord RedisInstanceStore []
  InstanceStoreProtocol
  (get! [_ id]
    (with-redis
      (car/get (instance-key id))))
  (save! [_ instance]                                 ; persistent
    (with-redis
      (car/set (instance-key (:id instance)) instance))
    instance)
  (save! [_ instance ttl]
    (with-redis
      (car/set (instance-key (:id instance)) instance)
      (car/expire (instance-key (:id instance)) (or 60000 ttl)))
    instance))

(def instance-store (atom nil))

; TODO: Only let this be called once?
(defn connect!
  ([{:keys [host port db],
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
      (reset! instance-store (RedisInstanceStore.)))))

(defn get-instance! [id]
  (get! @instance-store id))

(def default-instance-ttl 60000)

(defn save-instance!
  ([instance ttl]
    (save! @instance-store instance ttl))
  ([instance]
   (save! @instance-store instance default-instance-ttl)))

