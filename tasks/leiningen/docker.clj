(ns leiningen.docker
  (:require [leiningen.jar :refer [get-jar-filename]]
            [leiningen.uberjar :refer [uberjar]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :refer [blank?]]))

(defn docker
  "Build docker image"
  [project]
  (uberjar project)
  (let [jar-path (get-jar-filename project :standalone)]
    (sh "cp" jar-path "docker/lib/bartnet.jar")
    (let [docker (sh "docker" "build" "-t" "opsee/bartnet" ".")]
      (println (:out docker))
      (if-not (blank? (:err docker)) (println "Build error: " (:err docker))))))
