(ns bartnet.bastion
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [bartnet.sql :as sql]
            [bartnet.bus :as bus]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [aleph.tcp :as tcp]
            [clojure.tools.logging :as log])
  (:import (bartnet.bus MessageClient)
           (java.util Date)))

(defrecord BastionMessage [^Integer id
                           ^Integer version
                           ^Integer reply_to
                           ^Date sent
                           ^String topic
                           ^String type
                           ^String body])

(def protocol
  (gloss/compile-frame (gloss/delimited-frame ["\r\n"] (gloss/string :utf-8))))

(defn process-outgoing-msg [counter]
  (fn [msg]
    (let [outgoing (if-not (:id msg)
                     (assoc msg :id (swap! counter inc))
                     msg)]
      (generate-string outgoing))))

(defn wrap-duplex-stream [protocol s]
  (let [out (s/stream)
        counter (atom 0)]
    (s/connect
      (s/map #(io/encode protocol %)
        (s/map (process-outgoing-msg counter) out))
      s)

    (s/splice
      out
      (s/map #(map->BastionMessage (parse-string % true))
        (io/decode-stream s protocol)))))

(defn send-down-checks [customer-id db stream]
  (try
    (let [checks (sql/get-checks-by-customer-id db customer-id)]
      (log/info "checks" checks customer-id)
      (doseq [check checks]
        (do
          (s/put! stream (bus/make-msg "CheckCommand" {:action "create_check"
                                                       :parameters check})))))
    (catch Exception e (log/error "Error sending checks: " e))))

(defn bastion-client [instance stream]
  (reify
    MessageClient
    (deliver-to [_ topic msg]
      (s/put! stream msg))
    (session-id [_]
      @instance)))

(defn handler [db msg-bus]
  (let [instance (atom nil)]
    (fn [stream info]
      (d/loop [client (bastion-client instance stream)
               client-handle nil
               customer_id nil]
              (-> (s/take! stream ::none)

                  (d/chain
                    (fn [msg]
                      (when-not (= ::none msg)
                        (log/info "recvd" msg))
                      msg)

                    (fn [msg]
                      (when-not (= ::none msg)
                        (when client-handle
                          (bus/publish msg-bus client-handle customer_id (:topic msg) msg)))
                      msg)

                    (fn [msg]
                      (if-not (= ::none msg)
                        (if (= "Connected" (:type msg))
                          (let [body (parse-string (:body msg) true)
                                customer_id (:customer_id body)
                                client-handle (bus/register msg-bus client customer_id)]
                            (bus/publish msg-bus client-handle customer_id "connected" msg)
                            (s/put! stream (map->BastionMessage {:id (bus/id-inc client-handle)
                                                                 :reply_to (:id msg)
                                                                 :type "ConnectedResponse"
                                                                 :body "true"}))
                            (d/future (send-down-checks customer_id db stream))
                            (reset! instance (:instance body))
                            (d/recur client client-handle customer_id))
                          (d/recur client client-handle customer_id))
                        (do
                          (log/info "connection closed")
                          (s/close! stream)))))

                  (d/catch
                    (fn [ex]
                      (log/error ex "Error in bastion handler, shutting down.")
                      (s/close! stream))))))))

(defn bastion-server [db bus opts]
  (tcp/start-server
    (fn [s i] ((handler db bus) (wrap-duplex-stream protocol s) i)) opts))