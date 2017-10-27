## Overview

Support library for Clojure servers built on the [system/component
pattern](https://github.com/stuartsierra/component) and Ring.

Plumbing:

  * a place to store the system singleton, safe from namespace reloads
  * shortcuts for lifecycle management: recreate, restart

Smooth development experience:

  * automatic code reload on change
  * automatic system reset on code change
  * automatic webpage refresh on system reset
  * rendering of compile errors and runtime exceptions

Inspired by:

  * [`component.repl`](https://github.com/stuartsierra/component.repl): system
    storage, lifecycle shortcuts
  * [`lein-ring`](https://github.com/weavejester/lein-ring): automatic code
    reload, webpage refresh, error rendering

Uses [`clojure.tools.namespace`](https://github.com/clojure/tools.namespace) for
code reload. Adapts it to work in background threads such as filesystem
watchers. Properly refreshes namespaces in nREPL sessions (assumes
`clojure.tools.nrepl`).

Comparison with [`component.repl`](https://github.com/stuartsierra/component.repl):

  * automatic code reload

  * integrated webserver goodies: auto refresh on system reset, error rendering

Comparison with [`lein-ring`](https://github.com/weavejester/lein-ring):

  * not Ring-specific

  * doesn't mess with your build, AOT compilation works properly

  * code reload uses
    [`clojure.tools.namespace`](https://github.com/clojure/tools.namespace),
    avoiding limbo states

  * code reload involves a system reset, making it easy to redefine background
    activities such as job queues

  * code reload works on its own, you don't need a webpage open

  * code reload is vastly more reliable

  * page refresh is vastly more reliable


## Installation

Add to `project.clj`:

<!-- [![Clojars Project](https://img.shields.io/clojars/v/com.mitranim/forge.svg)](https://clojars.org/com.mitranim/forge) -->

```clj
[com.mitranim/forge "0.1.0-SNAPSHOT"]
```

Require in code:

```clj
(:require [com.mitranim.forge :as forge])
```


## Usage

```clj
(ns core
  (:require
    [com.mitranim.forge :as forge]
    [com.stuartsierra.component :as component]))

(defn create-system [prev-system]
  (reify
    component/Lifecycle
    (start [this] (println "starting") this)
    (stop [this] (println "stopping") this)))

(defn main []
  (forge/reset-system! create-system))

(defn main-dev []
  (forge/start-development! {:system-symbol 'core/create-system})
  (forge/reset))
```

When using Ring, add the middleware that automatically refreshes webpages and
renders errors:

```clj
(let [my-ring-handler (forge/wrap-development-features my-ring-handler)])
```

Launch your REPL and run an equivalent of this:

```clj
(forge/start-development! {:system-symbol 'app.core/create-system})
(forge/reset)
forge/sys
```

Now, modifying source files or running `(forge/reset)` will trigger a code
reload and system reset. The current system is always stored in `forge/sys`.

Enjoy your workflow!

The `template` folder in this repo provides the absolute smallest starting core.
Copy it to start playing around.

## API

The most important stuff is listed here. To dig deeper, check the source. It's
simple and hackable.

All functions here are thread-safe and idempotent.

#### `set-system-symbol!`

Tells Forge where to find your `create-system` function after a namespace
refresh. Needs to be called once before using `reset`. `start-development!` also
sets this.

```clj
(forge/set-system-symbol! 'app.core/create-system)
forge/system-symbol
```

#### `sys`

Stores the current system. Gets modified by `reset` and `reset-system!`. Can be
used to avoid passing the system everywhere. Also convenient in the REPL.

```clj
(forge/reset)
forge/sys
```

#### `start-development!`

Starts auto-reload and other goodies. Run it once after launching the REPL. See
[Usage](#usage) for example code.

#### `reset`

Reloads modified namespaces, recreates and restarts the system. Must be called
after `set-system-symbol!` or `start-development!`.

After one `start-development!` call, `reset` runs automatically on every source
change.

```clj
; once
(forge/start-development! {:system-symbol 'app.core/create-system})
(forge/reset)
```

#### `reset-system!`

Recreates and restarts the system, storing the result in `#'forge/sys`.
In development, use `reset` instead of this.

Define your "create-system" function. It must take one argument, the previous
version of the system, and return the next version _without starting it_.

Handles exceptions carefully:

* exception when stopping → store the partially stopped system so you can fix it
  manually

* exception when starting → stop the partially started system, store the
  remainder

The latter can be convenient when debugging production failures. If any
component fails to start, the rest won't keep the JVM from shutting down.

```clj
(defn create-system [prev-system]
  (component/system-map))

(defn main []
  (try (forge/reset-system! create-system)
    (catch Throwable err
      (shutdown-agents)
      (binding [*out* *err*] (prn err))
      (System/exit 1))))
```

#### `wrap-development-features`

Optional Ring middleware for auto-refresh and error rendering. Add to your
middleware stack as an outer layer, typically just before the 500 handler:

```clj
(def handler
  (-> routes
      ... other middleware ...
      forge/wrap-development-features
      my-500-handler))
```

Running `restart-system!` or `reset` will refresh any open webpages.

#### `refresh-namespaces`

Refreshes any modified namespaces. This is a version of
`clojure.tools.namespace.repl/refresh` that works in background threads, so it's
usable in filesystem watchers, HTTP handlers, etc. Used internally by `reset`.

```clj
(forge/refresh-namespaces)

; works
(future (forge/refresh-namespaces))
```

**Note:** unlike `(require 'my-ns :reload)`, this completely replaces namespace
objects, breaking `defonce`. To preserve state, keep it in your System and
migrate between system resets. If you have sufficiently good reasons, you can
opt a namespace out of "hard" reload into "soft" reload:

```clj
(ns my-ns
  (:require
    [clojure.tools.namespace.repl :refer [disable-unload!]]))

(disable-unload!)

(defonce blah blah)
```

#### ...

For lower-level stuff, please run `(dir com.mitranim.forge)` and check the
source; it's annotated and self-explanatory.

## Misc

Feedback, criticism, suggestions, and pull requests are welcome!

Open an issue or reach me on skype:mitranim.web or me@mitranim.com.
