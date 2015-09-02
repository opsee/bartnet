(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
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
                                 :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
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
