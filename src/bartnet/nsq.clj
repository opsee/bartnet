(ns bartnet.nsq
  (:require [manifold.bus :as bus]
            [manifold.stream :as s]
            [cheshire.core :refer :all])
  (:import (com.github.brainlag.nsq NSQConsumer NSQProducer ServerAddress)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (com.google.common.collect Sets)
           (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (java.io IOException)
           (java.util UUID)))

(def topic "_.launch")

(defn ensure-int [val]
  (if (= String (class val))
    (try
      (Integer/parseInt val)
      (catch Exception _ 0))
    val))

(defn nsq-lookup [lookup-addr produce-addr]
  (let [proxy (proxy [DefaultNSQLookup] []
                (lookup [topic]
                  (try
                    (proxy-super lookup topic)
                    (catch IOException _
                      (let [set (Sets/newHashSet)]
                        (.add set (ServerAddress. (:host produce-addr) (ensure-int (:port produce-addr))))
                        set)))))]
    (.addLookupAddress proxy (:host lookup-addr) (ensure-int (:port lookup-addr)))
    proxy))

(defn nsq-handler [bus]
  (reify NSQMessageCallback
    (message [_ msg]
      (let [body (parse-string (String. (.getMessage msg)) true)]
        (bus/publish! bus (:customer_id body) body)
        (.finished msg)))))

(defn launch-consumer [nsq-config bus]
  (let [lookup (nsq-lookup (:lookup nsq-config) (:produce nsq-config))
        channel-id (str (UUID/randomUUID) "#ephemeral")
        handler (nsq-handler bus)]
    (doto (NSQConsumer. lookup topic channel-id handler)
          (.start))))

(defn subscribe [bus customer-id callback]
  (s/consume callback (bus/subscribe bus customer-id)))

(defn launch-producer [nsq-config]
  (let [produce-addr (:produce nsq-config)]
    (-> (NSQProducer.)
        (.addAddress (:host produce-addr) (ensure-int (:port produce-addr)))
        (.start))))

(defn publish! [producer msg]
  (.produce producer topic (.getBytes (generate-string msg))))