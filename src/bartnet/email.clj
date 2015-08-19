(ns bartnet.email
  (:require [clj-http.client :as client]
            [clojure.string :as string]
            [clostache.parser :refer [render-resource]]
            [instaparse.core :as insta]
            [ring.util.codec :refer [url-encode]]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.match :refer [match]]))


(defn- send-mail! [config from to email]
  (let [api-key (:api_key config)
        base-url (:url config)]
    (if base-url
      (client/post (str base-url "/messages")
                   {:basic-auth ["api" api-key]
                    :form-params {:from from
                                  :to to
                                  :subject (:subject email)
                                  :html (:html email)
                                  :text (:plain email)}}))))

(def email-parser
  (insta/parser
    "email = subject (body)+
     subject = #'.+' <'\n'>
     sep = <';'> #'[a-zA-Z]+' <'==\n'>
     body = sep (#'.*' <'\n'>)* "))

(defn xform [email]
  (reduce (fn ([obj token]
               (match token
                      :email obj
                      [:subject subj] (assoc obj :subject subj)
                      [:body [:sep separator] & body] (assoc obj (keyword separator) (str/join "\n" body))))) {} email))

(defn- escape [value]
  (if value (str/replace (url-encode value) #"\+" "%2B")))

(defn- escape-data [data]
  (into data (map (fn [[k v]]
                    [(keyword (str (name k) "_escaped")) (escape v)])) data))

(defn render-email [template-path raw-data]
  (let [data (escape-data raw-data)
        tree (email-parser (render-resource template-path data))]
    (xform tree)))

(defn send-activation! [config signup id]
  (let [email (render-email "templates/activation.mustache", (merge signup {:id id}))]
    (send-mail! (:mailgun config)
                "welcome@opsee.co"
                (:email signup)
                email)))

(defn send-verification! [config login id]
  (let [email (render-email "templates/verification.mustache", (merge login {:id id}))]
    (send-mail! (:mailgun config)
                "verify@opsee.co"
                (:email login)
                email)))