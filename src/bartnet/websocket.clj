(ns bartnet.websocket
  (:require [bartnet.nsq :as nsq]
            [opsee.middleware.core :refer :all]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [opsee.middleware.auth :as auth]
            [ring.adapter.jetty9 :refer :all]
            [manifold.stream :as s])
  (:import (java.util.concurrent TimeUnit)))

(defn register-ws-connection [msg bus ws]
  (if-and-let [token (get-in msg [:attributes :token])
               auth-resp (auth/authorized? "bearer" token)
               [authorized {login :login}] auth-resp]
              (if authorized
                (do
                  (send! ws (generate-string {:command "authenticate"
                                              :state "ok"
                                              :attributes login}))
                  (nsq/subscribe bus (:customer_id login)
                                 (fn [stream]
                                   (fn [msg]
                                     (try
                                       (send! ws (generate-string msg))
                                       (catch Throwable _ (s/close! stream)))))))
                (send! ws (generate-string {:command "authenticate"
                                            :state "access-denied"})))
              (send! ws (generate-string {:command "authenticate"
                                          :state "bad-token"}))))

(defn ws-handler [scheduler bus]
  {:on-connect (fn [ws]
                 (.scheduleAtFixedRate scheduler #(send! ws (generate-string {:command "heartbeat"})) 10 10 (TimeUnit/SECONDS))
                 (log/info "new websocket connection" ws))
   :on-text    (fn [ws raw]
                 (let [msg (parse-string raw true)]
                   (log/info "ws message" msg)
                   (if (= "authenticate" (:command msg))
                     (register-ws-connection msg bus ws))))
   :on-closed  (fn [ws status-code reason]
                 (log/info "Websocket closing because:" reason))
   :on-error   (fn [ws e]
                 (log/error e "Exception in websocket"))})