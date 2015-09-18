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
            [clj-time.format :as f]
            [ring.adapter.jetty9 :refer [run-jetty]]
            [bartnet.auth :as auth]
            [clojure.java.io :as io]
            [bartnet.instance :as instance]
            [schema.core :as sch]
            [bartnet.util :as util])
  (:import [io.aleph.dirigiste Executors]
           (java.util.concurrent ScheduledThreadPoolExecutor)))

(log/info "Testing!")

;"2015-09-09T19:10:14.000-07:00"

(def fmt (f/formatter "yyyy-MM-dd'T'kk:mm:ss.SSSZZ"))

(defn joda [str]
  (f/parse fmt str))

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
    (api/handler executor scheduler bus @db test-config)))

(defn start-ws-server []
  (do
    (log/info "start server")
    (auth/set-secret! (util/slurp-bytes (:secret test-config)))
    (reset! ws-server (run-jetty
                       (api/handler executor scheduler bus @db test-config)
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
           (fact "tests a check")
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
                   (sql/get-check-by-id @db {:id "checkid123" :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"}) => empty?
                   (log/info "sdfsfdasdfadsf")
                   (log/info "gotsdfsdfsdf")))
           (fact "checks get updated"
                 (let [response ((app) (-> (mock/request :put "/checks/checkid123" (generate-string
                                                                                    {:interval 100
                                                                                     :target {:name "goobernetty"
                                                                                              :type "sg"
                                                                                              :id "sg123"}
                                                                                     :check_spec {:type_url "HttpCheck"
                                                                                                  :value {:name "doop"
                                                                                                          :path "/health"
                                                                                                          :port 80
                                                                                                          :verb "POST"
                                                                                                          :protocol "http"}}}))
                                           (mock/header "Authorization" auth-header)))]
                   (:status response) => 200
                   (:body response) => (is-json (contains {:interval 100}))
                   (sql/get-check-by-id @db {:id "checkid123" :customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"}) => (just (contains {:interval 100}))))
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

;(facts "about /instance/:id"
;       (let [my-instance {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1" :id "id" :name "my instance" :group_id "sg-123456"}]
;         (with-state-changes
;           [(before :facts
;                    (do
;                      (instance/create-memory-store bus)
;                      (instance/save-instance! my-instance)
;                      (do-setup)))]
;           (fact "GET existing instance returns the instance"
;                 (let [response ((app) (-> (mock/request :get "/instance/id")
;                                           (mock/header "Authorization" auth-header)))]
;                   (:status response) => 200
;                   (:body (contains my-instance))))
;           (fact "GET unknown instance returns 404"
;                 (let [response ((app) (-> (mock/request :get "/instance/foo")
;                                           (mock/header "Authorization" auth-header)))]
;                   (:status response) => 404))
;           (fact "GET requires authentication"
;                 (let [response ((app) (-> (mock/request :get "/instance/id")))]
;                   (:status response) => 401)))))
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
                                                              "us-west-1" {:reservations [{:group-names [],
                                                                                           :groups [],
                                                                                           :instances [{:monitoring {:state "disabled"},
                                                                                                        :tags [{:value "Opsee Bastion 5bb82086-51c6-11e5-9b33-db6aaaf21de2", :key "Name"}
                                                                                                               {:value "BastionInstance", :key "aws:cloudformation:logical-id"}
                                                                                                               {:value "arn:aws:cloudformation:us-west-1:933693344490:stack/opsee-bastion-a8a20324-57db-11e5-88a1-37e8cfb78834/a993d3c0-57db-11e5-8c48-50d5018012a6", :key "aws:cloudformation:stack-id"}
                                                                                                               {:value "opsee-bastion-a8a20324-57db-11e5-88a1-37e8cfb78834", :key "aws:cloudformation:stack-name"}],
                                                                                                        :root-device-type "ebs",
                                                                                                        :private-dns-name "ip-172-31-7-203.us-west-1.compute.internal",
                                                                                                        :hypervisor "xen",
                                                                                                        :subnet-id "subnet-eccedfaa",
                                                                                                        :key-name "bastion-testing",
                                                                                                        :architecture "x86_64",
                                                                                                        :security-groups [{:group-id "sg-92a4d9f7",
                                                                                                                           :group-name "opsee-bastion-a8a20324-57db-11e5-88a1-37e8cfb78834-BastionSecurityGroup-1CE8E9ZT4DX0I"}],
                                                                                                        :source-dest-check true,
                                                                                                        :root-device-name "/dev/xvda",
                                                                                                        :virtualization-type "hvm",
                                                                                                        :product-codes [],
                                                                                                        :instance-type "t2.micro",
                                                                                                        :ami-launch-index 0,
                                                                                                        :image-id "ami-2517ec61",
                                                                                                        :state {:name "running", :code 16},
                                                                                                        :state-transition-reason "",
                                                                                                        :network-interfaces [{:description "",
                                                                                                                              :private-dns-name "ip-172-31-7-203.us-west-1.compute.internal",
                                                                                                                              :subnet-id "subnet-eccedfaa",
                                                                                                                              :source-dest-check true,
                                                                                                                              :private-ip-addresses [{:private-ip-address "172.31.7.203",
                                                                                                                                                      :private-dns-name "ip-172-31-7-203.us-west-1.compute.internal",
                                                                                                                                                      :association {:public-ip "54.67.6.120",
                                                                                                                                                                    :public-dns-name "ec2-54-67-6-120.us-west-1.compute.amazonaws.com",
                                                                                                                                                                    :ip-owner-id "amazon"},
                                                                                                                                                      :primary true}],
                                                                                                                              :network-interface-id "eni-61f3343a",
                                                                                                                              :vpc-id "vpc-79b1491c",
                                                                                                                              :mac-address "06:f7:3f:cc:48:e1",
                                                                                                                              :association {:public-ip "54.67.6.120",
                                                                                                                                            :public-dns-name "ec2-54-67-6-120.us-west-1.compute.amazonaws.com",
                                                                                                                                            :ip-owner-id "amazon"},
                                                                                                                              :status "in-use",
                                                                                                                              :private-ip-address "172.31.7.203",
                                                                                                                              :owner-id "933693344490",
                                                                                                                              :groups [{:group-id "sg-92a4d9f7",
                                                                                                                                        :group-name "opsee-bastion-a8a20324-57db-11e5-88a1-37e8cfb78834-BastionSecurityGroup-1CE8E9ZT4DX0I"}],
                                                                                                                              :attachment {:device-index 0,
                                                                                                                                           :status "attached",
                                                                                                                                           :attachment-id "eni-attach-86f9abd5",
                                                                                                                                           :attach-time (joda "2015-09-10T09:50:13.000-07:00"),
                                                                                                                                           :delete-on-termination true}}],
                                                                                                        :vpc-id "vpc-79b1491c",
                                                                                                        :ebs-optimized false,
                                                                                                        :instance-id "i-8dd40a48",
                                                                                                        :iam-instance-profile {:id "AIPAIX6THYKQL7DSOL2YI",
                                                                                                                               :arn "arn:aws:iam::933693344490:instance-profile/opsee-bastion-a8a20324-57db-11e5-88a1-37e8cfb78834-BastionInstanceProfile-KU1X4B623C6T"},
                                                                                                        :public-dns-name "ec2-54-67-6-120.us-west-1.compute.amazonaws.com",
                                                                                                        :private-ip-address "172.31.7.203",
                                                                                                        :placement {:group-name "",
                                                                                                                    :availability-zone "us-west-1c",
                                                                                                                    :tenancy "default"},
                                                                                                        :client-token "opsee-Basti-11CG88GJ9UTYZ",
                                                                                                        :public-ip-address "54.67.6.120",
                                                                                                        :launch-time (joda "2015-09-10T09:50:13.000-07:00"),
                                                                                                        :block-device-mappings [{:device-name "/dev/xvda",
                                                                                                                                 :ebs {:volume-id "vol-da8cab23",
                                                                                                                                       :status "attached",
                                                                                                                                       :attach-time (joda "2015-09-10T09:50:17.000-07:00"),
                                                                                                                                       :delete-on-termination true}}]}],
                                                                                           :owner-id "933693344490",
                                                                                           :reservation-id "r-1b1b88d9"}]}
                                                              "us-west-2" {:reservations [{:groups [],
                                                                                           :instances [{:monitoring {:state "disabled"},
                                                                                                        :tags [{:value "coreos4", :key "Name"}],
                                                                                                        :root-device-type "ebs",
                                                                                                        :private-dns-name "ip-172-31-8-48.us-west-1.compute.internal",
                                                                                                        :hypervisor "xen",
                                                                                                        :subnet-id "subnet-eccedfaa",
                                                                                                        :key-name "c1-us-west-1",
                                                                                                        :architecture "x86_64",
                                                                                                        :security-groups [{:group-id "sg-c852dbad", :group-name "c1-us-west-1"}],
                                                                                                        :source-dest-check false,
                                                                                                        :root-device-name "/dev/xvda",
                                                                                                        :virtualization-type "hvm",
                                                                                                        :product-codes [],
                                                                                                        :instance-type "m3.medium",
                                                                                                        :ami-launch-index 0,
                                                                                                        :image-id "ami-c967938d",
                                                                                                        :state {:name "running", :code 16},
                                                                                                        :state-transition-reason "",
                                                                                                        :network-interfaces [{:description "",
                                                                                                                              :private-dns-name "ip-172-31-8-48.us-west-1.compute.internal",
                                                                                                                              :subnet-id "subnet-eccedfaa",
                                                                                                                              :source-dest-check false,
                                                                                                                              :private-ip-addresses [{:private-ip-address "172.31.8.48",
                                                                                                                                                      :private-dns-name "ip-172-31-8-48.us-west-1.compute.internal",
                                                                                                                                                      :association {:public-ip "52.8.155.47",
                                                                                                                                                                    :public-dns-name "ec2-52-8-155-47.us-west-1.compute.amazonaws.com",
                                                                                                                                                                    :ip-owner-id "amazon"},
                                                                                                                                                      :primary true}],
                                                                                                                              :network-interface-id "eni-0639735e",
                                                                                                                              :vpc-id "vpc-79b1491c",
                                                                                                                              :mac-address "06:5f:5d:63:d0:c5",
                                                                                                                              :association {:public-ip "52.8.155.47",
                                                                                                                                            :public-dns-name "ec2-52-8-155-47.us-west-1.compute.amazonaws.com",
                                                                                                                                            :ip-owner-id "amazon"},
                                                                                                                              :status "in-use",
                                                                                                                              :private-ip-address "172.31.8.48",
                                                                                                                              :owner-id "933693344490",
                                                                                                                              :groups [{:group-id "sg-c852dbad",
                                                                                                                                        :group-name "c1-us-west-1"}],
                                                                                                                              :attachment {:device-index 0,
                                                                                                                                           :status "attached",
                                                                                                                                           :attachment-id "eni-attach-859613d6",
                                                                                                                                           :attach-time (joda "2015-07-02T13:49:26.000-07:00"),
                                                                                                                                           :delete-on-termination true}}
                                                                                                                             {:description "nsqlookupd-1",
                                                                                                                              :private-dns-name "ip-172-31-11-136.us-west-1.compute.internal",
                                                                                                                              :subnet-id "subnet-eccedfaa",
                                                                                                                              :source-dest-check true,
                                                                                                                              :private-ip-addresses [{:private-ip-address "172.31.11.136",
                                                                                                                                                      :private-dns-name "ip-172-31-11-136.us-west-1.compute.internal",
                                                                                                                                                      :association {:public-ip "52.8.240.251",
                                                                                                                                                                    :public-dns-name "ec2-52-8-240-251.us-west-1.compute.amazonaws.com",
                                                                                                                                                                    :ip-owner-id "933693344490"},
                                                                                                                                                      :primary true}],
                                                                                                                              :network-interface-id "eni-d34d8888",
                                                                                                                              :vpc-id "vpc-79b1491c",
                                                                                                                              :mac-address "06:ba:44:c3:11:7f",
                                                                                                                              :association {:public-ip "52.8.240.251",
                                                                                                                                            :public-dns-name "ec2-52-8-240-251.us-west-1.compute.amazonaws.com",
                                                                                                                                            :ip-owner-id "933693344490"},
                                                                                                                              :status "in-use",
                                                                                                                              :private-ip-address "172.31.11.136",
                                                                                                                              :owner-id "933693344490",
                                                                                                                              :groups [{:group-id "sg-c852dbad", :group-name "c1-us-west-1"}],
                                                                                                                              :attachment {:device-index 1,
                                                                                                                                           :status "attached",
                                                                                                                                           :attachment-id "eni-attach-0de1b25e",
                                                                                                                                           :attach-time (joda "2015-09-09T19:10:14.000-07:00"),
                                                                                                                                           :delete-on-termination false}}],
                                                                                                        :vpc-id "vpc-79b1491c",
                                                                                                        :ebs-optimized false,
                                                                                                        :instance-id "i-38aae6fa",
                                                                                                        :iam-instance-profile {:id "AIPAJ2PPDVKELNHMYVUYC",
                                                                                                                               :arn "arn:aws:iam::933693344490:instance-profile/CoreOS_Cluster_Role"},
                                                                                                        :public-dns-name "ec2-52-8-155-47.us-west-1.compute.amazonaws.com",
                                                                                                        :private-ip-address "172.31.8.48",
                                                                                                        :placement {:group-name "",
                                                                                                                    :availability-zone "us-west-1c",
                                                                                                                    :tenancy "default"},
                                                                                                        :client-token "",
                                                                                                        :public-ip-address "52.8.155.47",
                                                                                                        :launch-time (joda "2015-07-14T18:57:33.000-07:00"),
                                                                                                        :block-device-mappings [{:device-name "/dev/xvda",
                                                                                                                                 :ebs {:volume-id "vol-099e30f0",
                                                                                                                                       :status "attached",
                                                                                                                                       :attach-time (joda "2015-07-02T13:49:30.000-07:00"),
                                                                                                                                       :delete-on-termination true}}]}
                                                                                                       {:monitoring {:state "disabled"},
                                                                                                        :tags [],
                                                                                                        :root-device-type "ebs",
                                                                                                        :private-dns-name "",
                                                                                                        :hypervisor "xen",
                                                                                                        :key-name "keypair-4I7OgHCnwnwvWBSf3tfP8g",
                                                                                                        :architecture "x86_64",
                                                                                                        :security-groups [],
                                                                                                        :root-device-name "/dev/xvda",
                                                                                                        :virtualization-type "hvm",
                                                                                                        :product-codes [],
                                                                                                        :instance-type "t2.micro",
                                                                                                        :ami-launch-index 0,
                                                                                                        :image-id "ami-3d73d356",
                                                                                                        :state {:name "terminated", :code 48},
                                                                                                        :state-transition-reason "User initiated (2015-09-10 19:03:28 GMT)",
                                                                                                        :network-interfaces [],
                                                                                                        :ebs-optimized false,
                                                                                                        :instance-id "i-e000bc43",
                                                                                                        :state-reason {:message "Client.UserInitiatedShutdown: User initiated shutdown", :code "Client.UserInitiatedShutdown"},
                                                                                                        :public-dns-name "",
                                                                                                        :placement {:group-name "", :availability-zone "us-east-1d", :tenancy "default"},
                                                                                                        :client-token "3f9585f7-93de-4036-a5ca-d56dc42bdfd2",
                                                                                                        :launch-time (joda "2015-09-10T11:59:42.000-07:00"),
                                                                                                        :block-device-mappings []}],
                                                                                           :owner-id "933693344490",
                                                                                           :group-names [],
                                                                                           :reservation-id "r-a67d6d71"}
                                                                                          {:groups [],
                                                                                           :instances [{:monitoring {:state "disabled"},
                                                                                                        :tags [],
                                                                                                        :root-device-type "ebs",
                                                                                                        :private-dns-name "",
                                                                                                        :hypervisor "xen",
                                                                                                        :key-name "keypair-6ZDzu0iCkL3nIfjGkMbznJ",
                                                                                                        :architecture "x86_64",
                                                                                                        :security-groups [],
                                                                                                        :root-device-name "/dev/xvda",
                                                                                                        :virtualization-type "hvm",
                                                                                                        :product-codes [],
                                                                                                        :instance-type "t2.micro",
                                                                                                        :ami-launch-index 0,
                                                                                                        :image-id "ami-3d73d356",
                                                                                                        :state {:name "terminated", :code 48},
                                                                                                        :state-transition-reason "User initiated (2015-09-10 19:34:14 GMT)",
                                                                                                        :network-interfaces [],
                                                                                                        :ebs-optimized false,
                                                                                                        :instance-id "i-5bd26df8",
                                                                                                        :state-reason {:message "Client.UserInitiatedShutdown: User initiated shutdown", :code "Client.UserInitiatedShutdown"},
                                                                                                        :public-dns-name "",
                                                                                                        :placement {:group-name "", :availability-zone "us-east-1d", :tenancy "default"},
                                                                                                        :client-token "5ef70dff-2275-4400-ba29-054590f152fd",
                                                                                                        :launch-time (joda "2015-09-10T12:29:21.000-07:00"),
                                                                                                        :block-device-mappings []}],
                                                                                           :owner-id "933693344490",
                                                                                           :group-names [],
                                                                                           :reservation-id "r-c804141f"}]}))]
         (fact "aws api's get called for every region"
               (let [response ((app) (-> (mock/request :post "/scan-vpcs" (generate-string {:access-key "SDFSDFDSF"
                                                                                            :secret-key "sdfsdf+sasdasdasdsdfsdf"
                                                                                            :regions ["us-west-1"
                                                                                                      "us-west-2"]}))))]
                 (:status response) => 201
                 (:body response) => (is-json (just [(contains {:region "us-west-1"
                                                                :ec2-classic false
                                                                :vpcs (just [(contains {:vpc-id "vpc-79b1491c"
                                                                                        :count 1})])})
                                                     (contains {:region "us-west-2"
                                                                :ec2-classic true
                                                                :vpcs (just [(contains {:vpc-id "vpc-82828282"
                                                                                        :count 1})])})]))))))

(facts "about bartnet server" :integration
       (with-state-changes
         [(before :facts (do
                           (core/start-server [(.getPath (io/resource "test-config.json"))])))
          (after :facts (do
                          (core/stop-server)))]))