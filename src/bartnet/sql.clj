(ns bartnet.sql
  (:import com.mchange.v2.c3p0.ComboPooledDataSource
           (java.sql Date))
  (:require [clj-postgresql.core]
            [clj-postgresql.types :refer [read-pgobject]]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :refer :all]
            [yesql.core :refer [defqueries]]
            [clojure.tools.logging :as log]))

(defmethod read-pgobject :json
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (parse-string val true)))

(defmethod read-pgobject :jsonb
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (parse-string val true)))

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

(extend-protocol jdbc/ISQLValue
  co.opsee.proto.Timestamp
  (sql-value [v]
    (if (and v (:seconds v))
      (Date. (:seconds v)))))

(defqueries "queries.sql")
