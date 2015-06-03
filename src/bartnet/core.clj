(ns bartnet.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [bartnet.api :as api]
            [bartnet.auth :as auth]
            [bartnet.identifiers :as identifiers]
            [bartnet.bastion :as bastion]
            [bartnet.instance :as instance]
            [bartnet.launch :as launch]
            [bartnet.db-cmd :as db-cmd]
            [bartnet.upload-cmd :as upload-cmd]
            [bartnet.pubsub :refer :all]
            [bartnet.sql :as sql]
            [bartnet.util :refer [if-and-let]]
            [manifold.stream :as s]
            [ring.adapter.jetty9 :refer :all]
            [cheshire.core :refer :all])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMap]
           [java.util.concurrent CopyOnWriteArraySet ExecutorService]
           [io.aleph.dirigiste Executors]
           (bartnet.instance MemoryInstanceStore)))

(defn discovery-handler [msg]
  (do
    (log/info "discovery " msg)
    (assoc msg :reply "ok")))

(def bastion-handlers
  {
   "discovery" discovery-handler
   })

(defprotocol Subscriptor
  (subscribe! [this topic])
  (unsubscribe! [this topic])
  (subscribed? [this topic]))

(defrecord ClientConnector [id customer-id login ^CopyOnWriteArraySet topics]
  Subscriptor
  (subscribe! [_ topic]
    (.add topics topic))
  (unsubscribe! [_ topic]
    (.remove topics topic))
  (subscribed? [_ topic]
    (.contains topics topic)))

(defn create-connector [login]
  (ClientConnector. (identifiers/generate) (:customer_id login) login (CopyOnWriteArraySet.)))

(defn register-ws-connection [clients db hmac secret ws]
  (if-and-let [auth-resp (auth/do-hmac-auth db hmac secret)
               [authorized {login :login}] auth-resp
               client (create-connector login)]
      (when authorized
        (do
          (.put clients ws client)
          client))))

(defn consume-bastion [ws client]
  (fn [msg]
    (log/info "publish" msg)
    (let [cmd (:command msg)]
      (when (subscribed? client cmd)
        (send! ws (generate-string msg))))))

(defn process-authorized-command [client ws pubsub ^ExecutorService executor msg]
    (case (:cmd msg)
      "subscribe" (do
                    (subscribe! client (:topic msg))
                    (send! ws (generate-string {:reply "ok"})))
      "launch" (let [stream (launch/launch-bastions executor (:customer-id client) msg {:owner-id "933693344490" :tag "stable"})]
                 (log/info "launched")
                 (loop [event @(s/take! stream)]
                   (if-not (= event :exit)
                     (do
                       (send! ws (generate-string event))
                       (recur @(s/take! stream))))))
      "echo" (send! ws (generate-string msg))))

(defn ws-handler [executor pubsub clients db secret]
  {:on-connect (fn [_])
   :on-text    (fn [ws msg]
                 (let [parsed-msg (parse-string msg true)
                       client (.get clients ws)]
                   (log/info "ws msg", parsed-msg)
                   (if client
                     (process-authorized-command client ws pubsub executor parsed-msg)
                     (if-and-let [hmac (:hmac parsed-msg)
                                  client (register-ws-connection clients db hmac secret ws)]
                                 (let [stream (register-ws-client pubsub client)]
                                   (log/info "authorized ws client")
                                   (s/consume (consume-bastion ws client) stream)
                                   (process-authorized-command client ws pubsub executor parsed-msg))
                                 (send! ws (generate-string {:error "unauthorized"}))))))
   :on-closed  (fn [ws status-code reason]
                 (if-let [client (.remove clients ws)]
                   (do
                     (s/close! (:client-server client))
                     (s/close! (:server-client client)))))
   :on-error   (fn [ws e])})

(def ^{:private true} bastion-server (atom nil))

(def ^{:private true} ws-server (atom nil))

(defn- start-bastion-server [db pubsub handlers options]
  (if-not @bastion-server (reset! bastion-server (bastion/bastion-server db pubsub handlers options))))

(defn- start-ws-server [executor db pubsub config clients]
  (if-not @ws-server
    (reset! ws-server
            (run-jetty
              (api/handler pubsub db config)
              (assoc (:server config)
                :websockets {"/stream" (ws-handler executor pubsub clients db (:secret config))})))))

(defn stop-server []
  (do
    (if @bastion-server (do
                          (.close @bastion-server)
                          (reset! bastion-server nil)))
    (if @ws-server (do
                     (.stop @ws-server)
                     (reset! ws-server nil)))))

(defn start-server [args]
  (let [config (parse-string (slurp (first args)) true)
        db (sql/pool (:db-spec config))
        pubsub (create-pubsub)
        executor (Executors/utilizationExecutor (:thread-util config) (:max-threads config))
        clients (NonBlockingHashMap.)]
    (start-bastion-server db pubsub bastion-handlers (:bastion-server config))
    (start-ws-server executor db pubsub config clients)
    (instance/create-memory-store)))

(.addShutdownHook
  (Runtime/getRuntime)
  (Thread. (fn []
             (println "Shutting down...")
             (stop-server))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (db-cmd/db-cmd subargs)
      "upload" (upload-cmd/upload subargs))))

