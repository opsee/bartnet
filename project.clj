(defproject bartnet "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main ^:skip-aot bartnet.core
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"]
                                  [ring/ring-mock "0.2.0"]]
                   :jvm-opts ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"]
                   :plugins [[lein-midje "3.0.0"]]}}
  :plugins [[s3-wagon-private "1.1.2"]]
  :java-source-paths ["src"]
  :aliases {"debug" ["with-profile" "dev" "run"]}
  :repositories [["snapshots" {:url "s3p://opsee-maven-snapshots/snapshot"
                               :username :env
                               :passphrase :env}]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [liberator "0.13"]
                 [compojure "1.3.4"]
                 [clj-time "0.11.0"]
                 [metosin/compojure-api "0.22.0" :exclusions [org.clojure/java.classpath hiccup clj-time joda-time]]
                 [clj-http "1.1.0"]
                 [circleci/clj-yaml "0.5.3"]
                 [drtom/clj-postgresql "0.5.0"]
                 [ring/ring-core "1.3.2"]
                 [info.sunng/ring-jetty9-adapter "0.8.1"]
                 [yesql "0.4.1-SNAPSHOT"]
                 [org.clojure/tools.cli "0.3.1"]
                 [cheshire "5.4.0"]
                 [com.boundary/high-scale-lib "1.0.6"]
                 [ring-cors "0.1.6"]
                 [io.netty/netty-all "4.1.0.Beta5"]
                 [com.google.protobuf/protobuf-java "3.0.0-alpha-3.1"]
                 [gloss "0.2.5"]
                 [com.github.brainlag/nsq-client "1.0.0.BETA" :exclusions [com.fasterxml.jackson.core/jackson-databind]]
                 [io.grpc/grpc-all "0.7.2"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]
                 [c3p0/c3p0 "0.9.1.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.10"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [org.liquibase/liquibase-core "3.1.1"]
                 [com.novemberain/validateur "2.4.2"]
                 [amazonica "0.3.21"]
                 [aleph "0.4.0"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [instaparse "1.4.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [manifold "0.1.0"]
                 [com.taoensso/carmine "2.10.0"]
                 [clj-disco "0.0.1"]
                 [org.bitbucket.b_c/jose4j "0.4.4"]])
