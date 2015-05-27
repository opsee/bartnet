(ns bartnet.core
  (:gen-class)
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [amazonica.aws.ec2 :refer [describe-vpcs describe-account-attributes]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.core :refer :all]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [ring-response]]
            [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [bartnet.identifiers :as identifiers]
            [bartnet.bastion :as bastion]
            [bartnet.launch :as launch]
            [bartnet.email :refer [send-activation! send-verification!]]
            [bartnet.db-cmd :as db-cmd]
            [bartnet.upload-cmd :as upload-cmd]
            [bartnet.pubsub :refer :all]
            [bartnet.util :refer [if-and-let]]
            [manifold.stream :as s]
            [yesql.util :refer [slurp-from-classpath]]
            [ring.adapter.jetty9 :refer :all])
  (:import [java.util Base64]
           [org.cliffc.high_scale_lib NonBlockingHashMap]
           [java.util.concurrent CopyOnWriteArraySet ExecutorService]
           [io.aleph.dirigiste Executors]
           (java.sql BatchUpdateException)
           (java.io IOException)
           (org.eclipse.jetty.server HttpInputOverHTTP)
           (java.sql BatchUpdateException)))

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
            req1 (assoc request :body body)]
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
  (if-let [body (get-in ctx [:request :body])]
    (parse-string body true)))

(defn respond-with-entity? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn allowed-to-auth?
  "Checks the body of the request and uses the password to authenticate the user."
  [db]
  (fn [ctx]
    (if-let [unsec-login (json-body ctx)]
      (auth/basic-authenticate db (:email unsec-login) (:password unsec-login)))))

(defn add-hmac-to-ctx
  "Gets the login from the context and generates an HMAC which gets added to the response"
  [secret]
  (fn [ctx]
    (let [login (:login ctx)
          id (str (:id login))
          hmac (str id "--" (.encodeToString (Base64/getUrlEncoder) (auth/generate-hmac-signature id secret)))]
      (ring-response {:headers {"X-Auth-HMAC" hmac}
                      :body (generate-string (merge (dissoc login :password_hash)
                                                    {:token (str "HMAC " hmac)}))}))))

(defn user-authorized?
  [db secret ctx]
  (if-let [auth-header (get-in ctx [:request :headers "authorization"])]
    (let [[auth-type slug] (str/split auth-header #" " 2)]
      (auth/authorized? db auth-type slug secret))))

(defn superuser-authorized?
  [db secret ctx]
  (if-let [[answer {login :login}] (user-authorized? db secret ctx)]
    (if (and answer (:admin login))
      [true, {:login login}])))

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  ([db secret fn-auth-level]
  (fn [ctx]
    (case (if (fn? fn-auth-level)
            (fn-auth-level ctx)
            fn-auth-level)
      :unauthenticated true
      :user (user-authorized? db secret ctx)
      :superuser (superuser-authorized? db secret ctx))))
  ([db secret]
   (authorized? db secret :user)))

(defn list-environments [db]
  (fn [ctx]
    (sql/get-environments-for-login db (:id (:login ctx)))))

(defn create-environment! [db]
  (fn [ctx]
    (let [login (:login ctx)
          id (identifiers/generate)
          env (assoc (json-body ctx) :id id)]
      (if (sql/insert-into-environments! db id (:name env))
        (if (sql/link-environment-and-login! db id (:id login))
          {:env env})))))

(defn get-environment [ctx]
  (:env ctx))

(defn get-new-login [ctx]
  (if-let [error (:error ctx)]
    (ring-response {:status (:status error)
                    :body (generate-string {:error (:message error)})
                    :headers {"Content-Type" "application/json"}})
    (dissoc (or (:new-login ctx) (:old-login ctx)) :password_hash)))

(defn environment-exists? [db id]
  (fn [ctx]
    (let [login (:login ctx)]
      (if-let [env (first (sql/get-environment-for-login db id (:id login)))]
        [true {:env env}]
        false))))

(defn update-environment! [db id]
  (fn [ctx]
    (let [env (json-body ctx)]
      (if (sql/update-environment! db (:name env) id)
        {:env env}))))

(defn delete-environment! [db id]
  (fn [ctx]
    (sql/toggle-environment! db false id)))

(defn ping-bastion! [pubsub id]
  (fn [ctx]
    (let [recv @(send-msg pubsub id "echo" "echo")]
      (log/info "msg " recv)
      recv)))

(defn list-bastions [pubsub]
  (fn [ctx]
    (let [login (:login ctx)]
      (for [brec (get-bastions pubsub (:customer_id login))
            :let [reg (:registration brec)]]
        reg))))

(defn cmd-bastion! [pubsub id]
  (fn [ctx]
    (let [cmd (json-body ctx)
          recv @(send-msg pubsub id (:cmd cmd) (:body cmd))]
      {:msg recv})))

(defn get-msg [ctx]
  (:msg ctx))

(defn check-exists? [pubsub db id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login db (:id login)))
          check (first (sql/get-check-by-id db id))]
      (if (= (:id env) (:environment_id check))
        {:check check}))))

