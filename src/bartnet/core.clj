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

; placeholders for init
(def config)
(def secret)

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

(defn allowed-to-auth?
  "Checks the body of the request and uses the password to authenticate the user."
  [ctx]
  (let [unsec-login (parse-stream (io/reader (get-in ctx [:request :body])) true)]
    (auth/basic-authenticate (:email unsec-login) (:password unsec-login))))

(defn add-hmac-to-ctx
  "Gets the login from the context and generates an HMAC which gets added to the response"
    [ctx]
    (let [login (:login ctx)
          id (str (:id login))
          hmac (str id "--" (.encodeToString (Base64/getUrlEncoder) (auth/generate-hmac-signature id secret)))]
      (ring-response {:headers {"X-Auth-HMAC" hmac}})))

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  [ctx]
  (log/info ctx)
  (let [[auth-type slug] (str/split (get-in ctx [:request :headers "authorization"]) #" " 2)]
    (auth/authorized? auth-type slug secret)))

(defn list-environments [ctx]
  (sql/get-environments-for-login (:id (:login ctx))))

(defn create-environment! [ctx]
  (let [login (:login ctx)
        id (identifiers/generate)
        env (assoc (parse-stream (io/reader (get-in ctx [:request :body])) true) :id id)]
    (log/info env)
    (if (sql/insert-into-environments! id (:name env))
      (if (sql/link-environment-and-login! id (:id login))
        {:env env}))))

(defn get-environment [ctx]
  (:env ctx))

(defresource authenticate-resource
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :allowed? allowed-to-auth?
             :handle-created add-hmac-to-ctx)

(defresource environments-resource
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :allowed? authorized?
             :post! create-environment!
             :handle-ok list-environments
             :handle-created get-environment)

;(defresource environment-resource [id]
;             :available-media-types ["application/json"]
;             :allowed-methods [:get :put :delete])

(defroutes app
           (ANY "/authenticate/password" [] authenticate-resource)
           (ANY "/environments" [] environments-resource)
           ;(ANY "/environments/:id" [id] (environment-resource id))
           )

(def handler
  (-> app
      (wrap-trace :header :ui)))

(defn initialize [conf]
  (do
    (def config conf)
    (def secret (.getBytes (:secret config)))
    (sql/pooled-queries (:db-spec config))))

(defn server-cmd [args]
  (let [conf (parse-string (slurp (first args)) :true)]
    (do
      (initialize conf)
      (run-jetty handler (:server conf)))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (server-cmd subargs)
      "db" (db-cmd/db-cmd subargs))))



