(ns bartnet.instance
  (:require [taoensso.carmine :as car]
            [clojure.tools.logging :as log]))

(defprotocol InstanceStoreProtocol
  "Exteranl storage for AWS instance data"
  (connect! [this] "Connect to the datastore with the provided configuration")
  (get! [this id] "Get an instance by instance ID")
  (save! [this instance] [store instance ttl] "Save an instance to the instance store w/ optional TTL"))

; TODO: Make redis connection information configurable
(def ^{:private true} redis-conn (atom nil))

(defmacro ^{:private true} with-redis [& body] `(car/wcar @redis-conn ~@body))

; The redis datastore looks roughly like so:
; `type:id`
; types:
;   - instance

(defn- map-to-redis-hash [m]
  )

(defn- instance-key [instance]
  (str "instance:" (:id instance)))

(defrecord RedisInstanceStore [connection]
  InstanceStoreProtocol
  (get! [_ id]
    (with-redis (car/get id)))
  (save! [_ instance]                                 ; persistent
    (with-redis
      (car/set (instance-key instance) (map-to-redis-hash instance))))
  (save! [this instance ttl]
    (try
      (save! this instance)
      (with-redis
        (car/expire (instance-key (:id instance)) 60000))
      (catch Exception e (log/info "Error communicating with redis: " e)))))

(def instance-store (atom nil))

(defn connect! [{:keys [host port db],
                 :or {
                      :host "127.0.0.1",
                      :port 6379,
                      :db 1
                      }
                 :as connection-details}]
  "Given a map of connection information, return an instance store"
  :pre (and (string? host) (number? port) (number? db))
  (do
    (reset! redis-conn connection-details)
    (reset! instance-store (RedisInstanceStore. connection-details))))


(defn get-instance [id]
  (get! instance-store id))

(def default-instance-ttl 60000)

(defn save-instance
  ([instance ttl]
    (save! instance-store instance ttl))
  ([instance]
   (save! instance-store instance default-instance-ttl)))

