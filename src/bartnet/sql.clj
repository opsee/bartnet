(ns bartnet.sql
  (:import com.mchange.v2.c3p0.ComboPooledDataSource)
  (:require [yesql.core :refer [defqueries]] [clojure.tools.logging :as log]))

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

(defn pooled-queries
  "Partials the connection pool as the first argument and stuffs all the queries into the calling namespace"
  [db-spec]
  (binding [*ns* (find-ns 'bartnet.sql)]
    (def pooled (pool db-spec))
      (doall
        (for [var (defqueries "queries.sql")]
          (let [new-func (partial @var pooled)]
            (eval `(def ~(:name (meta var)) ~new-func)))))))

(defn unpooled-queries
  [db-spec]
  (binding [*ns* (find-ns 'bartnet.sql)]
    (doall
      (for [var (defqueries "queries.sql")]
        (let [new-func (partial @var db-spec)]
          (eval `(def ~(:name (meta var)) ~new-func)))))))