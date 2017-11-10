(defproject template "0.0.0"
  :description "Starting template for an application that uses Forge"

  :dependencies [
    [org.clojure/clojure "1.8.0"]
    [com.stuartsierra/component "0.3.2"]
    [com.mitranim/forge "0.1.0"]
  ]

  :main core/main

  :repl-options {:skip-default-init true
                 :init-ns core
                 :init (main-dev)}
)
