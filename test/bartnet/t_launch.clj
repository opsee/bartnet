(ns bartnet.t-launch
  (:require [clj-http.client :as c]
            [bartnet.launch :as launch]
            [clj-yaml.core :as yaml])
  (:use [midje.sweet]
        [clj-http.fake]))

(def expected-userdata
  (str "#cloud-config\n"
       "write_files:\n"
       "- path: /etc/opsee/bastion-env.sh\n"
       "  permissions: '0644'\n"
       "  owner: root\n"
       "  content: |-\n"
       "    CUSTOMER_ID=custy1\n"
       "    CUSTOMER_EMAIL=custy1@opsee.com\n"
       "    BASTION_VERSION=stable\n"
       "    BASTION_ID=dorpydorp\n"
       "    VPN_PASSWORD=doopydoop\n"
       "    VPN_REMOTE=bastion.opsee.com\n"
       "    DNS_SERVER=2.2.2.2\n"
       "    BASTION_AUTH_TYPE=BASIC_TOKEN\n"
       "    NSQD_HOST=nsqd.in.opsee.com\n"
       "    BARTNET_HOST=https://bartnet.in.opsee.com\n"
       "coreos:\n"
       "  update: {reboot-strategy: etcd-lock, group: beta}\n"))

(defn str-eq-str-to-map [string] (reduce #(assoc % (read-string (nth %2 1)) (nth %2 2)) {} #_> (re-seq #"([^=\s]+)=([^=\s]+)" string)))

(with-fake-routes {"https://vape.opsy.co/bastions" {:post (fn [request] {:status 200 :headers {} :body "{\"id\":\"dorpydorp\",\"password\":\"doopydoop\"}"})}}
                  ;; Exact string match:

  (reset! launch/auth-addr "https://vape.opsy.co/bastions")
  (facts "get-bastion-creds"
         (let [creds (launch/get-bastion-creds "custy1")]
           (fact "has an id"
                 (:id creds) => "dorpydorp")
           (fact "has an password"
                 (:password creds) => "doopydoop")))
  (facts "userdata"
         (let [userdata (launch/generate-user-data "custy1" "custy1@opsee.com" (launch/get-bastion-creds "custy1") {:vpn-remote "bastion.opsee.com" :dns-server "2.2.2.2" :nsqd-host "nsqd.in.opsee.com" :bartnet-host "https://bartnet.in.opsee.com" :bastion-auth-type "BASIC_TOKEN"})
              expected-yaml (first ((yaml/parse-string expected-userdata) :write_files))
              received-yaml (first ((yaml/parse-string expected-userdata) :write_files))
              expected (assoc expected-yaml :content(str-eq-str-to-map (expected-yaml :content))) 
              received (assoc expected-yaml :content (str-eq-str-to-map (received-yaml :content)))]
           (fact "is generated"
             received => expected))))
