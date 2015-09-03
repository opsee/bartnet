(ns bartnet.t-launch
  (:require [clj-http.client :as c]
            [bartnet.launch :as launch])
  (:use [midje.sweet]
        [clj-http.fake]))

(def expected-userdata
  (str "#cloud-config\n"
       "write_files:\n"
       "- path: /etc/opsee/bastion-env.sh\n"
       "  permissions: '0644'\n"
       "  owner: root\n"
       "  content: |-\n"
       "    CUSTOMER_ID=\"custy1\"\n"
       "    BASTION_ID=\"dorpydorp\"\n"
       "    VPN_PASSWORD=\"doopydoop\"\n"))

(with-fake-routes {
                   "https://vape.opsy.co/bastions" {:post (fn [request] {:status 200 :headers {} :body "{\"id\":\"dorpydorp\",\"password\":\"doopydoop\"}"})}
                   }
                  ;; Exact string match:

                  (facts "get-bastion-creds"
                         (let [creds (launch/get-bastion-creds "custy1")]
                           (fact "has an id"
                                 (:id creds) => "dorpydorp")
                           (fact "has an password"
                                 (:password creds) => "doopydoop")))
                  (facts "userdata"
                         (let [userdata (launch/generate-user-data "custy1" (launch/get-bastion-creds "custy1"))]
                           (fact "is generated"
                                 userdata => expected-userdata)))
                  )