(defn update-check! [pubsub db id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login db (:id login)))
          check (:check ctx)
          updated-check (json-body ctx)]
      (if (= (:id env) (:environment_id check))
        (let [merged (merge check (assoc updated-check :id id))]
          (log/info merged)
          (if (sql/update-check! db merged)
            (let [final-check (first (sql/get-check-by-id db id))]
              (publish-command pubsub (:customer_id login) {:cmd "healthcheck"
                                                            :body final-check})
              {:check final-check})))))))

(defn delete-check! [pubsub db id]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login db (:id login)))
          check (first (sql/get-check-by-id db id))]
      (if (= (:id env) (:environment_id check))
        (do
          (sql/delete-check-by-id! db id)
          (publish-command pubsub (:customer_id login) {:cmd "delete"
                                                        :body {:checks [id]}}))))))

(defn create-check! [pubsub db]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login db (:id login)))
          check (json-body ctx)
          id (identifiers/generate)
          final-check (merge check {:id id, :environment_id (:id env)})]
      (sql/insert-into-checks! db final-check)
      (publish-command pubsub (:customer_id login) {:cmd "healthcheck"
                                                    :body final-check}))))

(defn list-checks [pubsub db]
  (fn [ctx]
    (let [login (:login ctx)
          env (first (sql/get-environments-for-login db (:id login)))]
      (sql/get-checks-by-env-id db (:id env)))))

(defn get-check [ctx]
  (:check ctx))

(def query-limit 100)

(defn retr-signup [db email default]
  (if-let [signup (first (sql/get-signup-by-email db email))]
    {:signup signup :duplicate true}
    [false, {:signup default}]))

(defn signup-exists? [db]
  (fn [ctx]
    (if-let [new-signup (json-body ctx)]
      (retr-signup db (:email new-signup) new-signup)
      (if-let [email (get-in ctx [:request :params "email"])]
        (retr-signup db email nil)
        true))))

(defn create-and-send-activation! [db config]
  (fn [ctx]
    (let [id (identifiers/generate)
          signup (:signup ctx)
          activation (assoc signup :id id)]
      (if (sql/insert-into-activations! db activation)
        (send-activation! config signup id)
        (let [saved-activation (first (sql/get-unused-activation db id))]
          {:activation saved-activation})))))

(defn create-and-send-verification! [db config login]
  (let [id (identifiers/generate)
        activation {:id id
                    :email (:email login)
                    :name (:name login)}]
    (if (sql/insert-into-activations! db activation)
      (send-verification! config login id))))

(defn create-signup! [db]
  (fn [ctx]
    (if (not (:duplicate ctx))
      (let [signup (:signup ctx)]
        (sql/insert-into-signups! db signup)))))


(defn list-signups [db]
  (fn [ctx]
    (let [page (or (get-in ctx [:request :params "page"]) 1)]
      (sql/get-signups db query-limit (* (- page 1) query-limit)))))

(defn get-signup [ctx]
  (if (:duplicate ctx)
    (ring-response {:status 409
                    :body (generate-string {:error "Conflict: that email is already signed up."})
                    :headers {"Content-Type" "application/json"}})
    (:signup ctx)))

(defn activation-exists? [db id]
  (fn [_]
    (if-let [activation (first (sql/get-unused-activation db id))]
      {:activation activation})))

