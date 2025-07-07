(ns dspy.config
  "Configuration management for delic with multiple sources and validation."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.error :as me]))

;; Configuration Schemas

(def backend-config-schema
  "Schema for backend configuration"
  [:map
   [:provider {:optional true} [:enum :openai :anthropic :ollama]]
   [:type {:optional true} [:enum :openai :anthropic :ollama]] ; backward compatibility
   [:model {:optional true} :string]
   [:api-key {:optional true} :string]
   [:base-url {:optional true} :string]
   [:organization {:optional true} :string]
   [:timeout-ms {:optional true} [:int {:min 1000}]]
   [:max-retries {:optional true} [:int {:min 0 :max 10}]]
   [:retry-delay-ms {:optional true} [:int {:min 100}]]])

(def storage-config-schema
  "Schema for storage configuration"
  [:map
   [:type {:optional true} [:enum :sqlite :file]]
   [:url {:optional true} :string]
   [:dir {:optional true} :string]
   [:db-spec {:optional true} :string]])

(def optimization-config-schema
  "Schema for optimization configuration"
  [:map
   [:strategy {:optional true} [:enum :beam :random :grid :identity]]
   [:beam-width {:optional true} [:int {:min 1 :max 20}]]
   [:max-iterations {:optional true} [:int {:min 1 :max 100}]]
   [:concurrency {:optional true} [:int {:min 1 :max 50}]]
   [:checkpoint-interval {:optional true} [:int {:min 1}]]
   [:timeout-ms {:optional true} [:int {:min 1000}]]
   [:verbose {:optional true} :boolean]])

(def config-schema
  "Complete configuration schema"
  [:map
   [:backend {:optional true} backend-config-schema]
   [:storage {:optional true} storage-config-schema]
   [:optimization {:optional true} optimization-config-schema]
   [:logging {:optional true} [:map
                               [:level {:optional true} [:enum :trace :debug :info :warn :error]]
                               [:format {:optional true} :string]]]
   [:development {:optional true} [:map
                                   [:portal {:optional true} :boolean]
                                   [:repl {:optional true} :boolean]
                                   [:hot-reload {:optional true} :boolean]]]])

;; Default Configuration

(def default-config
  "Default configuration values"
  {:backend {:provider :openai
             :model "gpt-4o-mini"
             :timeout-ms 30000
             :max-retries 3
             :retry-delay-ms 1000}
   :storage {:type :file
             :dir "./runs"}
   :optimization {:strategy :beam
                  :beam-width 4
                  :max-iterations 10
                  :concurrency 8
                  :checkpoint-interval 5
                  :timeout-ms 300000
                  :verbose false}
   :logging {:level :info
             :format "simple"}
   :development {:portal true
                 :repl true
                 :hot-reload false}})

;; Environment Variable Mapping

(def env-mappings
  "Map environment variables to configuration paths"
  {"OPENAI_API_KEY" [:backend :api-key]
   "ANTHROPIC_API_KEY" [:backend :api-key]
   "DSPY_PROVIDER" [:backend :provider]
   "DSPY_MODEL" [:backend :model]
   "DSPY_STORAGE" [:storage :url]
   "DSPY_STORAGE_DIR" [:storage :dir]
   "DSPY_STRATEGY" [:optimization :strategy]
   "DSPY_BEAM_WIDTH" [:optimization :beam-width]
   "DSPY_MAX_ITERATIONS" [:optimization :max-iterations]
   "DSPY_CONCURRENCY" [:optimization :concurrency]
   "DSPY_TIMEOUT" [:optimization :timeout-ms]
   "DSPY_VERBOSE" [:optimization :verbose]
   "DSPY_PORTAL" [:development :portal]
   "DSPY_LOG_LEVEL" [:logging :level]})

;; Configuration Loading

(defn parse-env-value
  "Parse environment variable value to appropriate type"
  [value config-path]
  (try
    (case (last config-path)
      (:beam-width :max-iterations :concurrency :timeout-ms :checkpoint-interval :retry-delay-ms)
      (Long/parseLong value)

      (:verbose :portal :repl :hot-reload)
      (Boolean/parseBoolean value)

      (:provider :type :strategy :level)
      (keyword value)

      ;; Default: return as string
      value)
    (catch Exception e
      (log/warn "Failed to parse environment variable"
                {:env-var (str config-path) :value value :error (.getMessage e)})
      value)))

