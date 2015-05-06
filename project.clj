(defproject bartnet "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main ^:skip-aot bartnet.core
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"]
                                  [ring/ring-mock "0.2.0"]]
                   :plugins [[lein-midje "3.0.0"]]}}
  :plugins [[s3-wagon-private "1.1.2"]]
  :repositories [["snapshots" {:url "s3p://opsee-maven-snapshots/snapshot"
                               :username :env
                               :passphrase :env}]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [liberator "0.12.2"]
                 [compojure "1.3.1"]
                 [clj-http "1.1.0"]
                 [ring/ring-core "1.3.2"]
                 [info.sunng/ring-jetty9-adapter "0.8.1"]
                 [yesql "0.4.1-SNAPSHOT"]
                 [org.clojure/tools.cli "0.3.1"]
                 [cheshire "5.4.0"]
                 [com.boundary/high-scale-lib "1.0.6"]
                 [ring-cors "0.1.6"]
                 [gloss "0.2.4"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]
                 [c3p0/c3p0 "0.9.1.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [org.liquibase/liquibase-core "3.1.1"]
                 [com.novemberain/validateur "2.4.2"]
                 [amazonica "0.3.21"]
                 [riemann "0.2.9" :exclusions [joda-time
                                               potemkin]]
                 [aleph "0.4.0-beta3"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [manifold "0.1.0-beta11"]])
