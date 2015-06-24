(ns bartnet.api
  (:require [bartnet.auth :as auth]
            [bartnet.email :refer [send-activation! send-verification!]]
            [bartnet.instance :as instance]
            [bartnet.sql :as sql]
            [clojure.tools.logging :as log]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [ring-response]]
            [yesql.util :refer [slurp-from-classpath]]
            [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [amazonica.aws.ec2 :refer [describe-vpcs describe-account-attributes]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.api.sweet :refer :all]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [bartnet.identifiers :as identifiers]
            [bartnet.launch :as launch]
            [bartnet.autobus :as msg]
            [schema.core :as sch])
  (:import (java.sql BatchUpdateException)
           [java.util Base64]
           (java.sql BatchUpdateException)))

;; Schemata

(sch/defschema Signup
  {
   :id sch/Int
   :email sch/Str
   :name sch/Str
   :created_at sch/Str
   :updated_at sch/Str
   :activation_id (sch/maybe sch/Str)
   :activation_used (sch/maybe sch/Bool)
   })

(sch/defschema Account
  {
   :email sch/Str
   :verified sch/Bool
   })

(sch/defschema APIObjectPointer
  {:type sch/Str :name sch/Str :id sch/Str})

(sch/defschema Organization
  {
   :name                   sch/Str
   :domain                 sch/Str
   :id                     sch/Str
   :users                  [APIObjectPointer]
   (sch/optional-key :teams) [APIObjectPointer]
   })

(sch/defschema Team
  {
   :name sch/Str
   :users [APIObjectPointer]
   })

(sch/defschema User
  {
   :id           sch/Str
   :meta         {
                  :admin       sch/Bool
                  :active      sch/Bool
                  :created_at  sch/Str
                  :updated_at  sch/Str
                  }
   :accounts     [Account]
   :integrations {
                  :slack {
                          :access_key sch/Str
                          :user       clojure.lang.APersistentMap
                          }
                  }
   (sch/optional-key :bio) {
                          :name sch/Str
                          :title sch/Str
                          }
   (sch/optional-key :organizations) [Organization]
   })

(def protocol-enum
  (sch/enum "http"))

(def verb-enum
  (sch/enum "GET" "PUT" "POST" "HEAD" "OPTIONS" "DELETE" "TRACE" "CONNECT"))

(def check-rel-enum
  (sch/enum "equal-to" "not-equal-to" "is-empty" "is-not-empty" "contains" "regex"))

(sch/defschema CheckAssertion
  {
   :relationship check-rel-enum
   :value sch/Str
   :type sch/Str
   })

(sch/defschema SilenceAttrs
  {
   :startDate sch/Str
   :duration sch/Int
   :user User
   })

(sch/defschema CheckStatus
  {
   :passing sch/Bool
   :passingChanged sch/Str
   :state sch/Str
   :silence SilenceAttrs
   })

(sch/defschema CheckNotification
  {
   :type sch/Str
   :value sch/Str
   })

(sch/defschema Check
  {
   :name sch/Str
   :id sch/Str
   :url sch/Str
   :interval sch/Int
   :protocol protocol-enum
   :verb verb-enum
   :assertions [CheckAssertion]
   :status CheckStatus
   :notifications [CheckNotification]
   })

(def build-check [])

(sch/defschema Instance
  {
   :id sch/Str
   :name sch/Str
   :customer_id sch/Str
   :meta {
          :state sch/Str
          :lastChecked sch/Str
          :created sch/Str
          :instanceSize sch/Str
          }
   })

(sch/defschema Group
  {
   :id          sch/Str
   :customer_id sch/Str
   :name        (sch/maybe sch/Str)
   })

(sch/defschema CompositeGroup
  (merge Group { :instances [Instance] }))

(sch/defschema CompositeInstance
  (merge Instance {
                   :checks [(sch/maybe Check)]
                   :groups [(sch/maybe Group)]
                   }))

(defn build-group [customer-id id]
  (let [group (instance/get-group! customer-id id)]
    {
     :name (:group_name group)
     :customer_id customer-id
     :id (:group_id group)
     :instances (:instances group)
     }))

