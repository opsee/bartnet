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
            [bartnet.autobus :as msg]
            [bartnet.sql :as sql]
            [bartnet.util :refer :all]
            [manifold.stream :as s]
            [ring.adapter.jetty9 :refer :all]
            [cheshire.core :refer :all])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMap]
           [java.util.concurrent CopyOnWriteArraySet ExecutorService]
           [io.aleph.dirigiste Executors]
           (bartnet.instance MemoryInstanceStore)
           (bartnet.autobus MessageClient)))

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
                (let [client (msg/register bus (ws-message-client ws) (:customer_id login))]
                  (log/info "authorizing client")
                  (send! ws (generate-string
                              (msg/map->Message {:command "authenticate"
                                                 :state "ok"
                                                 :attributes login})))
                  (log/info "sent msg")
                  client)
                (send! ws (generate-string
                            (msg/map->Message {:command "authenticate"
                                               :state "unauthenticated"}))))))

(def client-adapters (NonBlockingHashMap.))

(defn ws-handler [bus db secret]
  {:on-connect (fn [ws]
                 (log/info "new websocket connection" ws))
   :on-text    (fn [ws raw]
                 (let [msg (msg/map->Message (parse-string raw true))
                       client (.get client-adapters ws)]
                   (log/info "message" msg)
                   (if client
                     (msg/publish bus client msg)
                     (if (= "authenticate" (:command msg))
                       (if-let [ca (register-ws-connection db msg secret bus ws)]
                         (.put client-adapters ws ca))
                       (send! ws (generate-string (assoc msg :state "unauthenticated")))))))
   :on-closed  (fn [ws status-code reason]
                 (log/info "Websocket closing because:" reason)
                 (when-let [client (.remove client-adapters ws)]
                   (msg/close bus client)))
   :on-error   (fn [ws e]
                 (log/error e "Exception in websocket"))})

(def ^{:private true} bastion-server (atom nil))

(def ^{:private true} ws-server (atom nil))

(defn- start-bastion-server [db bus options]
  (if-not @bastion-server (reset! bastion-server (bastion/bastion-server db bus options))))

(defn- start-ws-server [executor db bus config]
  (if-not @ws-server
    (reset! ws-server
            (run-jetty
              (api/handler executor bus db config)
              (assoc (:server config)
                :websockets {"/stream" (ws-handler bus db (:secret config))})))))

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
        bus (msg/message-bus)
        executor (Executors/utilizationExecutor (:thread-util config) (:max-threads config))]
    (start-bastion-server db bus (:bastion-server config))
    (start-ws-server executor db bus config)
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

