(ns bartnet.t-core
  (:use midje.sweet)
  (:require [bartnet.core :as core]
            [bartnet.db-cmd :refer [migrate-db]]
            [yesql.util :refer [slurp-from-classpath]]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]))


(def test-config (parse-string (slurp-from-classpath "test-config.json") true))

(def db (atom nil))

(defn start-connection [] (reset! db {:connection (jdbc/get-connection (:db-spec test-config))}))

(def auth-header "HMAC 1--2jmj7l5rSw0yVb_vlWAYkK_YBwk=")

(defn do-setup []
  (do
      (start-connection)
      (migrate-db @db {:drop-all true :silent true})))

(defn app []
  (do (core/app @db test-config)))

(defn login-fixtures [db]
  (sql/insert-into-logins! db "cliff@leaninto.it" (auth/hash-password "cliff")))

(defn environment-fixtures [db]
  (do
    (sql/insert-into-environments! db "abc123" "Test Env")
    (sql/insert-into-environments! db "nice123" "Test2")
    (sql/link-environment-and-login! db "abc123" 1)
    (sql/link-environment-and-login! db "nice123" 1)))

(with-state-changes
  [(before :facts (do-setup))]
  (facts "Auth endpoint works"
         (fact "bounces bad logins"
               (let [response ((app) (mock/request :post "/authenticate/password"))]
                 (:status response) => 401))
         (with-state-changes
           [(before :facts (login-fixtures @db))]
           (fact "sets hmac header on good login"
                 (let [response ((app) (mock/request :post "/authenticate/password" (generate-string {"email" "cliff@leaninto.it" "password" "cliff"})))]
                   (:status response) => 201
                   (get-in response [:headers "X-Auth-HMAC"]) => "1--2jmj7l5rSw0yVb_vlWAYkK_YBwk="))))
  (facts "Environments endpoint works"
         (fact "bounces unauthorized requests"
               (let [response ((app) (mock/request :get "/environments"))]
                 (:status response) => 401)
               (let [response ((app) (-> (mock/request :get "/environments")
                                         (mock/header "Authorization" "blorpbloop")))]
                 (:status response) => 401))
         (with-state-changes
           [(before :facts (do (login-fixtures @db)))]
           (fact "lets in authorized requests"
                 (let [response ((app) (-> (mock/request :get "/environments")
                                           (mock/header "Authorization" auth-header)))]
                 (:status response) => 200
                 (:body response) => "[]"))
           (fact "creates new environments"
                 (let [response ((app) (-> (mock/request :post "/environments" (generate-string {"name" "New Environment"}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 201
                   (sql/get-environment-for-login @db (:id (parse-string (:body response) true)) 1) => (just [(contains {:name "New Environment"})]))))
         (with-state-changes
           [(before :facts (do (login-fixtures @db) (environment-fixtures @db)))]
           (fact "returns an array of environments"
                 (let [response ((app) (-> (mock/request :get "/environments")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (parse-string (:body response)) => (just [(contains {"id" "abc123" "name" "Test Env"})
                                                             (contains {"id" "nice123" "name" "Test2"})])))))
  (facts "Environment endpoint works"
         (with-state-changes
           [(before :facts (login-fixtures @db))]
           (fact "404's unknown environments"
                 (let [response ((app) (-> (mock/request :get "/environments/abc123")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 404)))
         (with-state-changes
           [(before :facts (do (login-fixtures @db) (environment-fixtures @db)))]
           (fact "returns known environments"
                 (let [response ((app) (-> (mock/request :get "/environments/abc123")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (parse-string (:body response)) => (contains {"id" "abc123" "name" "Test Env"})))
           (fact "updates environments"
                 (let [response ((app) (-> (mock/request :put "/environments/abc123" (generate-string {"name" "Test Update"}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 201
                   (sql/get-environment-for-login @db "abc123" 1) => (just [(contains {:name "Test Update"})]))))))
