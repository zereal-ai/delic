(ns dspy.cli
  "Command-line interface for delic (dspy-clj)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [dspy.core :as core]
   [dspy.optimize :as optimize]
   [dspy.pipeline :as pipeline]
   [dspy.storage.core :as storage])
  (:gen-class))

(def cli-options
  [["-c" "--config FILE" "Configuration file (EDN format)"
    :default "config.edn"]
   ["-o" "--out FILE" "Output file (default: stdout)"]
   ["-v" "--verbose" "Enable verbose output"]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["delic - Clojure DSPy: Systematic LLM Pipeline Optimization"
        ""
        "Usage: delic [options] <command> [args]"
        ""
        "Commands:"
        "  compile <pipeline-file>    Compile EDN pipeline to executable form"
        "  optimize <pipeline-file> <trainset-file> <metric>  Optimize pipeline"
        "  version                    Show version information"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  delic compile pipeline.edn"
        "  delic optimize pipeline.edn trainset.edn exact-match"
        "  delic --config prod.edn optimize pipeline.edn trainset.edn semantic-similarity"
        ""
        "For more information, see: https://github.com/your-org/delic"]
       (str/join \newline)))

(defn error-msg [errors]
  (str "Error parsing command line arguments:\n"
       (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either returns a map indicating the program
  should exit (with an error message and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      (empty? arguments) ; no command => show usage
      {:exit-message (usage summary)}

      :else ; valid args
      {:action (first arguments) :options options :arguments (rest arguments)})))

(defn load-config
  "Load configuration from EDN file with fallback to defaults."
  [config-file]
  (try
    (if (.exists (io/file config-file))
      (edn/read-string (slurp config-file))
      (do
        (println "Warning: Config file not found, using defaults:" config-file)
        {}))
    (catch Exception e
      (println "Error loading config file:" (.getMessage e))
      (System/exit 1))))

(defn load-edn-file
  "Load EDN data from file with error handling."
  [filename]
  (try
    (edn/read-string (slurp filename))
    (catch Exception e
      (println "Error loading file" filename ":" (.getMessage e))
      (System/exit 1))))

(defn write-output
  "Write output to file or stdout."
  [data output-file]
  (let [output-str (with-out-str (clojure.pprint/pprint data))]
    (if output-file
      (spit output-file output-str)
      (print output-str))))

(defn get-metric-fn
  "Get metric function by name."
  [metric-name]
  (case metric-name
    "exact-match" optimize/exact-match-metric
    "semantic-similarity" optimize/semantic-similarity-metric
    (do
      (println "Error: Unknown metric:" metric-name)
      (println "Available metrics: exact-match, semantic-similarity")
      (System/exit 1))))

(defn compile-command
  "Handle the compile command."
  [pipeline-file _config options]
  (let [pipeline-data (load-edn-file pipeline-file)
        compiled-pipeline (pipeline/compile-pipeline (:stages pipeline-data)
                                                     (:metadata pipeline-data))]
    (when (:verbose options)
      (println "Compiled pipeline with" (count (:stages pipeline-data)) "stages"))

    (write-output {:compiled-pipeline compiled-pipeline
                   :metadata {:compiled-at (java.time.Instant/now)
                              :source-file pipeline-file
                              :config-file (:config options)}}
                  (:out options))))

(defn optimize-command
  "Handle the optimize command."
  [pipeline-file trainset-file metric-name config options]
  (let [pipeline-data (load-edn-file pipeline-file)
        trainset (load-edn-file trainset-file)
        metric-fn (get-metric-fn metric-name)

        ;; Compile pipeline
        pipeline (pipeline/compile-pipeline (:stages pipeline-data)
                                            (:metadata pipeline-data))

        ;; Setup optimization options
        opt-config (merge {:strategy :beam
                           :beam-width 4
                           :max-iterations 10
                           :concurrency 8}
                          (:optimization config)
                          (when (:verbose options) {:verbose true}))

        ;; Setup storage if configured
        storage (when (:storage config)
                  (storage/make-storage (:storage config)))]

    (when (:verbose options)
      (println "Starting optimization with strategy:" (:strategy opt-config))
      (println "Training set size:" (count trainset))
      (println "Metric function:" metric-name))

    ;; Run optimization
    (let [result @(optimize/optimize pipeline trainset metric-fn opt-config)]
      (when (:verbose options)
        (println "Optimization completed!")
        (println "Best score:" (:best-score result))
        (println "Total iterations:" (:total-iterations result))
        (println "Converged:" (:converged? result)))

      ;; Save to storage if configured
      (when storage
        (let [run-id (storage/create-run! storage pipeline)]
          (doseq [{:keys [iteration score]} (:history result)]
            (storage/append-metric! storage run-id iteration score {}))
          (when (:verbose options)
            (println "Results saved to storage with run ID:" run-id))))

      (write-output {:optimization-result result
                     :metadata {:optimized-at (java.time.Instant/now)
                                :pipeline-file pipeline-file
                                :trainset-file trainset-file
                                :metric metric-name
                                :config opt-config}}
                    (:out options)))))

(defn version-command
  "Handle the version command."
  [_options]
  (println "delic version" (core/version))
  (println "Clojure version" (clojure-version))
  (println "Java version" (System/getProperty "java.version")))

(defn -main [& args]
  (let [{:keys [action options arguments exit-message ok?]} (validate-args args)]
    (if exit-message
      (do
        (println exit-message)
        (System/exit (if ok? 0 1)))
      (let [config (load-config (:config options))]
        (try
          (case action
            "compile"
            (if (empty? arguments)
              (do (println "Error: compile command requires a pipeline file")
                  (System/exit 1))
              (compile-command (first arguments) config options))

            "optimize"
            (if (< (count arguments) 3)
              (do (println "Error: optimize command requires <pipeline-file> <trainset-file> <metric>")
                  (System/exit 1))
              (let [[pipeline-file trainset-file metric-name] arguments]
                (optimize-command pipeline-file trainset-file metric-name config options)))

            "version"
            (version-command options)

            (do
              (println "Error: Unknown command:" action)
              (println "Available commands: compile, optimize, version")
              (System/exit 1)))

          (catch Exception e
            (println "Error:" (.getMessage e))
            (when (:verbose options)
              (.printStackTrace e))
            (System/exit 1)))))))