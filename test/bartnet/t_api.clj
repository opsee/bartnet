(ns bartnet.t-api
  (:use midje.sweet)
  (:require [bartnet.api :as api]
            [bartnet.core :as core]
            [bartnet.websocket :as websocket]
            [bartnet.autobus :as autobus]
            [bartnet.bastion-router :as router]
            [bartnet.bus :as bus]
            [bartnet.rpc :as rpc]
            [bartnet.fixtures :refer :all]
            [yesql.util :refer [slurp-from-classpath]]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [bartnet.sql :as sql]
            [manifold.stream :as s]
            [aleph.http :refer [websocket-client]]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [ring.adapter.jetty9 :refer [run-jetty]]
            [bartnet.auth :as auth]
            [clojure.java.io :as io]
            [bartnet.instance :as instance]
            [schema.core :as sch]
            [bartnet.util :as util])
  (:import [io.aleph.dirigiste Executors]
           (java.util.concurrent ScheduledThreadPoolExecutor)))

(log/info "Testing!")

; this token is for {"id":8,"customer_id":"154ba57a-5188-11e5-8067-9b5f2d96dce1","email":"cliff@leaninto.it","name":"cliff","verified":true,"admin":false,"active":true}
; it will expire in 10 yrs. hopefully that is long enough so that computers won't exist anymore
(def auth-header "Bearer eyJhbGciOiJBMTI4R0NNS1ciLCJlbmMiOiJBMTI4R0NNIiwiaXYiOiJXQWlLQ2Z1azk3TlBzM1ZYIiwidGFnIjoiaU56RG1LdjloQmE0TS1YU19YcEpPZyJ9.HqXl4bq3k3E9GQ7FtsWHaQ.SONY24NgxzEZk7c3.yYd7WZX3O8ChDIVFlG--kLr_bDfkNXcR7eAnCyZ-QhFKmlbKGKE9A1-uudKRPuZ05LEAxolOrZ0lPRkW7CM3jdEdYBcUITinztgz-POIdMOXdUjFODpNOVxlcHKtZo2JH1wNdzEobBtAmVbdkl2aNUJMhVSKWbsLV3efvKQ-wVfO3kHDNmYHJlp2DKh0-8yul4UcoDytkEDOfTrpGlZrxStXRNhSf0KhRK11fh3dXvyzj07OEdYuNVbqhtfyycBPUQUJnP1xDZTpDtZ3n7lJaA.OGbujXobjndTRus8wmCqIg")

(def bus (bus/message-bus (autobus/autobus)))
(def executor (Executors/utilizationExecutor 0.9 10))
(def scheduler (ScheduledThreadPoolExecutor. 10))
(def ws-server (atom nil))

(defn mock-checker-client [addr]
  (reify rpc/CheckerClient
    (shutdown [_])
    (test-check [_ check])
    (create-check [_ check])
    (update-check [_ check])
    (retrieve-check [_ check])
    (delete-check [_ check])))

(defn mock-get-customer-bastions [customer_id]
  ["i-8888888"])

(defn mock-get-service [customer_id instance_id service-name]
  {:host "localhost" :port 4001})

(defn do-setup []
  (do
    (start-connection)))

(defn app []
  (do
    (auth/set-secret! (util/slurp-bytes (:secret test-config)))
    (api/handler executor bus @db test-config)))

(defn start-ws-server []
  (do
    (log/info "start server")
    (auth/set-secret! (util/slurp-bytes (:secret test-config)))
    (reset! ws-server (run-jetty
                        (api/handler executor bus @db test-config)
                        (assoc (:server test-config)
                          :websockets {"/stream" (websocket/ws-handler scheduler bus)})))
    (log/info "server started")))

(defn stop-ws-server []
  (.stop @ws-server))

(facts "checks endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service]
         (with-state-changes
           [(before :facts (doto
                             (do-setup)))]
           (fact "empty checks are empty"
                 (let [response ((app) (-> (mock/request :get "/checks")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json empty?)))
           (fact "tests a check"
                 )
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
                     (:body response) => (is-json (just
                                                    (contains
                                                      {:check_spec
                                                       (contains {:value (contains {:name "A Good Check"})})})))))
             (fact "creates new checks"
                   (let [response ((app) (-> (mock/request :post "/checks" (generate-string
                                                                             {:interval 10
                                                                              :target {:name "goobernetty"
                                                                                       :type "sg"
                                                                                       :id "sg123679"}
                                                                              :check_spec {:type_url "HttpCheck"
                                                                                           :value {:name "A Good Check"
                                                                                                   :path "/health_check"
                                                                                                   :port 80
                                                                                                   :verb "GET"
                                                                                                   :protocol "http"}}}))
                                             (mock/header "Authorization" auth-header)))]
                     (:status response) => 201
                     (sql/get-checks-by-customer-id @db "154ba57a-5188-11e5-8067-9b5f2d96dce1") => (contains (contains {:interval 10}))))))))
