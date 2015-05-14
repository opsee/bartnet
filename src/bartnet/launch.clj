(ns bartnet.launch
  (:require [bartnet.identifiers :as identifiers]
            [bartnet.s3-buckets :as buckets]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [cheshire.core :refer :all]
            [instaparse.core :as insta]
            [clostache.parser :refer [render-resource]]
            [amazonica.aws.cloudformation :refer [create-stack]]
            [amazonica.aws.ec2 :refer [describe-images]]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.core.match :refer [match]])
  (:import [java.util.concurrent ExecutorService]
           [java.util Base64]))

(def beta-map (reduce #(assoc %1 %2 (buckets/url-to %2 "beta/bastion-cf.template")) {} buckets/regions))

(def cf-parser
  (insta/parser
    "message = line+
     line = key <'='> value <'\\n'>
     key = #'[a-zA-Z]+'
     value = <'\\''> (#'.')* <'\\''>"))

(defn encode-user-data [data]
  (let [encoder (Base64/getMimeEncoder)
        json (generate-string data)]
    (.encodeToString encoder (.getBytes json))))

(defn get-latest-stable-image [creds owner-id tag]
  (let [{images :images} (describe-images creds :owners [owner-id] :filters [{:name "tag:release" :values [tag]}])]
    (first (sort-by :name images))))

(defn parse-cloudformation-msg [msg]
  ;(log/info msg)
  (if-let [msg-str (:Message msg)]
    (let [parsed (cf-parser msg-str)]
      (try
        (assoc
          msg
          :Message
          (reduce (fn [obj token]
                    (match token
                           :message obj
                           [:line [:key key] [:value & strings]] (assoc obj
                                                                   (keyword key)
                                                                   (str/join "" strings))))
                  {}
                  parsed))
        (catch Exception e (pprint msg) (log/error e "exception on parse" parsed))))
    msg))

(defn launcher [creds id stream image-id instance-type vpc-id url-map user-data]
  (fn []
    (try
      (let [epp {:endpoint (:endpoint creds)}
            {topic-arn :topic-arn} (sns/create-topic creds {:name (str "opsee-bastion-build-sns-" id)})
            {queue-url :queue-url} (sqs/create-queue creds {:queue-name (str "opsee-bastion-build-sqs-" id)})
            {queue-arn :QueueArn} (sqs/get-queue-attributes queue-url)
            policy (render-resource "templates/sqs_policy.mustache" {:policy-id id :queue-arn queue-arn :topic-arn topic-arn})
            endpoint (keyword (:endpoint creds))
            template-url (endpoint url-map)
            _ (sqs/set-queue-attributes queue-url {"Policy" policy})
            {subscription-arn :SubscriptionArn} (sns/subscribe epp topic-arn "sqs" queue-arn)]
        (log/info queue-url endpoint template-url)
        (log/info "subscribe" topic-arn "sqs" queue-arn)
        (log/info "launching stack with " image-id vpc-id user-data)
        (create-stack creds
                      :stack-name (str "opsee-bastion-" id)
                      :template-url template-url
                      :capabilities ["CAPABILITY_IAM"]
                      :parameters [{:parameter-key "ImageId" :parameter-value image-id}
                                   {:parameter-key "InstanceType" :parameter-value instance-type}
                                   {:parameter-key "UserData" :parameter-value (encode-user-data user-data)}
                                   {:parameter-key "VpcId" :parameter-value vpc-id}]
                      :notification-arns [topic-arn])
        (loop [{messages :messages} (sqs/receive-message epp {:queue-url queue-url})]
          (if
            (not-any? #(true? %)
              (for [message messages
                    :let [msg-body (:body message)
                          msg (parse-cloudformation-msg (parse-string msg-body true))]]
                (do
                  (sqs/delete-message epp (assoc message :queue-url queue-url))
                  (s/put! stream msg)
                  (log/info (get-in msg [:Message :ResourceType]) (get-in msg [:Message :ResourceStatus]))
                  (and
                    (= "AWS::CloudFormation::Stack" (get-in msg [:Message :ResourceType]))
                    (contains? #{"CREATE_COMPLETE" "CREATE_FAILED"} (get-in msg [:Message :ResourceStatus]))))))
            (recur (sqs/receive-message epp {:queue-url queue-url}))))
        (log/info "exiting" id)
        (sqs/delete-queue epp queue-url)
        (sns/delete-topic epp topic-arn))
      (catch Exception ex (log/error ex "Exception in thread")))))

(defn launch-bastions [^ExecutorService executor customer-id msg options]
  (let [access-key (:access-key msg)
        secret-key (:secret-key msg)
        regions (:regions msg)
        instance-size (:instance-size msg)
        owner-id (:owner-id options)
        tag (:tag options)
        streams (flatten
                  (for [region-obj regions]
                    (let [creds {:access-key access-key
                                 :secret-key secret-key
                                 :endpoint (:region region-obj)}
                          {image-id :image-id} (get-latest-stable-image creds owner-id tag)]
                      (for [vpc (:vpcs region-obj)]
                        (do (log/info vpc)
                          (let [id (identifiers/generate)
                                vpc-id (:id vpc)
                                stream (s/stream)]
                             (.submit
                               executor
                               (launcher creds id stream image-id instance-size vpc-id beta-map {:customer-id customer-id}))
                             {:id id :stream stream}))))))]
    streams))


