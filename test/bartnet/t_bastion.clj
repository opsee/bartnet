(ns bartnet.t-bastion
  (:use midje.sweet)
  (:require [bartnet.pubsub :as pubsub]
            [bartnet.bastion :as bastion]
            [bartnet.fixtures :refer :all]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [clojure.tools.logging :as log]
            [aleph.tcp :as tcp]
            [amazonica.aws.sns :as sns]))


(def pubsub (atom nil))
(def server (atom nil))

(defn echo [msg]
  (assoc msg :reply "ok"))

(defn send-reg [client]
  (s/put! client {:command "connected",
                  :id 1,
                  :sent 0,
                  :version 1,
                  :message {:hostname "cliff.local",
                            :id "cliff",
                            :customer-id "cliff"}}))

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
    (reset! pubsub (pubsub/create-pubsub))
    (reset! server (bastion/bastion-server @db @pubsub cmds {:port port}))))

(defn teardown-server []
  (.close @server))

(facts "Bastion channel listens"
  (with-redefs [sns/create-topic (fn [topic] topic)
                sns/publish (fn [_] true)]
       (with-state-changes
         [(before :facts (setup-server 4080 {"echo" echo}))
          (after :facts (teardown-server))]
         (fact "can echo the client"
               (let [client @(client "localhost" 4080)]
                 @(s/put! client {:command "echo", :id 1}) => true
                 @(s/take! client) => {:command "echo", :id 1, :reply "ok", :in_reply_to 1}))
         (fact "registers the client"
               (let [client @(client "localhost" 4080)]
                 @(send-reg client) => true
                 @(s/take! client) => (contains {:in_reply_to 1})
                 (.close client)))
         (with-state-changes
           [(before :facts (do
                             (login-fixtures @db)
                             (environment-fixtures @db)
                             (check-fixtures @db)))]
           (fact "bastions will get existing checks on registration"
                 (let [client @(client "localhost" 4080)]
                   @(send-reg client) => true
                   @(s/take! client) => (contains {:in_reply_to 1})
                   (let [msg @(s/take! client)]
                     msg => (contains {:command "healthcheck"})
                     (:message msg) => (contains {:name "A Nice Check"})))))
         (fact "can send commands to the client"
               (let [client @(client "localhost" 4080)
                     _ @(send-reg client)
                     _ @(s/take! client)
                     defer (pubsub/send-msg @pubsub "cliff" "echo1" {:msg "hello"})
                     msg @(s/take! client)]
                 msg => (contains {:id 1, :message {:msg "hello"}, :version 1})
                 @(s/put! client {:id 2, :in_reply_to 1, :message {:msg "hey"}, :version 1, :sent 0})
                 @defer => (contains {:in_reply_to 1})
                 (.close client))))))
