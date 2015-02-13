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
            [liberator.representation :refer [ring-response]]
            [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [bartnet.identifiers :as identifiers]
            [bartnet.db-cmd :as db-cmd]
            [yesql.util :refer [slurp-from-classpath]]
            [ring.adapter.jetty9 :refer [run-jetty]])
  (:import [java.util Base64]))

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

(defn app [db, config]
  (let [secret (:secret config)]
    (routes
      (ANY "/authenticate/password" [] (authenticate-resource db secret))
      (ANY "/environments" [] (environments-resource db secret))
      (ANY "/environments/:id" [id] (environment-resource db secret id))
      (ANY "/logins" [] (logins-resource db secret)))))

(defn handler [db, config]
  (-> (app db config)
      (wrap-trace :header :ui)))

(defn server-cmd [args]
  (let [config (parse-string (slurp (first args)) true)
        db (sql/pool (:db-spec config))]
    (run-jetty handler (:server config))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (server-cmd subargs)
      "db" (db-cmd/db-cmd subargs))))

