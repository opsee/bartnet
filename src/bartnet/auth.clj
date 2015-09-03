(ns bartnet.auth
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all])
  (:import [org.jose4j.jwe JsonWebEncryption]
           [org.jose4j.keys AesKey]))

(def secret (atom nil))

(defn set-secret! [sekrit]
  (reset! secret (AesKey. sekrit)))

(defn token->login [token]
  (->
    (doto
      (JsonWebEncryption.)
      (.setKey @secret)
      (.setCompactSerialization token))
    (.getPayload)
    (parse-string keyword)))

(defn authorized? [token]
  (if token
    (try
      (if-let [login (token->login token)]
        [true, {:login login}])
      (catch Exception e
        (log/info "invalid token:" token "exception:" e)))))
