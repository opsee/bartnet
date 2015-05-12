(ns bartnet.launch
  (:require [bartnet.bastion :as bastion]
            [bartnet.identifiers :as identifiers]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [cheshire.core :refer :all]
            [amazonica.aws.cloudformation :refer [create-stack]]
            [clostache.parser :refer [render-resource]]
            [amazonica.aws.ec2 :refer [describe-images]])
  (:import [java.util.concurrent ExecutorService]
           [java.util Base64 Base64.Encoder]))

(defn encode-user-data [data]
  (let [encoder (Base64/getMimeEncoder)
        json (generate-string data)]
    (.encodeToString encoder (.getBytes json))))

(defn get-latest-stable-image [creds owner-id tag]
  (let [{images :images} (describe-images creds :owners [owner-id] :filters [{:name "tag:release" :values [tag]}])]
    (first (sort-by :name images))))

(defn launcher [creds]
  (fn []
    (let [id (identifiers/generate)]
      (create-stacl creds
                    :stack-name (str "opsee-bastion-id")
                    :template-body ))))

(defn launch-bastions [^ExecutorService executor customer-id msg options]
  (let [access-key (:access-key msg)
        secret-key (:secret-key msg)
        regions (:regions msg)
        owner-id (:owner-id options)
        tag (:tag options)]
    (doseq
      (for [region-obj regions]
        (let [creds {:access-key access-key
                     :secret-key secret-key
                     :region (:region region-obj)}
              image-id (get-latest-stable-image creds owner-id tag)]
          (for [vpc (:vpcs region-obj)]
            (let [stream (s/stream)]
               (.submit
                 executor
                 (fn []
                   (let [id (identifiers/generate)]
                     (create-stack creds
                                      :stack-name (str "opsee-bastion-" id)
                                      :template-body (render-resource "templates/bastion-cf.mustache"))))))))))))


