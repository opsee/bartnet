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
            [schema.core :as sch])
  (:import [io.aleph.dirigiste Executors]
           (java.util.concurrent ScheduledThreadPoolExecutor)))

(log/info "Testing!")

(def auth-header "HMAC 1--iFplvAUtzi_veq_dMKPfnjtg_SQ=")
(def auth-header2 "HMAC 2--kJXpzFOo0ZfI_PG069j0iNDAc-o=")
(def auth-header3 "HMAC 3--NSlzR-VsmftimMosLpXHoG1Z7kM=")

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
  (do (api/handler executor bus @db test-config)))

(defn start-ws-server []
  (do
    (log/info "start server")
    (reset! ws-server (run-jetty
                        (api/handler executor bus @db test-config)
                        (assoc (:server test-config)
                          :websockets {"/stream" (websocket/ws-handler scheduler bus @db (:secret test-config))})))
    (log/info "server started")))

(defn stop-ws-server []
  (.stop @ws-server))

(facts "Auth endpoint works"
       (fact "bounces bad logins"
             (let [response ((app) (mock/request :post "/authenticate/password"))]
               (:status response) => 401))
       (with-state-changes
         [(before :facts
                  (doto (do-setup)
                        login-fixtures))]
         (fact "sets hmac header on good login"
               (let [response ((app) (mock/request :post "/authenticate/password" (generate-string {:email "cliff@leaninto.it" :password "cliff"})))]
                 (:status response) => 201
                 (get-in response [:headers "X-Auth-HMAC"]) => "1--iFplvAUtzi_veq_dMKPfnjtg_SQ="
                 (:body response) => (is-json (contains {:token "HMAC 1--iFplvAUtzi_veq_dMKPfnjtg_SQ="}))))))
