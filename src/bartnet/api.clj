(ns bartnet.api
  (:require [bartnet.instance :as instance]
            [bartnet.sql :as sql]
            [bartnet.rpc :as rpc :refer [all-bastions]]
            [bartnet.results :as results]
            [opsee.middleware.protobuilder :as pb]
            [opsee.middleware.core :refer :all]
            [bartnet.bastion-router :as router]
            [clojure.tools.logging :as log]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [ring-response]]
            [ring.swagger.middleware :as rsm]
            [http.async.client :as http]
            [ring.util.http-response :refer :all]
            [amazonica.aws.ec2 :refer [describe-vpcs describe-account-attributes describe-instances]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.api.sweet :refer :all]
            [compojure.api.swagger :as cas]
            [compojure.api.middleware :as mw]
            [compojure.route :as rt]
            [cheshire.core :refer :all]
            [bartnet.identifiers :as identifiers]
            [bartnet.launch :as launch]
            [bartnet.bus :as bus]
            [schema.core :as sch]
            [liberator.dev :refer [wrap-trace]]
            [liberator.core :refer [resource defresource]])
  (:import (co.opsee.proto TestCheckRequest TestCheckResponse CheckResourceRequest Check)))

(def executor (atom nil))
(def scheduler (atom nil))
(def config (atom nil))
(def db (atom nil))
(def bus (atom nil))
(def client (atom nil))

(defn respond-with-entity? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn list-bastions [ctx]
  (let [login (:login ctx)
        instance-ids (router/get-customer-bastions (:customer_id login))]
    {:bastions instance-ids}))

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

(defn resolve-lastrun [customer-id check]
  (let [req (-> (CheckResourceRequest/newBuilder)
                (.addChecks (pb/hash->proto Check check))
                .build)
        retr-checks (all-bastions customer-id #(rpc/retrieve-check % req))
        max-check (max-key #(:seconds (:last_run %)) retr-checks)]
    (assoc check :last_run (:last_run max-check))))

(defn check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (if-let [check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
        {:check (dissoc (resolve-lastrun customer-id (resolve-target check)) :customer_id)}))))

(defn update-check! [id pb-check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          updated-check (pb/proto->hash pb-check)
          old-check (:check ctx)]
      (let [merged (merge old-check (assoc (resolve-target updated-check) :id id))]
        (log/info merged)
        (if (sql/update-check! @db (assoc merged :customer_id customer-id))
          (let [final-check (dissoc (resolve-target (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))) :customer_id)
                _ (log/info "final-check" final-check)
                check (pb/hash->proto Check final-check)
                checks (-> (CheckResourceRequest/newBuilder)
                           (.addChecks check)
                           .build)]
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
          (all-bastions customer-id #(rpc/delete-check % req)))))))

(defn create-check! [^Check check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check' (-> (.toBuilder check)
                     (.setId (identifiers/generate))
                     .build)
          checks (-> (CheckResourceRequest/newBuilder)
                     (.addChecks check')
                     .build)
          db-check (resolve-target (pb/proto->hash check'))]
      (sql/insert-into-checks! @db (assoc db-check :customer_id customer-id))
      (all-bastions (:customer_id login) #(rpc/create-check % checks))
      (log/info "chechf" db-check)
      {:checks db-check})))

(defn list-checks [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)
        checks (map #(resolve-target (dissoc % :customer_id)) (sql/get-checks-by-customer-id @db customer-id))]
    (map #(resolve-lastrun customer-id %) checks)
    (log/info "checks" checks)
    checks))

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
                                                             (:reservations reservations)))]]
                                (assoc vpc :count count))})}))

(defn get-http-body [response]
  (let [status (http/status response)]
    (log/info "status" status)
    (log/info "body" (http/string response))
    (cond
      (<= 200 status 299) (parse-string (http/string response) keyword)
      :else (throw (Exception. (str "failed to get instances from the instance store " status))))))

(defmulti results-merge (fn [[key _] _] key))

