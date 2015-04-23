(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [cheshire.core :refer :all]
            [yesql.util :refer [slurp-from-classpath]]
            [bartnet.db-cmd :refer [migrate-db]]))

(def test-config (parse-string (slurp-from-classpath "test-config.json") true))

(def db (atom nil))

(defn start-connection []
  (do
    (if-not @db (reset! db (sql/pool (:db-spec test-config))))
    (migrate-db @db {:drop-all true :silent true}))
  @db)

(defn login-fixtures [db]
  (sql/insert-into-logins! db {:email         "cliff@leaninto.it"
                               :password_hash (auth/hash-password "cliff")
                               :customer_id   "cliff"})
  (sql/insert-into-logins! db {:email         "cliff+notsuper@leaninto.it"
                               :password_hash (auth/hash-password "cliff")
                               :customer_id   "cliff2"}))

(defn unverified-fixtures [db]
  (sql/update-login! db {:id 2
                         :email "cliff+notsuper@leaninto.it"
                         :name ""
                         :verified false}))

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

(defn admin-fixtures [db]
  (do
    (sql/make-superuser! db true "cliff@leaninto.it")))

(defn signup-fixtures [db]
  (do
    (sql/insert-into-signups! db {:email "cliff+signup@leaninto.it" :name "cliff moon"})))

(defn activation-fixtures [db]
  (do
    (sql/insert-into-activations! db {:id "abc123"
                                      :email "cliff+signup@leaninto.it"
                                      :name "cliff"})
    (sql/insert-into-activations! db {:id "badid"
                                      :email "cliff+badsignup@leaninto.it"
                                      :name "derp"})
    (sql/insert-into-activations! db {:id "existing"
                                      :email "cliff+notsuper@leaninto.it"
                                      :name ""})))