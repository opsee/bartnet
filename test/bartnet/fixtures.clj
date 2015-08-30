(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
            [bartnet.auth :as auth]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [yesql.util :refer [slurp-from-classpath]]
            [bartnet.db-cmd :refer [migrate-db]])
  (:import (co.opsee.proto Timestamp)
           (java.sql BatchUpdateException)))

(defn- docker-knockout "don't judge me" [config]
  (if-let [slug (get (System/getenv) "POSTGRESQL_PORT")]
    (let [[_ _ port] (str/split slug #":")]
      (let [db-spec (:db-spec config)]
        (assoc config :db-spec (assoc db-spec :subname (str "//postgresql:" port "/bartnet_test")))))
    config))

(def test-config (docker-knockout (parse-string (slurp-from-classpath "test-config.json") true)))

(def db (atom nil))

(defn start-connection []
  (do
    (if-not @db (reset! db (sql/pool (:db-spec test-config))))
    (migrate-db @db {:drop-all true :silent true}))
  @db)

(defn is-json [checker]
  (fn [actual]
    (if (instance? String actual)
      (checker (parse-string actual true))
      (checker (parse-stream actual true)))))

(defn is-msg [checker]
  (fn [msg]
    (checker (assoc msg :body (parse-string (:body msg) true)))))

(defn login-fixtures [db]
  ; A shitty side-effect of using ysql in testing is that if you rename
  ; a column, you have to keep the original column name in your fixtures.
  ; :(
  (sql/insert-into-orgs! db {:name "greg", :subdomain "cliff"})
  (sql/insert-into-orgs! db {:name "greg", :subdomain "cliff2"})
  (sql/insert-into-logins! db {:email         "cliff@leaninto.it"
                               :password_hash (auth/hash-password "cliff")
                               :customer_id   "cliff"})
  (sql/insert-into-logins! db {:email         "cliff+notsuper@leaninto.it"
                               :password_hash (auth/hash-password "cliff")
                               :customer_id   "cliff2"})
  ; Login without a customer_id for testing new org creation in new accounts.
  (sql/insert-into-logins! db {:email         "cliff+newuser@leaninto.it"
                               :password_hash (auth/hash-password "cliff")}))


(defn unverified-fixtures [db]
  (sql/update-login! db {:id 2
                         :email "cliff+notsuper@leaninto.it"
                         :password_hash (auth/hash-password "cliff")
                         :name ""
                         :verified false}))

(defn environment-fixtures [db]
  (do
    (sql/insert-into-environments! db {:id "abc123", :name "Test Env"})
    (sql/insert-into-environments! db {:id "nice123", :name "Test2"})
    (sql/link-environment-and-login! db {:environment_id "abc123", :login_id 1})
    (sql/link-environment-and-login! db {:environment_id "nice123", :login_id 1})))

(defn target-fixtures [db]
  (do
    (sql/insert-into-targets! db {:id "sg-123"
                                  :type "sg"
                                  :name "coreos"})))

(defn check-fixtures [db]
  (do
    (sql/insert-into-targets! db {:id "sg-123"
                                  :name "boreos"
                                  :type "sg"})
    (sql/insert-into-checks! db {:id             "checkid123"
                                 :environment_id "abc123"
                                 :target_id      "sg-123"
                                 :interval       60
                                 :last_run       (-> (Timestamp/newBuilder)
                                                     (.setSeconds 1440802961)
                                                     .build)
                                 :check_spec     {:type_url "HttpCheck"
                                                  :value {:name "A Good Check"
                                                          :path "/health_check"
                                                          :port 80
                                                          :verb "GET"
                                                          :protocol "http"}}})))

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
    (sql/update-activations-set-used! db "badid")
    (sql/insert-into-activations! db {:id "existing"
                                      :email "cliff+notsuper@leaninto.it"
                                      :name ""})))

(defn team-fixtures [db]
  (do
    (sql/insert-into-teams! db {:id "existing"})))