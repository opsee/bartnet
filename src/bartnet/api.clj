(ns bartnet.api
  (:require [bartnet.sql :as sql]
            [bartnet.rpc :as rpc :refer [all-bastions]]
            [opsee.middleware.protobuilder :as pb]
            [opsee.middleware.core :refer :all]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
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
(def magic-exgid "127a7354-290e-11e6-b178-2bc1f6aefc14")

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

(defn add-check-assertions [check db]
  (assoc 
    check 
    :assertions 
    (let [db_assertions (sql/get-assertions db {:check_id (:id check) 
                                                :customer_id (:customer_id check)})]
      (map #(dissoc % :customer_id :check_id) db_assertions))))

(defn resolve-target [check]
  (if-let [target (:target check)]
    (assoc check :target_id (:id target) :target_name (:name target) :target_type (:type target))
    (dissoc (assoc check :target {
                                  :id (:target_id check)
                                  :name (:target_name check)
                                  :type (:target_type check)})
            :target_id :target_name :target_type)))

(defn resolve-lastrun [check customer-id]
  (try
    (let [req (-> (CheckResourceRequest/newBuilder)
                  (.addChecks (pb/hash->proto Check check))
                  .build)
          retr-checks (all-bastions (:execution_group_id check) #(rpc/retrieve-check % req))
          max-check (max-key #(:seconds (:last_run %)) retr-checks)]
      (assoc check :last_run (:last_run max-check)))
    (catch Exception _ check)))

(defn gql-check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (if-let [check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
        {:check (-> check
                    (resolve-target)
                    (resolve-lastrun customer-id)
                    (add-check-assertions @db))}))))

(defn check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (if-let [check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
        {:check (-> check
                    (resolve-target)
                    (resolve-lastrun customer-id)
                    (add-check-assertions @db))}))))

(defn update-check! [id pb-check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          updated-check (pb/proto->hash pb-check)
          assertions (:assertions updated-check)
          old-check (:check ctx)]
      (let [merged (merge old-check (assoc (resolve-target updated-check) :id id))]
        (log/debug "merged" merged)
        (when (sql/update-check! @db (assoc merged :customer_id customer-id))
            (sql/delete-assertions! @db {:customer_id customer-id :check_id id})
            (doall (map #(sql/insert-into-assertions! @db (assoc % :check_id id :customer_id customer-id)) assertions)))
        (let [updated-assertions (sql/get-assertions @db {:check_id id :customer_id customer-id})
              final-check (resolve-target (first (sql/get-check-by-id @db {:id id :customer_id customer-id})))
              final-check' (assoc final-check :assertions updated-assertions)
              _ (log/debug "final-check" final-check')
              check (-> (.toBuilder (pb/hash->proto Check final-check'))
                        .build)
              checks (-> (CheckResourceRequest/newBuilder)
                         (.addChecks check)
                         .build)]
            (all-bastions (:execution_group_id merged) #(rpc/update-check % checks))
            {:check final-check'})))))

(defn delete-check! [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check (first (sql/get-check-by-id @db {:id id :customer_id customer-id}))]
      (do
        (sql/delete-check-by-id! @db {:id id :customer_id customer-id})
        (sql/delete-assertions! @db {:id id :customer_id customer-id})
        (let [req (-> (CheckResourceRequest/newBuilder)
                      (.addChecks (-> (Check/newBuilder)
                                      (.setId id)
                                      .build))
                      .build)]
          (all-bastions (:execution_group_id check) #(rpc/delete-check % req)))))))

(defn create-check! [^Check check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check-id (identifiers/generate)
          assertions (.getAssertionsList check)
          check' (-> (.toBuilder check)
                     (.setId check-id)
                     .build)
          checks (-> (CheckResourceRequest/newBuilder)
                     (.addChecks check')
                     .build)
          ided-check (pb/proto->hash check')
          check-type (get-in ided-check [:target :type])
          exgid (if (= "external_host" check-type)
                  magic-exgid
                  (or (:execution_group_id ided-check) customer-id))
          db-check (resolve-target ided-check)]
      (doall (map #(sql/insert-into-assertions! @db (assoc % :check_id check-id :customer_id customer-id)) (map pb/proto->hash assertions)))
      (sql/insert-into-checks! @db (assoc db-check :customer_id customer-id :execution_group_id exgid))
      (all-bastions exgid #(rpc/create-check % checks))
      (log/debug "check" ided-check)
      {:checks-resource {:checks [ided-check]}})))

(defn list-checks [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)
        checks (map #(-> %
                         (add-check-assertions @db)
                         (resolve-target)) (sql/get-checks-by-customer-id @db customer-id))]
    (map #(resolve-lastrun % customer-id) checks)
    {:checks checks}))

(defn exgid-list-checks [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          db-checks (if (= customer-id magic-exgid)
                      (sql/get-global-checks-by-execution-group-id @db {:execution_group_id id})
                      (sql/get-checks-by-execution-group-id @db {:execution_group_id id :customer_id customer-id}))
          checks (map #(-> %
                           (add-check-assertions @db)
                           (resolve-target)) db-checks)]
      {:checks checks})))

(defn gql-list-checks [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)
        checks (map #(-> %
                         (add-check-assertions @db)
                         (resolve-target)) (sql/get-checks-by-customer-id @db customer-id))]
    (map #(resolve-lastrun % customer-id) checks)
    {:checks checks}))

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

(defresource gql-check-resource [id check]
  :as-response (pb-as-response Check)
  :available-media-types ["application/json" "application/x-protobuf"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (gql-check-exists? id)
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
  :handle-created :checks-resource
  :handle-ok list-checks)

(defresource checks-exgid-resource [id]
  :as-response (pb-as-response CheckResourceRequest)
  :available-media-types ["application/json" "application/x-protobuf"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok (exgid-list-checks id))

(defresource gql-checks-resource [checks]
  :as-response (pb-as-response CheckResourceRequest)
  :available-media-types ["application/json" "application/x-protobuf"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok gql-list-checks)

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

  (context* "/gql" []
    :tags ["gql"]

    (GET* "/checks/:id" [id]
      :summary "Retrieve a check by its ID."
      :produces ["application/x-protobuf"]
      :return (pb/proto->schema Check)
      (gql-check-resource id nil))

    (GET* "/checks" []
      :summary "list all checks without hitting beabus"
      :produces ["application/json"]
      :return [(pb/proto->schema Check)]
      (gql-checks-resource nil)))

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
      (check-resource id check))

    (GET* "/exgid/:id" [id]
      :summary "List all checks by execution group id"
      :produces ["application/json" "application/x-protobuf"]
      :return [(pb/proto->schema Check)]
      (checks-exgid-resource id)))
 
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
