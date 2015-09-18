(ns bartnet.instance
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [clj-http.client :as http]))

(def store-host "https://fieri.opsy.co")
(def instance-endpoint "/instances")
(def group-endpoint "/groups")

(defn- request [endpoint options]
  (let [response (http/post (str store-host endpoint) {:content-type :json :accept :json :body (generate-string options)})]
    (case (:status response)
      200 (parse-string (:body response) keyword)
      (throw (Exception. "failed to get instances from the instance store")))))

(defn list-instances! [options]
  (request instance-endpoint options))

(defn list-groups! [options]
  (request group-endpoint options))
