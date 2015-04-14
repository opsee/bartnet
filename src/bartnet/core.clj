(ns bartnet.core
  (:gen-class)
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.core :refer :all]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [ring.middleware.cors :refer [wrap-cors]]
            [liberator.representation :refer [ring-response]]
            [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [bartnet.identifiers :as identifiers]
            [bartnet.bastion :as bastion]
            [bartnet.db-cmd :as db-cmd]
            [bartnet.pubsub :refer :all]
            [bartnet.util :refer [if-and-let]]
            [manifold.stream :as s]
            [yesql.util :refer [slurp-from-classpath]]
            [ring.adapter.jetty9 :refer :all])
  (:import [java.util Base64]
           [org.cliffc.high_scale_lib NonBlockingHashMap]
           [java.util.concurrent CopyOnWriteArraySet]))

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

(defn send-mail [config from to subject body]
  (let [api-key (:mailgun_api_key config)]
    (client/post "https://api.mailgun.net/v3/mg.opsee.co/messages"
                 {:basic-auth ["api" api-key]
                  :form-params {:from from
                                :to to
                                :subject subject
                                :text body}})))

(defn json-body [ctx]
  (if-let [body (get-in ctx [:request :body])]
      (parse-stream (io/reader body) true)))

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
      (ring-response {:headers {"X-Auth-HMAC" hmac}}))))

(defn user-authorized?
  [db secret ctx]
  (if-let [auth-header (get-in ctx [:request :headers "authorization"])]
    (let [[auth-type slug] (str/split auth-header #" " 2)]
      (auth/authorized? db auth-type slug secret))))

(defn superuser-authorized?
  [db secret ctx]
  (if-let [[answer {login :login}] (user-authorized? db secret ctx)]
    (if (and answer (re-matches #"@opsee.co$" (:email login)))
      [true, {:login login}])))

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  ([db secret fn-auth-level]
  (fn [ctx]
    (case (fn-auth-level ctx)
      :unauthenticated true
      :user (user-authorized? db secret ctx)
      :superuser (superuser-authorized? db secret ctx))))
  ([db secret]
   (authorized? db secret (fn [ctx] :user))))

(defn create-login! [db]
  (fn [ctx]
    ))

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
  (:new-login ctx))

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

(defn signup-exists? [db]
  (fn [ctx]
    (let [new-signup (json-body ctx)]
      (if-let [signup (first (sql/get-signup-by-email db (:email new-signup)))]
        {:signup signup}
        [false, {:signup new-signup}]))))

(defn create-signup! [db]
  (fn [ctx]
    (let [signup (:signup ctx)]
      (sql/insert-into-signups! db signup))))

(defn list-signups [db]
  (fn [ctx]
    (let [page (or (get-in ctx [:request :params :page]) 1)]
      (sql/get-signups db query-limit (* (- page 1) query-limit)))))

(defn get-signup [ctx]
  (:signup ctx))

(defresource signups-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:post :get]
             :exists? (signup-exists? db)
             :post-to-existing? false
             :authorized? (authorized? db secret #(case (get-in % [:request :request-method])
                                                   :get :superuser
                                                   :unauthenticated))
             :post! (create-signup! db)
             :handle-ok (list-signups db)
             :handle-created get-signup)

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

(defresource logins-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :authorized? (authorized? db secret (fn [_] :superuser))
             :post! (create-login! db)
             :handle-created get-new-login)

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
             :respond-with-entity? #(not= (get-in % [:request :request-method]) :delete)
             :delete! (delete-check! pubsub db id)
             :handle-ok get-check)

(defresource checks-resource [pubsub db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :authorized? (authorized? db secret)
             :post! (create-check! pubsub db)
             :handle-created get-check
             :handle-ok (list-checks pubsub db))

(defn wrap-options [handler]
  (fn [request]
    (log/info request)
    (if (= :options (:request-method request))
      {:status 200
       :body ""
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, PUT, POST, PATCH, DELETE"
                 "Access-Control-Allow-Headers" "Accept-Encoding,Authorization,Content-Type,X-Auth-HMAC"
                 "Access-Control-Max-Age" "1728000"}}
      (handler request))))

(defn app [pubsub db config]
  (let [secret (:secret config)]
    (routes
      (ANY "/signups" [] (signups-resource db secret))
      (ANY "/authenticate/password" [] (authenticate-resource db secret))
      (ANY "/environments" [] (environments-resource db secret))
      (ANY "/environments/:id" [id] (environment-resource db secret id))
      (ANY "/logins" [] (logins-resource db secret))
      (ANY "/bastions" [] (bastions-resource pubsub db secret))
      (ANY "/bastions/:id" [id] (bastion-resource pubsub db secret id))
      (ANY "/discovery" [] (discovery-resource pubsub db secret))
      (ANY "/checks" [] (checks-resource pubsub db secret))
      (ANY "/checks/:id" [id] (check-resource pubsub db secret id)))))

(defn handler [pubsub db config]
  (-> (app pubsub db config)
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

(defn process-authorized-command [client ws pubsub msg]
    (case (:cmd msg)
      "subscribe" (do
                    (subscribe! client (:topic msg))
                    (send! ws (generate-string {:reply "ok"})))
      "echo" (send! ws (generate-string msg))))

(defn ws-handler [pubsub clients db secret]
  {:on-connect (fn [_])
   :on-text    (fn [ws msg]
                 (let [parsed-msg (parse-string msg true)
                       client (.get clients ws)]
                   (log/info "ws msg", parsed-msg)
                   (if client
                     (process-authorized-command client ws pubsub parsed-msg)
                     (if-and-let [hmac (:hmac parsed-msg)
                                  client (register-ws-connection clients db hmac secret ws)]
                                 (let [stream (register-ws-client pubsub client)]
                                   (s/consume (consume-bastion ws client) stream)
                                   (process-authorized-command client ws pubsub parsed-msg))
                                 (send! ws (generate-string {:error "unauthorized"}))))))
   :on-closed  (fn [ws status-code reason]
                 (if-let [client (.remove clients ws)]
                   (do
                     (s/close! (:client-server client))
                     (s/close! (:server-client client)))))
   :on-error   (fn [ws e])})

(defn server-cmd [args]
  (let [config (parse-string (slurp (first args)) true)
        db (sql/pool (:db-spec config))
        pubsub (create-pubsub)
        clients (NonBlockingHashMap.)]
    (bastion/bastion-server db pubsub bastion-handlers (:bastion-server config))
    (run-jetty
      (handler pubsub db config)
      (assoc (:server config)
        :websockets {"/stream" (ws-handler pubsub clients db (:secret config))}))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (server-cmd subargs)
      "db" (db-cmd/db-cmd subargs))))