(defn build-composite-group [customer-id id]
  (let [group (build-group customer-id id)]
    (merge group
      (let [instances (:instances group)]
        (assoc (dissoc group :instances)
          :instances (map #(instance/get-instance! customer-id %) instances))))))

(defn build-composite-instance [instance]
  (let [group-hints (:groups instance)
        instance    (dissoc instance :groups)
        customer-id (:customer_id instance)]
    (merge instance
      {
       ;:checks (map build-check (sql/get-checks-by-customer-id @db customer-id))
       :groups (map (fn [g] (build-group customer-id (:group_id g))) group-hints)
       })))

(defn find-instance [id]
  (fn [ctx]
    (log/info "find-instance was called")
    (let [login       (:login ctx)
          customer_id (:customer_id login)
          instance    (instance/get-instance! customer_id id)]
      (log/info "login: " login " customer_id: " customer_id " instance: " instance)
      (when instance
        {:instance (build-composite-instance instance)}))))

(defn get-instances [ctx]
  (let [customer-id (get-in ctx [:login :customer_id])]
    {:instances (instance/list-instances! customer-id)}))

(sch/defschema CompositeGroup
  (merge Group
    {
     :checks [Check]
     :instances [Instance]
     }))

(sch/defschema CheckTarget
  {
   :type sch/Str
   :id sch/Str
   })

(sch/defschema CompositeCheck
  (merge Check
    {
     :targets [CheckTarget]
     }))


(def executor (atom nil))
(def config (atom nil))
(def db (atom nil))
(def secret (atom nil))
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
                          req1 (assoc request :strbody body)]
                      (log/info "request:" req1)
                      (handler req1))
                    (do (log/info "request:" request)
                        (handler request)))))

(defn log-response [handler]
  (fn [request]
    (let [response (handler request)]
      (log/info "response:" response)
      response)))

(defn log-and-error [ex]
  (log/error ex "problem encountered")
  {:status 500
   :headers {"Content-Type" "application/json"}
   :body (generate-string {:error (.getMessage ex)})})

(defn robustify-errors [^Exception ex]
  (if (instance? BatchUpdateException ex)
    (log-and-error (.getNextException ex))
    (log-and-error ex)))


(defn json-body [ctx]
  (if-let [body (get-in ctx [:request :strbody])]
    (parse-string body true)))

(defn respond-with-entity? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn allowed-to-auth?
  "Checks the body of the request and uses the password to authenticate the user."
  [ctx]
    (if-let [unsec-login (json-body ctx)]
      (auth/basic-authenticate @db (:email unsec-login) (:password unsec-login))))

(defn generate-hmac-string [id]
  (str id "--" (.encodeToString (Base64/getUrlEncoder) (auth/generate-hmac-signature id @secret))))

(defn add-hmac-to-ctx
  "Gets the login from the context and generates an HMAC which gets added to the response"
  [ctx]
    (let [login (:login ctx)
          id (str (:id login))
          hmac (generate-hmac-string id)]
      (ring-response {:headers {"X-Auth-HMAC" hmac}
                      :body (generate-string (merge (dissoc login :password_hash)
                                               {:token (str "HMAC " hmac)}))})))

(defn user-authorized? [ctx]
  (if-let [auth-header (get-in ctx [:request :headers "authorization"])]
    (let [[auth-type slug] (str/split auth-header #" " 2)]
      (auth/authorized? @db auth-type slug @secret))))

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

(defn list-environments [ctx]
  (sql/get-environments-for-login @db (:id (:login ctx))))

(defn create-environment! [ctx]
  (let [login (:login ctx)
        id (identifiers/generate)
        env (assoc (json-body ctx) :id id)]
    (if (sql/insert-into-environments! @db id (:name env))
      (if (sql/link-environment-and-login! @db id (:id login))
        {:env env}))))

(defn get-new-login [ctx]
  (if-let [error (:error ctx)]
    (ring-response {:status (:status error)
                    :body (generate-string {:error (:message error)})
                    :headers {"Content-Type" "application/json"}})
    (do
      (let [login (or (:new-login ctx) (:old-login ctx))]
        (let [token (generate-hmac-string (:id login))]
          (assoc (dissoc login :password_hash) :token token))))))

(defn environment-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)]
      (if-let [env (first (sql/get-environment-for-login @db id (:id login)))]
        [true {:env env}]
        false))))

(defn update-environment! [id]
  (fn [ctx]
    (let [env (json-body ctx)]
      (if (sql/update-environment! @db (:name env) id)
        {:env env}))))

(defn delete-environment! [id]
  (fn [ctx]
    (sql/toggle-environment! @db false id)))

