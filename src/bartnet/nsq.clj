(ns bartnet.nsq
  (:require [cheshire.core :refer :all]
            [bartnet.identifiers :as identifiers]
            [bartnet.bus :as bus])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQProducer NSQConsumer)
           (bartnet.bus InternalBus Consumer)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (org.apache.http.impl.client HttpClients CloseableHttpClient)
           (org.apache.http.client.methods HttpGet)))

(defn delivery [client topic]
  (proxy [NSQMessageCallback] []
    (message [msg]
      (bus/deliver-to client topic (parse-string (String. (.getMessage msg)) true))
      (.finished msg))))

(defn- create-topic [^CloseableHttpClient http-client host port topic]
  )

(defn- does-topic-exist [^CloseableHttpClient http-client host port topic]
  (let [http-get (HttpGet. (str "http://" host ":" port "/lookup?topic=" topic))
        response (.execute http-client http-get)]
    (try
      (let [status (-> response
                       .getStatusLine
                       .getStatusCode)]
        (and (> status 199)
             (< status 300)))
      (finally (.close response)))))

(defn- create-topic-if-necessary [^CloseableHttpClient http-client config topic]
  (when-not (does-topic-exist http-client
                              (get-in config [:lookup :host])
                              (get-in config [:lookup :port]) topic)
    (create-topic http-client
                  (get-in config [:producer :host])
                  (get-in config [:producer :host]) topic)))


(defn message-bus [config]
  (let [lookup-addr (:lookup config)
        produce-addr (:producer config)
        http-client (HttpClients/createDefault)
        lookup (doto (DefaultNSQLookup.) (.addLookupAddress (:host lookup-addr) (:port lookup-addr)))
        producer (-> (NSQProducer.)
                     (.addAddress (:host produce-addr) (:port produce-addr))
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
