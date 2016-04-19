(ns bartnet.t-api
  (:use midje.sweet)
  (:require [bartnet.api :as api]
            [bartnet.core :as core]
            [bartnet.websocket :as websocket]
            [bartnet.bastion-router :as router]
            [bartnet.rpc :as rpc]
            [opsee.middleware.test-helpers :refer :all]
            [opsee.middleware.config :refer [config]]
            [bartnet.fixtures :refer :all]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [bartnet.sql :as sql]
            [manifold.stream :as s]
            [aleph.http :refer [websocket-client]]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [clj-time.format :as f]
            [ring.adapter.jetty9 :refer [run-jetty]]
            [clojure.java.io :as io]
            [bartnet.instance :as instance]
            [bartnet.nsq :as nsq]
            [schema.core :as sch]
            [manifold.bus :as bus]
            [opsee.middleware.core :refer :all])
  (:import [io.aleph.dirigiste Executors]
           (java.util.concurrent ScheduledThreadPoolExecutor)))

(def defaults {"DB_NAME" "bartnet_test"
               "DB_HOST" "localhost"
               "DB_PORT" "5432"
               "DB_USER" "postgres"
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))

;"2015-09-09T19:10:14.000-07:00"

(def fmt (f/formatter "yyyy-MM-dd'T'kk:mm:ss.SSSZZ"))

(defn joda [str]
  (f/parse fmt str))

; this token is for {"id":8,"customer_id":"154ba57a-5188-11e5-8067-9b5f2d96dce1","email":"cliff@leaninto.it","name":"cliff","verified":true,"admin":false,"active":true}
; it will expire in 10 yrs. hopefully that is long enough so that computers won't exist anymore
(def auth-header "Bearer eyJhbGciOiJBMTI4R0NNS1ciLCJlbmMiOiJBMTI4R0NNIiwiaXYiOiJXQWlLQ2Z1azk3TlBzM1ZYIiwidGFnIjoiaU56RG1LdjloQmE0TS1YU19YcEpPZyJ9.HqXl4bq3k3E9GQ7FtsWHaQ.SONY24NgxzEZk7c3.yYd7WZX3O8ChDIVFlG--kLr_bDfkNXcR7eAnCyZ-QhFKmlbKGKE9A1-uudKRPuZ05LEAxolOrZ0lPRkW7CM3jdEdYBcUITinztgz-POIdMOXdUjFODpNOVxlcHKtZo2JH1wNdzEobBtAmVbdkl2aNUJMhVSKWbsLV3efvKQ-wVfO3kHDNmYHJlp2DKh0-8yul4UcoDytkEDOfTrpGlZrxStXRNhSf0KhRK11fh3dXvyzj07OEdYuNVbqhtfyycBPUQUJnP1xDZTpDtZ3n7lJaA.OGbujXobjndTRus8wmCqIg")

(def bus (bus/event-bus))
(def executor (Executors/utilizationExecutor 0.9 10))
(def scheduler (ScheduledThreadPoolExecutor. 10))
(def ws-server (atom nil))
(def rpc-message-received (atom nil))

(defn mock-checker-client [addr]
  (reify rpc/CheckerClient
    (shutdown [_])
    (test-check [_ check])
    (create-check [_ check]
      (reset! rpc-message-received check))
    (update-check [_ check]
      (reset! rpc-message-received check))
    (retrieve-check [_ check])
    (delete-check [_ check])))

(defn mock-get-customer-bastions [customer_id]
  ["i-8888888"])

(defn mock-get-service [customer_id instance_id service-name]
  {:host "localhost" :port 4001})

(defn do-setup []
  (do
    (start-connection test-config)))

(defn app []
  (do
    (api/handler executor scheduler bus nil nil @db test-config)))

(defn start-ws-server []
  (do
    (log/info "start server")
    (reset! ws-server (run-jetty
                       (api/handler executor scheduler bus nil nil @db test-config)
                       (assoc (:server test-config)
                              :websockets {"/stream" (websocket/ws-handler scheduler bus)})))
    (log/info "server started")))

(defn stop-ws-server []
  (.stop @ws-server))