(defn- use-activation! [db login activation]
  (let [saved-login (first (sql/get-active-login-by-email db (:email login)))]
    (sql/update-activations-set-used! db (:id activation))
    {:new-login saved-login}))

(defn activate-activation! [db]
  (fn [ctx]
    (let [activation (:activation ctx)]
      (if-not activation
        {:error {:status 409
                 :message "invalid activation"}}
        (if-let [existing-login (first (sql/get-active-login-by-email db (:email activation)))]
          ;this is a verification of an existing login's email change
          (if (sql/update-login! db (merge existing-login {:verified true}))
            (use-activation! db existing-login activation))
          ;this is the activation of a new account
          (if-let [login-details (json-body ctx)]
            (let [hashed-pw (auth/hash-password (:password login-details))
                  login (assoc login-details :password_hash hashed-pw
                                             :email (:email activation)
                                             :name (:name activation))]
              (if (sql/insert-into-logins! db login)
                (use-activation! db login activation)))))))))

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

(defn login-exists? [db id]
  (fn [ctx]
    (if-let [login (first (sql/get-active-login-by-id db id))]
      [true, {:old-login login}])))

(defn- do-update! [db old-login new-login verified]
  (let [hash (if (:new_password new-login)
               (auth/hash-password (:new_password new-login))
               (:password_hash old-login))
        merged (merge old-login new-login {:verified verified :password_hash hash})]
    (sql/update-login! db merged)))

(defn update-login! [db config id]
  (fn [ctx]
    (let [new-login (:new-login ctx)
          old-login (:old-login ctx)
          verified (if (contains? new-login :email)
                     (= (:email new-login) (:email old-login))
                     true)]
      (if (and (not verified)
               (not (empty? (sql/get-any-login-by-email db (:email new-login)))))
        {:error {:status 409
                 :message (str "Email " (:email new-login) " already exists.")}}
        (if (do-update! db old-login new-login verified)
          (if-let [saved-login (first (sql/get-active-login-by-id db id))]
            (do
              (if-not verified (create-and-send-verification! db config new-login))
              {:new-login saved-login})))))))

(defn delete-login! [db id]
  (fn [ctx]
    (sql/deactivate-login! db id)))

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

(defn subdomain-exists? [db subdomain]
  (empty? (sql/get-org-by-subdomain db subdomain)))

(defn create-org! [db]
  (fn [ctx]
    (let [org (json-body ctx)]
      (sql/insert-into-orgs! db org)
      {:org org})))

(defn get-org [ctx]
    (:org ctx))

(defn instance-exists? [id]
  (fn [_]
    (if-let [instance (sql/get-instance-by-id db id)]
      [true, {:instance instance}])
    false))

(defn get-instance [ctx]
  (:instance ctx))

(defresource signups-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:post :get]
             :exists? (signup-exists? db)
             :authorized? (authorized? db secret #(case (get-in % [:request :request-method])
                                                   :get :superuser
                                                   :unauthenticated))
             :post! (create-signup! db)
             :handle-ok (list-signups db)
             :handle-created get-signup)

(defresource signup-resource [db secret config]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized? db secret :superuser)
             :exists? (signup-exists? db)
             :post! (create-and-send-activation! db config)
             :handle-created get-activation)

(defresource activation-resource [db id]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :exists? (activation-exists? db id)
             :post! (activate-activation! db)
             :handle-ok get-activation
             :handle-created get-new-login)

(defresource authenticate-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (allowed-to-auth? db)
             :handle-created (add-hmac-to-ctx secret))

(defresource environments-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :authorized? (authorized? db secret)
             :post! (create-environment! db)
             :handle-ok (list-environments db)
             :handle-created get-environment)

(defresource environment-resource [db secret id]
             :available-media-types ["application/json"]
             :allowed-methods [:get :put :delete]
             :authorized? (authorized? db secret)
             :exists? (environment-exists? db id)
             :put! (update-environment! db id)
             :delete! (delete-environment! db id)
             :handle-ok get-environment)

;(defresource logins-resource [db secret]
;             :available-media-types ["application/json"]
;             :allowed-methods [:get :post]
;             :authorized? (authorized? db secret (fn [_] :superuser))
;             :post! (create-login! db)
;             :handle-ok (list-logins db)
;             :handle-created get-new-login)

