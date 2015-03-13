(ns bartnet.bastion
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [gloss.core :as gloss]
            [cheshire.core :refer :all]
            [gloss.io :as io]
            [aleph.tcp :as tcp]
            [bartnet.pubsub :refer :all]
            [clojure.tools.logging :as log])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMapLong]))

(defn get-and-inc [counter]
  (let [i @counter]
    (swap! counter inc)
    i))

(defrecord Connection [id customer-id counter stream replies]
  SendableConn
  (send-and-recv [_ cmd msg]
    (let [i (get-and-inc counter)
          out {:version 1, :id i, :command cmd, :message msg}
          defer (d/deferred)]
      (.put replies i defer)
      @(s/put! stream out)
      defer)))

(def protocol
  (gloss/compile-frame (gloss/string :utf-8 :delimiters ["\r\n"])))

(defn wrap-duplex-stream [protocol s]
  (let [wrapped (let [out (s/stream)]
    (s/connect
      (s/map #(io/encode protocol %)
        (s/map #(generate-string %) out))
      s)

    (s/splice
      out
      (s/map #(parse-string % true)
             (s/map #(io/decode protocol %) s))))]
    (log/info (str "wrapped " wrapped))
    wrapped))

(defn consume-commands [connection]
  (fn [msg]
    (send-and-recv connection (:cmd msg) (:body msg))))

(defn register-connection [pubsub counter replies stream msg]
  (let [id (get-in msg [:message :id])
        customer-id (get-in msg [:message :customer-id])
        connection (Connection. id customer-id counter stream replies)
        stream (register-bastion pubsub connection msg)]
    (s/consume (consume-commands connection) stream)
    {:in_reply_to (:id msg),
     :id (get-and-inc counter),
     :version 1,
     :command (:command msg),
     :message "ok"}))

(defn handler [pubsub counter replies cmds]
  (fn [stream info]
    (do
      (log/info (str "new client: " info))
      (d/loop []
              (-> (s/take! stream ::none)
                  (d/chain
                    (fn [msg]
                      (log/info msg)
                      msg)

                    (fn [reply-msg]
                      (if (:in_reply_to reply-msg)
                        (do
                          (if-let [defer (.remove replies (long (:in_reply_to reply-msg)))]
                            (do
                              (d/success! defer reply-msg)
                              ::none)))
                        reply-msg))

                    (fn [msg]
                      (let [id (:id msg)]
                        (if (= ::none msg)
                          ::none
                          (if-let [cmd (:command msg)]
                            (if (= cmd "connected")
                              (d/future (register-connection pubsub counter replies stream msg))
                              (d/future (assoc
                                          ((get cmds cmd) msg)
                                          :in_reply_to id)))))))

                    (fn [reply]
                      (s/put! stream reply))

                    (fn [result]
                      (when result
                        (d/recur))))

                  (d/catch
                    (fn [ex]
                      (.printStackTrace ex)
                      (s/close! stream))))))))

(defn bastion-server [pubsub cmds opts]
    (tcp/start-server
        (fn [s i] ((handler pubsub (atom 0) (NonBlockingHashMapLong.) cmds) (wrap-duplex-stream protocol s) i)) opts))