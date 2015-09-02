(ns bartnet.api
  (:require [bartnet.instance :as instance]
            [bartnet.sql :as sql]
            [bartnet.rpc :as rpc :refer [all-bastions]]
            [bartnet.protobuilder :as pb]
            [bartnet.bastion-router :as router]
            [clojure.tools.logging :as log]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [ring-response]]
            [yesql.util :refer [slurp-from-classpath]]
            [amazonica.aws.ec2 :refer [describe-vpcs describe-account-attributes]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.api.sweet :refer :all]
            [compojure.api.meta :as meta]
            [compojure.api.middleware :as mw]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [bartnet.identifiers :as identifiers]
            [bartnet.auth :as auth]
            [bartnet.launch :as launch]
            [bartnet.util :as util]
            [bartnet.bus :as bus]
            [schema.core :as sch]
            [liberator.dev :refer [wrap-trace]]
            [liberator.core :refer [resource defresource]])
  (:import (java.sql BatchUpdateException)
           [java.util Base64]
           (java.sql BatchUpdateException)
           (co.opsee.proto TestCheckRequest TestCheckResponse CheckResourceRequest Check)
           (java.io ByteArrayInputStream InputStream)))

;; preamble enables spiffy protobuf coercion

(defmethod meta/restructure-param :proto [_ [value clazz] acc]
  (-> acc
      (update-in [:lets] into [value (meta/src-coerce! (resolve clazz) :body-params :proto)])
      (assoc-in [:parameters :parameters :body] (pb/proto->schema (resolve clazz)))))


(defn build-group [customer-id id]
  (let [group (instance/get-group! customer-id id)]
    {
     :name        (:group_name group)
     :customer_id customer-id
     :id          (:group_id group)
     :instances   (:instances group)
     }))

