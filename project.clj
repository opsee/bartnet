(defproject bartnet "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main ^:skip-aot bartnet.core
  :profiles {:uberjar {:aot :all}}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [liberator "0.12.2"]
                 [compojure "1.3.1"]
                 [ring/ring-core "1.3.2"]
                 [info.sunng/ring-jetty9-adapter "0.8.1"]
                 [yesql "0.4.0"]
                 [cheshire "5.4.0"]
                 [org.mindrot/jbcrypt "0.3m"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]
                 [c3p0/c3p0 "0.9.1.2"]
                 [org.clojure/tools.logging "0.3.1"]])
