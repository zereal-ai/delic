(ns dspy.tools.clojure-interpreter
  "SCI-based Clojure interpreter tool for safe code execution."
  (:require
   [dspy.tool :as tool]
   [manifold.deferred :as d]
   [sci.core :as sci]
   [clojure.string :as str]
   [clojure.set]
   [clojure.walk]
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]))

;; Default SCI context with safe bindings
(def default-sci-context
  "Default SCI context with commonly used namespaces and functions."
  (sci/init {:namespaces {'clojure.core (sci/copy-ns clojure.core (sci/create-ns 'clojure.core))
                          'clojure.string (sci/copy-ns clojure.string (sci/create-ns 'clojure.string))
                          'clojure.set (sci/copy-ns clojure.set (sci/create-ns 'clojure.set))
                          'clojure.walk (sci/copy-ns clojure.walk (sci/create-ns 'clojure.walk))
                          'clojure.pprint (sci/copy-ns clojure.pprint (sci/create-ns 'clojure.pprint))
                          'clojure.edn (sci/copy-ns clojure.edn (sci/create-ns 'clojure.edn))}
             :classes {'java.lang.String String
                       'java.lang.Long Long
                       'java.lang.Double Double
                       'java.lang.Integer Integer
                       'java.lang.Boolean Boolean
                       'java.util.Date java.util.Date
                       'java.util.UUID java.util.UUID
                       'java.math.BigDecimal BigDecimal
                       'java.math.BigInteger BigInteger}}))

(defn safe-eval
  "Safely evaluate Clojure code using SCI.

   Args:
     code - String containing Clojure code to evaluate

   Options:
     :context - SCI context to use (default: default-sci-context)
     :timeout-ms - Timeout in milliseconds (default: 5000)
     :capture-output? - Whether to capture printed output (default: true)

   Returns:
     Map with :result, :output, :error, and :execution-time-ms"
  [code & {:keys [context timeout-ms capture-output?]
           :or {context default-sci-context
                timeout-ms 5000
                capture-output? true}}]
  (let [start-time (System/currentTimeMillis)
        output-buffer (if capture-output? (java.io.StringWriter.) nil)]
    (try
      (let [result (if capture-output?
                     (binding [*out* output-buffer]
                       (sci/eval-string* context code))
                     (sci/eval-string* context code))
            execution-time (- (System/currentTimeMillis) start-time)
            captured-output (if capture-output? (.toString output-buffer) nil)]
        {:result result
         :output captured-output
         :error nil
         :execution-time-ms execution-time})
      (catch Exception e
        (let [execution-time (- (System/currentTimeMillis) start-time)]
          {:result nil
           :output (if capture-output? (.toString output-buffer) nil)
           :error (ex-message e)
           :execution-time-ms execution-time})))))

(defn format-result
  "Format evaluation result for display.

   Args:
     result - Result from safe-eval

   Returns:
     Formatted string representation"
  [result]
  (let [{:keys [result output error execution-time-ms]} result]
    (str
     (when output
       (str "Output:\n" output "\n"))
     (if error
       (str "Error: " error)
       (str "Result: " (with-out-str (pprint/pprint result))))
     (str "\nExecution time: " execution-time-ms "ms"))))

(defn create-clojure-interpreter
  "Create a ClojureInterpreter tool instance.

   Options:
     :context - Custom SCI context to use
     :timeout-ms - Default timeout for code execution
     :capture-output? - Whether to capture printed output by default
     :name - Custom name for the tool (default: :clojure-interpreter)
     :description - Custom description for the tool

   Returns:
     Tool instance implementing ITool protocol"
  [& {:keys [context timeout-ms capture-output? name description]
      :or {context default-sci-context
           timeout-ms 5000
           capture-output? true
           name :clojure-interpreter
           description "Clojure interpreter that can safely execute Clojure code using SCI"}}]

  (tool/simple-tool
   name
   description
   [:map
    [:code :string]
    [:timeout-ms {:optional true} :int]
    [:capture-output {:optional true} :boolean]]
   [:map
    [:result :any]
    [:output [:maybe :string]]
    [:error [:maybe :string]]
    [:execution-time-ms :int]
    [:formatted-result :string]]

   (fn [inputs]
     (let [code (:code inputs)
           timeout (or (:timeout-ms inputs) timeout-ms)
           capture? (if (contains? inputs :capture-output)
                      (:capture-output inputs)
                      capture-output?)
           eval-result (safe-eval code
                                  :context context
                                  :timeout-ms timeout
                                  :capture-output? capture?)
           formatted (format-result eval-result)]
       (assoc eval-result :formatted-result formatted)))))

;; Utility functions for working with interpreter results

(defn extract-result
  "Extract just the result value from interpreter output."
  [interpreter-result]
  (:result interpreter-result))

(defn extract-output
  "Extract captured output from interpreter result."
  [interpreter-result]
  (:output interpreter-result))

(defn extract-error
  "Extract error message from interpreter result."
  [interpreter-result]
  (:error interpreter-result))

(defn successful?
  "Check if interpreter execution was successful."
  [interpreter-result]
  (nil? (:error interpreter-result)))

(defn execution-time
  "Get execution time from interpreter result."
  [interpreter-result]
  (:execution-time-ms interpreter-result))

(defn format-for-llm
  "Format interpreter result for LLM consumption."
  [interpreter-result]
  (let [{:keys [result output error execution-time-ms]} interpreter-result]
    (if error
      (str "Execution failed with error: " error)
      (str "Execution successful.\n"
           (when output (str "Output: " output "\n"))
           "Result: " (pr-str result) "\n"
           "Execution time: " execution-time-ms "ms"))))

;; Pre-configured interpreter instances

(defonce ^:dynamic *default-interpreter*
  (create-clojure-interpreter))

;; Convenience functions for direct evaluation

(defn eval-clojure
  "Evaluate Clojure code using the default interpreter."
  [code]
  (tool/invoke-tool *default-interpreter* {:code code}))