(ns bartnet.results
  (:require [cheshire.core :refer :all]
            [opsee.middleware.auth :refer [login->token]]
            [clojure.string :refer :all]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]))

(def results-addr (atom nil))

; customer_id =
; if id

(defn q [s] (str "\"" s "\""))

(defn gen-query [{id :id
                  check-id :check_id
                  customer-id :customer_id
                  type :type}]
  (str "customer_id = " (q customer-id)
       (if id
         (str " and host = " (q id)))
       (if check-id
         (str " and service = " (q check-id)))
       (if type
         (str " and type = " (q type)))))

(defn get-results [ options]
  (let [login (:login options)
        token (login->token login)
        query (gen-query (assoc options :type "result"))]
    (try
      (http/get (join "/" [@results-addr "results"])
                {:throw-entire-message? true
                 :query-params {:q query}
                 :headers {"Authorization" token}})
      (catch Exception ex
        (do (log/error "encountered error talking to beavis" token query)
            (throw ex))))))

(defn delete-results [login check-id]
  (let [token (login->token login)
        url (join "/" [@results-addr "results" check-id])]
    (try
      (http/delete url
                   {:throw-entire-message? true
                    :headers {"Authorization" token}})
      (catch Exception ex
        (do (log/error "encountered error talking to beavis" token url)
            (throw ex))))))