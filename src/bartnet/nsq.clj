(ns bartnet.nsq
  (:require [cheshire.core :refer :all]
            [bartnet.identifiers :as identifiers]
            [bartnet.bus :as bus])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQProducer NSQConsumer ServerAddress)
           (bartnet.bus InternalBus Consumer)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (com.google.common.collect Sets)
           (java.io IOException)))

(defn nsq-lookup [lookup-addr produce-addr]
  (let [proxy (proxy [DefaultNSQLookup] []
                (lookup [topic]
                  (try
                    (proxy-super lookup topic)
                    (catch IOException _
                      (let [set (Sets/newHashSet)]
                        (.add set (ServerAddress. (:host produce-addr) (:port produce-addr)))
                        set)))))]
    (.addLookupAddress proxy (:host lookup-addr) (:port lookup-addr))
    proxy))

(defn delivery [client topic]
  (proxy [NSQMessageCallback] []
    (message [msg]
      (bus/deliver-to client topic (parse-string (String. (.getMessage msg)) true))
      (.finished msg))))

(defn message-bus [config]
  (let [lookup-addr (:lookup config)
        produce-addr (:producer config)
        lookup (nsq-lookup lookup-addr produce-addr)
        producer (-> (NSQProducer.)
                     (.addAddress (:host produce-addr) (:port produce-addr))
                     (.start))]
    (reify InternalBus
      (publish! [_ topic msg]
        (.produce producer topic (.getBytes (generate-string msg))))

      (subscribe! [_ topic client]
        (let [consumer (->
                        (NSQConsumer. lookup topic (str (identifiers/generate) "#ephemeral") (delivery client topic))
                        (.start))]
          (reify Consumer
            (stop! [_]
              (.shutdown consumer))))))))
