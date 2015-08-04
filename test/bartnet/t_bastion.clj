(ns bartnet.t-bastion
  (:use midje.sweet)
  (:require [bartnet.bastion :as bastion]
            [bartnet.bus :as bus]
            [bartnet.autobus :as autobus]
            [bartnet.fixtures :refer :all]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [clojure.tools.logging :as log]
            [aleph.tcp :as tcp]
            [amazonica.aws.sns :as sns]
            [bartnet.autobus :as msg]))


(def bus (atom nil))
(def server (atom nil))

(def registration-event
  {:id 1
   :version 1
   :sent 0
   :type "Connected"
   :body (generate-string {:time 0
                           :customer_id "cliff"
                           :instance {:InstanceId "i25738ajfi"}})})

(defn publisher [customer-id]
  (let [client (bus/register @bus (bus/publishing-client) customer-id)]
    (fn [topic msg]
      (bus/publish @bus client customer-id topic msg))))

(defn echo [msg]
  (assoc msg :reply "ok"))

(defn send-reg [client]
  (s/put! client registration-event))

(defn client [host port]
  (let [c (-> (tcp/client {:host host, :port port})
              (d/chain
                (fn [c]
                  (log/info c)
                  c)
                #(bastion/wrap-duplex-stream bastion/protocol %))
              (d/catch (fn [ex]
                         (log/error (str "error " ex)))))]
    (log/info (str "client " c))
    c))

(defn setup-server [port cmds]
  (do
    (start-connection)
    (reset! bus (bus/message-bus (autobus/autobus)))
    (reset! server (bastion/bastion-server @db @bus {:port port}))))

(defn teardown-server []
  (.close @server))

(facts "Bastion channel listens"
  (with-redefs [sns/create-topic (fn [& _] {:topic-arn "test topic"})
                sns/publish (fn [& _] true)]
       (with-state-changes
         [(before :facts (setup-server 4080 {"echo" echo}))
          (after :facts (teardown-server))]
         (fact "registers the client"
               (let [client @(client "localhost" 4080)]
                 @(send-reg client) => true
                 @(s/take! client) => (is-msg (contains {:reply_to 1}))
                 (.close client)))
         (with-state-changes
           [(before :facts (do
                             (login-fixtures @db)
                             (environment-fixtures @db)
                             (check-fixtures @db)))]
           (fact "bastions will get existing checks on registration"
                 (let [client @(client "localhost" 4080)]
                   @(send-reg client) => true
                   @(s/take! client) => (is-msg (contains {:reply_to 1}))

                   @(s/take! client) => (is-msg (contains {:type "CheckCommand"
                                                           :body (contains {:action "create_check"
                                                                            :parameters (contains {:name "A Nice Check"})})}))))))))
