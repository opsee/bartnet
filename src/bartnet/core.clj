(ns bartnet.core
  (:gen-class)
  (:require [liberator.core :refer [resource defresource]]
            [liberator.dev :refer [wrap-trace]]
            [ring.middleware.params :refer [wrap-params]]
            [compojure.core :refer :all]

            [clojure.data.json :as json]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [yesql.util :refer [slurp-from-classpath]]
            [ring.adapter.jetty9 :refer [run-jetty]]))

; placeholders for init
(def config)
(def secret)

(defmethod liberator.representation/render-map-generic "application/json" [data _]
  (generate-string data))

(defmethod liberator.representation/render-seq-generic "application/json" [data _]
  (generate-string data))

(defn list-environments [ctx]
  (sql/get-environments-for-login (:id (:login ctx))))

(defresource authenticate-resource
             :available-media-types ["application/json"]
             :allowed-methods [:post]
             :allowed? auth/allowed-to-auth?
             :handle-created (auth/add-hmac-to-ctx secret))

(defresource environments-resource
             :available-media-types ["application/json"]
             :allowed-methods [:get :post]
             :allowed? (auth/authorized? secret)
             :handle-ok list-environments)

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
  (let [conf (json/read-str (slurp (first args)) :key-fn keyword)]
    (do
      (initialize conf)
      (run-jetty handler (:server conf)))))

(defn db-cmd [args]
  (let [subcmd (first args)
        subargs (rest args)]
    (case subcmd
      "migrate" nil)))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (server-cmd subargs)
      "db" (db-cmd subargs))))



