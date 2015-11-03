(ns bartnet.results
  (:require [cheshire.core :refer :all]
            [opsee.middleware.auth :refer [login->token]]
            [clojure.string :refer :all]
            [clj-http.client :as http]))

(def results-addr (atom nil))

; customer_id =
; if id

(defn q [s] (str "\"" s "\""))

(defn gen-query [{id :id
                  check-id :check_id
                  customer-id :customer_id}]
  (str "customer_id = " (q customer-id)
       (if id
         (str " and host = " (q id)))
       (if check-id
         (str " and service = " (q check-id)))))

(defn get-results [ options]
  (let [login (:login options)
        token (login->token login)
        query (gen-query options)]
    (http/get  (join "/" [@results-addr "results"])
              {:query-params {:q query}
               :headers {"Authorization" token}})))
