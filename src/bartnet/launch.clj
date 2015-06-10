(ns bartnet.launch
  (:require [bartnet.identifiers :as identifiers]
            [bartnet.s3-buckets :as buckets]
            [bartnet.autobus :as msg]
            [cheshire.core :refer :all]
            [instaparse.core :as insta]
            [clostache.parser :refer [render-resource]]
            [amazonica.aws.cloudformation :refer [create-stack]]
            [amazonica.aws.ec2 :refer [describe-images]]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [clojure.tools.logging :as log]
            [clj-time.format :as f]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.core.match :refer [match]])
  (:import [java.util.concurrent ExecutorService]
           [java.util Base64 Date]))

(def formatter (f/formatters :date-time))

(def beta-map (reduce #(assoc %1 %2 (buckets/url-to %2 "beta/bastion-cf.template")) {} buckets/regions))

(def cf-parser
  (insta/parser
    "message = line+
     line = key <'='> value <'\\n'>
     key = #'[a-zA-Z]+'
     value = <'\\''> (#'(?s).')* <'\\''>"))

(defn encode-user-data [data]
  (let [encoder (Base64/getMimeEncoder)
        json (generate-string data)]
    (.encodeToString encoder (.getBytes json))))

(defn get-latest-stable-image [creds owner-id tag]
  (let [{images :images} (describe-images creds :owners [owner-id] :filters [{:name "tag:release" :values [tag]}])]
    (first (sort-by :name images))))

(defn attributes->state [attributes]
  (case [(get attributes :ResourceStatus) (get attributes :ResourceType)]
    ["CREATE_COMPLETE" "AWS::CloudFormation::Stack"] "complete"
    ["ROLLBACK_COMPLETE" "AWS::CloudFormation::Stack"] "failed"
    "launching"))

(defn parse-cloudformation-msg [customer-id msg]
  (if-let [msg-str (:Message msg)]
    (try
      (let [parsed (cf-parser msg-str)
            attributes (reduce (fn [obj token]
                                 (match token
                                   :message obj
                                   [:line [:key key] [:value & strings]] (assoc obj
                                                                           (keyword key)
                                                                           (str/join "" strings))))
                         {} parsed)
            state (attributes->state attributes)]
        (msg/map->Message {:command "launch-bastion"
                           :sent (Date.)
                           :attributes attributes
                           :customer_id customer-id
                           :service "launch-bastion"
                           :state state
                           :time (f/parse formatter (:Timestamp msg))}))
        (catch Exception e (pprint msg) (log/error e "exception on parse")))))

(defn launcher [creds id bus client image-id instance-type vpc-id url-map customer-id]
  (fn []
    (try
      (let [{topic-arn :topic-arn} (sns/create-topic creds {:name (str "opsee-bastion-build-sns-" id)})
            {queue-url :queue-url} (sqs/create-queue creds {:queue-name (str "opsee-bastion-build-sqs-" id)})
            {queue-arn :QueueArn} (sqs/get-queue-attributes creds {:queue-url queue-url :attribute-names ["All"]})
            policy (render-resource "templates/sqs_policy.mustache" {:policy-id id :queue-arn queue-arn :topic-arn topic-arn})
            endpoint (keyword (:endpoint creds))
            template-url (endpoint url-map)
            _ (sqs/set-queue-attributes creds queue-url {"Policy" policy})
            {subscription-arn :SubscriptionArn} (sns/subscribe creds topic-arn "sqs" queue-arn)]
        (log/info queue-url endpoint template-url)
        (log/info "subscribe" topic-arn "sqs" queue-arn)
        (log/info "launching stack with " image-id vpc-id customer-id)
        (create-stack creds
                      :stack-name (str "opsee-bastion-" id)
                      :template-url template-url
                      :capabilities ["CAPABILITY_IAM"]
                      :parameters [{:parameter-key "ImageId" :parameter-value image-id}
                                   {:parameter-key "InstanceType" :parameter-value instance-type}
                                   {:parameter-key "UserData" :parameter-value (encode-user-data {:customer_id customer-id})}
                                   {:parameter-key "VpcId" :parameter-value vpc-id}]
                      :notification-arns [topic-arn])
        (loop [{messages :messages} (sqs/receive-message creds {:queue-url queue-url})]
          (if
            (not-any? #(true? %)
              (for [message messages
                    :let [msg-body (:body message)
                          msg (parse-cloudformation-msg customer-id (parse-string msg-body true))]]
                (do
                  (sqs/delete-message creds (assoc message :queue-url queue-url))
                  (msg/publish bus client msg)
                  (log/info (get-in msg [:attributes :ResourceType]) (get-in msg [:attributes :ResourceStatus]))
                  (contains? #{"complete" "failed"} (:state msg)))))
            (recur (sqs/receive-message creds {:queue-url queue-url
                                             :wait-time-seconds 20}))))
        (log/info "exiting" id)
        (msg/publish bus client
          (msg/map->Message {:customer_id customer-id
                             :command "bastion-launch"
                             :state "ok"
                             :attributes {:status :success}}))
        (sqs/delete-queue creds queue-url)
        (sns/delete-topic creds topic-arn))
      (catch Exception ex (log/error ex "Exception in thread")))))

(defn launch-bastions [^ExecutorService executor bus customer-id msg options]
  (let [access-key (:access-key msg)
        secret-key (:secret-key msg)
        regions (:regions msg)
        instance-size (:instance-size msg)
        owner-id (:owner-id options)
        tag (:tag options)
        client (msg/register bus (msg/publishing-client) customer-id)]
    (log/info regions)
    (dorun
      (for [region-obj regions]
        (let [creds {:access-key access-key
                     :secret-key secret-key
                     :endpoint (:region region-obj)}
              {image-id :image-id} (get-latest-stable-image creds owner-id tag)]
          (dorun (for [vpc (:vpcs region-obj)]
            (do (log/info vpc)
              (let [id (identifiers/generate)
                    vpc-id (:id vpc)]
                 (.submit
                   executor
                   (launcher creds id bus client image-id instance-size vpc-id beta-map customer-id))
                 {:id id})))))))))


