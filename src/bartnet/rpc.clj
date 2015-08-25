(ns bartnet.rpc
  (:require [bartnet.protobuilder :as proto]
            [clojure.tools.logging :as log])
  (:import (io.grpc.transport.netty NettyChannelBuilder NegotiationType)
           (co.opsee.proto CheckerGrpc TestCheckRequest CheckerGrpc$CheckerBlockingStub)))

(defrecord CheckTesterClient [channel ^CheckerGrpc$CheckerBlockingStub stub])

(defn check-tester-client [{:keys [host port]}]
  (let [channel (->
                  (NettyChannelBuilder/forAddress host port)
                  (.negotiationType NegotiationType/PLAINTEXT)
                  .build)
        stub (CheckerGrpc/newBlockingStub channel)]
    (->CheckTesterClient channel stub)))

(defn test-check [^CheckTesterClient client check]
  (log/info "check" check)
  ;(let [req (proto/hash->proto TestCheckRequest check)]
    (let [resp (.testCheck (:stub client) check)]
      (log/info "resp" resp)
      resp))

(defn dbcheck->protocheck [check]
  (merge check {:check_spec {}}))

(defn create-check [^CheckTesterClient client check]
  )