(ns bartnet.rpc
  (:require [bartnet.bastion-router :as router]
            [clojure.tools.logging :as log])
  (:import (io.grpc.transport.netty NettyChannelBuilder NegotiationType)
           (co.opsee.proto CheckerGrpc CheckerGrpc$CheckerBlockingClient)
           (io.grpc ChannelImpl)))

(defprotocol CheckerClient
  (shutdown [this])
  (test-check [this req])
  (create-check [this req])
  (update-check [this req])
  (retrieve-check [this req])
  (delete-check [this req]))

(defrecord RealCheckerClient [^ChannelImpl channel ^CheckerGrpc$CheckerBlockingClient stub]
  CheckerClient
  (test-check [_ check]
    (log/info "test check" check)
    (let [resp (.testCheck stub check)]
      (log/info "resp" resp)
      resp))

  (create-check [_ check]
    (log/info "create check" check)
    (let [resp (.createCheck stub check)]
      (log/info "resp" resp)
      resp))

  (update-check [_ check]
    (log/info "update check" check)
    (let [resp (.updateCheck stub check)]
      (log/info "resp" resp)
      resp))

  (retrieve-check [_ check]
    (log/info "retrieve check" check)
    (let [resp (.retrieveCheck stub check)]
      (log/info "resp" resp)
      resp))

  (delete-check [_ check]
    (log/info "delete check" check)
    (let [resp (.deleteCheck stub check)]
      (log/info "resp" resp)
      resp))

  (shutdown [_]
    (.shutdown channel)))

(defn checker-client [{:keys [host port]}]
  (let [channel (->
                 (NettyChannelBuilder/forAddress host port)
                 (.negotiationType NegotiationType/PLAINTEXT)
                 .build)
        stub (CheckerGrpc/newBlockingStub channel)]
    (->RealCheckerClient channel stub)))

(defn try-bastions [customer-id action]
  (loop [bastions (router/get-customer-bastions customer-id)]
    (let [bastion (first bastions)]
      (if-let [addr (router/get-service customer-id bastion "checker")]
        (let [client (checker-client addr)]
          (if-let [result (try
                            (action client)
                            (catch Exception ex (if (rest bastions)
                                                  nil
                                                  (throw ex)))
                            (finally (shutdown client)))]
            result
            (recur (rest bastions))))))))

(defn all-bastions [customer-id action]
  (doall
   (for [bastion (router/get-customer-bastions customer-id)]
     (if-let [addr (router/get-service customer-id bastion "checker")]
       (let [client (checker-client addr)]
         (try
           (action client)
           (catch Exception ex (log/warn ex "Error talking to bastion " bastion))
           (finally (shutdown client))))))))