(defn get-bastions [bus customer-id] {})

(defn list-bastions []
  (fn [ctx]
    (let [login (:login ctx)]
      (for [brec (get-bastions @bus (:customer_id login))
            :let [reg (:registration brec)]]
        reg))))

(defn send-msg [bus id cmd body] {})

(defn cmd-bastion! [id]
  (fn [ctx]
    (let [cmd (json-body ctx)
          recv @(send-msg @bus id (:cmd cmd) (:body cmd))]
      {:msg recv})))

(defn check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login @db (:id login)))
          check (first (sql/get-check-by-id @db id))]
      (if (= (:id env) (:environment_id check))
        {:check check}))))

(defn publish-command [msg]
  (msg/publish @bus @client msg))

(defn update-check! [id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login @db (:id login)))
          check (:check ctx)
          updated-check (json-body ctx)]
      (if (= (:id env) (:environment_id check))
        (let [merged (merge check (assoc updated-check :id id))]
          (log/info merged)
          (if (sql/update-check! @db merged)
            (let [final-check (first (sql/get-check-by-id @db id))]
              (publish-command (msg/map->Message {:customer_id (:customer_id login)
                                                  :command "healthcheck"
                                                  :state "update"
                                                  :attributes final-check}))
              {:check final-check})))))))

(defn delete-check! [id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login @db (:id login)))
          check (first (sql/get-check-by-id @db id))]
      (if (= (:id env) (:environment_id check))
        (do
          (sql/delete-check-by-id! @db id)
          (publish-command (msg/map->Message {:customer_id (:customer_id login)
                                              :command "healthcheck"
                                              :state "delete"
                                              :attributes {:checks id}})))
        (log/info (:id env) (:environment_id check))))))

(defn create-check! [ctx]
  (let [login (:login ctx)
        env (first (sql/get-environments-for-login @db (:id login)))
        check (json-body ctx)
        id (identifiers/generate)
        final-check (merge check {:id id, :environment_id (:id env)})]
    (sql/insert-into-checks! @db final-check)
    (publish-command (msg/map->Message {:customer_id (:customer_id login)
                                        :command "healthcheck"
                                        :state "new"
                                        :attributes final-check}))))

(defn list-checks [ctx]
  (let [login (:login ctx)
        env (first (sql/get-environments-for-login @db (:id login)))]
    (sql/get-checks-by-env-id @db (:id env))))

(def query-limit 100)

(defn retr-signup [email default]
  (if-let [signup (first (sql/get-signup-by-email @db email))]
    {:signup signup :duplicate true}
    [false, {:signup default}]))

(defn signup-exists? [ctx]
    (if-let [new-signup (json-body ctx)]
      (retr-signup (:email new-signup) new-signup)
      (if-let [email (get-in ctx [:request :params "email"])]
        (retr-signup email nil)
        true)))

(defn create-and-send-activation! [ctx]
    (let [id (identifiers/generate)
          signup (:signup ctx)
          activation (assoc signup :id id)]
      (if (sql/insert-into-activations! @db activation)
        (send-activation! @config signup id)
        (let [saved-activation (first (sql/get-unused-activation @db id))]
          {:activation saved-activation}))))

(defn create-and-send-verification! [login]
  (let [id (identifiers/generate)
        activation {:id id
                    :email (:email login)
                    :name (:name login)}]
    (if (sql/insert-into-activations! @db activation)
      (send-verification! @config login id))))

(defn create-signup! [ctx]
  (if (not (:duplicate ctx))
    (let [signup (:signup ctx)]
      (sql/insert-into-signups! @db signup))))

(defn list-signups [ctx]
  (let [page (or (get-in ctx [:request :params "page"]) 1)]
    (sql/get-signups-with-activations @db query-limit (* (- page 1) query-limit))))

(defn get-signup [ctx]
  (if (:duplicate ctx)
    (ring-response {:status 409
                    :body (generate-string {:error "Conflict: that email is already signed up."})
                    :headers {"Content-Type" "application/json"}})
    (:signup ctx)))

(defn activation-exists? [id]
  (fn [_]
    (if-let [activation (first (sql/get-unused-activation @db id))]
      {:activation activation})))

(defn- use-activation! [login activation]
  (let [saved-login (first (sql/get-active-login-by-email @db (:email login)))]
    (sql/update-activations-set-used! @db (:id activation))
    {:new-login saved-login}))

