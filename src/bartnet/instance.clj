(ns bartnet.instance
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [bartnet.results :as results]
            [clojure.string :refer :all]
            [http.async.client :as http]))

(def store-addr (atom nil))

(defn- request [method client customer-id endpoint body]
  (let [uri (join "/" [@store-addr endpoint])]
    (case method
      :get (http/GET client uri
                     :headers {"Customer-Id" customer-id
                               "Content-Type" "application/json"
                               "Accept" "application/json"})
      :post (http/POST client uri
                       :headers {"Customer-Id" customer-id
                                 "Content-Type" "application/json"
                                 "Accept" "application/json"}
                       :body body))))

(defn- get [client endpoint options]
  (let [customer-id (:customer_id options)
        type (:type options)
        id (:id options)
        ep (cond-> [endpoint]
             type (conj type)
             id (conj id))]
    (request :get client customer-id (join "/" ep) nil)))

(defn- post [client endpoint options]
  (let [customer-id (:customer_id options)
        options (dissoc options :customer_id)]
    (request :post client customer-id endpoint (generate-string options))))

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
