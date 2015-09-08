(ns bartnet.instance
  (:require [taoensso.carmine :as car]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [bartnet.bus :as bus]
            [cheshire.core :refer :all]
            [bartnet.util :refer :all]
            [manifold.deferred :as d])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMap NonBlockingHashSet)
           (clojure.lang Keyword)
           (bartnet.bus MessageClient)))

;; The InstanceStore is the location for all instance and group data required
;; by Bartnet. Currently this includes (per customer_id):
;;   - Instance objects (see Instance schema)
;;   - Set of all instances
;;   - Groups of instances (by service, security group, etc).
;;   - Group metadata (description of the group)
;;   - Set of all groups
;;
;; Upon insertion to the instance store, the following happens:
;;   - The instance is inserted into the master instance set
;;   - Any groups to which the instances belongs are created
;;   - Those groups are added to the set of all groups
;;   - Group metadata is stored
;;   - The instance is added to each of those groups
;;
;; This facilitates searching groups without maintaining any complex
;; data structures ourselves. We will outgrow this at some point.

(defprotocol InstanceStoreProtocol
  "External storage for AWS instance data"
  (list! [this customer-id] "List instances for a customer")
  (get! [this customer-id id] "Get an instance by customer and instance ID")
  (save! [this instance] [this instance ttl] "Save an instance to the instance store w/ optional TTL")
  (group-list! [this customer-id] "List groups for a customer")
  (group-get! [this customer-id id] "Get an instance group by ID")
  (group-save! [this customer-id group] "Save group metadata"))

(def ^{:private true} redis-conn (atom nil))
(def ^{:private true} instance-store (atom nil))

(defn carmine-connection-details []
  {:pool {} :spec @redis-conn})

(defn str->int [k m]
  (let [v (get m k)]
    (when (get m k) {k (Integer. (str (get m k)))})))

(defn- sanitize-connection-details' [conn-info]
  (let [sanitized (merge conn-info
                         (str->int :port conn-info)
                         (str->int :db conn-info))]
    sanitized))

