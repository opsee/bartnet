(ns bartnet.websocket
  (:require [bartnet.autobus :refer :all]
            [bartnet.auth :as auth]
            [bartnet.util :refer :all]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty9 :refer :all])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMap)
           (java.util.concurrent TimeUnit ScheduledThreadPoolExecutor)))

(defn ws-message-client [ws]
  (reify
    MessageClient
    (deliver-to [_ msg]
      (try
        (send! ws (generate-string msg))
        (catch Exception e nil)))))

(defn register-ws-connection [db msg secret bus ws]
  (if-and-let [hmac (get-in msg [:attributes :hmac])
               auth-resp (auth/do-hmac-auth db hmac secret)
               [authorized {login :login}] auth-resp]
    (if authorized
      (let [client (register bus (ws-message-client ws) (:customer_id login))]
        (log/info "authorizing client")
        (send! ws (generate-string
                    (map->Message {:command "authenticate"
                                   :state "ok"
                                   :attributes login})))
        (log/info "sent msg")
        client)
      (send! ws (generate-string
                  (map->Message {:command "authenticate"
                                 :state "unauthenticated"}))))))

(def client-adapters (NonBlockingHashMap.))

(defn ws-handler [scheduler bus db secret]
  {:on-connect (fn [ws]
                 (.scheduleAtFixedRate scheduler #(send! ws (generate-string {:command "heartbeat"})) 10 10 (TimeUnit/SECONDS))
                 (log/info "new websocket connection" ws))
   :on-text (fn [ws raw]
              (let [msg (map->Message (parse-string raw true))
                    client (.get client-adapters ws)]
                (log/info "message" msg)
                (if client
                  (publish bus client msg)
                  (if (= "authenticate" (:command msg))
                    (if-let [ca (register-ws-connection db msg secret bus ws)]
                      (.put client-adapters ws ca))
                    (send! ws (generate-string (assoc msg :state "unauthenticated")))))))
   :on-closed (fn [ws status-code reason]
                (log/info "Websocket closing because:" reason)
                (when-let [client (.remove client-adapters ws)]
                  (close bus client)))
   :on-error (fn [ws e]
               (log/error e "Exception in websocket"))})