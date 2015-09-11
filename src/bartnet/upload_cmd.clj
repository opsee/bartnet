(ns bartnet.upload-cmd
  (:use [amazonica.aws.s3]
        [bartnet.s3-buckets])
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [cheshire.core :refer :all])
  (:import (java.io File)
           (com.amazonaws.services.s3.model CannedAccessControlList)))

(def upload-options
  [["-p" "--path PATH" "the local path to the file that must be uploaded"]
   ["-r" "--remote REMOTE" "the remote path where the file must uploaded"
    :default nil]
   ["-a" "--access-key ACCESS-KEY" "the aws access key ID"
    :default (System/getenv "AWS_ACCESS_KEY_ID")]
   ["-s" "--secret-key SECRET-KEY" "the aws secret key"
    :default (System/getenv "AWS_SECRET_ACCESS_KEY")]
   ["-g" "--regions REGIONS" "a comma separated list of regions to do the upload (defaults to all)"
    :default nil
    :parse-fn #(str/split % #",\s*")]])

(defn upload-usage [options-summary]
  (->> ["This is the upload command for bartnet."
        ""
        "usage bartnet upload [options] <config file>"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn do-upload [creds resource-path s3-path deploy-regions]
  (dorun
   (for [region deploy-regions
         :let [bucket (region bastion-cf-buckets)]]
     (do
       (log/info "uploading to " region)
       (put-object (assoc creds :region region)
                   :bucket-name bucket
                   :key s3-path
                   :canned-acl CannedAccessControlList/PublicRead
                   :file (File. resource-path))))))

(defn upload [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args upload-options)]
    (cond
      (:help options) (exit 0 (upload-usage summary))
      (not= (count arguments) 1) (exit 1 (upload-usage summary))
      errors (exit 1 (error-msg errors)))
    (let [config (parse-string (slurp (first arguments)) true)
          path (:path options)
          remote (or (:remote options) path)
          deploy-regions (or (:regions options) regions)
          creds {:access-key (:access-key options)
                 :secret-key (:secret-key options)}]
      (do-upload creds path remote deploy-regions))))

