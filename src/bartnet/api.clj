(ns bartnet.api
  (:require [bartnet.instance :as instance]
            [bartnet.sql :as sql]
            [bartnet.rpc :as rpc :refer [all-bastions]]
            [bartnet.aws-rpc :as aws-rpc]
            [bartnet.results :as results]
            [opsee.middleware.protobuilder :as pb]
            [opsee.middleware.core :refer :all]
            [bartnet.bastion-router :as router]
            [clojure.tools.logging :as log]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [Representation ring-response render-map-generic]]
            [ring.swagger.middleware :as rsm]
            [clj-http.client :as http]
            [ring.util.http-response :refer :all]
            [amazonica.aws.ec2 :refer [describe-vpcs describe-account-attributes describe-instances describe-subnets]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.api.sweet :refer :all]
            [ring.middleware.format-response :refer [make-encoder]]
            [compojure.api.swagger :as cas]
            [compojure.api.middleware :as mw]
            [compojure.route :as rt]
            [ring.swagger.schema :as schema]
            [cheshire.core :refer :all]
            [bartnet.identifiers :as identifiers]
            [bartnet.launch :as launch]
            [schema.core :as sch]
            [liberator.dev :refer [wrap-trace]]
            [liberator.core :refer [resource defresource]])
  (:import (co.opsee.proto TestCheckRequest TestCheckResponse CheckResourceRequest Check Assertion)
           (co.opsee.proto RebootInstancesRequest StartInstancesRequest StopInstancesRequest RebootInstancesResult StartInstancesResult StopInstancesResult)
           (com.google.protobuf GeneratedMessage)
           (java.io ByteArrayInputStream)))

(def executor (atom nil))
(def scheduler (atom nil))
(def config (atom nil))
(def db (atom nil))
(def bus (atom nil))
(def producer (atom nil))
(def consumer (atom nil))

(extend-type GeneratedMessage
  Representation
  (as-response [this ctx]
    {:body (.toByteArray this)
     :headers {"Content-Type" "application/x-protobuf"}}))

(defn pb-as-response [clazz]
  (fn [data ctx]
    (case (get-in ctx [:representation :media-type])
                  "application/x-protobuf" {:body (ByteArrayInputStream.  (.toByteArray (pb/hash->proto clazz data)))
                                            :headers {"Content-Type" "application/x-protobuf"}}
                  (liberator.representation/as-response data ctx))))

(defn encode-protobuf [body]
  (.toByteArray body))

(defn respond-with-entity? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn get-http-body [response]
  (let [status (:status response)]
    (cond
      (<= 200 status 299) (parse-string (:body response) keyword)
      :else (throw (Exception. (str "failed to get instances from the instance store " status))))))