(defn activate-activation! [ctx]
  (let [activation (:activation ctx)]
    (if-not activation
      {:error {:status 409
               :message "invalid activation"}}
      (if-let [existing-login (first (sql/get-active-login-by-email @db (:email activation)))]
        ;this is a verification of an existing login's email change
        (if (sql/update-login! @db (merge existing-login {:verified true}))
          (use-activation! existing-login activation))
        ;this is the activation of a new account
        (if-let [login-details (json-body ctx)]
          (let [hashed-pw (auth/hash-password (:password login-details))
                login (assoc login-details :password_hash hashed-pw
                                           :email (:email activation)
                                           :name (:name activation))]
            (if (sql/insert-into-logins! @db login)
              (use-activation! login activation))))))))

(defn allowed-edit-login? [id]
  (fn [ctx]
    (let [login (:login ctx)]
      (if (or (:admin login)
            (= id (:id login)))
        (let [new-login (json-body ctx)]
          (if (:new_password new-login)
            (if (auth/password-match? (:old_password new-login)
                  (:password_hash login))
              [true, {:new-login new-login}]
              false)
            [true, {:new-login new-login}]))))))

(defn login-exists? [id]
  (fn [ctx]
    (if-let [login (first (sql/get-active-login-by-id @db id))]
      [true, {:old-login login}])))

(defn- do-update! [old-login new-login verified]
  (let [hash (if (:new_password new-login)
               (auth/hash-password (:new_password new-login))
               (:password_hash old-login))
        merged (merge old-login new-login {:verified verified :password_hash hash})]
    (sql/update-login! @db merged)))

(defn update-login! [id]
  (fn [ctx]
    (let [new-login (:new-login ctx)
          old-login (:old-login ctx)
          verified (if (contains? new-login :email)
                     (= (:email new-login) (:email old-login))
                     true)]
      (if (and (not verified)
            (not (empty? (sql/get-any-login-by-email @db (:email new-login)))))
        {:error {:status 409
                 :message (str "Email " (:email new-login) " already exists.")}}
        (if (do-update! old-login new-login verified)
          (if-let [saved-login (first (sql/get-active-login-by-id @db id))]
            (do
              (if-not verified (create-and-send-verification! new-login))
              {:new-login saved-login})))))))

(defn delete-login! [id]
  (fn [ctx]
    (sql/deactivate-login! @db id)))

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
                  {:region region
                   :ec2-classic (ec2-classic? attrs)
                   :vpcs (:vpcs vpcs)}))}))

(defn subdomain-exists? [subdomain]
  (empty? (sql/get-org-by-subdomain @db subdomain)))

(defn create-org! [ctx]
  (let [org (json-body ctx)
        login (:login ctx)
        customer-id (:customer_id login)]
    (sql/insert-into-orgs! @db org)
    (when-not customer-id
      (sql/update-login! @db (assoc login :customer_id (:subdomain org))))
    {:org org}))

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

(defn launch-bastions! [ctx]
  (let [login (:login ctx)
        launch-cmd (json-body ctx)]
    {:regions (launch/launch-bastions @executor @bus (:customer_id login) launch-cmd (:ami @config))}))

(defresource signups-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post :get]
  :exists? signup-exists?
  :authorized? (authorized? #(case (get-in % [:request :request-method])
                                        :get :superuser
                                        :unauthenticated))
  :post! create-signup!
  :handle-ok list-signups
  :handle-created get-signup)

(defresource signup-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized? :superuser)
  :exists? signup-exists?
  :post! create-and-send-activation!
  :handle-created :activation)

(defresource activation-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :exists? (activation-exists? id)
  :post! activate-activation!
  :handle-ok :activation
  :handle-created get-new-login)

(defresource authenticate-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? allowed-to-auth?
  :handle-created add-hmac-to-ctx)

(defresource environments-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :authorized? (authorized?)
  :post! create-environment!
  :handle-ok list-environments
  :handle-created :env)

(defresource environment-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (environment-exists? id)
  :put! (update-environment! id)
  :delete! (delete-environment! id)
  :handle-ok :env)

(defresource login-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :patch :delete]
  :authorized? (authorized?)
  :allowed? (allowed-edit-login? id)
  :exists? (login-exists? id)
  :patch! (update-login! id)
  :delete! (delete-login! id)
  :new? false
  :respond-with-entity? respond-with-entity?
  :handle-ok get-new-login)

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

