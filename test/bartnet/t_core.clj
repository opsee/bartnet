(ns bartnet.t-core
  (:use midje.sweet)
  (:require [bartnet.core :as core]
            [bartnet.pubsub :as pubsub]
            [bartnet.fixtures :refer :all]
            [yesql.util :refer [slurp-from-classpath]]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [bartnet.sql :as sql]
            [manifold.stream :as s]
            [aleph.http :refer [websocket-client]]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [ring.adapter.jetty9 :refer [run-jetty]])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMap]))

(def clients (atom nil))

(def auth-header "HMAC 1--2jmj7l5rSw0yVb_vlWAYkK_YBwk=")

(def pubsub (atom nil))

(def ws-server (atom nil))

(defn do-setup []
  (do
    (reset! pubsub (pubsub/create-pubsub))
    (reset! clients (NonBlockingHashMap.))
    (start-connection)))

(defn app []
  (do (core/app @pubsub @db test-config)))

(defn start-ws-server []
  (do
    (log/info "start server")
    (reset! ws-server (run-jetty
                        (core/handler @pubsub @db test-config)
                        (assoc (:server test-config)
                          :websockets {"/stream" (core/ws-handler @pubsub @clients @db (:secret test-config))})))
    (log/info "server started")))

(defn stop-ws-server []
  (.stop @ws-server))

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
                   (sql/get-environment-for-login @db "abc123" 1) => (just [(contains {:name "Test Update"})])))))
  (facts "checks endpoint works"
         (with-state-changes
           [(before :facts (do (login-fixtures @db) (environment-fixtures @db)))]
           (fact "empty checks are empty"
                 (let [response ((app) (-> (mock/request :get "/checks")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (parse-string (:body response)) => []))
           (with-state-changes
             [(before :facts (check-fixtures @db))]
             (fact "checks need auth"
                   (let [response ((app) (-> (mock/request :get "/checks")
                                             (mock/header "Authorization" "asasdasd")))]
                     (:status response) => 401))
             (fact "checks get returned"
                   (let [response ((app) (-> (mock/request :get "/checks")
                                             (mock/header "Authorization" auth-header)))]
                     (:status response) => 200
                     (parse-string (:body response) true) => (just (contains {:name "A Nice Check" :id "checkid123"}))))
             (fact "creates new checks"
                   (let [response ((app) (-> (mock/request :post "/checks" (generate-string
                                                                             {:name "A New Check"
                                                                              :description "Here is my nice check"
                                                                              :group_type "rds"
                                                                              :group_id "rds123"
                                                                              :check_type "postgres"
                                                                              :check_request "select 1;"
                                                                              :check_interval 60
                                                                              :port 5433}))
                                             (mock/header "Authorization" auth-header)))]
                     (:status response) => 201
                     (sql/get-checks-by-env-id @db "abc123") => (contains (contains {:name "A New Check"})))))))
  (facts "signups enpoint works"
         (with-state-changes
           [(before :facts (do (login-fixtures @db) (signup-fixtures @db)))]
           (fact "signups get created"
                 (let [response ((app) (-> (mock/request :post "/signups" (generate-string
                                                                            {:email "cliff+newsignup@leaninto.it"
                                                                             :name "cliff moon"}))))]
                   (:status response) => 201
                   (sql/get-signup-by-email @db "cliff+newsignup@leaninto.it") =not=> empty?))))
  (facts "check endpoint works"
         (with-state-changes
           [(before :facts (do
                             (login-fixtures @db)
                             (environment-fixtures @db)
                             (check-fixtures @db)))]
           (fact "checks that don't exist will 404"
                 (let [response ((app) (-> (mock/request :get "/checks/derpderp")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 404))
           (fact "checks need auth"
                 (let [response ((app) (-> (mock/request :get "/checks/checkid123")
                                           (mock/header "Authorization" "sdfsdfsdfsdf")))]
                   (:status response) => 401))
           (fact "checks that exist get returned"
                 (let [response ((app) (-> (mock/request :get "/checks/checkid123")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (parse-string (:body response) true) => (contains {:name "A Nice Check" :id "checkid123"})))
           (fact "checks get deleted"
                 (let [stream (pubsub/subscribe-command @pubsub "cliff")
                       response ((app) (-> (mock/request :delete "/checks/checkid123")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 204
                   (sql/get-check-by-id @db "checkid123") => empty?
                   @(s/take! stream) => (contains {:cmd "delete"
                                                   :body (contains {:checks (just "checkid123")})})))
           (fact "checks get updated"
                 (let [stream (pubsub/subscribe-command @pubsub "cliff")
                       response ((app) (-> (mock/request :put "/checks/checkid123" (generate-string {:check_interval 100
                                                                                                     :port 443}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (parse-string (:body response) true) => (contains {:port 443 :check_interval 100})
                   (sql/get-check-by-id @db "checkid123") => (just (contains {:port 443 :check_interval 100}))
                   @(s/take! stream) => (contains {:body (contains {:port 443 :check_interval 100})})))
           (fact "new checks get saved"
                 (let [stream (pubsub/subscribe-command @pubsub "cliff")
                       response ((app) (-> (mock/request :post "/checks" (generate-string {:environment_id "abc123"
                                                                                           :name "My Dope Fuckin Check"
                                                                                           :description "yo"
                                                                                           :group_type "sg"
                                                                                           :group_id "sg345"
                                                                                           :check_type "postgres"
                                                                                           :check_request "select 1 from table;"
                                                                                           :check_interval 60
                                                                                           :port 5432}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 201
                   @(s/take! stream) => (contains {:body (contains {:name "My Dope Fuckin Check"})})))))
  (facts "Websocket handling works"
         (with-state-changes
           [(before :facts (do
                             (login-fixtures @db)
                             (start-ws-server)))
            (after :facts (stop-ws-server))]
           (fact "registers a websocket client"
                 (let [client @(websocket-client "ws://localhost:8080/stream/")]
                   @(s/put! client (generate-string {:cmd "echo", :hmac "1--2jmj7l5rSw0yVb_vlWAYkK_YBwk="})) => true
                   (parse-string @(s/take! client) true) => (contains {:cmd "echo"})
                   (.close client)))
           (fact "rejects commands from an unauthorized client"
                 (let [client @(websocket-client "ws://localhost:8080/stream/")]
                   @(s/put! client (generate-string {:cmd "echo"})) => true
                   (parse-string @(s/take! client) true) => (contains {:error "unauthorized"})
                   (.close client)))
           (fact "subscribes to bastion discovery messages"
                 (let [client @(websocket-client "ws://localhost:8080/stream/")]
                   @(s/put! client (generate-string {:cmd "subscribe" :topic "discovery" :hmac "1--2jmj7l5rSw0yVb_vlWAYkK_YBwk="})) => true
                   (parse-string @(s/take! client) true) => (contains {:reply "ok"})
                   @(pubsub/publish-bastion @pubsub "cliff" {:command "discovery"
                                                             :id 1
                                                             :sent 0
                                                             :message {:group_name "group 1"
                                                                       :port 3884
                                                                       :protocol "sql"
                                                                       :request "select 1;"}}) => true
                   (parse-string @(s/take! client) true) => (contains {:command "discovery"})
                   (.close client)))
           )))
