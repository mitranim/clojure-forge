(ns com.mitranim.forge
  "Support library for Clojure servers built on the System/Component pattern"
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.tools.namespace.repl :as ns-tools]
    [clojure.tools.nrepl.middleware.session :as nrepl-session]
    [com.stuartsierra.component :as component]
    [org.httpkit.server :refer [run-server]]
    [hawk.core :as hawk]
    [clj-stacktrace.core :as cs]
    [clj-stacktrace.repl :as csr])
  (:import
    [clojure.lang IRef ExceptionInfo]
    [java.util Properties UUID]
    [java.io BufferedReader]
    [org.httpkit.server HttpServer]))


(set! *warn-on-reflection* true)

(ns-tools/disable-unload!)



(defonce sys nil)

(defonce status nil)

(defonce system-symbol nil)

(defonce ^HttpServer status-server nil)

(defonce auto-reloader nil)

(defonce development? false)



(defn set-development!
  "Enable development features such as error middleware. Usage:
    (forge/set-development! true)
  See also:
    forge/start-development!"
  [value]
  (alter-var-root #'development? (constantly value)))


(defn set-system-symbol!
  "Takes a namespace-qualified symbol referring to the 'new system' function.
  A 'system' is a record implementing the Lifecycle protocol;
  see https://github.com/stuartsierra/component for details.
  The function must take the previous system and return a new one;
  forge/reset will use it to create and restart the system after each reset.

  Usage:

    (forge/set-system-symbol! '<my-namespace>/<create-system>)
    (forge/reset)"
  [sym] {:pre [(or (nil? sym) (symbol? sym))]}
  (alter-var-root #'system-symbol (constantly sym)))



(defn- return-or-throw [value]
  (if (instance? Throwable value) (throw value) value))

(defn- refresh-session-ns [session-state]
  (if-let [ns (get session-state #'*ns*)]
    (assoc session-state #'*ns* (find-ns (ns-name ns)))
    session-state))

(defn refresh-namespaces
  "Version of clojure.tools.namespace.repl/refresh that:
    * works in non-REPL threads
    * refreshes stale #'*ns* for all nREPL sessions
    * throws errors instead of returning them"
  []
  (return-or-throw (with-bindings {#'*ns* *ns*} (ns-tools/refresh)))
  (doseq [session (vals @@#'nrepl-session/sessions)]
    (swap! session refresh-session-ns)))



(defmacro ^:private tracking-status
  "Runs exprs in an implicit do, storing either :ok or exception
  into #'forge/status. Returns :ok or throws."
  [& exprs]
  `(locking #'status
     (try
       (alter-var-root #'status (constantly (do ~@exprs :ok)))
       (catch Throwable err#
         (alter-var-root #'status (constantly err#))
         (throw err#)))))



(defmacro ^:private try-sys
  "Runs the expressions, catching system exceptions from the Component library,
  passing their ex-data to the handler block before rethrowing.
  Usage: (try-sys <exprs> (on-sys-error <ex-map-bindings> <exprs>))"
  [& exprs]
  (let [block (butlast exprs)
        [catch-sym binding & catch-block] (last exprs)]
    (when-not (= catch-sym 'on-sys-error)
      (throw (new IllegalArgumentException "try-sys must end with an on-sys-error clause")))
    `(try ~@block
       (catch ExceptionInfo err#
         (when (:system (ex-data err#))
           (let [~binding (ex-data err#)] ~@catch-block))
         (throw err#)))))

(defmacro ^:private reset-root! [sym expr]
  `(alter-var-root (var ~sym) (constantly ~expr)))

(defn reset-system!
  "Recreate and restart the system, using the provided constructor.
  Stores the system into #'forge/sys, and the operation status
  (exception or :ok) into #'forge/status.

  Tries to handle exceptions as gracefully as possible:
    * exception during start -> store the partially started system,
      stopping it
    * exception during stop -> store the partially stopped system,
      removing the component that failed to stop (this one is questionable)

  Always stores the last meaningful state of the system,
  whether partially started or stopped.

  Flow:

    create next system
      exception? don't handle
      ok?        stop previous system
        exception? store partially stopped system, throw
        ok?        start next system
          exception? store partially started system, try to stop
            exception? store partially stopped system
            ok?        throw
          ok?        store and return started system

  The provided function must take one argument, the previous system,
  and return the new system without starting it.

  Usage:

    (forge/reset-system! create-system)

  In development, use forge/reset instead."
  [create-system] {:pre [(ifn? create-system)]}
  (tracking-status
    (locking #'sys
      (let [sys-next (create-system sys)]
        (when sys
          (try-sys (component/stop sys)
            (on-sys-error {sys-partial :system bad-key :system-key}
              (reset-root! sys (if bad-key (dissoc sys-partial bad-key) sys-partial)))))
        (try-sys
          (reset-root! sys sys-next)
          (reset-root! sys (component/start sys-next))
          (on-sys-error {sys-partial :system}
            (reset-root! sys sys-partial)
            (try-sys (component/stop sys)
              (on-sys-error {sys-partial :system bad-key :system-key}
                (reset-root! sys (if bad-key (dissoc sys-partial bad-key) sys-partial))))))))))

(defn stop-system! []
  "Stops the system, storing the stopped version into #'forge/sys, and the
  operation status (exception or :ok) into #'forge/status."
  (tracking-status (alter-var-root #'sys component/stop)))



(defn- report-missing-system-symbol []
  (when-not system-symbol
    (throw (new Exception
                (str "#'forge/system-symbol not set, please call "
                     "(forge/set-system-symbol! '<my-namespace>/<create-system>)")))))

(defn reset
  "Resets changed namespaces and restarts the system in development mode,
  storing it into #'forge/sys. Usage:

    (forge/set-system-symbol! '<main-namespace>/<create-system>)
    (forge/reset)

  Development ONLY. For production, start your system directly:

    (component/start (my-create-system nil))"
  []
  (tracking-status
    (refresh-namespaces)
    (report-missing-system-symbol)
    (reset-system! (resolve system-symbol))))




(def ^:private STOP_TIMEOUT 100)

(defn- next-change [^IRef iref]
  (let [change (promise)]
    (add-watch iref
               (UUID/randomUUID)
               (fn [key iref _ value]
                 (remove-watch iref key)
                 (deliver change value)))
    change))

(defn- report-status-change
  "Blocks until #'forge/status changes, then returns a 204 response.
  See also:
    forge/start-status-server!
    forge/wrap-add-refresh-script"
  [_]
  @(next-change #'status)
  {:status 204
   :headers {"Access-Control-Allow-Origin" "*"}})

(defn start-status-server!
  "Idempotently starts a server that reports changes in #'forge/status.
  Picks a random available port. Use forge/wrap-add-refresh-script
  to inject a script that will auto-refresh the page on status change.

  See also:
    forge/wrap-add-refresh-script
    forge/start-development!"
  ([] (start-status-server! nil))
  ([options]
   (tracking-status
     (alter-var-root #'status-server
      (fn [^HttpServer prev]
        (when prev (.stop prev STOP_TIMEOUT))
        (-> (run-server report-status-change (merge {:port 0} options))
            meta
            :server))))))

(defn stop-status-server!
  "See forge/start-status-server!."
  []
  (tracking-status
    (alter-var-root #'status-server
      (fn [^HttpServer prev]
        (when prev (.stop prev STOP_TIMEOUT))
        nil))))



(defn wrap-throw-bad-status
  "Development Ring middleware. On each request, checks #'forge/status.
  If that's a throwable, throws it, so that forge/wrap-render-exception can
  render it.

  See also: forge/wrap-development-features"
  [handler]
  (fn [request]
    (let [status status]
      (if (instance? Throwable status)
        (throw status)
        (handler request)))))


(defn- str-includes? [value sub]
  (and (string? value) (string/includes? value sub)))

(defn- elem-color [elem]
  (let [color (csr/elem-color elem)]
    (or ({:cyan :darkcyan, :yellow :orange} color) color)))

; https://github.com/weavejester/hiccup/blob/846d7ef8248af2c4644c27302aaf00056d6491c0/src/hiccup/util.clj#L80
(defn- escape-html
  "Change special characters into HTML character entities."
  [text]
  (when text
    (.. ^String text
      (replace "&"  "&amp;")
      (replace "<"  "&lt;")
      (replace ">"  "&gt;")
      (replace "\"" "&quot;")
      (replace "'" "&apos;"))))

(defn- exception-markup
  "Renders exception to HTML markup."
  [^Throwable err]
  (let [arsed (cs/parse-exception err)
        {:keys [^Class class message trace-elems]} arsed
        print-width (csr/find-source-width arsed)
        offset (quot print-width 3)]
    (str
      "<span class='error-header-container' style='margin-left: "offset"ch'>"
        "<h1 class='error-header'>"(escape-html (.getName class))": "(escape-html message)"</h1>"
      "</span>"
      (when-let [data (ex-data err)]
        (str
          "<span style='margin-left: "offset"ch'>"
            "<h2 class='error-data'>"(escape-html (with-out-str (pprint data)))"</h2>"
          "</span>"))
      (apply str
        (for [elem trace-elems]
          (str
            "<span class='error-frame' style='color: "(escape-html (name (elem-color elem)))"'>"
              (escape-html (csr/pst-elem-str false elem print-width))
            "</span>")))
      (when (.getCause err) (exception-markup (.getCause err))))))

(defn- render-exception-page [err]
  "Renders exception to full-page HTML markup."
  (apply str
    "<!doctype html>"
    "<html>"
      "<head>"
        "<meta charset='utf-8' />"
        "<title>Error Stacktrace</title>"
        "<style>"(slurp (io/resource "com/mitranim/forge/css/stacktrace.css"))"</style>"
        "<link rel='icon' href='data:;base64,=' />"
      "</head>"
      "<body class='page-container'>"
        "<pre class='errors-container'>"(exception-markup err)"</pre>"
      "</body>"
    "</html>"))

(defn- accepts-html? [value]
  (or (str-includes? value "text/html") (str-includes? value "*/*")))

(defn- get-header [{headers :headers} header-name]
  (when (seq headers)
    (loop [[[key val] & rest] (seq headers)]
      (cond (.equalsIgnoreCase (name header-name) (name key)) val
            rest (recur rest)))))

(defn wrap-render-exception
  "Development Ring middleware. Catches exceptions and renders them to HTML.
  Similar to ring.middleware.stacktrace/wrap-stacktrace, but correctly renders
  metadata for clojure.lang.ExceptionInfo, and arguably looks better.

  Options:

    :render      ƒ exception -> response body

  See also: forge/wrap-development-features"
  ([handler] (wrap-render-exception handler {:render render-exception-page}))
  ([handler {render :render}] {:pre [(ifn? render)]}
   (fn [request]
     (if (accepts-html? (get-header request "accept"))
       (try (handler request)
         (catch Throwable err
           {:status 500
            :headers {"content-type" "text/html", "server" nil}
            :body (render err)}))
       (handler request)))))

(defn- warning-popup [text]
  (apply str
    "<div id='forgeRefreshContainer' class='forge-refresh-container' style='"
      "position: fixed; bottom: 1rem; left: 1rem; margin-right: 1rem; padding: 0; "
      "font-family: monospace; "
      "display: flex; flex-direction: row; align-items: stretch; "
      "background-color: lightgoldenrodyellow; box-shadow: 0 0 3px -1px gray;'>"
      "<span style='padding: 1rem'>"(escape-html text)"</span>"
      "<button onclick='forgeRefreshContainer.remove()' style='"
        "padding: 1rem; cursor: pointer; font-family: inherit; "
        "font-size: inherit; border: none; background-color: khaki; "
        "line-height: inherit;'>"
        "Close"
      "</button>"
    "</div>"))

(defn- warning-script [text]
  (str
    "console.warn('" (string/replace text "'" "\\'") "')\n"
    "document.body.insertAdjacentHTML('beforeend', '" (string/replace (warning-popup text) "'" "\\'") "')"))

(defn- refresh-script
  "Creates a script that connects to #'forge/status-server, reloading the
  page when #'forge/status changes, i.e. on code reload or system restart."
  ([]
   (if (not status-server)
     (warning-script (str "Status server not found. Make sure to call "
                          "forge/start-status-server! in your main function."))
     (let [port (.getPort status-server)]
       (if (and (integer? port) (> port 0))
         (refresh-script port)
         (warning-script (str "Status server has invalid port: " port ". "
                              "Probably down or restarting."))))))
  ([port] (str "
fetch('http://localhost:" port "')
  .then(res => {
    if (res.ok) window.location.reload()
    else return Promise.reject(res.text())
  })
  .catch(() => {
    " (warning-script "Lost connection to status server. Consider refreshing.") "
  })
")))

(defn wrap-add-refresh-script
  "Development Ring middleware. Injects a script that reloads the page when
  #'forge/status changes, i.e. on code reload or system restart. The response
  must have the HTML content type.

  See also: forge/wrap-development-features"
  [handler]
  (fn [request]
    (let [response (handler request)
          html? (str-includes? (get-header response "content-type") "text/html")]
      (cond (and html? (string? (:body response)))
            (update response :body str "<script>" (refresh-script) "</script>")
            ; probably hiccup markup
            (and html? (vector? (:body response)))
            (update response :body conj "<script>" (refresh-script) "</script>")
            :else response))))



(defn wrap-development-features
  "Ring middleware that combines other Forge middlewares.
  Enables development goodies such as page auto-refreshing
  and detailed exception rendering. Only enabled in development mode."
  [handler] {:pre [(ifn? handler)]}
  (let [wrapped
        (-> handler
            wrap-throw-bad-status
            wrap-render-exception
            wrap-add-refresh-script)]
    (fn [request]
      ((if development? wrapped handler) request))))



(def ^:private default-paths ["src"])

(defn- hawk-reset [_ _]
  (try (reset)
    (catch Exception err
      (binding [*out* *err*] (prn err)))))

(defn start-auto-reload!
  {:doc (str
  "Idempotently starts a filesystem watcher that detects source code changes
  and runs forge/reset, reloading the code and restarting the system.

  Default watch paths: " default-paths "

  See also:
    forge/reset
    forge/start-development!")}
  ([] (start-auto-reload! default-paths))
  ([paths]
   (tracking-status
     (alter-var-root #'auto-reloader
      (fn [prev]
        (when prev (hawk/stop! prev))
        (hawk/watch! [{:paths paths :handler hawk-reset}]))))))

(defn stop-auto-reload!
  "See forge/start-auto-reload!."
  []
  (tracking-status
    (alter-var-root #'auto-reloader
      (fn [prev]
        (when prev (hawk/stop! prev))
        nil))))



(defn start-development!
  "Enables development features such as auto-reload, status-reporting server,
  and optional reporting middleware for your Ring server.
  Idempotent. Run it once in your main function (development only).

  Usage:

    (forge/start-development! {:system-symbol '<my-namespace>/<create-system>})
    (forge/reset)

  For Ring, add forge/wrap-development-features your middleware stack, as an
  outer layer.

  To customise, copy and modify the source:

    (source forge/start-development!)"

  [{:keys [source-paths system-symbol] :or {source-paths default-paths}}]
  {:pre [(sequential? source-paths) (symbol? system-symbol)]}

  (apply ns-tools/set-refresh-dirs source-paths)
  (set-development! true)
  (set-system-symbol! system-symbol)
  (start-auto-reload! source-paths)
  (start-status-server!))

(defn stop-development!
  "See forge/start-development!."
  []
  (set-development! false)
  (stop-auto-reload!)
  (stop-status-server!))



(defn read-props
  "Reads and parses properties from file or URL.
  See java.util.Properties for accepted syntax.

  Usage:
    (def env (merge {} (System/getenv) (forge/read-props \".env\")))

  Compile-time validation:
    (defmacro getenv [key] (forge/get-strict env key))
    (getenv \"MY_VAR\")"
  ^Properties
  [source]
  (with-open [^BufferedReader reader (io/reader source)]
    (doto (new Properties) (.load reader))))

(defn get-strict
  "Reads a map property, throwing an exception if it's missing.
  Use with forge/read-props to validate env properties."
  [map key]
  (when-not (contains? map key)
    (throw (ex-info (str "property not found: " key)
                    {:type ::property-not-found :key key})))
  (get map key))