(defn build-composite-group [customer-id id]
  (let [group (build-group customer-id id)]
    (merge group
           (let [instances (:instances group)]
             (assoc (dissoc group :instances)
               :instances (map #(instance/get-instance! customer-id %) instances))))))

(defn build-composite-instance [instance]
  (let [group-hints (:groups instance)
        instance (dissoc instance :groups)
        customer-id (:customer_id instance)]
    (merge instance
           {
            ;:checks (map build-check (sql/get-checks-by-customer-id @db customer-id))
            :groups (map (fn [g] (build-group customer-id (:group_id g))) group-hints)
            })))

(defn find-instance [id]
  (fn [ctx]
    (log/info "find-instance was called")
    (let [login (:login ctx)
          customer-id (:customer_id login)
          instance (instance/get-instance! customer-id id)]
      (log/info "login: " login " customer_id: " customer-id " instance: " instance)
      (when instance
        {:instance (build-composite-instance instance)}))))

(defn get-instances [ctx]
  (let [customer-id (get-in ctx [:login :customer_id])]
    {:instances (instance/list-instances! customer-id)}))

(def executor (atom nil))
(def config (atom nil))
(def db (atom nil))
(def bus (atom nil))
(def client (atom nil))

(defn param->int [n]
  (Integer/parseInt n))

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

; TODO: Loginator for Clojure
(defn log-request [handler]
  (fn [request]
    (if-let [body-rdr (:body request)]
      (let [body (slurp body-rdr)
            req' (assoc request
                   :strbody body
                   :body (ByteArrayInputStream. (.getBytes body)))
            req'' (if-not (get-in req' [:headers "Content-Type"])
                    (assoc-in req' [:headers "Content-Type"] "application/json"))]
        (log/info "request:" req'')
        (handler req''))
      (do (log/info "request:" request)
          (handler request)))))

(defn log-response [handler]
  (fn [request]
    (let [response (handler request)
          response' (if (instance? java.io.InputStream (:body response))
                      (assoc response :body (slurp (:body response)))
                      response)]
      (log/info "response:" response')
      response')))

(defn log-and-error [ex]
  (log/error ex "problem encountered")
  {:status  500
   :headers {"Content-Type" "application/json"}`
   :body    (generate-string {:error (.getMessage ex)})})

(defn robustify-errors [^Exception ex]
  (if (instance? BatchUpdateException ex)
    (log-and-error (.getNextException ex))
    (log-and-error ex)))


(defn json-body [ctx]
  (if-let [body (get-in ctx [:request :strbody])]
    (parse-string body true)
    (if-let [in (get-in ctx [:request :body])]
      (parse-stream in true))))

(defn respond-with-entity? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn user-authorized? [ctx]
  (if-let [auth-header (get-in ctx [:request :headers "authorization"])]
    (let [[_ slug] (str/split auth-header #" " 2)]
      (auth/authorized? slug))))

(defn superuser-authorized? [ctx]
  (if-let [[answer {login :login}] (user-authorized? ctx)]
    (if (and answer (:admin login))
      [true, {:login login}])))

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  ([fn-auth-level]
   (fn [ctx]
     (case (if (fn? fn-auth-level)
             (fn-auth-level ctx)
             fn-auth-level)
       :unauthenticated true
       :user (user-authorized? ctx)
       :superuser (superuser-authorized? ctx))))
  ([]
   (authorized? :user)))

(defn list-bastions []
  (fn [ctx]
    (let [login (:login ctx)
          instance-ids (router/get-customer-bastions (:customer_id login))]
      {:bastions instance-ids})))

(defn send-msg [bus id cmd body] {})

(defn cmd-bastion! [id]
  (fn [ctx]
    (let [cmd (json-body ctx)
          recv @(send-msg @bus id (:cmd cmd) (:body cmd))]
      {:msg recv})))

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
    (let [login (:login ctx)]
      (if-let [check (first (sql/get-check-by-id @db id))]
        {:check (dissoc (resolve-lastrun (:customer_id login) (resolve-target check)) :customer_id)}))))

(defn update-check! [id pb-check]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          updated-check (pb/proto->hash pb-check)
          old-check (:check ctx)]
      (let [merged (merge old-check (assoc (resolve-target updated-check) :id id))]
        (log/info merged)
        (if (sql/update-check! @db (assoc merged :customer_id customer-id))
          (let [final-check (dissoc (resolve-target (first (sql/get-check-by-id @db id))) :customer_id)
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
          check (first (sql/get-check-by-id @db id))]
      (do
        (sql/delete-check-by-id! @db id)
        (let [req (-> (CheckResourceRequest/newBuilder)
                      (.addChecks (-> (Check/newBuilder)
                                      (.setId id)
                                      .build))
                      .build)]
          (all-bastions (:customer_id login) #(rpc/delete-check % req)))))))

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

(defn scan-vpcs [ctx]
  (let [creds (json-body ctx)]
    {:regions (for [region (:regions creds)]
                (let [cd (assoc creds :endpoint region)
                      vpcs (describe-vpcs cd)
                      attrs (describe-account-attributes cd)]
                  {:region      region
                   :ec2-classic (ec2-classic? attrs)
                   :vpcs        (:vpcs vpcs)}))}))

(defn find-group [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          group (build-composite-group customer-id id)]
      (when group
        {:group group}))))

(defn get-groups [ctx]
  (let [customer-id (get-in ctx [:login :customer_id])]
    {:groups (instance/list-groups! customer-id)}))

(defn test-check! [instance_id testCheck]
  (fn [ctx]
    (let [login (:login ctx)
          addr (router/get-service (:customer_id login) instance_id "checker")
          _ (log/info "addr" addr)
          client (rpc/checker-client addr)
          response (rpc/test-check client testCheck)]
      (log/info "resp" response)
      {:test-results response})))

(defn launch-bastions! [ctx]
  (let [login (:login ctx)
        launch-cmd (json-body ctx)]
    {:regions (launch/launch-bastions @executor @bus (:customer_id login) launch-cmd (:ami @config))}))

(defresource instance-resource [id]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized?)
             :exists? (find-instance id)
             :handle-ok :instance)

(defresource instances-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized?)
             :handle-ok get-instances)

(defresource group-resource [id]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized?)
             :exists? (find-group id)
             :handle-ok :group)

(defresource groups-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized?)
             :handle-ok get-groups)

(defresource launch-bastions-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized?)
             :post! launch-bastions!
             :handle-created :regions)

(defresource bastion-resource [id]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized?)
             :post! (cmd-bastion! id)
             :handle-created :msg)

(defresource bastions-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized?)
             :handle-ok list-bastions)

(defresource test-check-resource [id testCheck]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized?)
             :post! (test-check! id testCheck)
             :handle-created (fn [ctx] (pb/proto->hash (:test-results ctx))))

(defresource discovery-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:get])

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

(defresource scan-vpc-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :post! scan-vpcs
             :handle-created :regions)

(defn wrap-options [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status  200
       :body    ""
       :headers {"Access-Control-Allow-Origin"  "*"
                 "Access-Control-Allow-Methods" "GET, PUT, POST, PATCH, DELETE"
                 "Access-Control-Allow-Headers" "Accept-Encoding,Authorization,Content-Type"
                 "Access-Control-Max-Age"       "1728000"}}
      (handler request))))

(defapi bartnet-api
        {:exceptions {:exception-handler robustify-errors}
         ;:validation-errors {:error-handler robustify-errors}
         :coercion   (fn [_] (assoc mw/default-coercion-matchers
                               :proto pb/proto-walker))}
        (swagger-docs "/api/swagger.json"
                      {:info {:title       "Opsee API"
                              :description "Own your availability."
                              }})
        (swagger-ui "/api/swagger" :swagger-docs "/api/swagger.json")
        ;; TODO: Split out request methods and document with swagger metadata
        (GET*    "/health_check" [] "A ok")
        (ANY*    "/scan-vpcs" [] (scan-vpc-resource))
        (ANY*    "/bastions" [] (bastions-resource))
        (ANY*    "/bastions/launch" [] (launch-bastions-resource))
        (ANY*    "/bastions/:id" [id] (bastion-resource id))
        (POST*   "/bastions/:id/test-check" [id]
                 :summary "Tells the bastion instance in question to test out a check and return the response"
                 :proto [testCheck TestCheckRequest]
                 :return (pb/proto->schema TestCheckResponse)
                 (test-check-resource id testCheck))

        (ANY*    "/discovery" [] (discovery-resource))

        (POST*   "/checks" []
                 :summary "Create a check"
                 :proto [check Check]
                 :return (pb/proto->schema Check)
                 (checks-resource check))

        (GET*    "/checks" []
                 :summary "List all checks"
                 :return [(pb/proto->schema Check)]
                 (checks-resource nil))

        (GET*    "/checks/:id" [id]
                 :summary "Retrieve a check by its ID."
                 :return (pb/proto->schema Check)
                 (check-resource id nil))

        (DELETE* "/checks/:id" [id]
                 :summary "Delete a check by its ID."
                 (check-resource id nil))

        (PUT*    "/checks/:id" [id]
                 :summary "Update a check by its ID."
                 :proto [check Check]
                 :return (pb/proto->schema Check)
                 (check-resource id check))


        ;; DONE
        (GET*    "/instance/:id" [id]
                 :summary "Retrieve instance by ID."
                 :path-params [id :- sch/Str]
                 ;:return (sch/maybe CompositeInstance)
                 (instance-resource id))
        (GET*    "/instances" []
                 :summary "Retrieve a list of instances."
                 (instances-resource))
        (GET*    "/group/:id" [id]
                 :summary "Retrieve a Group by ID."
                 :path-params [id :- sch/Str]
                 ;:return (sch/maybe CompositeGroup)
                 (group-resource id))
        (GET*    "/groups" []
                 :summary "Retrieve a list of groups."
                 (groups-resource)))

(defn handler [exe message-bus database conf]
  (reset! executor exe)
  (reset! bus message-bus)
  (reset! db database)
  (reset! config conf)
  (reset! client (bus/register @bus (bus/publishing-client) "*"))
  (->
    bartnet-api
    (log-request)
    (log-response)
    (wrap-cors :access-control-allow-origin [#".*"]
               :access-control-allow-methods [:get :put :post :patch :delete])
    (wrap-options)
    (wrap-params)
    (wrap-trace :header :ui)))
