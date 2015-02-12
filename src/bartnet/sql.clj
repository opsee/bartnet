(ns bartnet.sql
  (:import com.mchange.v2.c3p0.ComboPooledDataSource)
  (:require [yesql.core :refer [defqueries]]
            [clojure.tools.logging :as log]))

(defn pool
  [config]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass (:classname config))
               (.setJdbcUrl (str "jdbc:" (:subprotocol config) ":" (:subname config)))
               (.setUser (:user config))
               (.setPassword (:password config))
               (.setMaxPoolSize (:max-conns config))
               (.setMinPoolSize (:min-conns config))
               (.setInitialPoolSize (:init-conns config)))]
    {:datasource cpds}))

(defqueries "queries.sql")