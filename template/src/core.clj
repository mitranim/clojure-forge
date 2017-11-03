(ns core
  (:require
    [com.stuartsierra.component :as component]
    [com.mitranim.forge :as forge]))

(defn create-system [prev-system]
  (reify
    component/Lifecycle
    (start [this] (println "starting") this)
    (stop [this] (println "stopping") this)))

(defn main []
  (forge/reset-system! create-system))

(defn main-dev []
  (forge/start-development! {:system-symbol `create-system})
  (forge/reset-system! create-system))
