(ns bartnet.rpc
  (:require [bartnet.protobuilder :as proto])
  (:import (io.grpc.transport.netty NettyChannelBuilder NegotiationType)
           (co.opsee CheckerGrpc TestCheckRequest CheckerGrpc$CheckerBlockingStub)))

(defrecord CheckTesterClient [channel ^CheckerGrpc$CheckerBlockingStub stub])

(defn check-tester-client [{:keys [host port]}]
  (let [channel (->
                  (NettyChannelBuilder/forAddress host port)
                  (.negotiationType NegotiationType/PLAINTEXT)
                  .build)
        stub (CheckerGrpc/newBlockingStub channel)]
    (->CheckTesterClient channel stub)))

(defn test-check [^CheckTesterClient client check]
  (let [req (proto/hash->proto TestCheckRequest check)]
    (proto/proto->hash (.testCheck (:stub client) req))))