(defresource login-resource [db config secret id]
             :available-media-types ["application/json"]
             :allowed-methods [:get :patch :delete]
             :authorized? (authorized? db secret)
             :allowed? (allowed-edit-login? id)
             :exists? (login-exists? db id)
             :patch! (update-login! db config id)
             :delete! (delete-login! db id)
             :new? false
             :respond-with-entity? respond-with-entity?
             :handle-ok get-new-login)

(defresource instance-resource [db secret id]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized? db secret)
             :exists? (instance-exists? db id)
             :handle-ok get-instance)

(defresource bastion-resource [pubsub db secret id]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized? db secret)
             :post! (cmd-bastion! pubsub id)
             :handle-created get-msg)

(defresource bastions-resource [pubsub db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized? db secret)
             :handle-ok (list-bastions pubsub))

(defresource discovery-resource [pubsub db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get])

(defresource check-resource [pubsub db secret id]
             :available-media-types ["application/json"]
             :allowed-methods [:get :put :delete]
             :authorized? (authorized? db secret)
             :exists? (check-exists? pubsub db id)
             :put! (update-check! pubsub db id)
             :new? false
             :respond-with-entity? respond-with-entity?
             :delete! (delete-check! pubsub db id)
             :handle-ok get-check)

(defresource checks-resource [pubsub db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :authorized? (authorized? db secret)
             :post! (create-check! pubsub db)
             :handle-created get-check
             :handle-ok (list-checks pubsub db))

(defresource scan-vpc-resource []
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :post! scan-vpcs
             :handle-created get-vpcs)

(defresource subdomain-resource [db secret subdomain]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized? db secret)
             :handle-ok (fn [_] {:available (subdomain-exists? db subdomain)}))

(defresource orgs-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :authorized? (authorized? db secret)
             :exists? (fn [ctx]
                        (let [subdomain (:subdomain (json-body ctx))]
                          (subdomain-exists? db subdomain)))
             :post! (create-org! db)
             :handle-created get-org)

(defresource org-resource [db secret subdomain]
             :available-media-types ["application/json"]
             :allowed-methods [:get]
             :authorized? (authorized? db secret)
             :exists? (fn [_] ((if (subdomain-exists? db subdomain) [true {:org (first (sql/get-org-by-subdomain db subdomain))}] false)))
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

(defn app [pubsub db config]
  (let [secret (:secret config)
        mailgun (:mailgun config)]
    (routes
      (GET "/health_check", [], "A ok")
      (ANY "/signups" [] (signups-resource db secret))
      (ANY "/signups/send-activation" [] (signup-resource db secret mailgun))
      (GET "/activations/:id", [id] (activation-resource db id))
      (POST "/activations/:id/activate", [id] (activation-resource db id))
      (POST "/verifications/:id/activate", [id] (activation-resource db id))
      (ANY "/authenticate/password" [] (authenticate-resource db secret))
      (ANY "/environments" [] (environments-resource db secret))
      (ANY "/environments/:id" [id] (environment-resource db secret id))
      (ANY "/logins/:id" [id] (login-resource db config secret (param->int id)))
      (ANY "/scan-vpcs" [] (scan-vpc-resource))
      (POST "/orgs" [] (orgs-resource db secret))
      (ANY "/orgs/:subdomain" [subdomain] (org-resource db secret subdomain))
      (GET "/orgs/subdomain/:subdomain" [subdomain] (subdomain-resource db secret subdomain))
      (ANY "/bastions" [] (bastions-resource pubsub db secret))
      (ANY "/bastions/:id" [id] (bastion-resource pubsub db secret id))
      (ANY "/discovery" [] (discovery-resource pubsub db secret))
      (ANY "/checks" [] (checks-resource pubsub db secret))
      (ANY "/checks/:id" [id] (check-resource pubsub db secret id))
      (GET "/instance/:id" [id] (instance-resource db secret id)))))

(defn handler [pubsub db config]
  (-> (app pubsub db config)
      (log-request)
      (robustify)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :put :post :patch :delete]
                 :access-control-allow-headers ["X-Auth-HMAC"])
      (wrap-options)
      (wrap-params)
      (wrap-trace :header :ui)))

(defn discovery-handler [msg]
  (do
    (log/info "discovery " msg)
    (assoc msg :reply "ok")))

