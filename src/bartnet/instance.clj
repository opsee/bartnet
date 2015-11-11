(ns bartnet.instance
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [bartnet.results :as results]
            [clojure.string :refer :all]
            [clj-http.client :as http]))

(def store-addr (atom nil))

(defn- request [method customer-id endpoint body]
  (let [uri (join "/" [@store-addr endpoint])]
    (case method
      :get (http/get uri
                     {:throw-entire-message? true
                      :headers {"Customer-Id" customer-id
                                "Content-Type" "application/json"
                                "Accept" "application/json"}})
      :post (http/post uri
                       {:throw-entire-message? true
                        :headers {"Customer-Id" customer-id
                                  "Content-Type" "application/json"
                                  "Accept" "application/json"}
                        :body body}))))

(defn- get [endpoint options]
  (let [customer-id (:customer_id options)
        type (:type options)
        id (:id options)
        ep (cond-> [endpoint]
             type (conj type)
             id (conj id))]
    (request :get customer-id (join "/" ep) nil)))

(defn- post [endpoint options]
  (let [customer-id (:customer_id options)
        options (dissoc options :customer_id)]
    (request :post customer-id endpoint (generate-string options))))

(defn list-instances! [ options]
  {:store (get  (if (:id options) "instance"
                                  "instances") options)
   :results (results/get-results  options)})

(defn list-groups! [ options]
  {:store (get (if (:id options) "group"
                                 "groups") options)
   :results (results/get-results  options)})

(defn get-customer! [ options]
  (get  "customer" options))

(defn discover! [ options]
  (post  "onboard" options))
