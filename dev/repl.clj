(ns repl
  (:use clojure.repl)
  (:require
    [com.mitranim.forge :as forge]
    [com.stuartsierra.component :as component]
    [org.httpkit.server :refer [run-server]])
  (:import
    [org.httpkit.server HttpServer]))

(set! *warn-on-reflection* true)


(def env (merge {} (System/getenv) (forge/read-props "dev/env.properties")))

(defmacro getenv [key] (forge/get-strict env key))



(defn page [request]
  ; (throw (new Exception "runtime failure"))
  ; (throw (new Exception "<script>console.warn('failed to escape?')</script>"))
  {:headers {"content-type" "text/html", "server" nil}
   :body (str
    "<!doctype html>"
    "<html>"
      "<head>"
        "<link rel='icon' href='data:;base64,=' />"
        "<title>demo page</title>"
      "</head>"
      "<body style='padding: 1rem; font-family: monospace; max-width: 80ch'>"
        "<p>Status: " forge/status "</p>"
        "<p>Development mode: " forge/development? "</p>"
        "<p>"
          (if-not forge/development?
            (str
              "Run (repl/-main-dev) to enable development features such as "
              "auto-restart, auto-refresh and exception rendering.")
            (str
              "Change the code to observe auto-restart and auto-refresh. "
              "Create compilation errors and runtime exceptions to observe exception rendering."))
        "</p>"
      "</body>"
    "</html>")})


(def handler
  (-> page
      forge/wrap-development-features))


(defrecord Srv [^HttpServer http-server]
  component/Lifecycle
  (start [this]
    (when http-server
      (println "Stopping server")
      (.stop http-server 100))
    ; This should produce a compile-time error if the property is missing
    (let [port (Long/parseLong (getenv "PORT"))]
      (println "Starting server on" (str "http://localhost:" port))
      (assoc this :http-server
        (-> (run-server handler {:port port}) meta :server))))
  (stop [this]
    (when http-server
      (println "Stopping server")
      (.stop http-server 100))
    (assoc this :http-server nil)))



(defn object-wait [^Object object ms]
  (locking object (.wait object ms)))

(defn current-thread-nap [ms]
  (try (object-wait (Thread/currentThread) ms)
    (catch InterruptedException err nil)))

(defn bg-loop [fun & args] {:pre [(ifn? fun)]}
  #(while true
    (try (apply fun args)
      (catch Exception err
        (binding [*out* *err*]
          (println "Exception in background thread" (str (Thread/currentThread)))
          (prn err))
        (current-thread-nap 10000)))))



(defn bg-tick []
  ; (println "ticking")
  (current-thread-nap 5000))



(defrecord Sched [^Thread thread ^Runnable runnable]
  component/Lifecycle
  (start [this]
    (when thread (.stop thread))
    ; (println "Starting background thread")
    (assoc this :thread
      (doto (new Thread runnable) (.setDaemon true) .start)))
  (stop [this]
    (when thread
      ; (println "Stopping background thread")
      (.stop thread))
    (assoc this :thread nil)))



(defn create-system [sys-prev]
  (component/system-map
    :srv (new Srv nil)
    :sched (new Sched nil (bg-loop bg-tick))))



(defn -main []
  (forge/reset-system! create-system))



(defn -main-dev []
  (forge/start-development! {:system-symbol `create-system
                             :source-paths ["dev" "src"]})
  (forge/reset-system! create-system))
