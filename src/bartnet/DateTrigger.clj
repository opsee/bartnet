(ns bartnet.DateTrigger
  (:gen-class :implements [org.h2.api.Trigger] :init my-init :prefix Trigger- :constructors {[] []})
  (:import [java.util Date Arrays]
           [java.sql Connection Timestamp])
  (:require [clojure.tools.logging :as log]))

(defn Trigger-my-init [] [[]])

(defn Trigger-init
  [^bartnet.DateTrigger this ^Connection conn ^String schemaName ^String triggerName ^String tableName ^Boolean before ^Integer type]
  nil)

(defn Trigger-fire
  [^bartnet.DateTrigger this ^Connection conn _ new-row]
  (do
    (let [index (count (take-while #(not (instance? Timestamp %)) new-row))]
      (aset new-row index (new Date)))))

(defn Trigger-close [^bartnet.DateTrigger this] nil)
(defn Trigger-remove [^bartnet.DateTrigger this] nil)