(facts "checks endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service
                     clj-http.client/get (mock-http {"/results" {:status 200 :body (:customer-query fixtures)}})]
         (with-state-changes
           [(before :facts (doto
                            (do-setup)))]
           (fact "empty checks are empty"
                 (let [response ((app) (-> (mock/request :get "/checks")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json (just {:checks empty?}))))
           (fact "tests a check")
           (with-state-changes
             [(before :facts (do (check-fixtures @db)
                             (assertions-fixtures @db)))]
             (fact "checks need auth"
                   (let [response ((app) (-> (mock/request :get "/checks")
                                             (mock/header "Authorization" "asasdasd")))]
                     (:status response) => 401))
             (fact "checks get returned"
                   (let [response ((app) (-> (mock/request :get "/checks")
                                             (mock/header "Authorization" auth-header)))]
                     (:status response) => 200
                     (:body response) => (is-json (just
                                                    {:checks
                                                     (just
                                                       (contains
                                                         {:results not-empty
                                                          :assertions not-empty
                                                          :check_spec
                                                          (contains {:value (contains {:name "A Good Check"})})}))}))))
             (fact "creates new checks"
                   (let [response ((app) (-> (mock/request :post "/checks" (generate-string
                                                                            {:interval 10
                                                                             :name "A Good Check"
                                                                             :target {:name "goobernetty"
                                                                                      :type "sg"
                                                                                      :id "sg123679"}
                                                                             :check_spec {:type_url "HttpCheck"
                                                                                          :value {:name "A Good Check"
                                                                                                  :path "/health_check"
                                                                                                  :port 80
                                                                                                  :verb "GET"
                                                                                                  :protocol "http"}}
                                                                             :assertions [{:key "foo"
                                                                                           :value "bar"
                                                                                           :relationship "equal"
                                                                                           :operand "bar"
                                                                                           }]}))
                                             (mock/header "Authorization" auth-header)))]
                     (:status response) => 201
                     (let [check (first (sql/get-checks-by-customer-id @db "154ba57a-5188-11e5-8067-9b5f2d96dce1"))]
                       check => (contains {:interval 10})
                       (first (sql/get-assertions @db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                                       :check_id (:id check)})) => (contains {:key "foo"}))))))))

