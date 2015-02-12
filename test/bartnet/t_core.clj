(ns bartnet.t-core
  (:use midje.sweet)
  (:require [bartnet.core :as core]
            [bartnet.db-cmd :refer [migrate-db]]
            [yesql.util :refer [slurp-from-classpath]]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [bartnet.sql :as sql]
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
      (log/info @db)
      (migrate-db @db {})))

(defn app []
  (do (log/info @db)
      (core/app @db test-config)))

(with-state-changes [(before :facts (do-setup))
                     (around :facts (jdbc/with-db-transaction [databas @db] ?form (jdbc/db-set-rollback-only! databas)))]
  (facts "Environments endpoint works"
         (fact "auth bounces bad request"
               (let [response ((app) (mock/request :get "/environments"))]
                 (:status response) => 401))))