(facts "Environments endpoint works"
       (fact "bounces unauthorized requests"
             (let [response ((app) (mock/request :get "/environments"))]
               (:status response) => 401)
             (let [response ((app) (-> (mock/request :get "/environments")
                                       (mock/header "Authorization" "blorpbloop")))]
               (:status response) => 401))
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures))]
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
         [(before :facts (doto
                           (do-setup)
                           login-fixtures
                           environment-fixtures))]
         (fact "returns an array of environments"
               (let [response ((app) (-> (mock/request :get "/environments")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 200
                 (:body response) => (is-json (just [(contains {:id "abc123" :name "Test Env"})
                                                     (contains {:id "nice123" :name "Test2"})]))))))
(facts "Environment endpoint works"
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures))]
         (fact "404's unknown environments"
               (let [response ((app) (-> (mock/request :get "/environments/abc123")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 404)))
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures
                           environment-fixtures))]
         (fact "returns known environments"
               (let [response ((app) (-> (mock/request :get "/environments/abc123")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 200
                 (:body response) => (is-json (contains {:id "abc123" :name "Test Env"}))))
         (fact "updates environments"
               (let [response ((app) (-> (mock/request :put "/environments/abc123" (generate-string {:name "Test Update"}))
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 201
                 (sql/get-environment-for-login @db "abc123" 1) => (just [(contains {:name "Test Update"})])))))
(facts "/signups"
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures
                           signup-fixtures
                           admin-fixtures))]
         (fact "signups get created"
               (let [response ((app) (-> (mock/request :post "/signups" (generate-string
                                                                          {:email "cliff+newsignup@leaninto.it"
                                                                           :name "cliff moon"}))))]
                 (:status response) => 201
                 (sql/get-signup-by-email @db "cliff+newsignup@leaninto.it") =not=> empty?))
         (fact "signups don't get duplicated"
               (let [response ((app) (-> (mock/request :post "/signups" (generate-string
                                                                          {:email "cliff+signup@leaninto.it"
                                                                           :name "cliff 2"}))))]
                 (:status response) => 409
                 (count (sql/get-signups @db 100 0)) => 1
                 (:body response) => (is-json (contains {:error #"Conflict"}))))
         (fact "superusers can list out signups"
               (let [response ((app) (-> (mock/request :get "/signups")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 200
                 (let [body (parse-string (:body response) true)]
                   (sch/validate [api/Signup] (:signups body)) => (:signups body)
                   (:pages body) => 1)))
         (fact "superusers can send an activation email"
               (let [response ((app) (-> (mock/request :post "/signups/send-activation?email=cliff%2Bsignup@leaninto.it")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 201
                 (count (sql/get-unused-activations @db)) => 1))))

(facts "activations endpoint works"
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures
                           signup-fixtures
                           admin-fixtures
                           unverified-fixtures
                           activation-fixtures))]
         (fact "activation endpoint turns into a login"
               (let [response ((app) (-> (mock/request :post "/activations/abc123/activate" (generate-string
                                                                                              {:password "cliff"}))))]
                 (:status response) => 201
                 (:body response) => (is-json (contains {:email "cliff+signup@leaninto.it"}))
                 (:body response) => (is-json (fn [actual] (string? (:token actual))))))
         (fact "activation records for existing logins will result in the login being set to verified"
               (let [response ((app) (-> (mock/request :post "/activations/existing/activate")))]
                 (:status response) => 201
                 (:body response) => (is-json (contains {:id 2}))
                 (sql/get-active-login-by-id @db 2) => (just (contains {:verified true}))))
         (fact "activations already used will result in a 409 conflict"
               (let [response ((app) (-> (mock/request :post "/activations/badid/activate" (generate-string
                                                                                             {:password "cliff"}))))]
                 (:status response) => 409
                 (:body response) => (is-json (contains {:error #"invalid activation"}))))))
(facts "orgs endpoint works"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      login-fixtures
                      signup-fixtures))]
    (facts "about /orgs"
      (fact "POST creates a new org"
        (let [response ((app) (-> (mock/request :post "/orgs" (generate-string
                                                                 {:name "test org"
                                                                  :subdomain "foo"}))
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 201
          (:body response) => (is-json (contains {:name "test org"}))))
      (fact "POST associates the current login with the new org"
        (let [response ((app) (-> (mock/request :post "/orgs" (generate-string
                                                                {:name "test org"
                                                                 :subdomain "foo"}))
                                (mock/header "Authorization" auth-header3)))]
          (:status response) => 201
          (:customer_id (first (sql/get-any-login-by-email @db "cliff+newuser@leaninto.it"))) => "foo")))
    (facts "about /orgs/subdomain/:subdomain"
      (fact "GET returns availability for a subdomain"
        (let [response ((app) (-> (mock/request :get "/orgs/subdomain/cliff")
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (contains {:available false})))
        (let [response ((app) (-> (mock/request :get "/orgs/subdomain/apples")
                                (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (contains {:available true})))))))
(facts "login endpoint works"
       (with-state-changes
         [(before :facts (doto
                           (do-setup)
                           login-fixtures
                           admin-fixtures))]
         (fact "can retrieve own login"
               (let [response ((app) (-> (mock/request :get "/logins/2")
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 200
                 (:body response) => (is-json (contains {:id 2}))))
         (fact "cannot retrieve someone elses login"
               (let [response ((app) (-> (mock/request :get "/logins/1")
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 403))
         (fact "superuser can retrieve someone elses login"
               (let [response ((app) (-> (mock/request :get "/logins/2")
                                         (mock/header "Authorization" auth-header)))]
                 (:status response) => 200
                 (:body response) => (is-json (contains {:id 2}))))
         (fact "user can edit their own name"
               (let [response ((app) (-> (mock/request :patch "/logins/2" (generate-string {:name "derp"}))
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 200
                 (:body response) => (is-json (contains {:id 2 :name "derp" :verified true}))))
         (fact "changing email address will change verified status"
               (let [response ((app) (-> (mock/request :patch "/logins/2" (generate-string {:email "cliff+hello@leaninto.it"}))
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 200
                 (:body response) => (is-json (contains {:id 2 :email "cliff+hello@leaninto.it" :verified false}))
                 (:body response) =not=> (is-json (contains {:password_hash #""}))))
         (fact "changing the password without the old one will result in a 403"
               (let [response ((app) (-> (mock/request :patch "/logins/2" (generate-string {:old_password "nomegusta"
                                                                                            :new_password "hacker"}))
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 403))
         (fact "changing the password with a matching old one will work"
               (let [response ((app) (-> (mock/request :patch "/logins/2" (generate-string {:old_password "cliff"
                                                                                            :new_password "huck"}))
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 200
                 (:password_hash (first (sql/get-active-login-by-id @db 2))) => #(auth/password-match? "huck" %)))
         (fact "changing to an existing email address will return a 409"
               (let [response ((app) (-> (mock/request :patch "/logins/2" (generate-string {:email "cliff@leaninto.it"}))
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 409
                 (first (sql/get-active-login-by-id @db 2)) => (contains {:email "cliff+notsuper@leaninto.it"})))
         (fact "a user can deactivate their account"
               (let [response ((app) (-> (mock/request :delete "/logins/2")
                                         (mock/header "Authorization" auth-header2)))]
                 (:status response) => 204
                 (sql/get-active-login-by-id @db 2) => empty?))))
(facts "checks endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service]
         (with-state-changes
           [(before :facts (doto
                             (do-setup)
                             login-fixtures
                             environment-fixtures))]
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
                     (sql/get-checks-by-env-id @db "abc123") => (contains (contains {:interval 10}))))))))
(facts "check endpoint works"
       (with-redefs [rpc/checker-client mock-checker-client
                     router/get-customer-bastions mock-get-customer-bastions
                     router/get-service mock-get-service]
         (with-state-changes
           [(before :facts (doto
                             (do-setup)
                             login-fixtures
                             environment-fixtures
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
  (let [my-instance {:customer_id "cliff" :id "id" :name "my instance" :group_id "sg-123456"}]
    (with-state-changes
      [(before :facts
         (do
           (instance/create-memory-store bus)
           (instance/save-instance! my-instance)
           (doto (do-setup)
                  login-fixtures)))]
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