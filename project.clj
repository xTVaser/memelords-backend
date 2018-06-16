(defproject backend "0.1.0"
  :description "Memelords Backend"
  :url ""
  :min-lein-version "2.8.0"
  :uberjar-name "memelords-backend.jar"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[aleph "0.4.6"]
                 [buddy/buddy-sign "3.0.0"]
                 [buddy/buddy-hashers "1.3.0"]
                 [gloss "0.2.6"]
                 [compojure "1.6.1"]
                 [ring/ring-json "0.4.0"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.4.474"]
                 [com.google.cloud/google-cloud-datastore "1.31.0"]]
  :main ^:skip-aot backend.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
