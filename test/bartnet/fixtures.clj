(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
            [clojure.java.jdbc :as jdbc]
            [bartnet.auth :as auth]
            [cheshire.core :refer :all]
            [yesql.util :refer [slurp-from-classpath]]
            [bartnet.db-cmd :refer [migrate-db]]))

(def test-config (parse-string (slurp-from-classpath "test-config.json") true))

(def db (atom nil))

(defn start-connection []
  (do
    (reset! db {:connection (jdbc/get-connection (:db-spec test-config))})
    (migrate-db @db {:drop-all true :silent true})))

(defn login-fixtures [db]
  (sql/insert-into-logins! db "cliff@leaninto.it" (auth/hash-password "cliff")))

(defn environment-fixtures [db]
  (do
    (sql/insert-into-environments! db {:id "abc123", :name "Test Env"})
    (sql/insert-into-environments! db {:id "nice123", :name "Test2"})
    (sql/link-environment-and-login! db {:environment_id "abc123", :login_id 1})
    (sql/link-environment-and-login! db {:environment_id "nice123", :login_id 1})))

(defn check-fixtures [db]
  (do
    (sql/insert-into-checks! db {:id "checkid123"
                                 :environment_id "abc123"
                                 :name "A Nice Check"
                                 :description "description"
                                 :group_type "sg"
                                 :group_id "sg123"
                                 :check_type "http"
                                 :check_request "GET /health_check"
                                 :check_interval 60
                                 :port 80})))

(defn signup-fixtures [db]
  (do
    (sql/insert-into-signups! db {:email "cliff+signup@leaninto.it" :name "cliff moon"})))