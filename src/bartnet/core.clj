(ns bartnet.core
  (:gen-class)
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
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
            [bartnet.db-cmd :as db-cmd]
            [bartnet.pubsub :refer :all]
            [manifold.stream :as s]
            [aleph.tcp :as tcp]
            [yesql.util :refer [slurp-from-classpath]]
            [ring.adapter.jetty9 :refer [run-jetty]])
  (:import [java.util Base64]
           [org.cliffc.high_scale_lib NonBlockingHashMap]))

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

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

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  [db secret]
  (fn [ctx]
    (if-let [auth-header (get-in ctx [:request :headers "authorization"])]
      (let [[auth-type slug] (str/split auth-header #" " 2)]
        (auth/authorized? db auth-type slug secret)))))

(defn logins-resource-authorized? [db secret]
  (fn [ctx]
    (if-let [[answer {login :login}] ((authorized? db secret) ctx)]
      (if (and answer (re-matches #"@opsee.co$" (:email login)))
        [true, {:login login}]))))

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

(defn ping-bastion! [pubsub id]
  (fn [ctx]
    (let [recv (send-msg pubsub id "echo" "echo")]
      (log/info "msg " recv)
      recv)))

(defn list-bastions [pubsub]
  (fn [ctx]
    (let [login (:login ctx)]
      (for [brec (get-bastions pubsub (:customer-id login))
            :let [reg (:registration brec)]]
        reg))))

(defn cmd-bastion! [pubsub id]
  (fn [ctx]
    (let [cmd (json-body ctx)
          recv (send-msg pubsub id (:cmd cmd) (:body cmd))]
      {:msg recv})))

(defn get-msg [ctx]
  (:msg ctx))

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
             :handle-ok get-environment)

(defresource logins-resource [db secret]
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :authorized? (logins-resource-authorized? db secret)
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
      (ANY "/authenticate/password" [] (authenticate-resource db secret))
      (ANY "/environments" [] (environments-resource db secret))
      (ANY "/environments/:id" [id] (environment-resource db secret id))
      (ANY "/logins" [] (logins-resource db secret))
      (ANY "/bastions" [] (bastions-resource pubsub db secret))
      (ANY "/bastions/:id" [id] (bastion-resource pubsub db secret id)))))

(defn handler [pubsub db config]
  (-> (app pubsub db config)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :put :post :patch :delete]
                 :access-control-allow-headers ["X-Auth-HMAC"])
      (wrap-options)
      (wrap-trace :header :ui)))

(defn discovery-handler [msg]
  (do
    (log/info "discovery " msg)
    (assoc msg :reply "ok")))

(def bastion-handlers
  {
   "discovery" discovery-handler
   })

(defrecord ClientConnector [id customer-id login])

(defn register-ws-connection [clients db hmac secret ws]
  (let [[authorized {:keys [login]}] (auth/do-hmac-auth db hmac secret)]
    (when authorized
      (.put clients ws (ClientConnector. (identifiers/generate) (:customer-id login) login)))))

(defn consume-bastion [ws]
  (fn [msg]
    ))

(defn process-authorized-command [client pubsub msg]
    (case (:cmd msg)
    "subscribe" ()
    "" ()))

(defn ws-handler [pubsub clients db secret]
  {:on-connect (fn [_])
   :on-text    (fn [ws msg]
                 (let [parsed-msg (parse-string msg true)
                       client (.get clients ws)]
                   (if client
                     (process-authorized-command client pubsub parsed-msg)
                     (if-let [hmac (:hmac msg)]
                       (if-let [client (register-ws-connection clients db hmac secret ws)]
                         (let [stream (register-ws-client pubsub client)]
                           (future (s/consume (consume-bastion ws) stream))
                           (process-authorized-command client pubsub parsed-msg)))))))
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
    (bastion/bastion-server pubsub bastion-handlers (:bastion-server config))
    (run-jetty
      (handler pubsub db config)
      (assoc (:server config)
        :websockets {"/subscribe" (ws-handler pubsub clients db (:secret config))}))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (server-cmd subargs)
      "db" (db-cmd/db-cmd subargs))))

