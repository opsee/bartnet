(ns bartnet.auth
  (:require [bartnet.sql :as sql]
           [clojure.string :as str]
           [clojure.data.json :as json]
           [liberator.representation :refer [ring-response]]
           [clojure.tools.logging :as log]
           [clojure.java.io :as io])
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

(defn allowed-to-auth?
  "Checks the body of the request and uses the password to authenticate the user."
  [ctx]
  (let [unsec-login (json/read (io/reader (get-in ctx [:request :body])) :key-fn keyword)]
    (basic-authenticate (:email unsec-login) (:password unsec-login))))

(defn generate-hmac-signature [id, secret]
  (.digest
    (doto (MessageDigest/getInstance "SHA1")
      (.digest (.getBytes (str id)))
      (.digest secret))))

(defn add-hmac-to-ctx
  "Gets the login from the context and generates an HMAC which gets added to the response"
  [secret]
  (fn
    [ctx]
    (let [login (:login ctx)
          id (str (:id login))
          hmac (str id "--" (.encodeToString (Base64/getUrlEncoder) (generate-hmac-signature id secret)))]
      (ring-response {:headers {"X-Auth-HMAC" hmac}}))))

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

(defn authorized?
  "Determines whether a request has the correct authorization headers, and sets the login id in the ctx."
  [secret]
  (fn
    [ctx]
    (log/info ctx)
    (let [[auth-type slug] (str/split (get-in ctx [:request :headers "authorization"]) #" " 2)]
      (case (str/lower-case auth-type)
        "basic" (do-basic-auth slug)
        "token" (do-token-auth slug)
        "hmac" (do-hmac-auth slug secret)))))