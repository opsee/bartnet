(ns bartnet.nsq
  (:require [cheshire.core :refer :all]
            [bartnet.identifiers :as identifiers]
            [bartnet.bus :as bus])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQProducer NSQConsumer)
           (bartnet.bus InternalBus Consumer)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)))

(defn delivery [client topic]
  (proxy [NSQMessageCallback] []
    (message [msg]
      (bus/deliver-to client topic (parse-string (String. (.getMessage msg)) true))
      (.finished msg))))

(defn message-bus [host port]
  (let [lookup (doto (DefaultNSQLookup.) (.addLookupAddress host port))
        producer (-> (NSQProducer.)
                     (.addAddress host port)
                     (.start))]
    (reify InternalBus
      (publish! [_ topic msg]
        (.produce producer topic (.getBytes (generate-string msg))))

      (subscribe! [_ topic client]
        (let [consumer (->
                         (NSQConsumer. lookup topic (identifiers/generate) (delivery client topic))
                         (.start))]
          (reify Consumer
            (stop! [_]
              (.shutdown consumer))))))))
