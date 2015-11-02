(ns bartnet.instance
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [bartnet.results :as results]
            [clojure.string :refer :all]
            [http.async.client :as http]))

(def store-addr (atom nil))

(defn- request [method client customer-id endpoint body]
  (let [opts {:headers {"Customer-Id" customer-id
                        "Content-Type" "application/json"
                        "Accept" "application/json"}
              :body body}]
    (apply method client (join "/" [@store-addr endpoint]) opts)))

(defn- get [client endpoint options]
  (let [customer-id (:customer_id options)
        type (:type options)
        id (:id options)
        ep (cond-> [endpoint]
             type (conj type)
             id (conj id))]
    (request http/GET client customer-id (join "/" ep) nil)))

(defn- post [client endpoint options]
  (let [customer-id (:customer_id options)
        options (dissoc options :customer_id)]
    (request http/POST client customer-id endpoint (generate-string options))))

(defn list-instances! [client options]
  {:store (get client (if (:id options) "instance"
                                        "instances") options)
   :results (results/get-results client options)})

(defn list-groups! [client options]
  {:store (get client (if (:id options) "group"
                                        "groups") options)
   :results (results/get-results client options)})

(defn get-customer! [client options]
  (get client "customer" options))

(defn discover! [client options]
  (post client "onboard" options))