(defn load-env-config
  "Load configuration from environment variables"
  []
  (reduce (fn [config [env-var config-path]]
            (if-let [value (System/getenv env-var)]
              (assoc-in config config-path (parse-env-value value config-path))
              config))
          {}
          env-mappings))

(defn load-edn-config
  "Load configuration from EDN file"
  [file-path]
  (try
    (if (.exists (io/file file-path))
      (do
        (log/info "Loading configuration from" file-path)
        (edn/read-string (slurp file-path)))
      (do
        (log/debug "Configuration file not found" file-path)
        {}))
    (catch Exception e
      (log/error "Failed to load configuration file" file-path (.getMessage e))
      (throw (ex-info "Configuration file error"
                      {:file file-path
                       :error (.getMessage e)})))))

(defn merge-configs
  "Merge multiple configuration maps with proper precedence"
  [& configs]
  (reduce (fn [result config]
            (merge-with
             (fn [a b]
               (if (and (map? a) (map? b))
                 (merge a b)
                 b))
             result
             config))
          {}
          configs))

(defn validate-config
  "Validate configuration against schema"
  [config]
  (if (m/validate config-schema config)
    config
    (let [errors (me/humanize (m/explain config-schema config))]
      (throw (ex-info "Invalid configuration"
                      {:config config
                       :errors errors})))))

(defn load-config
  "Load configuration from multiple sources with validation.

   Sources (in order of precedence):
   1. Environment variables (highest priority)
   2. EDN file
   3. Default configuration (lowest priority)

   Args:
     file-path - Path to EDN configuration file (optional)

   Returns:
     Validated configuration map"
  ([]
   (load-config "config.edn"))
  ([file-path]
   (let [env-config (load-env-config)
         edn-config (load-edn-config file-path)
         merged-config (merge-configs default-config edn-config env-config)]
     (log/debug "Configuration loaded"
                {:sources {:default (keys default-config)
                           :file (keys edn-config)
                           :env (keys env-config)}})
     (validate-config merged-config))))

;; Configuration Utilities

(defn ^:export get-backend-config
  "Get backend configuration with provider normalization"
  [config]
  (let [backend-config (:backend config)]
    ;; Support legacy :type key for backward compatibility
    (if (and (:type backend-config) (not (:provider backend-config)))
      (assoc backend-config :provider (:type backend-config))
      backend-config)))

(defn ^:export get-storage-config
  "Get storage configuration with URL parsing"
  [config]
  (let [storage-config (:storage config)]
    (if-let [url (:url storage-config)]
      (cond
        (.startsWith url "sqlite://")
        (assoc storage-config
               :type :sqlite
               :db-spec (str "jdbc:sqlite:" (subs url 9)))

        (.startsWith url "file://")
        (assoc storage-config
               :type :file
               :dir (subs url 7))

        :else storage-config)
      storage-config)))

(defn ^:export get-optimization-config
  "Get optimization configuration"
  [config]
  (:optimization config))

(defn ^:export development-mode?
  "Check if running in development mode"
  [config]
  (get-in config [:development :portal] false))

(defn ^:export hot-reload-enabled?
  "Check if hot reload is enabled"
  [config]
  (get-in config [:development :hot-reload] false))

;; Configuration Watching (for hot reload)

(defn ^:export watch-config-file
  "Watch configuration file for changes and reload"
  [file-path callback]
  (let [file (io/file file-path)]
    (when (.exists file)
      (let [last-modified (atom (.lastModified file))
            watch-thread (Thread.
                          (fn []
                            (while true
                              (Thread/sleep 1000)
                              (let [current-modified (.lastModified file)]
                                (when (> current-modified @last-modified)
                                  (reset! last-modified current-modified)
                                  (try
                                    (log/info "Configuration file changed, reloading..." file-path)
                                    (callback (load-config file-path))
                                    (catch Exception e
                                      (log/error "Failed to reload configuration" (.getMessage e)))))))))]
        (.setDaemon watch-thread true)
        (.start watch-thread)
        watch-thread))))

;; Configuration Export

(defn export-config
  "Export current configuration to EDN format"
  [config]
  (with-out-str
    (clojure.pprint/pprint config)))

(defn ^:export save-config
  "Save configuration to EDN file"
  [config file-path]
  (spit file-path (export-config config))
  (log/info "Configuration saved to" file-path))