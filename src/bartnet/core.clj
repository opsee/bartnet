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
            [bartnet.bus :as bus]
            [bartnet.autobus :as autobus]
            [bartnet.websocket :as websocket]
            [bartnet.sql :as sql]
            [bartnet.util :refer :all]
            [ring.adapter.jetty9 :refer :all]
            [cheshire.core :refer :all]
            [clj-disco.core :as disco])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor]
           [io.aleph.dirigiste Executors]))

(def ^{:private true} bastion-server (atom nil))

(def ^{:private true} ws-server (atom nil))

(defn- start-bastion-server [db bus options]
  (if-not @bastion-server (reset! bastion-server (bastion/bastion-server db bus options))))

(defn- start-ws-server [executor scheduler db bus config]
  (if-not @ws-server
    (reset! ws-server
            (run-jetty
              (api/handler executor bus db config)
              (assoc (:server config)
                :websockets {"/stream" (websocket/ws-handler scheduler bus db (:secret config))})))))

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
        bus (bus/message-bus (autobus/autobus))
        executor (Executors/utilizationExecutor (:thread-util config) (:max-threads config))
        scheduler (ScheduledThreadPoolExecutor. 10)
        redis-conn (disco/get-service-endpoint "bartnet-redis")]
    (if redis-conn
      (instance/create-redis-store bus redis-conn)
      ;; XXX: Maybe we want to hard fail instead or make this configurable?
      (instance/create-memory-store bus))
    (start-bastion-server db bus (:bastion-server config))
    (start-ws-server executor scheduler db bus config)))

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