(facts "check endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service
                     clj-http.client/get (mock-http {"/results" {:status 200 :body (:customer-query fixtures)}})
                     clj-http.client/delete (mock-http {"/results/checkid123" {:status 204 :body ""}})]
         (with-state-changes
           [(before :facts (do
                             (doto
                               (do-setup)
                               (check-fixtures)
                               (assertions-fixtures))
                             (reset! rpc-message-received nil)))]
           (fact "checks that don't exist will 404"
                 (let [response ((app) (-> (mock/request :get "/checks/derpderp")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 404))
           (fact "checks need auth"
                 (let [response ((app) (-> (mock/request :get "/checks/check1")
                                           (mock/header "Authorization" "sdfsdfsdfsdf")))]
                   (:status response) => 401))
           (fact "checks that exist get returned"
                 (let [response ((app) (-> (mock/request :get "/checks/check1")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json (contains {:id "check1"
                                                           :check_spec (contains {:value (contains {:name "A Good Check"})})
                                                           :results not-empty
                                                           :assertions not-empty}))))
           (fact "checks get deleted"
                 (let [response ((app) (-> (mock/request :delete "/checks/check1")
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 204
                   (sql/get-check-by-id @db {:id "checkid123" :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"}) => empty?
                   (log/info "sdfsfdasdfadsf")
                   (log/info "gotsdfsdfsdf")))
           (fact "checks get updated"
                 (let [response ((app) (-> (mock/request :put "/checks/check1" (generate-string
                                                                                 {:interval 100
                                                                                  :name "doop"
                                                                                  :target {:name "goobernetty"
                                                                                           :type "sg"
                                                                                           :id "sg123"}
                                                                                  :check_spec {:type_url "HttpCheck"
                                                                                               :value {:name "doop"
                                                                                                       :path "/health"
                                                                                                       :port 80
                                                                                                       :verb "POST"
                                                                                                       :protocol "http"}}
                                                                             :assertions [{:key "foo"
                                                                                           :value "bar"
                                                                                           :relationship "equal"
                                                                                           :operand "bar"
                                                                                           }]}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json (contains {:interval 100}))
                   (sql/get-check-by-id @db {:id "check1" :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"}) => (just (contains {:interval 100}))
                   (.getAssertionsCount (.getChecks @rpc-message-received 0)) => 1))
           (fact "new checks get saved"
                 (let [response ((app) (-> (mock/request :post "/checks" (generate-string
                                                                          {:interval 10
                                                                           :name "A Good Check"
                                                                           :target {:name "goobernetty"
                                                                                    :type "sg"
                                                                                    :id "sg123"}
                                                                           :check_spec {:type_url "HttpCheck"
                                                                                        :value {:name "A Good Check"
                                                                                                :path "/health_check"
                                                                                                :port 80
                                                                                                :verb "GET"
                                                                                                :protocol "http"}}
                                                                           :assertions [{:key "foo"
                                                                                         :value "bar"
                                                                                         :relationship "equal"
                                                                                         :operand "bar"
                                                                                         }]}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 201)))))

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
                                                                        :is-default true}]}))
                     amazonica.aws.ec2/describe-instances (fn [creds & args]
                                                            (case (:endpoint creds)
                                                              "us-west-1" (:us-west-1 fixtures)
                                                              "us-west-2" (:us-west-2 fixtures)))
                     amazonica.aws.ec2/describe-subnets (fn [creds]
                                                          (case (:endpoint creds)
                                                            "us-west-1" {:subnets
                                                                         [{:tags []
                                                                           :subnet-id "subnet-abcd1111"
                                                                           :default-for-az true
                                                                           :state "available"
                                                                           :vpc-id "vpc-79b1491c"
                                                                           :map-public-ip-on-launch true
                                                                           :available-ip-address-count 124
                                                                           :cidr-block "172.31.0.0/24"}]}
                                                            "us-west-2" {:subnets
                                                                         [{:tags []
                                                                           :subnet-id "subnet-zyxw2222"
                                                                           :default-for-az true
                                                                           :state "available"
                                                                           :vpc-id "vpc-82828282"
                                                                           :map-public-ip-on-launch true
                                                                           :available-ip-address-count 124
                                                                           :cidr-block "172.31.0.0/24"}]}))]
         (fact "aws api's get called for every region"
               (let [response ((app) (-> (mock/request :post "/scan-vpcs" (generate-string {:access-key "SDFSDFDSF"
                                                                                            :secret-key "sdfsdf+sasdasdasdsdfsdf"
                                                                                            :regions ["us-west-1"
                                                                                                      "us-west-2"]}))))]
                 (:status response) => 201
                 (:body response) => (is-json (just [(contains {:region "us-west-1"
                                                                :ec2-classic false
                                                                :vpcs (just [(contains {:vpc-id "vpc-79b1491c"
                                                                                        :count 1
                                                                                        :subnets (just [(contains {:subnet-id "subnet-abcd1111"
                                                                                                                   :vpc-id "vpc-79b1491c"})])})])})
                                                     (contains {:region "us-west-2"
                                                                :ec2-classic true
                                                                :vpcs (just [(contains {:vpc-id "vpc-82828282"
                                                                                        :count 1
                                                                                        :subnets (just [(contains {:subnet-id "subnet-zyxw2222"
                                                                                                                   :vpc-id "vpc-82828282"})])})])})]))))))
(facts "instance store"
  (with-redefs [clj-http.client/get (mock-http {"/groups/security" {:status 200 :body (:groups fixtures)}
                                                "/group/security/sg-c852dbad" {:status 200 :body (:group fixtures)}
                                                "/groups/elb" {:status 200 :body (:elbs fixtures)}
                                                "/group/elb/lasape" {:status 200 :body (:elb fixtures)}
                                                "/instances/ec2" {:status 200 :body (:instances fixtures)}
                                                "/instance/ec2/i-38aae6fa" {:status 200 :body (:instance fixtures)}
                                                {:url "/results"
                                                 :query-params {:q "customer_id = \"154ba57a-5188-11e5-8067-9b5f2d96dce1\" and type = \"result\""}} {:status 200 :body (:customer-query fixtures)}
                                                {:url "/results"
                                                 :query-params {:q "customer_id = \"154ba57a-5188-11e5-8067-9b5f2d96dce1\" and host = \"sg-c852dbad\" and type = \"result\""}} {:status 200 :body (:group-query fixtures)}})]
    (fact "/groups/security"
      (let [response ((app) (-> (mock/request :get "/groups/security")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:groups (contains
                                                      [(contains {:group (contains {:GroupId "sg-c852dbad"})
                                                                  :results (contains [(contains {:check_id "check2"})])})])}))))
    (fact "/groups/security/id"
      (let [response ((app) (-> (mock/request :get "/groups/security/sg-c852dbad")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:group (contains {:GroupId "sg-c852dbad"})
                                            :results (contains [(contains {:check_id "check2"})])
                                            :instances (contains [(contains {:results (contains [(contains {:check_id "check2"})])})])
                                            :instance_count 4}))))
    (fact "/groups/elb"
      (let [response ((app) (-> (mock/request :get "/groups/elb")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:groups (contains
                                                      [(contains {:group (contains {:LoadBalancerName "lasape"})
                                                                  :results (contains [(contains {:check_id "check3"})])})])}))))
    (fact "/groups/elb/id")
    (fact "/instances/ec2")
    (fact "/instances/ec2/id")))



(facts "about bartnet server" :integration
       (with-state-changes
         [(before :facts (do
                           (core/start-server [(.getPath (io/resource "test-config.json"))])))
          (after :facts (do
                          (core/stop-server)))]))
