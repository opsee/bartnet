(ns bartnet.auth
  (:require [bartnet.sql :as sql]
           [clojure.string :as str]
           [clojure.tools.logging :as log])
  (:import [org.mindrot.jbcrypt BCrypt]
           [java.util Base64]
           [java.security MessageDigest]
           [org.eclipse.jetty.util B64Code]
           [org.eclipse.jetty.util StringUtil]))

(defn hash-password [password]
  (BCrypt/hashpw password (BCrypt/gensalt)))

(defn basic-authenticate [db username password]
  (if-let [login (first (sql/get-active-login-by-email db username))]
    (if (BCrypt/checkpw password (:password_hash login))
      [true, {:login login}]
      false)))

(defn password-match? [pass hash]
  (BCrypt/checkpw pass hash))

(defn generate-hmac-signature [id, secret]
  (.digest
    (doto (MessageDigest/getInstance "SHA1")
      (.update (.getBytes (str id)))
      (.update (.getBytes secret)))))

(defn do-basic-auth [db slug]
  (let [decoded (B64Code/decode slug StringUtil/__ISO_8859_1)
        index (.indexOf decoded)]
    (if (> index 0)
      (let [username (.substring decoded 0 index)
            password (.substring decoded (+ index 1))]
        (basic-authenticate db username password)))))

(defn do-token-auth [db token]
  (if-let [login (sql/get-active-login-by-token db token)]
    [true, {:login login}]
    false))

(defn do-hmac-auth [db hmac secret]
  (let [[id digest] (str/split hmac #"--")]
    (if (MessageDigest/isEqual
          (.decode (Base64/getUrlDecoder) digest)
          (generate-hmac-signature id secret))
      (if-let [login (first (sql/get-active-login-by-id db (Integer/parseInt id)))]
        [true {:login login}]
        false))))

(defn authorized? [db auth-type slug secret]
  (case (str/lower-case auth-type)
    "basic" (do-basic-auth db slug)
    "token" (do-token-auth db slug)
    "hmac" (do-hmac-auth db slug secret)
    false))