(def bastion-handlers
  {
   "discovery" discovery-handler
   })

(defprotocol Subscriptor
  (subscribe! [this topic])
  (unsubscribe! [this topic])
  (subscribed? [this topic]))

(defrecord ClientConnector [id customer-id login ^CopyOnWriteArraySet topics]
  Subscriptor
  (subscribe! [_ topic]
    (.add topics topic))
  (unsubscribe! [_ topic]
    (.remove topics topic))
  (subscribed? [_ topic]
    (.contains topics topic)))

(defn create-connector [login]
  (ClientConnector. (identifiers/generate) (:customer_id login) login (CopyOnWriteArraySet.)))

(defn register-ws-connection [clients db hmac secret ws]
  (if-and-let [auth-resp (auth/do-hmac-auth db hmac secret)
               [authorized {login :login}] auth-resp
               client (create-connector login)]
      (when authorized
        (do
          (.put clients ws client)
          client))))

(defn consume-bastion [ws client]
  (fn [msg]
    (log/info "publish" msg)
    (let [cmd (:command msg)]
      (when (subscribed? client cmd)
        (send! ws (generate-string msg))))))

(defn process-authorized-command [client ws pubsub ^ExecutorService executor msg]
    (case (:cmd msg)
      "subscribe" (do
                    (subscribe! client (:topic msg))
                    (send! ws (generate-string {:reply "ok"})))
      "launch" (let [stream (launch/launch-bastions executor (:customer-id client) msg {:owner-id "933693344490" :tag "stable"})]
                 (log/info "launched")
                 (loop [event @(s/take! stream)]
                   (if-not (= event :exit)
                     (do
                       (send! ws (generate-string event))
                       (recur @(s/take! stream))))))
      "echo" (send! ws (generate-string msg))))

(defn ws-handler [executor pubsub clients db secret]
  {:on-connect (fn [_])
   :on-text    (fn [ws msg]
                 (let [parsed-msg (parse-string msg true)
                       client (.get clients ws)]
                   (log/info "ws msg", parsed-msg)
                   (if client
                     (process-authorized-command client ws pubsub executor parsed-msg)
                     (if-and-let [hmac (:hmac parsed-msg)
                                  client (register-ws-connection clients db hmac secret ws)]
                                 (let [stream (register-ws-client pubsub client)]
                                   (log/info "authorized ws client")
                                   (s/consume (consume-bastion ws client) stream)
                                   (process-authorized-command client ws pubsub executor parsed-msg))
                                 (send! ws (generate-string {:error "unauthorized"}))))))
   :on-closed  (fn [ws status-code reason]
                 (if-let [client (.remove clients ws)]
                   (do
                     (s/close! (:client-server client))
                     (s/close! (:server-client client)))))
   :on-error   (fn [ws e])})

(def ^{:private true} bastion-server (atom nil))

(def ^{:private true} ws-server (atom nil))

(defn- start-bastion-server [db pubsub handlers options]
  (if-not @bastion-server (reset! bastion-server (bastion/bastion-server db pubsub handlers options))))

(defn- start-ws-server [executor db pubsub config clients]
  (if-not @ws-server
    (reset! ws-server
            (run-jetty
              (handler pubsub db config)
              (assoc (:server config)
                :websockets {"/stream" (ws-handler executor pubsub clients db (:secret config))})))))

(defn stop-server []
  (do
    (if @bastion-server (do
                          (.close @bastion-server)
                          (reset! bastion-server nil)))
    (if @ws-server (do
                     (.stop @ws-server)
                     (reset! ws-server nil)))))

(defn start-server [args]
  (let [config (parse-string (slurp (first args)) true)
        db (sql/pool (:db-spec config))
        pubsub (create-pubsub)
        executor (Executors/utilizationExecutor (:thread-util config) (:max-threads config))
        clients (NonBlockingHashMap.)]
    (start-bastion-server db pubsub bastion-handlers (:bastion-server config))
    (start-ws-server executor db pubsub config clients)))

(.addShutdownHook
  (Runtime/getRuntime)
  (Thread. (fn []
             (println "Shutting down...")
             (stop-server))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (db-cmd/db-cmd subargs)
      "upload" (upload-cmd/upload subargs))))

