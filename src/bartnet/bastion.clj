(ns bartnet.bastion
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [bartnet.sql :as sql]
            [bartnet.autobus :as msg]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [aleph.tcp :as tcp]
            [clojure.tools.logging :as log])
  (:import (bartnet.autobus MessageClient Message)))

(def protocol
  (gloss/compile-frame (gloss/string :utf-8 :delimiters ["\r\n"])))

(defn wrap-duplex-stream [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(io/encode protocol %)
        (s/map #(generate-string %) out))
      s)

    (s/splice
      out
      (s/map #(msg/map->Message (parse-string % true))
             (s/map #(io/decode protocol %) s)))))

(defn send-down-checks [counter customer-id db stream]
  (try
    (let [checks (sql/get-checks-by-customer-id db customer-id)]
      (log/info "checks" checks customer-id)
      (doseq [check checks]
        (do
          (s/put! stream (msg/map->Message {:id (swap! counter inc)
                                            :command "healthcheck"
                                            :customer_id customer-id
                                            :state "update"
                                            :attributes check})))))
    (catch Exception e (log/error e))))

(defn bastion-client [stream]
  (reify
    MessageClient
    (deliver-to [_ msg]
      (s/put! stream msg))))

(defn handler [db msg-bus]
  (fn [stream info]
    (d/loop [client (bastion-client stream)
             client-handle nil]
            (-> (s/take! stream ::none)

                (d/chain
                  (fn [msg]
                    (when-not (= ::none msg)
                      (log/info "recvd" msg))
                    msg)

                  (fn [msg]
                    (when-not (= ::none msg)
                      (when client-handle
                        (msg/publish msg-bus client-handle msg)))
                    msg)

                  (fn [msg]
                    (if (= "connected" (:command msg))
                      (let [client-handle (msg/register msg-bus client (:customer_id msg))]
                        (msg/publish msg-bus client-handle msg)
                        (s/put! stream (msg/map->Message {:id (swap! (:counter client-handle) inc)
                                                          :in_reply_to (:id msg)
                                                          :customer_id (:customer_id msg)
                                                          :state "ok"}))
                        (d/future (send-down-checks (:counter client-handle) (:customer_id msg) db stream))
                        (d/recur client client-handle))
                      (d/recur client client-handle))))

                (d/catch
                  (fn [ex]
                    (log/error ex "Error in bastion handler, shutting down.")
                    (s/close! stream)))))))

(defn bastion-server [db bus opts]
  (tcp/start-server
    (fn [s i] ((handler db bus) (wrap-duplex-stream protocol s) i)) opts))