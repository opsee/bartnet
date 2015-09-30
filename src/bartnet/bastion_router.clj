(ns bartnet.bastion-router
  (:require [cheshire.core :refer [parse-string]]
            [verschlimmbesserung.core :as etcd]
            [clj-disco.core :as disco]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; piggybacks off of the disco connection

; /opsee.co/routes/customer_id/instance_id
; { "name": { name: "", port: "", hostname: "" } }

(def base-path "/opsee.co/routes")

(defn- customer-path [customer_id]
  (str/join "/" [base-path customer_id]))

(defn- instance-path [customer_id instance_id]
  (str/join "/" [(customer-path customer_id) instance_id]))

(defn- host-port [v]
  (when v
    {:port (Integer/parseInt (get v "port"))
     :host (get v "hostname")}))

(defn get-customer-bastions [customer_id]
  (disco/with-etcd
    (let [client @disco/client]
      (keys (etcd/get client (customer-path customer_id))))))

(defn get-bastion-service [portmap service-name]
  (when portmap
    (let [m (parse-string portmap)]
      (get m service-name))))

(defn get-service [customer_id instance_id service-name]
  (disco/with-etcd
    (let [client @disco/client]
      (host-port
        (get-bastion-service
          (etcd/get client (instance-path customer_id instance_id))
          service-name)))))