(defn add-instance-results [instance results]
  (let [instance-id (get-in instance [:instance :InstanceId])]
    (assoc instance :results (sequence
                               (comp

                                 (map (fn [result]
                                        (update result :responses (fn [responses]
                                                                    (filter #(= instance-id (:host %)) responses)))))
                                 (filter #(not-empty (:responses %))))
                                 results))))

(defn add-group-results [group results]
  (assoc group :results (filter #(= (or (get-in group [:group :LoadBalancerName]) (get-in group [:group :GroupId])) (:host %)) results)))

(defn add-check-results [check results]
  (assoc check :results (filter #(= (:id check) (:service %)) results)))

(defn add-check-assertions [check db]
  (assoc 
    check 
    :assertions 
    (sql/get-assertions db {:check_id (:id check) 
                            :customer_id (:customer_id check)
                            })))

(defmulti results-merge (fn [[key _] _] key))

(defmethod results-merge :instance [[key instance] results]
  (add-instance-results {key instance} results))
(defmethod results-merge :instances [[key instances] results]
  [key (for [instance instances] (add-instance-results instance results))])
(defmethod results-merge :group [[key group] results]
  (add-group-results {key group} results))
(defmethod results-merge :groups [[key groups] results]
  [key (for [group groups] (add-group-results group results))])
(defmethod results-merge :instance_count [ic _] ic)

(defn list-bastions [ctx]
  (let [login (:login ctx)
        instance-ids (router/get-customer-bastions (:customer_id login))]
    {:bastions (reduce conj [] instance-ids)}))

(defn ensure-target-created [target]
  (if (empty? (sql/get-target-by-id @db (:id target)))
    (sql/insert-into-targets! @db target))
  (:id target))

(defn retrieve-target [target-id]
  (first (sql/get-target-by-id @db target-id)))

(defn resolve-target [check]
  (if (:target check)
    (assoc check :target_id (ensure-target-created (:target check)))
    (dissoc (assoc check :target (retrieve-target (:target_id check))) :target_id)))

(defn resolve-lastrun [check customer-id]
  (try
    (let [req (-> (CheckResourceRequest/newBuilder)
                  (.addChecks (pb/hash->proto Check check))
                  .build)
          retr-checks (all-bastions customer-id #(rpc/retrieve-check % req))
          max-check (max-key #(:seconds (:last_run %)) retr-checks)]
      (assoc check :last_run (:last_run max-check)))
    (catch Exception _ check)))

(defn check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (if-let [check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
        {:check (-> check
                    (resolve-target)
                    (resolve-lastrun customer-id)
                    (add-check-results (get-http-body (results/get-results {:login login :customer_id customer-id :check_id id})))
                    (dissoc :customer_id))}))))

(defn update-check! [id pb-check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          db-assertions (sql/get-assertions @db {:customer_id customer-id :check_id id})
          assertions (map #(-> (.toBuilder (pb/hash->proto Assertion %)) .build) db-assertions)
          updated-check (pb/proto->hash pb-check)
          old-check (:check ctx)]
      (let [merged (merge old-check (assoc (resolve-target updated-check) :id id))]
        (log/info merged)
        (if (sql/update-check! @db (assoc merged :customer_id customer-id))
          (let [final-check (dissoc (resolve-target (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))) :customer_id)
                _ (log/info "final-check" final-check)
                check (-> (.toBuilder (pb/hash->proto Check final-check))
                          .clearAssertions
                          (.addAllAssertions assertions)
                          .build)
                checks (-> (CheckResourceRequest/newBuilder)
                           (.addChecks check)
                           .build)]
            ;; delete results from beavis first so that we avoid a race between the bastion and us
            ;; calling beavis
            (results/delete-results login id)
            (all-bastions (:customer_id login) #(rpc/update-check % checks))

            {:check final-check}))))))

(defn delete-check! [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
      (do
        (sql/delete-check-by-id! @db {:id id :customer_id customer-id})
        (let [req (-> (CheckResourceRequest/newBuilder)
                      (.addChecks (-> (Check/newBuilder)
                                      (.setId id)
                                      .build))
                      .build)]
          (all-bastions customer-id #(rpc/delete-check % req)))
        (results/delete-results login id)))))

(defn create-check! [^Check check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check-id (identifiers/generate)
          db-assertions (sql/get-assertions @db {:customer_id customer-id :check_id check-id})
          assertions (map #(-> (.toBuilder %) .build) db-assertions)
          check' (-> (.toBuilder check)
                     (.setId check-id)
                     (.addAllAssertions assertions)
                     .build)
          checks (-> (CheckResourceRequest/newBuilder)
                     (.addChecks check')
                     .build)
          db-check (resolve-target (pb/proto->hash check'))]
      (sql/insert-into-checks! @db (assoc db-check :customer_id customer-id))
      (all-bastions (:customer_id login) #(rpc/create-check % checks))
      (log/info "check" db-check)
      {:checks db-check})))


(defn reboot-instances! [^RebootInstancesRequest rebootInstancesRequest]
  (fn [ctx]
    (let [login (:login ctx)
          response (aws-rpc/all-bastions (:customer_id login) #(aws-rpc/reboot-instances % rebootInstancesRequest))]
      (log/info "resp" response)
      {:reboot-instances-result response})))


(defn start-instances! [^StartInstancesRequest startInstancesRequest]
  (fn [ctx]
    (let [login (:login ctx)
          response (aws-rpc/all-bastions (:customer_id login) #(aws-rpc/start-instances % startInstancesRequest))]
      (log/info "resp" response)
      {:start-instances-result response})))

(defn stop-instances! [^StopInstancesRequest stopInstancesRequest]
  (fn [ctx]
    (let [login (:login ctx)
          response (aws-rpc/all-bastions (:customer_id login) #(aws-rpc/stop-instances % stopInstancesRequest))]
      (log/info "resp" response)
      {:stop-instances-result response})))

(defn list-checks [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)
        results (get-http-body (results/get-results {:login login :customer_id customer-id}))
        checks (map #(-> %
                         (add-check-assertions @db)
                         (resolve-target)
                         (dissoc :customer_id)
                         (add-check-results results)) (sql/get-checks-by-customer-id @db customer-id))]
    (map #(resolve-lastrun % customer-id) checks)
    {:checks checks}))

(defn ec2-classic? [attrs]
  (let [ttr (:account-attributes attrs)
        supported (first (filter
                          #(= "supported-platforms" (:attribute-name %))
                          ttr))]
    (not (empty? (filter
                  #(or (.equalsIgnoreCase "EC2-Classic" (:attribute-value %))
                       (.equalsIgnoreCase "EC2" (:attribute-value %)))
                  (:attribute-values supported))))))

(defn scan-vpcs [req]
  (fn [ctx]
    {:regions (for [region (:regions req)
                    :let [cd {:access-key (:access-key req)
                              :secret-key (:secret-key req)
                              :endpoint region}
                          vpcs (describe-vpcs cd)
                          subnets (describe-subnets cd)
                          attrs (describe-account-attributes cd)]]
                {:region      region
                 :ec2-classic (ec2-classic? attrs)
                 :vpcs        (for [vpc (:vpcs vpcs)
                                    :let [reservations (describe-instances cd :filters [{:name "vpc-id"
                                                                                         :values [(:vpc-id vpc)]}])
                                          count (reduce +
                                                        (map (fn [res]
                                                               (count (filter #(not= "terminated"
                                                                                     (get-in % [:state :name]))
                                                                              (:instances res))))
                                                             (:reservations reservations)))
                                          subnets (filter #(= (:vpc-id vpc) (:vpc-id %)) (:subnets subnets))]]
                                (assoc vpc :count count :subnets subnets))})}))

(defn call-instance-store! [meth opts]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (let [{store-req :store results-req :results} (meth (assoc (or opts {})
                                                            :customer_id customer-id
                                                            :login login))
            store (get-http-body store-req)
            results (get-http-body results-req)]
        (log/info "results" results)
        (into {} (map #(results-merge % results)) store)))))

(defn get-customer! [ctx]
  (let [customer-id (get-in ctx [:login :customer_id])]
    (instance/get-customer! {:customer_id customer-id})))

(defn test-check! [testCheck]
  (fn [ctx]
    (let [login (:login ctx)
          response (rpc/try-bastions (:customer_id login) #(rpc/test-check % testCheck))]
      (log/info "resp" response)
      {:test-results response})))

(defn launch-bastions! [launch-cmd]
  (fn [ctx]
    (let [login (:login ctx)]
      {:regions (launch/launch-bastions @executor @scheduler @producer login launch-cmd (:ami @config) (:bastion @config))})))

(defresource instances-resource [opts]
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok (call-instance-store! instance/list-instances! opts))

(defresource groups-resource [opts]
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok (call-instance-store! instance/list-groups! opts))

(defresource customers-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok get-customer!)

(defresource launch-bastions-resource [launch-cmd]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (launch-bastions! launch-cmd)
  :handle-created :regions)

(defresource bastions-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok list-bastions)

(defresource test-check-resource [testCheck]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (test-check! testCheck)
  :handle-created (fn [ctx] (pb/proto->hash (:test-results ctx))))

(defresource check-resource [id check]
  :as-response (pb-as-response Check)
  :available-media-types ["application/json" "application/x-protobuf"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (check-exists? id)
  :put! (update-check! id check)
  :new? false
  :respond-with-entity? respond-with-entity?
  :delete! (delete-check! id)
  :handle-ok :check)

(defresource checks-resource [checks]
  :as-response (pb-as-response CheckResourceRequest)
  :available-media-types ["application/json" "application/x-protobuf"]
  :allowed-methods [:get :post]
  :authorized? (authorized?)
  :post! (create-check! checks)
  :handle-created :checks
  :handle-ok list-checks)


(defresource start-instances-resource [startInstancesRequest]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (start-instances! startInstancesRequest)
  :handle-ok :start-instances-result)


(defresource stop-instances-resource [stopInstancesRequest]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (stop-instances! stopInstancesRequest)
  :handle-ok :stop-instances-result)


(defresource reboot-instances-resource [rebootInstancesRequest]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (reboot-instances! rebootInstancesRequest)
  :handle-ok :reboot-instances-result)

(defresource scan-vpc-resource [req]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :post! (scan-vpcs req)
  :handle-created :regions)

(def EC2Region (sch/enum "ap-northeast-1" "ap-southeast-1" "ap-southeast-2"
                         "eu-central-1" "eu-west-1"
                         "sa-east-1"
                         "us-east-1" "us-west-1" "us-west-2"))

(def LaunchVpc "A VPC for launching"
  (sch/schema-with-name
   {:id sch/Str
    (sch/optional-key :subnet_id) (sch/maybe sch/Str)
    (sch/optional-key :instance_id) (sch/maybe sch/Str)}
   "LaunchVpc"))

(def LaunchRegion "An ec2 region for launching"
  (sch/schema-with-name
   {:region EC2Region
    :vpcs [LaunchVpc]}
   "LaunchRegion"))

(def LaunchCmd "A schema for launching bastions"
  (sch/schema-with-name
   {:access-key sch/Str
    :secret-key sch/Str
    :regions [LaunchRegion]
    :instance-size (sch/enum "t2.micro" "t2.small" "t2.medium" "t2.large"
                             "m4.large" "m4.xlarge" "m4.2xlarge" "m4.4xlarge" "m4.10xlarge"
                             "m3.medium" "m3.large" "m3.xlarge" "m3.2xlarge")}
   "LaunchCmd"))

(def ScanVpcsRequest
  (sch/schema-with-name
   {:access-key sch/Str
    :secret-key sch/Str
    :regions [EC2Region]}
   "ScanVpcsRequest"))

(def Tag
  (sch/schema-with-name
   {:key sch/Str
    :value sch/Str}
   "Tag"))

(def Subnet
  (sch/schema-with-name
    {:tags [Tag]
     :subnet-id sch/Str
     :default-for-az sch/Bool
     :state sch/Str
     :availability-zone sch/Str
     :vpc-id sch/Str
     :map-public-ip-on-launch sch/Bool
     :available-ip-address-count sch/Bool
     :cidr-block sch/Str}
    "Subnet"))

(def ScanVpc
  (sch/schema-with-name
   {:state sch/Str
    :vpc-id sch/Str
    :tags [Tag]
    :subnets [Subnet]
    :cidr-block sch/Str
    :dhcp-options-id sch/Str
    :instance-tenancy sch/Str
    :is-default sch/Bool}
   "ScanVpc"))

(def ScanVpcsRegion
  (sch/schema-with-name
   {:region EC2Region
    :ec2-class sch/Bool
    :vpcs [ScanVpc]}
   "ScanVpcsRegion"))

(def ScanVpcsResponse
  (sch/schema-with-name
   {:regions [ScanVpcsRegion]}
   "ScanVpcsResponse"))

(defapi bartnet-api
  {:exceptions {:exception-handler robustify-errors}
   :coercion   (fn [_] (-> mw/default-coercion-matchers
                           (assoc :proto pb/proto-walker)
                           (dissoc :response)))
   :format {:formats [:json (make-encoder encode-protobuf "application/x-protobuf" :binary)]}}
  (routes
    (GET* "/api/swagger.json" {:as req}
      :no-doc true
      :name ::swagger
      (let [runtime-info (rsm/get-swagger-data req)
            base-path {:basePath (cas/base-path req)}
            options (:ring-swagger (mw/get-options req))]
        (ok
          (let [swagger (merge runtime-info base-path)
                result (merge-with merge
                                   (ring.swagger.swagger2/swagger-json swagger options)
                                   {:info        {:title       "Opsee API"
                                                  :description "Be More Opsee"}
                                    :definitions (pb/anyschemas)})]
            result)))))
  (swagger-ui "/api/swagger" :swagger-docs "/api/swagger.json")
  ;; TODO: Split out request methods and document with swagger metadata
  (GET* "/health_check" []
    :no-doc true
    "A ok")


  (GET* "/eat-shit" []
    :no-doc true
    (fn [request]
      (throw (Exception. "this is just a test"))))

  (POST* "/scan-vpcs" []
    :summary "Scans the regions requested for any VPC's and instance counts."
    :body [vpc-req ScanVpcsRequest]
    :return [ScanVpcsResponse]
    (scan-vpc-resource vpc-req))

  (context* "/bastions" []
    :tags ["bastions"]

    (GET* "/" []
      :no-doc true
      (bastions-resource))

    (POST* "/launch" []
      :summary "Launch bastions in the given VPC's."
      :body [launch-cmd LaunchCmd]
      :return [LaunchCmd]
      (launch-bastions-resource launch-cmd))

    (POST* "/test-check" []
      :summary "Tells the bastion to test out a check and return the response"
      :proto [testCheck TestCheckRequest]
      :return (pb/proto->schema TestCheckResponse)
      (test-check-resource testCheck)))

  (context* "/aws" []
    :tags ["aws"]

    (POST* "/start-instances" []
      :summary "Reboot an instance by id"
      :proto [startInstancesRequest StartInstancesRequest]
      :return (pb/proto->schema StartInstancesResult)
        (start-instances-resource startInstancesRequest))

    (POST* "/stop-instances" []
      :summary "Reboot an instance by id"
      :proto [stopInstancesRequest StopInstancesRequest]
      :return (pb/proto->schema StartInstancesResult)
        (stop-instances-resource stopInstancesRequest))

    (POST* "/reboot-instances" []
      :summary "Reboot an instance by id"
      :proto [rebootInstancesRequest RebootInstancesRequest]
      :return (pb/proto->schema RebootInstancesResult)
        (reboot-instances-resource rebootInstancesRequest)))
    
  (context* "/checks" []
    :tags ["checks"]

    (POST* "/" []
      :summary "Create a check"
      :proto [check Check]
      :return (pb/proto->schema Check)
      (checks-resource check))

    (GET* "/" []
      :summary "List all checks"
      :produces ["application/json" "application/x-protobuf"]
      :return [(pb/proto->schema Check)]
      (checks-resource nil))

    (GET* "/:id" [id]
      :summary "Retrieve a check by its ID."
      :produces ["application/json" "application/x-protobuf"]
      :return (pb/proto->schema Check)
      (check-resource id nil))

    (DELETE* "/:id" [id]
      :summary "Delete a check by its ID."
      (check-resource id nil))

    (PUT* "/:id" [id]
      :summary "Update a check by its ID."
      :proto [check Check]
      :return (pb/proto->schema Check)
      (check-resource id check)))
 
  (context* "/instances" []
    :tags ["instances"]

    (GET* "/" []
      :summary "Retrieve a list of instances."
      (instances-resource nil))

    (GET* "/:type" [type]
      :summary "Retrieve a list of instances by type."
      (instances-resource {:type type}))

    (GET* "/:type/:id" [type id]
      :summary "Retrieve a single ec2 instance."
      (instances-resource {:type type :id id})))


  (context* "/groups" []
    :tags ["groups"]

    (GET* "/" []
      :summary "Retrieve a list of groups."
      (groups-resource nil))

    (GET* "/:type" [type]
      :summary "Retrieve a list of security groups."
      (groups-resource {:type type}))

    (GET* "/:type/:id" [type id]
      :summary "Retrieve a list of instances belonging to a security group."
      (groups-resource {:id id :type type})))

  (GET* "/customer" []
    :summary "Retrieve a customer from the instance store."
    (customers-resource))

  (rt/not-found "Not found."))

(defn handler [exe sched message-bus prod con database conf]
  (reset! executor exe)
  (reset! scheduler sched)
  (reset! bus message-bus)
  (reset! producer prod)
  (reset! consumer con)
  (reset! db database)
  (reset! config conf)
  (->
   bartnet-api
   (log-request)
   (log-response)
   (wrap-cors :access-control-allow-origin [#"https?://localhost(:\d+)?"
                                            #"https?://(\w+\.)?opsee\.com"
                                            #"https?://(\w+\.)?opsee\.co"
                                            #"https?://(\w+\.)?opsy\.co"
                                            #"null"]
              :access-control-allow-methods [:get :put :post :patch :delete])
   (vary-origin)
   (wrap-params)
   (setup-yeller)))
