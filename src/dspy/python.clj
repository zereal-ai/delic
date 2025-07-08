(ns dspy.python
  "Python runtime initialization for delic.

   This namespace provides Python integration via libpython-clj without auto-initializing
   the Python runtime or exposing libpython-clj details to the consuming application.

   The user must explicitly call `init-python!` with their local Python configuration
   before using any Python-based delic functionality."
  (:require [libpython-clj2.python :as py]
            [clojure.tools.logging :as log]))

(defonce ^:private python-initialized? (atom false))

(defn init-python!
  "Initializes the Python runtime. Must be called before any Python-based delic functionality is used.

   Takes a map with the following keys:
   - :python-executable - Path to Python executable (e.g., \"/usr/bin/python3\")
   - :library-path - Path to Python library (e.g., \"/usr/lib/libpython3.10.so\")

   Example:
   (init-python! {:python-executable \"/usr/bin/python3.10\"
                  :library-path \"/usr/lib/libpython3.10.so\"})

   Returns true if initialization was successful, false if already initialized."
  [{:keys [python-executable library-path] :as config}]
  {:pre [(string? python-executable)
         (string? library-path)]}
  (if @python-initialized?
    (do
      (log/debug "Python runtime already initialized, skipping...")
      false)
    (do
      (log/info "Initializing Python runtime with config:" config)
      (py/initialize! :python-executable python-executable
                      :library-path library-path)
      (reset! python-initialized? true)
      (log/info "Python runtime initialized successfully")
      true)))

(defn initialized?
  "Returns true if the Python runtime has been initialized."
  []
  @python-initialized?)

(defn require-python!
  "Throws an exception if Python runtime is not initialized.

   This is a utility function that can be called by other namespaces
   to ensure Python is available before attempting to use it."
  []
  (when-not @python-initialized?
    (throw (ex-info "Python runtime not initialized. Call (dspy.python/init-python! config) first."
                    {:type :python-not-initialized
                     :suggestion "Call (dspy.python/init-python! {:python-executable \"/path/to/python\" :library-path \"/path/to/libpython.so\"})"}))))

(defn shutdown-python!
  "Shuts down the Python runtime if it was initialized.

   Note: libpython-clj2 handles Python shutdown automatically,
   so this function just resets our initialization state.
   This is primarily useful for testing or when you need to reinitialize
   with different configuration."
  []
  (when @python-initialized?
    (log/info "Resetting Python runtime state")
    (reset! python-initialized? false)
    (log/info "Python runtime state reset")))