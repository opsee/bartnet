(ns bartnet.db-cmd
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [bartnet.sql :as sql])
  (:import  [liquibase Liquibase]
            [liquibase.resource ClassLoaderResourceAccessor]
            [liquibase.database.jvm JdbcConnection]
            [java.io OutputStreamWriter]
            [java.nio.charset Charset]
            [liquibase.logging LogFactory LogLevel Logger]))

(def rollback-options
  [["-t" "--tag TAG" "the tag we should rollback migrations to"]
   ["-r" "--revision REVISION" "the changeset revision to rollback to"
    :parse-fn #(Integer/parseInt %)]])

(def migrate-options
  [["-n" "--dry-run" "output the DDL to stdout, don't run it"
    :default false
    :parse-fn #(Boolean/valueOf %)]
   ["-c" "--count COUNT" "only apply the next N change sets"
    :parse-fn #(Integer/parseInt %)]
   ["-i" "--include CONTEXT" "include change sets from the given context"]])

(def drop-all-options [])

(defn usage [cmd options-summary]
  (->> ["This is the db command for bartnet."
        ""
        (str "usage bartnet db " cmd " [options] <config file>")
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn rollback-usage [options-summary]
  (usage "rollback" options-summary))

(defn migrate-usage [options-summary]
  (usage "migrate" options-summary))

(defn drop-all-usage [options-summary]
  (usage "drop-all" options-summary))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn get-connection [{:keys [connection datasource]}]
  (cond
    connection connection
    datasource (.getConnection datasource)))

(defn drop-all [pool options]
  (with-open [conn (new JdbcConnection (get-connection pool))]
    (let [liquibase (new Liquibase "migrations.xml" (new ClassLoaderResourceAccessor) conn)]
      (.dropAll liquibase))))

(defn migrate-db [pool options]
  (with-open [conn (new JdbcConnection (get-connection pool))]
    (let [liquibase (new Liquibase "migrations.xml" (new ClassLoaderResourceAccessor) conn)]
      (if (:silent options)
        (.setLogLevel (LogFactory/getLogger) LogLevel/OFF))
      (if (:drop-all options)
        (.dropAll liquibase))
      (if-let [count (:count options)]
        (if (:dry-run options)
          (.update liquibase count "" (new OutputStreamWriter System/out (Charset/forName "UTF-8")))
          (.update liquibase count ""))
        (if (:dry-run options)
          (.update liquibase "" (new OutputStreamWriter System/out (Charset/forName "UTF-8")))
          (.update liquibase ""))))))

(defn rollback-db [pool options]
  (with-open [conn (new JdbcConnection (get-connection pool))]
    (let [liquibase (new Liquibase "migrations.xml" (new ClassLoaderResourceAccessor) conn)]
      (if-let [tag (:tag options)]
        (.rollback liquibase tag "")
        (.rollback liquibase (:revision options) "")))))

(defn migrate-cmd [args cmd options-summary usage-cmd]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args options-summary)]
    (cond
      (:help options) (exit 0 (usage-cmd summary))
      (not= (count arguments) 1) (exit 1 (usage-cmd summary))
      errors (exit 1 (error-msg errors)))
    (let [config (parse-string (slurp (first arguments)) true)
          pool (sql/pool (:db-spec config))]
      (cmd pool options))))

(defn db-cmd [args]
  (case (first args)
    "migrate" (migrate-cmd (rest args) migrate-db migrate-options migrate-usage)
    "rollback" (migrate-cmd (rest args) rollback-db rollback-options rollback-usage)
    "drop-all" (migrate-cmd (rest args) drop-all drop-all-options drop-all-usage)))