(facts "check endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service]
         (with-state-changes
           [(before :facts (doto
                             (do-setup)
                             check-fixtures))]
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
                   (:body response) => (is-json (contains {:id "checkid123" :check_spec (contains {:value (contains {:name "A Good Check"})})}))))
           (fact "checks get deleted"
                 (let [response ((app) (-> (mock/request :delete "/checks/checkid123")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 204
                   (sql/get-check-by-id @db "checkid123") => empty?
                   (log/info "sdfsfdasdfadsf")
                   (log/info "gotsdfsdfsdf")))
           (fact "checks get updated"
                 (let [response ((app) (-> (mock/request :put "/checks/checkid123" (generate-string {:interval 100}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json (contains {:interval 100}))
                   (sql/get-check-by-id @db "checkid123") => (just (contains {:interval 100}))))
           (fact "new checks get saved"
                 (let [response ((app) (-> (mock/request :post "/checks" (generate-string
                                                                           {:interval 10
                                                                            :target {:name "goobernetty"
                                                                                     :type "sg"
                                                                                     :id "sg123"}
                                                                            :check_spec {:type_url "HttpCheck"
                                                                                         :value {:name "A Good Check"
                                                                                                 :path "/health_check"
                                                                                                 :port 80
                                                                                                 :verb "GET"
                                                                                                 :protocol "http"}}}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 201)))))

(facts "about /instance/:id"
  (let [my-instance {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1" :id "id" :name "my instance" :group_id "sg-123456"}]
    (with-state-changes
      [(before :facts
         (do
           (instance/create-memory-store bus)
           (instance/save-instance! my-instance)
           (do-setup)))]
      (fact "GET existing instance returns the instance"
        (let [response ((app) (-> (mock/request :get "/instance/id")
                                (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body (contains my-instance))))
      (fact "GET unknown instance returns 404"
        (let [response ((app) (-> (mock/request :get "/instance/foo")
                                (mock/header "Authorization" auth-header)))]
          (:status response) => 404))
      (fact "GET requires authentication"
        (let [response ((app) (-> (mock/request :get "/instance/id")))]
          (:status response) => 401)))))
(facts "VPC scanning without EC2-Classic"
       (with-redefs [amazonica.aws.ec2/describe-account-attributes (fn [creds]
                                                                     (case (:endpoint creds)
                                                                       "us-west-1" {:account-attributes
                                                                                    [{:attribute-name "vpc-max-security-groups-per-interface",
                                                                                      :attribute-values [{:attribute-value "5"}]}
                                                                                     {:attribute-name "max-instances",
                                                                                      :attribute-values [{:attribute-value "20"}]}
                                                                                     {:attribute-name "supported-platforms",
                                                                                      :attribute-values [{:attribute-value "VPC"}]}
                                                                                     {:attribute-name "default-vpc",
                                                                                      :attribute-values [{:attribute-value "vpc-79b1491c"}]}
                                                                                     {:attribute-name "max-elastic-ips",
                                                                                      :attribute-values [{:attribute-value "5"}]}
                                                                                     {:attribute-name "vpc-max-elastic-ips",
                                                                                      :attribute-values [{:attribute-value "5"}]}]}
                                                                       "us-west-2" {:account-attributes
                                                                                    [{:attribute-name "vpc-max-security-groups-per-interface",
                                                                                      :attribute-values [{:attribute-value "5"}]}
                                                                                     {:attribute-name "max-instances",
                                                                                      :attribute-values [{:attribute-value "20"}]}
                                                                                     {:attribute-name "supported-platforms",
                                                                                      :attribute-values [{:attribute-value "VPC"} {:attribute-value "EC2"}]}
                                                                                     {:attribute-name "default-vpc",
                                                                                      :attribute-values [{:attribute-value "vpc-82828282"}]}
                                                                                     {:attribute-name "max-elastic-ips",
                                                                                      :attribute-values [{:attribute-value "5"}]}
                                                                                     {:attribute-name "vpc-max-elastic-ips",
                                                                                      :attribute-values [{:attribute-value "5"}]}]}))
                     amazonica.aws.ec2/describe-vpcs (fn [creds]
                                                       (case (:endpoint creds)
                                                         "us-west-1" {:vpcs
                                                                      [{:state "available",
                                                                        :tags [],
                                                                        :cidr-block "172.31.0.0/16",
                                                                        :vpc-id "vpc-79b1491c",
                                                                        :dhcp-options-id "dopt-9dc9d5ff",
                                                                        :instance-tenancy "default",
                                                                        :is-default true}]}
                                                         "us-west-2" {:vpcs
                                                                      [{:state "available",
                                                                        :tags [],
                                                                        :cidr-block "172.31.0.0/16",
                                                                        :vpc-id "vpc-82828282",
                                                                        :dhcp-options-id "dopt-9dc9d5ff",
                                                                        :instance-tenancy "default",
                                                                        :is-default true}]}))]
         (fact "aws api's get called for every region"
               (let [response ((app) (-> (mock/request :post "/scan-vpcs" (generate-string {:access-key "SDFSDFDSF"
                                                                                            :secret-key "sdfsdf+sasdasdasdsdfsdf"
                                                                                            :regions ["us-west-1"
                                                                                                      "us-west-2"]}))))]
                 (:status response) => 201
                 (:body response) => (is-json (just [(contains {:region "us-west-1"
                                                                :ec2-classic false
                                                                :vpcs (just [(contains {:vpc-id "vpc-79b1491c"})])})
                                                     (contains {:region "us-west-2"
                                                                :ec2-classic true
                                                                :vpcs (just [(contains {:vpc-id "vpc-82828282"})])})]))))))

(facts "about bartnet server" :integration
  (with-state-changes
    [(before :facts (do
                      (core/start-server [(.getPath (io/resource "test-config.json"))])
                     ))
     (after :facts (do
                     (core/stop-server)))]))