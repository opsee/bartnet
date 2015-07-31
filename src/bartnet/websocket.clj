(ns bartnet.websocket
  (:require [bartnet.bus :as bus]
            [bartnet.auth :as auth]
            [bartnet.util :refer :all]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [ring.adapter.jetty9 :refer :all]
            [bartnet.bus :as bus])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMap)
           (java.util.concurrent TimeUnit)
           (bartnet.bus MessageClient)
           (java.util Date)))


(def client-adapters (NonBlockingHashMap.))
(def customer-ids (NonBlockingHashMap.))

(defn ws-message-client [ws]
  (reify
    MessageClient
    (deliver-to [_ topic msg]
      (try
        (let [body (parse-string (:body msg) true)]
          (send! ws (generate-string {:command topic
                                      :sent    (Date.)
                                      :attributes (:attributes body)
                                      :state (:state body)
                                      :instance_id (:instance_id body)
                                      :service topic})))
        (catch Exception e nil)))
    (session-id [this] nil)))

(defn register-ws-connection [db msg secret bus ws]
  (if-and-let [hmac (get-in msg [:attributes :hmac])
               auth-resp (auth/do-hmac-auth db hmac secret)
               [authorized {login :login}] auth-resp]
    (if authorized
      (let [client (bus/register bus (ws-message-client ws) (:customer_id login))]
        (log/info "authorizing client")
        (.put customer-ids ws (:customer_id login))
        (send! ws (generate-string {:command "authenticate"
                                    :state "ok"
                                    :attributes login}))
        (log/info "sent msg")
        client)
      (send! ws (generate-string {:command "authenticate"
                                  :state "unauthenticated"})))))



(defn bus-msg [in]
  (bus/make-msg "Websocket" in))

(defn ws-handler [scheduler bus db secret]
  {:on-connect (fn [ws]
                 (.scheduleAtFixedRate scheduler #(send! ws (generate-string {:command "heartbeat"})) 10 10 (TimeUnit/SECONDS))
                 (log/info "new websocket connection" ws))
   :on-text (fn [ws raw]
              (let [msg (parse-string raw true)
                    client (.get client-adapters ws)
                    customer-id (.get customer-ids ws)]
                (log/info "message" msg)
                (if client
                  (bus/publish bus client customer-id "websocket-command" (bus-msg msg))
                  (if (= "authenticate" (:command msg))
                    (if-let [ca (register-ws-connection db msg secret bus ws)]
                      (.put client-adapters ws ca))
                    (send! ws (generate-string (assoc msg :state "unauthenticated")))))))
   :on-closed (fn [ws status-code reason]
                (log/info "Websocket closing because:" reason)
                (when-let [client (.remove client-adapters ws)]
                  (bus/close bus client)))
   :on-error (fn [ws e]
               (log/error e "Exception in websocket"))})