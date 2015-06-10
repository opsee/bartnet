(ns bartnet.instance
  (:require [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [bartnet.autobus :as msg]
            [manifold.deferred :as d])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMap NonBlockingHashSet)
           (clojure.lang Keyword)
           (bartnet.autobus MessageClient MessageBus)))

(defprotocol InstanceStoreProtocol
  "External storage for AWS instance data"
  (get! [this customer_id id] "Get an instance by customer and instance ID")
  (save! [this instance] [this instance ttl] "Save an instance to the instance store w/ optional TTL")
  (group-get! [this customer_id id] "Get an instance group by ID")
  (group-save! [this customer_id group] "Save group metadata"))

(def ^{:private true} redis-conn (atom nil))
(def ^{:private true} instance-store (atom nil))

(defn carmine-connection-details []
  {:pool {} :spec @redis-conn})

(defmacro ^{:private true} with-redis [& body]
  `(try
     (car/wcar (carmine-connection-details) ~@body)
     (catch Exception e#
       (log/info "Error communicating with redis: " e# (.printStackTrace e#)))))

(defn- key-for [^Keyword type customer_id id]
  (string/join ":" [(name type) customer_id id]))

(defn- ikey [customer_id id]
  (key-for :instance customer_id id))

(defn- gkey [customer_id id]
  (key-for :group customer_id id))

(defn- instance->ikey [instance]
  (ikey (:customer_id instance) (:id instance)))

(defn- instance->gkey [instance]
  (let [group-id (:group_id instance)]
    (key-for :group (:customer_id instance) group-id)))

(defn- gmetakey [customer_id id]
  (key-for :group-meta customer_id id))

(defrecord MemoryInstanceStore [instances]
  InstanceStoreProtocol
  (get! [_ customer_id id]
    (.get instances (ikey customer_id id)))
  (save! [_ instance]
    ; Put the instance into instance storage.
    (.put instances (instance->ikey instance) instance)
    ; Group storage
    (let [customer_id (:customer_id instance)]
      (doseq [group (:groups instance)]
        (let [group_id (:group_id group)
              gkey           (gkey customer_id group_id)
              group-meta-key (gmetakey customer_id group_id)]
          ; Prime the group's instance list key
          (when-not (.get instances gkey)
            (.put instances gkey (NonBlockingHashSet.))
            (.put instances group-meta-key group))
          ; Put the instance into the appropriate group
          (.add (.get instances gkey) (:id instance))))))
  (save! [this instance _]
    (log/warn "MemoryInstanceStore does not support TTL")
    (save! this instance))
  (group-get! [_ customer_id id]
    (assoc (.get instances (key-for :group-meta customer_id id))
      :instances (seq (.get instances (key-for :group customer_id id)))))
  (group-save! [_ customer_id group]
    (.put instances (key-for :group-meta customer_id (:group_id group)) group)))

;The redis datastore looks roughly like so:
;`type:customer_id:id`
;types:
;  - instance
;  - group
;  - group-meta

(defrecord RedisInstanceStore []
  InstanceStoreProtocol
  (get! [_ customer_id id]
    (with-redis
      (car/get (ikey customer_id id))))
  (save! [_ instance]
    (with-redis
      (car/set (instance->ikey instance) instance)
      (let [customer_id (:customer_id instance)]
        (doseq [group (:groups instance)]
          (car/sadd (gkey customer_id (:group_id group)) (:id instance))
          (car/set (gmetakey customer_id (:group_id group)) group))))
    instance)
  (save! [this instance ttl]
    (save! this instance)
    (with-redis
      (car/expire (instance->ikey instance) ttl)
      ; TODO: Expire instances from group and expire empty groups from group list.
      )
    instance)
  (group-get! [_ customer_id id]
    (with-redis
      (let [group-meta (car/get (key-for :group-meta customer_id id))
            instances (car/get (key-for :group customer_id id))]
        (assoc group-meta :instances instances))))
  (group-save! [_ customer_id group]
    (with-redis
      (car/set (key-for :group-meta customer_id (:id group)) group))))

(defn get-instance! [customer_id id]
  (get! @instance-store customer_id id))

(defn save-instance!
  ([instance ttl]
    (save! @instance-store instance ttl))
  ([instance]
   (save! @instance-store instance)))

(defn get-group! [customer_id id]
  (group-get! @instance-store customer_id id))

(defn save-group! [customer_id group]
  (group-save! @instance-store customer_id group))

(defn- filter-instance-attributes [attrs]
  {
   :created (:LaunchTime attrs)
   :instanceSize (:InstanceType attrs)
   })

(defn message->instance [msg]
  (let [instance-attributes (get-in msg [:attributes :instance])
        instance-id (:InstanceID instance-attributes)
        customer-id (:customer_id msg)
        instance-name (:Value (filter #(= "Name" (:Key %)) (:Tags instance-attributes)))]
    {:name        instance-name
     :id          instance-id
     :customer_id customer-id
     :groups      (map (fn [g] {:group_name (:GroupName g)
                          :group_id (:GroupID g)})
                    (:SecurityGroups instance-attributes))
     :meta        (filter-instance-attributes instance-attributes)}))

(defn instance-message-client []
  (reify
    MessageClient
    (deliver-to [_ msg]
      (when-let [instance (get-in msg [:attributes :instance])]
        (d/deferred (do
                      (log/info "instance-store registering instance:" (:InstanceID instance))
                      (save-instance! (message->instance msg))))))))

(defn connect-bus [bus]
  (log/info "Connecting instance store to message bus.")
  (let [client (msg/register bus (instance-message-client) '("*"))]
    (msg/subscribe bus client '("*.discovery"))))

(defn create-memory-store
  ([bus coll]
   (reset! instance-store (MemoryInstanceStore. coll))
   (connect-bus bus))
  ([bus]
   (log/info "Setting up MoemoryInstanceStore")
   (create-memory-store bus (NonBlockingHashMap.))))

(defn create-redis-store [bus connection-details]
  "Given a map of connection information, return an instance store"
  (do
    (reset! redis-conn connection-details)
    (reset! instance-store (RedisInstanceStore.))
    (connect-bus bus)))