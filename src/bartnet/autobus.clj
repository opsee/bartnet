(ns bartnet.autobus
  (:require [manifold.stream :as s]
            [manifold.bus :as bus]
            [bartnet.bus :as bbus]
            [clojure.tools.logging :as log])
  (:import (bartnet.bus MessageClient InternalBus Consumer)))

(defrecord TestingClient [stream]
  MessageClient
  (deliver-to [_ topic msg]
    (log/info "deliver test" msg)
    (s/put! stream msg))
  (session-id [this] nil))

(defn testing-client []
  (TestingClient. (s/stream)))

(defn autobus []
  (let [bus (bus/event-bus)]
    (reify InternalBus
      (publish! [_ topic msg]
        (bus/publish! bus topic msg))
      (subscribe! [_ topic client]
        (let [stream (bus/subscribe bus topic)]
          (s/consume
           (fn [msg]
             (bbus/deliver-to client topic msg))
           stream)
          (reify Consumer
            (stop! [_]
              (s/close! stream))))))))