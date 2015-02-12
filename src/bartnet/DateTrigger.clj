(ns bartnet.DateTrigger
  (:gen-class :implements [org.h2.api.Trigger] :init my-init :prefix Trigger- :constructors {[] []})
  (:import [java.util Date]
           [java.sql Connection]))

(defn Trigger-my-init [] [[]])

(defn Trigger-init
  [^bartnet.DateTrigger this ^Connection conn ^String schemaName ^String triggerName ^String tableName ^Boolean before ^Integer type]
  nil)

(defn Trigger-fire
  [^bartnet.DateTrigger this ^Connection conn _ new-row]
  (aset new-row (- (.length new-row)) (new Date)))

(defn Trigger-close [^bartnet.DateTrigger this] nil)
(defn Trigger-remove [^bartnet.DateTrigger this] nil)