(defresource discovery-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get])

(defresource check-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (check-exists? id)
  :put! (update-check! id)
  :new? false
  :respond-with-entity? respond-with-entity?
  :delete! (delete-check! id)
  :handle-ok :check)

(defresource checks-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :authorized? (authorized?)
  :post! create-check!
  :handle-created :check
  :handle-ok list-checks)

(defresource scan-vpc-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :post! scan-vpcs
  :handle-created :regions)

(defresource subdomain-resource [subdomain]
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :handle-ok (fn [_] {:available (subdomain-exists? subdomain)}))

(defresource orgs-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :exists? (fn [ctx]
             (let [subdomain (:subdomain (json-body ctx))]
               (subdomain-exists? subdomain)))
  :post! create-org!
  :handle-created :org)

(defresource org-resource [subdomain]
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :exists? (fn [_] ((if (subdomain-exists? subdomain) [true {:org (first (sql/get-org-by-subdomain @db subdomain))}] false)))
  :handle-ok :org)

(defn wrap-options [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 200
       :body ""
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, PUT, POST, PATCH, DELETE"
                 "Access-Control-Allow-Headers" "Accept-Encoding,Authorization,Content-Type,X-Auth-HMAC"
                 "Access-Control-Max-Age" "1728000"}}
      (handler request))))

(defapi bartnet-api
  {:exceptions {:exception-handler robustify-errors}}
  (swagger-docs "/api/swagger.json"
    {:info {
            :title "Opsee API"
            :description "Own your availability."
            }})
  (swagger-ui "/api/swagger" :swagger-docs "/api/swagger.json")
  ;; TODO: Split out request methods and document with swagger metadata
  (GET* "/health_check" [] "A ok")
  (ANY* "/signups/send-activation" [] (signup-resource))
  (GET* "/activations/:id" [id] (activation-resource id))
  (POST* "/activations/:id/activate" [id] (activation-resource id))
  (POST* "/verifications/:id/activate" [id] (activation-resource id))
  (ANY* "/authenticate/password" [] (authenticate-resource))
  (ANY* "/environments" [] (environments-resource))
  (ANY* "/environments/:id" [id] (environment-resource id))
  (ANY* "/logins/:id" [id] (login-resource (param->int id)))
  (ANY* "/scan-vpcs" [] (scan-vpc-resource))
  (POST* "/orgs" [] (orgs-resource))
  (ANY* "/orgs/:subdomain" [subdomain] (org-resource subdomain))
  (GET* "/orgs/subdomain/:subdomain" [subdomain] (subdomain-resource subdomain))
  (ANY* "/bastions" [] (bastions-resource))
  (ANY* "/bastions/launch" [] (launch-bastions-resource))
  (ANY* "/bastions/:id" [id] (bastion-resource id))
  (ANY* "/discovery" [] (discovery-resource))
  (ANY* "/checks" [] (checks-resource))
  (ANY* "/checks/:id" [id] (check-resource id))

  ;; DONE
  (GET* "/signups" []
    :summary "List signups, including activation id and status."
    ;:return (sch/maybe [Signup])
    (signups-resource))
  (POST* "/signups" [] (signups-resource))
  (GET* "/instance/:id" [id]
    :summary "Retrieve instance by ID."
    :path-params [id :- sch/Str]
    ;:return (sch/maybe CompositeInstance)
    (instance-resource id))
  (GET* "/instances" []
    :summary "Retrieve a list of instances."
    (instances-resource))
  (GET* "/group/:id" [id]
    :summary "Retrieve a Group by ID."
    :path-params [id :- sch/Str]
    ;:return (sch/maybe CompositeGroup)
    (group-resource id))
  (GET* "/groups" []
    :summary "Retrieve a list of groups."
    (groups-resource)))

(defn handler [exe message-bus database conf]
  (reset! executor exe)
  (reset! bus message-bus)
  (reset! db database)
  (reset! config conf)
  (reset! secret (:secret conf))
  (reset! client (msg/register @bus (msg/publishing-client) "*"))
  (->
    bartnet-api
    (log-request)
    (log-response)
    (wrap-cors :access-control-allow-origin [#".*"]
      :access-control-allow-methods [:get :put :post :patch :delete]
      :access-control-allow-headers ["X-Auth-HMAC"])
    (wrap-options)
    (wrap-params)
    (wrap-trace :header :ui)))
