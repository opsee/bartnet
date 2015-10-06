(ns bartnet.launch
  (:require [bartnet.s3-buckets :as buckets]
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
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.core.match :refer [match]]
            [bartnet.instance :as instance])
  (:import [java.util.concurrent ExecutorService TimeUnit ScheduledFuture]
           [java.util Base64]))

(def formatter (f/formatters :date-time))

(def auth-endpoint "https://vape.opsy.co/bastions")

(def beta-map (reduce #(assoc %1 %2 (buckets/url-to %2 "beta/bastion-cf.template")) {} buckets/regions))

(def beat-delay 10)

(def slack-url "https://hooks.slack.com/services/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")

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

(defn get-bastion-creds [customer-id]
  (let [response (http/post auth-endpoint {:content-type :json :accept :json :body (generate-string {:customer_id customer-id})})]
    (case (:status response)
      200 (parse-string (:body response) keyword)
      (throw (Exception. "failed to get bastion credentials from vape")))))

(defn attributes->state [attributes]
  (case [(get attributes :ResourceStatus) (get attributes :ResourceType)]
    ["CREATE_COMPLETE" "AWS::CloudFormation::Stack"] "complete"
    ["ROLLBACK_COMPLETE" "AWS::CloudFormation::Stack"] "failed"
    "launching"))

(defn generate-env-shell [env]
  (str/join "\n" (map
                  (fn [[k v]]
                    (str (name k) "=" v))
                  env)))

(defn generate-user-data [customer-id bastion-creds] ;ca cert key]
  (str
   "#cloud-config\n"
   (yaml/generate-string
    {:write_files [{:path "/etc/opsee/bastion-env.sh"
                    :permissions "0644"
                    :owner "root"
                    :content (generate-env-shell
                              {:CUSTOMER_ID customer-id
                               ; It's possible at some point that we want to make this configurable.
                               ; At that point we'll want to associate a customer with a specific
                               ; bastion version and expose a mechanism to change that version.
                               ; Until then, always launch with the stable version.
                               :BASTION_VERSION "stable"
                               :BASTION_ID (:id bastion-creds)
                               :VPN_PASSWORD (:password bastion-creds)})}]
     :coreos {:update {:reboot-strategy "etcd-lock" :group "alpha"}}})))

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
        {:attributes attributes
         :instance_id instance-id
         :state state})
      (catch Exception e (pprint msg) (log/error e "exception on parse")))))

(defn launch-heartbeat [bus client customer-id instance-id]
  (fn []
    (bus/publish bus client customer-id "launch-bastion" (bus/make-msg "LaunchEvent" {:instance_id instance-id
                                                                                      :state "launching"}))))
(defn send-slack-error-msg [msg]
  (http/post slack-url {:form-params {:payload (generate-string {:text (str "error while launching bastion " msg)})}}))

(defn launcher [creds bastion-creds bus client image-id instance-type vpc-id login keypair template-src executor]
  (fn []
    (try
      (let [id (:id bastion-creds)
            customer-id (:customer_id login)
            state (atom nil)
            endpoint (keyword (:endpoint creds))
            template-map (if-let [res (:resource template-src)]
                           {:template-body (-> res
                                               io/resource
                                               io/file
                                               slurp)}
                           {:template-url (buckets/url-to endpoint (:template-url template-src))})
            {topic-arn :topic-arn} (sns/create-topic creds {:name (str "opsee-bastion-build-sns-" id)})
            {queue-url :queue-url} (sqs/create-queue creds {:queue-name (str "opsee-bastion-build-sqs-" id)})
            {queue-arn :QueueArn} (sqs/get-queue-attributes creds {:queue-url queue-url :attribute-names ["All"]})
            launch-beater (.scheduleAtFixedRate executor (launch-heartbeat bus client customer-id id) beat-delay beat-delay TimeUnit/SECONDS)
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
                                                                                        (generate-user-data customer-id bastion-creds))}
                                           {:parameter-key "VpcId" :parameter-value vpc-id}
                                           {:parameter-key "KeyName" :parameter-value keypair}]
                              :tags [{:key "Name" :value (str "Opsee Bastion " customer-id)}]
                              :notification-arns [topic-arn]} template-map))
        (loop [{messages :messages} (sqs/receive-message creds {:queue-url queue-url})]
          (if
           (not-any? #(true? %)
                     (for [message messages
                           :let [msg-body (:body message)
                                 msg (parse-cloudformation-msg id (parse-string msg-body true))]]
                       (do
                         (sqs/delete-message creds (assoc message :queue-url queue-url))
                         (bus/publish bus client customer-id "launch-bastion" (bus/make-msg "LaunchEvent" msg))
                         (log/info (get-in msg [:attributes :ResourceType]) (get-in msg [:attributes :ResourceStatus]))
                         (reset! state (:state msg))
                         (contains? #{"complete" "failed"} (:state msg)))))
            (recur (sqs/receive-message creds {:queue-url queue-url
                                               :wait-time-seconds 20}))))
        (log/info "exiting" id)
        (bus/publish bus client customer-id "launch-bastion"
                     (bus/make-msg "LaunchEvent" {:state "ok" :attributes {:status :success}}))
        (.cancel launch-beater false)
        (sns/delete-topic creds topic-arn)
        (sqs/delete-queue creds queue-url)
        (if (= "failed" @state)
          (send-slack-error-msg (str "BASTION LAUNCH FAILED - user-email: " (:email login)
                                     " customer-id: " customer-id
                                     " user-id " (:id login)))
          (instance/discover! {:access_key (:access-key creds)
                               :secret_key (:secret-key creds)
                               :region (:endpoint creds)
                               :customer_id customer-id
                               :user_id (:id login)})))
      (catch Exception ex (do
                            (log/error ex "Exception in thread")
                            (send-slack-error-msg
                             (str "BASTION LAUNCH EXCEPTION: "
                                  (.getMessage ex)
                                  " - user-email: " (:email login)
                                  " customer-id: " (:customer_id login)
                                  " user-id " (:id login))))))))

(defn launch-bastions [^ExecutorService executor scheduler bus login msg options]
  (let [access-key (:access-key msg)
        secret-key (:secret-key msg)
        regions (:regions msg)
        instance-size (:instance-size msg)
        customer-id (:customer_id login)
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
                       (let [bastion-creds (get-bastion-creds customer-id)
                             vpc-id (:id vpc)]
                         (.submit
                          executor
                          (launcher creds bastion-creds bus
                                    client image-id instance-size
                                    vpc-id login keypair
                                    template-src scheduler))
                         (assoc vpc :instance_id (:id bastion-creds)))))]
        (assoc region-obj :vpcs vpcs)))))

