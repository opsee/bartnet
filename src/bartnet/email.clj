(ns bartnet.email
  (:require [clj-http.client :as client]
            [clojure.string :as string]
            [clostache.parser :refer [render-resource]]))


(defn- send-mail! [config from to subject body]
  (let [api-key (:api_key config)
        base-url (:url config)]
    (if base-url
      (client/post (str base-url "/messages")
                   {:basic-auth ["api" api-key]
                    :form-params {:from from
                                  :to to
                                  :subject subject
                                  :text body}}))))

(defn render-email [template-path data]
  (let [rendered (render-resource template-path data)
        [subj body] (string/split rendered #"\n\n" 2)]
    {:subject subj
     :body body}))

(defn send-activation! [config signup id]
  (let [email (render-email "templates/activation.mustache", (merge signup {:id id}))]
    (send-mail! config
                "welcome@opsee.co"
                (:email signup)
                (:subject email)
                (:body email))))

(defn send-verification! [config login id]
  (let [email (render-email "templates/verification.mustache", (merge login {:id id}))]
    (send-mail! config
                 "verify@opsee.co"
                 (:email login)
                 (:subject email)
                 (:body email))))