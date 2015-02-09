(ns bartnet.auth
  (:require [bartnet.sql :as sql]
           [clojure.string :as str]
           [clojure.tools.logging :as log])
  (:import [org.mindrot.jbcrypt BCrypt]
           [java.util Base64]
           [java.security MessageDigest]
           [org.eclipse.jetty.util B64Code]
           [org.eclipse.jetty.util StringUtil]))

(defn basic-authenticate [username password]
  (if-let [login (first (sql/get-active-login-by-email username))]
    (if (BCrypt/checkpw password (:password_hash login))
      [true, {:login login}]
      false)))

(defn generate-hmac-signature [id, secret]
  (.digest
    (doto (MessageDigest/getInstance "SHA1")
      (.digest (.getBytes (str id)))
      (.digest secret))))

(defn do-basic-auth [slug]
  (let [decoded (B64Code/decode slug StringUtil/__ISO_8859_1)
        index (.indexOf decoded)]
    (if (> index 0)
      (let [username (.substring decoded 0 index)
            password (.substring decoded (+ index 1))]
        (basic-authenticate username password)))))

(defn do-token-auth [token]
  (if-let [login (sql/get-active-login-by-token token)]
    [true, {:login login}]
    false))

(defn do-hmac-auth [hmac, secret]
  (let [[id digest] (str/split hmac #"--")]
    (if (MessageDigest/isEqual
          (.decode (Base64/getUrlDecoder) digest)
          (generate-hmac-signature id secret))
      (if-let [login (first (sql/get-active-login-by-id (read-string id)))]
        [true {:login login}]
        false))))

(defn authorized? [auth-type slug, secret]
  (case (str/lower-case auth-type)
    "basic" (do-basic-auth slug)
    "token" (do-token-auth slug)
    "hmac" (do-hmac-auth slug secret)))