(defn add-instance-results [instance results]
  (if-let [r (first (filter #(= (:InstanceId instance) (:host %)) results))]
    (assoc instance :result r)
    instance))

(defn add-group-results [group results]
  (if-let [r (first (filter #(= (or (:LoadBalancerName group) (:GroupId group)) (:host %)) results))]
    (assoc group :result r)
    group))

(defn unpack-responses [results]
  (mapcat #(cons % (:responses %)) results))

(defmethod results-merge :instance [[key instance] results]
  [key (add-instance-results instance results)])
(defmethod results-merge :instances [[key instances] results]
  [key (for [instance instances] (add-instance-results instance results))])
(defmethod results-merge :group [[key group] results]
  [key (add-group-results group results)])
(defmethod results-merge :groups [[key groups] results]
  [key (for [group groups] (add-group-results group results))])

(defn call-instance-store! [meth opts]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (with-open [client (http/create-client)]
        (let [{store-req :store results-req :results} (meth client (assoc (or opts {})
                                                                     :customer_id customer-id
                                                                     :login login))
              store (get-http-body store-req)
              results (get-http-body results-req)]
          (into {} (map #(results-merge % (unpack-responses results))) store))))))

(defn test-check! [testCheck]
  (fn [ctx]
    (let [login (:login ctx)
          response (rpc/try-bastions (:customer_id login) #(rpc/test-check % testCheck))]
      (log/info "resp" response)
      {:test-results response})))

(defn launch-bastions! [launch-cmd]
  (fn [ctx]
    (let [login (:login ctx)]
      {:regions (launch/launch-bastions @executor @scheduler @bus login launch-cmd (:ami @config))})))

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
  :handle-ok (call-instance-store! instance/get-customer! nil))

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
  :as-response (fn [data _] {:body data})
  :available-media-types ["application/json"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (check-exists? id)
  :put! (update-check! id check)
  :new? false
  :respond-with-entity? respond-with-entity?
  :delete! (delete-check! id)
  :handle-ok :check)

(defresource checks-resource [checks]
  :as-response (fn [data _] {:body data})
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :authorized? (authorized?)
  :post! (create-check! checks)
  :handle-created :checks
  :handle-ok list-checks)

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

(def ScanVpc
  (sch/schema-with-name
   {:state sch/Str
    :vpc-id sch/Str
    :tags [Tag]
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
   :coercion   (fn [_] (assoc mw/default-coercion-matchers
                              :proto pb/proto-walker))}
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
                                     {:info {:title "Opsee API"
                                             :description "Be More Opsee"}
                                      :definitions (pb/anyschemas)})]
              result)))))
  (swagger-ui "/api/swagger" :swagger-docs "/api/swagger.json")
        ;; TODO: Split out request methods and document with swagger metadata
  (GET*    "/health_check" []
           :no-doc true
           "A ok")

  (POST*   "/scan-vpcs" []
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

  (context* "/checks" []
    :tags ["checks"]

    (POST* "/" []
      :summary "Create a check"
      :proto [check Check]
      :return (pb/proto->schema Check)
      (checks-resource check))

    (GET* "/" []
      :summary "List all checks"
      :return [(pb/proto->schema Check)]
      (checks-resource nil))

    (GET* "/:id" [id]
      :summary "Retrieve a check by its ID."
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

  (GET* "/instance/:type/:id" [type id]
    (instances-resource {:type type :id id}))

  (context* "/groups" []
    :tags ["groups"]

    (GET* "/" []
      :summary "Retrieve a list of groups."
      :no-doc true
      (groups-resource nil))

    (GET* "/:type" [type]
      :summary "Retrieve a list of security groups."
      :no-doc true
      (groups-resource {:type type}))

    (GET* "/:type/:id" [type id]
      :summary "Retrieve a list of instances belonging to a security group."
      :no-doc true
      (groups-resource {:id id :type type})))

  (GET* "/group/:type/:id" [type id]
    (groups-resource {:id id :type type}))

  (GET* "/customer" []
    :summary "Retrieve a customer from the instance store."
    :no-doc true
    (customers-resource))

  (rt/not-found "Not found."))

(defn handler [exe sched message-bus database conf]
  (reset! executor exe)
  (reset! scheduler sched)
  (reset! bus message-bus)
  (reset! db database)
  (reset! config conf)
  (reset! client (bus/register @bus (bus/publishing-client) "*"))
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
   (wrap-trace :header :ui)))
