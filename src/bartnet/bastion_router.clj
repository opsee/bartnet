(ns bartnet.bastion-router
  (:require [verschlimmbesserung.core :as etcd]
            [clj-disco.core :as disco]
            [clojure.string :as str]))

;; piggybacks off of the disco connection

; /opsee.co/routes/customer_id/instance_id/svcname=ip:port

(def base-path "/opsee.co/routes")

(defn- customer-path [customer_id]
  (str/join "/" [base-path customer_id]))

(defn- instance-path [customer_id instance_id]
  (str/join "/" [(customer-path customer_id) instance_id]))

(defn- service-path [customer_id instance_id service-name]
  (str/join "/" [(instance-path customer_id instance_id) service-name]))

(defn- host-port [v]
  (let [[host port] (str/split v #":")]
    {:host host :port (Integer/parseInt port)}))

(defn get-customer-bastions [customer_id]
  (disco/with-etcd
    (let [client @disco/client]
      (keys (etcd/get client (customer-path customer_id))))))

(defn get-services-for-bastion [customer_id instance_id]
  (disco/with-etcd
    (let [client @disco/client]
      (into {}
            (map (fn [[k v]] {k (host-port v)}))
            (etcd/get client (instance-path customer_id instance_id))))))

(defn get-service [customer_id instance_id service-name]
  (disco/with-etcd
    (let [client @disco/client]
      (host-port
       (etcd/get client (service-path customer_id instance_id service-name))))))