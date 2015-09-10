(ns bartnet.s3-buckets)

(def regions #{:ap-northeast-1 :ap-southeast-1 :ap-southeast-2
               :eu-central-1 :eu-west-1
               :sa-east-1
               :us-east-1 :us-west-1 :us-west-2})

(def bastion-cf-buckets (reduce
                         #(assoc %1 %2 (str "opsee-bastion-cf-" (name %2)))
                         {}
                         regions))

(defn url-to [region path]
  (let [bucket-name ((keyword region) bastion-cf-buckets)]
    (str "https://s3.amazonaws.com/" bucket-name "/" path)))