(defn- sanitize-connection-details [conn-info]
  (if-let [spec (:spec conn-info)]
    (sanitize-connection-details' spec)
    (sanitize-connection-details' conn-info)))

(defmacro ^{:private true} with-redis [& body]
  `(try
     (car/wcar (carmine-connection-details) ~@body)
     (catch Exception e#
       (log/info "Error communicating with redis: " e# (.printStackTrace e#)))))

(defn- key-for [^Keyword type customer_id id]
  (string/join ":" [(name type) customer_id id]))

(defn- ikey [customer_id id]
  (key-for :instance customer_id id))

(defn- gkey [customer-id id]
  (key-for :group customer-id id))

(defn- instance->ikey [instance]
  (ikey (:customer_id instance) (:id instance)))

(defn- gmetakey [customer-id id]
  (key-for :group-meta customer-id id))

(defn- iskey [customer-id]
  (key-for :instance-set customer-id "*"))

(defn- gskey [customer-id]
  (key-for :group-set customer-id "*"))

(defrecord MemoryInstanceStore [instances]
  InstanceStoreProtocol
  (list! [_ customer-id]
    (seq (.get instances (iskey customer-id))))
  (get! [_ customer-id id]
    (.get instances (ikey customer-id id)))
  (save! [_ instance]
    ; Put the instance into instance storage.
    (.put instances (instance->ikey instance) instance)
    (let [customer-id (:customer_id instance)]
      ; Add the instance to the customer's instance set
      (let [instance-set-key (iskey customer-id)]
        (when-not (.get instances instance-set-key)
          (.put instances instance-set-key (NonBlockingHashSet.)))
        (.add (.get instances instance-set-key) (select-keys instance [:id :name])))
      ; Group storage
      (doseq [group (:groups instance)]
        (let [group-id (:group_id group)
              group-name (:group_name group)
              gkey           (gkey customer-id group-id)
              group-set-key  (gskey customer-id)
              group-meta-key (gmetakey customer-id group-id)]
          ; Add the group to the customer's group set
          (when-not (.get instances group-set-key)
            (.put instances group-set-key (NonBlockingHashSet.)))
          (.add (.get instances group-set-key) {:id group-id :name group-name})
          ; Prime the group's instance list key
          (when-not (.get instances gkey)
            (.put instances gkey (NonBlockingHashSet.))
            (.put instances group-meta-key group))
          ; Put the instance into the appropriate group
          (.add (.get instances gkey) (:id instance))))))
  (save! [this instance _]
    (log/warn "MemoryInstanceStore does not support TTL")
    (save! this instance))
  (group-get! [_ customer-id id]
    (assoc (.get instances (key-for :group-meta customer-id id))
      :instances (seq (.get instances (key-for :group customer-id id)))))
  (group-save! [_ customer-id group]
    (.put instances (key-for :group-meta customer-id (:group_id group)) group))
  (group-list! [_ customer-id]
    (seq (.get instances (gskey customer-id)))))

(defn- scan-wrapper
  ([fn key idx]
   (with-redis
     (fn key idx)))
  ([fn key]
   (let [page (scan-wrapper fn key 0)]
     (when-not (empty? page)
       (let [cursor   (Integer. (first page))
             smembers (second page)]
         (if (= 0 cursor)
           smembers
           (concat smembers (scan-wrapper fn key cursor))))))))

(defrecord RedisInstanceStore []
  InstanceStoreProtocol
  (list! [_ customer-id]
    (scan-wrapper car/sscan (iskey customer-id)))
  (get! [_ customer-id id]
    (with-redis
      (car/get (ikey customer-id id))))
  (save! [_ instance]
    (with-redis
      (car/set (instance->ikey instance) instance)
      (let [customer-id (:customer_id instance)]
        (car/sadd (iskey customer-id) (select-keys instance [:id :name]))
        (doseq [group (:groups instance)]
          (let [group-id (:group_id group)
                group-name (:group_name group)
                group-key (gkey customer-id group-id)
                group-set-key (gskey customer-id)
                group-meta-key (gmetakey customer-id group-id)]
            (car/sadd group-set-key {:id group-id :name group-name})
            (car/sadd group-key (:id instance))
            (car/set group-meta-key group)))))
    instance)
  (save! [this instance ttl]
    (save! this instance)
    (with-redis
      (car/expire (instance->ikey instance) ttl)
      ; TODO: Expire instances from group and expire empty groups from group list.
      )
    instance)
  (group-get! [_ customer-id id]
    (let [group-meta (with-redis (car/get (gmetakey customer-id id)))
          instances (scan-wrapper car/sscan (gkey customer-id id))]
      (assoc group-meta :instances instances)))
  (group-save! [_ customer-id group]
    (with-redis
      (car/set (gmetakey customer-id (:id group)) group)))
  (group-list! [_ customer-id]
    (scan-wrapper car/sscan (gskey customer-id))))

(defn list-instances! [customer-id]
  (list! @instance-store customer-id))

(defn list-groups! [customer-id]
  (group-list! @instance-store customer-id))

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

(defn- message->instance [msg instance-attributes]
  (let [instance-id (:InstanceID instance-attributes)
        customer-id (:customer_id msg)
        instance-name (:Value (first (filter #(= "Name" (:Key %)) (:Tags instance-attributes))))]
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
    (deliver-to [_ topic msg]
      (log/info "msg" topic msg)
      (when (= (:type msg) "Instance")
        (let [instance (parse-string (:body msg) true)]
          (d/deferred (do
                        (log/info "instance-store registering instance:" (:InstanceID instance))
                        (save-instance! (message->instance msg instance)))))))
    (session-id [this] nil)))

(defn connect-bus [bus]
  (log/info "Connecting instance store to message bus.")
  (let [client (bus/register bus (instance-message-client) [bus/firehose-sigil])]
    (bus/subscribe bus client bus/firehose-sigil '("discovery"))))

(defn create-memory-store
  ([bus coll]
   (reset! instance-store (MemoryInstanceStore. coll))
   (when bus (connect-bus bus)))
  ([bus]
   (log/info "Setting up MemoryInstanceStore")
   (create-memory-store bus (NonBlockingHashMap.))))

(defn create-redis-store [bus connection-details]
  "Given a map of connection information, return an instance store"
  (do
    (reset! redis-conn (sanitize-connection-details connection-details))
    (reset! instance-store (RedisInstanceStore.))
    (log/info "Connecting to RedisInstanceStore at" @redis-conn)
    (when bus (connect-bus bus))))