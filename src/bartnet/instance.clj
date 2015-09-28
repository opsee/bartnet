(ns bartnet.instance
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [clojure.string :refer :all]
            [clj-http.client :as http]))

(def store-host (atom nil))

(defn- request [method customer-id endpoint body]
  (let [opts {"Customer-Id" customer-id :content-type :json :accept :json :body body}
        response (method (join "/" [@store-host endpoint]) opts)
        status (:status response)]
    (cond
      (<= 200 status 299) (parse-string (:body response) keyword)
      :else (throw (Exception. "failed to get instances from the instance store")))))

(defn- get [endpoint options]
  (let [customer-id (:customer_id options)
        type (:type options)
        id (:id options)
        ep (cond-> [endpoint]
             type (conj type)
             id (conj id))]
    (request http/get customer-id (join "/" ep) nil)))

(defn- post [endpoint options]
  (let [customer-id (:customer_id options)
        options (dissoc options :customer_id)]
    (request http/post customer-id endpoint (generate-string options))))

(defn list-instances! [options]
  (if (:id options)
    (get "instance" options)
    (get "instances" options)))

(defn list-groups! [options]
  (if (:id options)
    (get "group" options)
    (get "groups" options)))

(defn get-customer! [options]
  (get "customer" options))

(defn discover! [options]
  (post "discovery" options))
