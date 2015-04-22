(ns bartnet.t-email
  (:use midje.sweet)
  (:require [bartnet.email :refer [render-email]]))

(def id "abc123")
(def naame "himynameis")
(def email "cliff+derp@leaninto.it")

(fact "rendering works"
      (let [rendered (render-email "templates/activation.mustache" {:id id
                                                                    :name naame
                                                                    :email email})]
        (:subject rendered) => #"himynameis"
        (:body rendered) => #"abc123"))
