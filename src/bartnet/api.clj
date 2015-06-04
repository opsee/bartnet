(ns bartnet.api
  (:require [bartnet.auth :as auth]
            [bartnet.email :refer [send-activation! send-verification!]]
            [bartnet.instance :as instance]
            [bartnet.pubsub :refer :all]
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
            [bartnet.identifiers :as identifiers])
  (:import (java.sql BatchUpdateException)
           [java.util Base64]
           (java.sql BatchUpdateException)))

(def config (atom nil))
(def db (atom nil))
(def secret (atom nil))
(def pubsub (atom nil))

(defn param->int [n]
  (Integer/parseInt n))

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

(defn log-request [handler]
  (fn [request]
    (if-let [body-rdr (:body request)]
      (let [body (slurp body-rdr)
            req1 (assoc request :strbody body)]
        (log/info req1)
        (handler req1))
      (do (log/info request)
          (handler request)))))

(defn log-and-error [ex]
  (log/error ex "problem encountered")
  {:status 500
   :headers {"Content-Type" "application/json"}
   :body (generate-string {:error (.getMessage ex)})})

(defn robustify [handler]
  (fn [request]
    (try
      (handler request)
      (catch BatchUpdateException ex (log-and-error (.getNextException ex)))
      (catch Exception ex (log-and-error ex)))))

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

(defn add-hmac-to-ctx
  "Gets the login from the context and generates an HMAC which gets added to the response"
  [ctx]
    (let [login (:login ctx)
          id (str (:id login))
          hmac (str id "--" (.encodeToString (Base64/getUrlEncoder) (auth/generate-hmac-signature id @secret)))]
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

(defn get-environment [ctx]
  (:env ctx))

(defn get-new-login [ctx]
  (if-let [error (:error ctx)]
    (ring-response {:status (:status error)
                    :body (generate-string {:error (:message error)})
                    :headers {"Content-Type" "application/json"}})
    (dissoc (or (:new-login ctx) (:old-login ctx)) :password_hash)))

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

(defn ping-bastion! [id]
  (fn [ctx]
    (let [recv @(send-msg @pubsub id "echo" "echo")]
      (log/info "msg " recv)
      recv)))

(defn list-bastions []
  (fn [ctx]
    (let [login (:login ctx)]
      (for [brec (get-bastions @pubsub (:customer_id login))
            :let [reg (:registration brec)]]
        reg))))

(defn cmd-bastion! [id]
  (fn [ctx]
    (let [cmd (json-body ctx)
          recv @(send-msg @pubsub id (:cmd cmd) (:body cmd))]
      {:msg recv})))

(defn get-msg [ctx]
  (:msg ctx))

(defn check-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login @db (:id login)))
          check (first (sql/get-check-by-id @db id))]
      (if (= (:id env) (:environment_id check))
        {:check check}))))

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
              (publish-command @pubsub (:customer_id login) {:cmd "healthcheck"
                                                            :body final-check})
              {:check final-check})))))))

(defn delete-check! [id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login @db (:id login)))
          check (first (sql/get-check-by-id @db id))]
      (if (= (:id env) (:environment_id check))
        (do
          (sql/delete-check-by-id! @db id)
          (publish-command @pubsub (:customer_id login) {:cmd "delete"
                                                        :body {:checks [id]}}))))))

(defn create-check! [ctx]
  (let [login (:login ctx)
        env (first (sql/get-environments-for-login @db (:id login)))
        check (json-body ctx)
        id (identifiers/generate)
        final-check (merge check {:id id, :environment_id (:id env)})]
    (sql/insert-into-checks! @db final-check)
    (publish-command @pubsub (:customer_id login) {:cmd "healthcheck"
                                                  :body final-check})))

(defn list-checks [ctx]
  (let [login (:login ctx)
        env (first (sql/get-environments-for-login @db (:id login)))]
    (sql/get-checks-by-env-id @db (:id env))))

(defn get-check [ctx]
  (:check ctx))

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
    (sql/get-signups @db query-limit (* (- page 1) query-limit))))

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

(defn get-activation [ctx]
  (:activation ctx))

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

