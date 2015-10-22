(ns bartnet.fixtures
  (:require [bartnet.sql :as sql]
            [cheshire.core :refer :all]
            [clojure.string :as str]
            [opsee.middleware.migrate :refer [migrate-db]]
            [opsee.middleware.test-helpers :refer :all]
            [opsee.middleware.config :refer [config]])
  (:import (co.opsee.proto Timestamp)))

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
