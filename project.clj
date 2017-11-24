(defproject com.mitranim/forge "0.1.1"
  :description "Support library for Clojure servers built on the System/Component pattern"
  :url "https://github.com/Mitranim/clojure-forge"
  :dependencies [
    [org.clojure/clojure "1.8.0"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/tools.nrepl "0.2.12"]
    [com.stuartsierra/component "0.3.2"]
    [hawk "0.2.11"]
    [http-kit "2.2.0"]
    [clj-stacktrace "0.2.8"]
  ]
  :profiles {:dev {:source-paths ["dev" "src"]}}
  :repl-options {:skip-default-init true
                 :init-ns repl
                 :init (-main-dev)}
  :deploy-repositories [["clojars" {:sign-releases false}]]
)
