(ns bartnet.nsq
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQProducer)
           (bartnet.bus MessageBus)))

(defrecord ClientAdapter [client consumers counter permissions])

(defn- client-adapter [client perms]
  (ClientAdapter. client (atom {}) (atom 0) (atom perms)))

(defn message-bus [host port]
  (let [lookup (doto (DefaultNSQLookup.) (.addLookupAddress host port))
        producer (-> (NSQProducer.)
                     (.addAddress host port)
                     (.start))]
    (reify MessageBus

      (publish [this adapter customer_id topic msg]
        )

      (register [this client customer_ids]
        )

      (subscribe [this adapter topics]
        )

      (unsubscribe [this adapter topics]
        )

      (close [this adapter]
        ))))