(defn get-vpcs [ctx]
  (:regions ctx))

(defn subdomain-exists? [subdomain]
  (empty? (sql/get-org-by-subdomain @db subdomain)))

(defn create-org! [ctx]
  (let [org (json-body ctx)]
    (sql/insert-into-orgs! @db org)
    {:org org}))

(defn get-org [ctx]
  (:org ctx))

(defn find-instance [id]
  (fn [ctx]
    (let [login       (:login ctx)
          customer_id (:customer_id login)
          instance    (instance/get-instance! customer_id id)]
      (when instance
        {:instance instance}))))

(defn get-instance [ctx]
  (:instance ctx))

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
  :handle-created get-activation)

(defresource activation-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :exists? (activation-exists? id)
  :post! activate-activation!
  :handle-ok get-activation
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
  :handle-created get-environment)

(defresource environment-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:get :put :delete]
  :authorized? (authorized?)
  :exists? (environment-exists? id)
  :put! (update-environment! id)
  :delete! (delete-environment! id)
  :handle-ok get-environment)

;(defresource logins-resource []
;             :available-media-types ["application/json"]
;             :allowed-methods [:get :post]
;             :authorized? (authorized? :superuser)
;             :post! create-login!
;             :handle-ok list-logins
;             :handle-created get-new-login)

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
  :handle-ok get-instance)

(defresource bastion-resource [id]
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :authorized? (authorized?)
  :post! (cmd-bastion! id)
  :handle-created get-msg)

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
  :handle-ok get-check)

(defresource checks-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:get :post]
  :authorized? (authorized?)
  :post! create-check!
  :handle-created get-check
  :handle-ok list-checks)

(defresource scan-vpc-resource []
  :available-media-types ["application/json"]
  :allowed-methods [:post]
  :post! scan-vpcs
  :handle-created get-vpcs)

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
  :handle-created get-org)

(defresource org-resource [subdomain]
  :available-media-types ["application/json"]
  :allowed-methods [:get]
  :authorized? (authorized?)
  :exists? (fn [_] ((if (subdomain-exists? subdomain) [true {:org (first (sql/get-org-by-subdomain @db subdomain))}] false)))
  :handle-ok get-org)

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
  (swagger-docs "/api/swagger.json")
  (swagger-ui "/api/swagger" :swagger-docs "/api/swagger.json")
  (GET* "/health_check", [], "A ok")
  (ANY* "/signups" [] (signups-resource))
  (ANY* "/signups/send-activation" [] (signup-resource))
  (GET* "/activations/:id", [id] (activation-resource id))
  (POST* "/activations/:id/activate", [id] (activation-resource id))
  (POST* "/verifications/:id/activate", [id] (activation-resource id))
  (ANY* "/authenticate/password" [] (authenticate-resource))
  (ANY* "/environments" [] (environments-resource))
  (ANY* "/environments/:id" [id] (environment-resource id))
  (ANY* "/logins/:id" [id] (login-resource (param->int id)))
  (ANY* "/scan-vpcs" [] (scan-vpc-resource))
  (POST* "/orgs" [] (orgs-resource))
  (ANY* "/orgs/:subdomain" [subdomain] (org-resource subdomain))
  (GET* "/orgs/subdomain/:subdomain" [subdomain] (subdomain-resource subdomain))
  (ANY* "/bastions" [] (bastions-resource))
  (ANY* "/bastions/:id" [id] (bastion-resource id))
  (ANY* "/discovery" [] (discovery-resource))
  (ANY* "/checks" [] (checks-resource))
  (ANY* "/checks/:id" [id] (check-resource id))
  (GET* "/instance/:id" [id] (instance-resource id)))

(defn handler [message-bus database conf]
  (reset! pubsub message-bus)
  (reset! db database)
  (reset! config conf)
  (reset! secret (:secret conf))
  (->
    bartnet-api
    (log-request)
    (robustify)
    (wrap-cors :access-control-allow-origin [#".*"]
      :access-control-allow-methods [:get :put :post :patch :delete]
      :access-control-allow-headers ["X-Auth-HMAC"])
    (wrap-options)
    (wrap-params)
    (wrap-trace :header :ui)))