(ns bartnet.aws-rpc
  (:require [bartnet.bastion-router :as router]
            [clojure.tools.logging :as log])
  (:import (io.grpc.transport.netty NettyChannelBuilder NegotiationType)
           (co.opsee.proto ec2Grpc ec2Grpc$ec2BlockingClient)
           (io.grpc ChannelImpl)))

(defprotocol ec2Client
  (shutdown [this])
  (start-instances [this req])
  (stop-instances [this req])
  (reboot-instances [this req]))

(defrecord Realec2Client [^ChannelImpl channel ^ec2Grpc$ec2BlockingClient stub]
  ec2Client
  (start-instances [_ req]
    (log/info "start instances" req)
    (let [resp (.startInstances stub req)]
      (log/info "resp" resp)
      resp))

  (stop-instances [_ req]
    (log/info "stop instances" req)
    (let [resp (.stopInstances stub req)]
      (log/info "resp" resp)
      resp))

  (reboot-instances [_ req]
    (log/info "reboot instances" req)
    (let [resp (.rebootInstances stub req)]
      (log/info "resp" resp)
      resp))

  (shutdown [_]
    (.shutdown channel)))

(defn ec2-client [{:keys [host port]}]
  (let [channel (->
                 (NettyChannelBuilder/forAddress host port)
                 (.negotiationType NegotiationType/PLAINTEXT)
                 .build)
        stub (ec2Grpc/newBlockingStub channel)]
    (->Realec2Client channel stub)))

(defn specific-bastion [customer-id action host port]
  (let [client (ec2-client {:host host :port port})]
         (try
           (action client)
           (catch Exception ex (log/warn ex "Error talking to bastion "))
           (finally (shutdown client)))))
