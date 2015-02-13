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

(defn do-setup []
  (do
      (start-connection)
      (migrate-db @db {:drop-all true})))

(defn app []
  (do (log/info @db)
      (core/app @db test-config)))

(defn login-fixtures [db]
  (sql/insert-into-logins! db "cliff@leaninto.it" (auth/hash-password "cliff")))

(defn environment-fixtures [db]
  (do
    (sql/insert-into-environments! db "abc123" "Test Env")
    (sql/link-environment-and-login! db "abc123" 1)))

(with-state-changes
  [(before :facts (do-setup))
   ;(around :facts (jdbc/with-db-transaction [databas @db :isolation :read-uncommitted] (log/info @db databas) ?form (jdbc/db-set-rollback-only! databas)))
   ]
  (facts "Auth endpoint works"
         (fact "bounces bad logins"
               (let [response ((app) (mock/request :post "/authenticate/password"))]
                 (:status response) => 401))
         (with-state-changes [(before :facts (do (log/info @db) (login-fixtures @db)))]))
  (facts "Environments endpoint works"
         (fact "bounces unauthorized requests"
               (let [response ((app) (mock/request :get "/environments"))]
                 (:status response) => 401))
         (with-state-changes [(before :facts (do (login-fixtures @db) (environment-fixtures @db)))])))
