(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [cemerick.url :refer [url]]
            [clojure.tools.logging :as log]
            [clojure.core.match :refer :all]
            [opsee.middleware.migrate :refer [migrate-db]]
            [opsee.middleware.test-helpers :refer :all]
            [opsee.middleware.config :refer [config]])
  (:import (co.opsee.proto Timestamp)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.regex Pattern)
           (clojure.lang PersistentArrayMap PersistentVector Seqable IPersistentMap)))

(defn check-fixtures [db]
  (do
    (sql/insert-into-checks! db {:id             "check1"
                                 :name           "boreos"
                                 :customer_id    "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                 :execution_group_id nil
                                 :target_id      "sg-123"
                                 :target_type    "sg"
                                 :target_name    "boreos"
                                 :interval       60
                                 :check_spec     {:type_url "HttpCheck"
                                                  :value {:name "A Good Check"
                                                          :path "/health_check"
                                                          :port 80
                                                          :verb "GET"
                                                          :protocol "http"}}})))

(defn check-fixtures-2 [db]
  (do
    (sql/insert-into-checks! db {:id             "check1"
                                 :name           "my-service elb"
                                 :customer_id    "375f5afc-1880-11e6-a61e-6fdd17fa0f56"
                                 :execution_group_id nil
                                 :target_id      "my-service"
                                 :interval       60
                                 :target_type    "elb"
                                 :target_name    "my-service"
                                 :check_spec     {:type_url "HttpCheck"
                                                  :value {:name "elb check"
                                                          :path "/health"
                                                          :port 9092
                                                          :verb "GET"}}})
    (sql/insert-into-checks! db {:id             "check2"
                                 :name           "my-serice rds"
                                 :customer_id    "375f5afc-1880-11e6-a61e-6fdd17fa0f56"
                                 :execution_group_id nil
                                 :target_id      "my-service"
                                 :interval       60
                                 :target_name    "my-service"
                                 :target_type    "dbinstance"
                                 :check_spec     {:type_url "CloudWatchCheck"
                                                  :value {:metrics (vector
                                                                     {:name  "CPUUtilization"
                                                                      :namespace "AWS/RDS"}
                                                                     {:name "DatabaseConnections"
                                                                      :namespace "AWS/RDS"}
                                                                     {:name "FreeStorageSpace"
                                                                      :namespace "AWS/RDS"}
                                                                     )}}})))


(defn assertions-fixtures [db]
      (sql/insert-into-assertions! db {:check_id "check1"
                                       :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :key "body"
                                       :value ""
                                       :relationship "equal"
                                       :operand "foo"
                                       })
      (sql/insert-into-assertions! db {:check_id "check1"
                                       :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :key "header"
                                       :value "accept-encoding"
                                       :relationship "equal"
                                       :operand "gzip"
                                       }))

(defn assertions-fixtures-2 [db]
      (sql/insert-into-assertions! db {:check_id "check1"
                                       :customer_id "375f5afc-1880-11e6-a61e-6fdd17fa0f56"
                                       :key "body"
                                       :value ""
                                       :relationship "equal"
                                       :operand "foo"
                                       })
      (sql/insert-into-assertions! db {:check_id "check1"
                                       :customer_id "375f5afc-1880-11e6-a61e-6fdd17fa0f56"
                                       :key "header"
                                       :value "accept-encoding"
                                       :relationship "equal"
                                       :operand "gzip"
                                       }))

(def fixtures (parse-string (slurp-from-classpath "fixtures.json") true))

(defmulti url-matcher (fn [path req] [(class path) (class req)]))
(defmethod url-matcher [Pattern String] [path req]
  (log/info "Pattern String" path req)
  (re-matches path req))
(defmethod url-matcher [IPersistentMap IPersistentMap] [path req]
  (log/info "Map Map" path req)
  (every? (fn [k] (url-matcher (get path k) (get req k))) (keys path)))
(defmethod url-matcher [Seqable Seqable] [path req]
  (log/info "Seq Seq" path req)
  (loop [matcher path
         to-match req]
    (let [m-head (first matcher)
          t-head (first to-match)]
      (if (nil? m-head)
        true
        (if (url-matcher m-head t-head)
          (recur (rest matcher)
                 (rest to-match)))))))
(defmethod url-matcher :default [path req]
  (log/info ":default" path req)
  (= path req))

(defn- match-path [mappings url opts]
  (or (some (fn [[path val]]
              (if (url-matcher (if-not (map? path)
                                 {:url path}
                                 path) (assoc opts :url url))
                val))
            mappings)
      {:status 404 :body nil}))

(defprotocol MockResponse
  (status [this])
  (body [this]))

(defn- safe-url [path]
  (try
    (:path (url path))
    (catch Exception _ path)))

(defn mock-http [mappings]
  (fn [uri opts]
    (let [response (match-path mappings (safe-url uri) opts)]
      (log/info "response" response)
      (assoc response :body (generate-string (:body response))))))

(defn mock-status [response]
  (status response))

(defn mock-body [response]
  (body response))
