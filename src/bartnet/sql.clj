(ns bartnet.sql
  (:import com.mchange.v2.c3p0.ComboPooledDataSource)
  (:require [yesql.core :refer [defqueries]]
            [clojure.tools.logging :as log]))

(defn build-jdbc-url [config]
  (str
    "jdbc:"
    (:subprotocol config)
    ":"
    (if-let [host (:host config)]
      (str "//" host (if-let [port (:port config)] (str ":" port)) "/"))
    (:subname config)))

(defn pool
  [config]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass (:classname config))
               (.setJdbcUrl (build-jdbc-url config))
               (.setUser (:user config))
               (.setPassword (:password config))
               (.setMaxPoolSize (:max-conns config))
               (.setMinPoolSize (:min-conns config))
               (.setInitialPoolSize (:init-conns config)))]
    {:datasource cpds}))

(defqueries "queries.sql")