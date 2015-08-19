(ns bartnet.launch
  (:require [bartnet.identifiers :as identifiers]
            [bartnet.s3-buckets :as buckets]
            [bartnet.bus :as bus]
            [cheshire.core :refer :all]
            [instaparse.core :as insta]
            [clostache.parser :refer [render-resource]]
            [clojure.java.io :as io]
            [amazonica.aws.cloudformation :refer [create-stack]]
            [amazonica.aws.ec2 :refer [describe-images]]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [clojure.tools.logging :as log]
            [clj-time.format :as f]
            [clj-yaml.core :as yaml]
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
  (let [encoder (Base64/getMimeEncoder)]
    (.encodeToString encoder (.getBytes data))))

(defn get-latest-stable-image [creds owner-id tag]
  (let [{images :images} (describe-images creds :owners [owner-id] :filters [{:name "tag:release" :values [tag]}])]
    (first (sort-by :name #(compare %2 %1) images))))

(defn attributes->state [attributes]
  (case [(get attributes :ResourceStatus) (get attributes :ResourceType)]
    ["CREATE_COMPLETE" "AWS::CloudFormation::Stack"] "complete"
    ["ROLLBACK_COMPLETE" "AWS::CloudFormation::Stack"] "failed"
    "launching"))

(defn generate-env-shell [env]
  (str/join "\n" (map
                   (fn [[k v]]
                     (str (name k) "=\"" v "\""))
                   env)))

(defn generate-user-data [customer-id env] ;ca cert key]
  (str
    "#cloud-config\n"
    (yaml/generate-string
      {:write_files [{:path "/etc/opsee/bastion-env.sh"
                      :permissions "0644"
                      :owner "root"
                      :content (generate-env-shell
                                 {:CUSTOMER_ID customer-id})}]})))
                     ;                         :CA_PATH "/etc/opsee/ca.pem"
                     ;                         :CERT_PATH "/etc/opsee/cert.pem"
                     ;                         :KEY_PATH "/etc/opsee/key.pem"}))}
                     ;{:path "/etc/opsee/ca.pem"
                     ; :permissions 0644
                     ; :owner "root"
                     ; :contents ca}
                     ;{:path "/etc/opsee/cert.pem"
                     ; :permissions 0644
                     ; :owner "root"
                     ; :contents cert}
                     ;{:path "/etc/opsee/key.pem"
                     ; :perm}]})))

(defn parse-cloudformation-msg [instance-id msg]
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
        (bus/make-msg "LaunchEvent" {:attributes attributes
                                     :instance_id instance-id
                                     :state state}))
        (catch Exception e (pprint msg) (log/error e "exception on parse")))))

(defn launcher [creds id bus client image-id instance-type vpc-id url-map customer-id keypair template-src]
  (fn []
    (try
      (let [endpoint (keyword (:endpoint creds))
            template-map (if-let [res (:resource template-src)]
                           {:template-body (-> res
                                             io/resource
                                             io/file
                                             slurp)}
                           {:template-url (buckets/url-to endpoint (:template-url template-src))})
            {topic-arn :topic-arn} (sns/create-topic creds {:name (str "opsee-bastion-build-sns-" id)})
            {queue-url :queue-url} (sqs/create-queue creds {:queue-name (str "opsee-bastion-build-sqs-" id)})
            {queue-arn :QueueArn} (sqs/get-queue-attributes creds {:queue-url queue-url :attribute-names ["All"]})
            policy (render-resource "templates/sqs_policy.mustache" {:policy-id id :queue-arn queue-arn :topic-arn topic-arn})
            _ (sqs/set-queue-attributes creds queue-url {"Policy" policy})
            {subscription-arn :SubscriptionArn} (sns/subscribe creds topic-arn "sqs" queue-arn)]
        (log/info queue-url endpoint template-map)
        (log/info "subscribe" topic-arn "sqs" queue-arn)
        (log/info "launching stack with " image-id vpc-id customer-id)
        (create-stack creds
          (merge {:stack-name (str "opsee-bastion-" id)
                  :capabilities ["CAPABILITY_IAM"]
                  :parameters [{:parameter-key "ImageId" :parameter-value image-id}
                               {:parameter-key "InstanceType" :parameter-value instance-type}
                               {:parameter-key "UserData" :parameter-value (encode-user-data
                                                                             (generate-user-data customer-id {}))}
                               {:parameter-key "VpcId" :parameter-value vpc-id}
                               {:parameter-key "KeyName" :parameter-value keypair}]
                  :notification-arns [topic-arn]} template-map))
        (loop [{messages :messages} (sqs/receive-message creds {:queue-url queue-url})]
          (if
            (not-any? #(true? %)
              (for [message messages
                    :let [msg-body (:body message)
                          msg (parse-cloudformation-msg id (parse-string msg-body true))]]
                (do
                  (sqs/delete-message creds (assoc message :queue-url queue-url))
                  (bus/publish bus client customer-id "launch-bastion" msg)
                  (log/info (get-in msg [:attributes :ResourceType]) (get-in msg [:attributes :ResourceStatus]))
                  (contains? #{"complete" "failed"} (:state msg)))))
            (recur (sqs/receive-message creds {:queue-url queue-url
                                             :wait-time-seconds 20}))))
        (log/info "exiting" id)
        (bus/publish bus client customer-id "launch-bastion"
                     (bus/make-msg "LaunchEvent" {:state "ok" :attributes {:status :success}}))
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
        keypair (:keypair options)
        template-src (:template-src options)
        client (bus/register bus (bus/publishing-client) customer-id)]
    (log/info regions)
    (for [region-obj regions]
        (let [creds {:access-key access-key
                     :secret-key secret-key
                     :endpoint (:region region-obj)}
              {image-id :image-id} (get-latest-stable-image creds owner-id tag)
              vpcs (for [vpc (:vpcs region-obj)]
                     (do (log/info vpc)
                         (let [id (identifiers/generate)
                               vpc-id (:id vpc)]
                           (.submit
                             executor
                             (launcher creds id bus client image-id instance-size vpc-id beta-map customer-id keypair template-src))
                           (assoc vpc :instance_id id))))]
          (assoc region-obj :vpcs vpcs)))))


