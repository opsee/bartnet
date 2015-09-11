(ns bartnet.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [clojure.tools.cli :refer [parse-opts]]
            [bartnet.api :as api]
            [bartnet.instance :as instance]
            [bartnet.db-cmd :as db-cmd]
            [bartnet.upload-cmd :as upload-cmd]
            [bartnet.bus :as bus]
            [bartnet.autobus :as autobus]
            [bartnet.nsq :as nsq]
            [bartnet.websocket :as websocket]
            [bartnet.sql :as sql]
            [bartnet.util :refer :all]
            [ring.adapter.jetty9 :refer :all]
            [cheshire.core :refer :all]
            [clj-disco.core :as disco]
            [bartnet.auth :as auth]
            [bartnet.util :as util])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor]
           [io.aleph.dirigiste Executors]))

(def ^{:private true} ws-server (atom nil))

(defn- start-ws-server [executor scheduler db bus config]
  (if-not @ws-server
    (do
      (auth/set-secret! (util/slurp-bytes (:secret config)))
      (reset! ws-server
              (run-jetty
               (api/handler executor scheduler bus db config)
               (assoc (:server config)
                      :websockets {"/stream" (websocket/ws-handler scheduler bus)}))))))

(defn stop-server []
  (do
    (if @ws-server (do
                     (.stop @ws-server)
                     (reset! ws-server nil)))))

(defn start-server [args]
  (let [config (parse-string (slurp (last args)) true)
        db (sql/pool (:db-spec config))
        bus (bus/message-bus (if (:nsq config)
                               (nsq/message-bus (:nsq config))
                               (autobus/autobus)))
        executor (Executors/utilizationExecutor (:thread-util config) (:max-threads config))
        scheduler (ScheduledThreadPoolExecutor. 10)
        redis-conn (disco/get-service-endpoint "bartnet-redis")]
    (if redis-conn
      (instance/create-redis-store bus redis-conn)
      ;; XXX: Maybe we want to hard fail instead or make this configurable?
      (instance/create-memory-store bus))
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

