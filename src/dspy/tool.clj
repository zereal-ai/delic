(ns dspy.tool
  "Tool protocol and infrastructure for external tool integration."
  (:require
   [malli.core :as m]
   [malli.generator :as mg]
   [manifold.deferred :as d]
   [clojure.string :as str]))

(defprotocol ITool
  "Protocol for tools that can be invoked by LLM modules.

   Tools provide external capabilities like code execution, web search,
   database queries, API calls, etc. All methods return deferreds for
   async execution."

  (-name [this]
    "Return the name of this tool as a keyword.
     Used for tool identification and invocation.")

  (-description [this]
    "Return a human-readable description of what this tool does.
     Used for LLM context and documentation.")

  (-input-schema [this]
    "Return a Malli schema describing the expected input format.
     Used for input validation and LLM context generation.")

  (-output-schema [this]
    "Return a Malli schema describing the output format.
     Used for output validation and type checking.")

  (-invoke [this inputs]
    "Invoke the tool with the given inputs.

     Args:
       inputs - Map conforming to the input schema

     Returns:
       Deferred containing map conforming to output schema"))

;; Tool registry for dynamic tool management
(defonce ^:private tool-registry (atom {}))

(defn register-tool!
  "Register a tool in the global tool registry.

   Args:
     tool - Tool instance implementing ITool protocol

   Returns:
     The registered tool"
  [tool]
  (let [tool-name (-name tool)]
    (swap! tool-registry assoc tool-name tool)
    tool))

(defn unregister-tool!
  "Remove a tool from the global registry.

   Args:
     tool-name - Keyword name of the tool to remove"
  [tool-name]
  (swap! tool-registry dissoc tool-name))

(defn get-tool
  "Get a tool from the registry by name.

   Args:
     tool-name - Keyword name of the tool

   Returns:
     Tool instance or nil if not found"
  [tool-name]
  (get @tool-registry tool-name))

(defn list-tools
  "List all registered tools.

   Returns:
     Vector of tool names (keywords)"
  []
  (vec (keys @tool-registry)))

(defn get-all-tools
  "Get all registered tools as a map.

   Returns:
     Map of tool-name -> tool-instance"
  []
  @tool-registry)

;; Tool validation and introspection

(defn tool?
  "Check if an object implements the ITool protocol."
  [obj]
  (satisfies? ITool obj))

(defn validate-tool-input
  "Validate input data against a tool's input schema.

   Args:
     tool - Tool instance
     inputs - Input data to validate

   Returns:
     true if valid, false otherwise"
  [tool inputs]
  (when-let [schema (-input-schema tool)]
    (m/validate schema inputs)))

(defn validate-tool-output
  "Validate output data against a tool's output schema.

   Args:
     tool - Tool instance
     outputs - Output data to validate

   Returns:
     true if valid, false otherwise"
  [tool outputs]
  (when-let [schema (-output-schema tool)]
    (m/validate schema outputs)))

(defn explain-tool-input
  "Explain why tool input validation failed.

   Args:
     tool - Tool instance
     inputs - Input data that failed validation

   Returns:
     Human-readable explanation of validation failure"
  [tool inputs]
  (when-let [schema (-input-schema tool)]
    (m/explain schema inputs)))

(defn explain-tool-output
  "Explain why tool output validation failed.

   Args:
     tool - Tool instance
     outputs - Output data that failed validation

   Returns:
     Human-readable explanation of validation failure"
  [tool outputs]
  (when-let [schema (-output-schema tool)]
    (m/explain schema outputs)))

;; Tool execution with validation and error handling

(defn invoke-tool
  "Invoke a tool with input validation and error handling.

   Args:
     tool - Tool instance
     inputs - Input data

   Options:
     :validate-input? - Whether to validate inputs (default: true)
     :validate-output? - Whether to validate outputs (default: true)
     :timeout-ms - Timeout in milliseconds (default: 30000)

   Returns:
     Deferred containing validated output or error"
  [tool inputs & {:keys [validate-input? validate-output? timeout-ms]
                  :or {validate-input? true
                       validate-output? true
                       timeout-ms 30000}}]
  (try
    ;; Validate inputs if requested
    (when validate-input?
      (when-not (validate-tool-input tool inputs)
        (throw (ex-info "Tool input validation failed"
                        {:tool (-name tool)
                         :inputs inputs
                         :explanation (explain-tool-input tool inputs)}))))

    ;; Invoke the tool with timeout
    (d/chain
     (d/timeout! (-invoke tool inputs) timeout-ms)
     (fn [outputs]
       ;; Validate outputs if requested
       (when validate-output?
         (when-not (validate-tool-output tool outputs)
           (throw (ex-info "Tool output validation failed"
                           {:tool (-name tool)
                            :outputs outputs
                            :explanation (explain-tool-output tool outputs)}))))
       outputs))

    (catch Exception e
      (d/error-deferred e))))

(defn invoke-tool-by-name
  "Invoke a tool by name from the registry.

   Args:
     tool-name - Keyword name of the tool
     inputs - Input data

   Options:
     Same as invoke-tool

   Returns:
     Deferred containing validated output or error"
  [tool-name inputs & opts]
  (if-let [tool (get-tool tool-name)]
    (apply invoke-tool tool inputs opts)
    (d/error-deferred
     (ex-info "Tool not found in registry"
              {:tool-name tool-name
               :available-tools (list-tools)}))))

;; Tool context for modules

(defrecord ToolContext [tools metadata]
  ;; ToolContext provides tools to modules that need them

  Object
  (toString [this]
    (str "ToolContext{tools: " (vec (keys tools)) "}")))

(defn create-tool-context
  "Create a tool context for passing to modules.

   Args:
     tools - Map of tool-name -> tool-instance or vector of tools

   Options:
     :metadata - Additional metadata about the context

   Returns:
     ToolContext instance"
  [tools & {:keys [metadata]}]
  (let [tool-map (if (map? tools)
                   tools
                   (into {} (map (fn [tool] [(-name tool) tool]) tools)))]
    (->ToolContext tool-map metadata)))

(defn get-tool-from-context
  "Get a tool from a tool context.

   Args:
     context - ToolContext instance
     tool-name - Keyword name of the tool

   Returns:
     Tool instance or nil if not found"
  [context tool-name]
  (get (:tools context) tool-name))

(defn list-tools-in-context
  "List all tools available in a context.

   Args:
     context - ToolContext instance

   Returns:
     Vector of tool names (keywords)"
  [context]
  (vec (keys (:tools context))))

(defn invoke-tool-from-context
  "Invoke a tool from a context.

   Args:
     context - ToolContext instance
     tool-name - Keyword name of the tool
     inputs - Input data

   Options:
     Same as invoke-tool

   Returns:
     Deferred containing validated output or error"
  [context tool-name inputs & opts]
  (if-let [tool (get-tool-from-context context tool-name)]
    (apply invoke-tool tool inputs opts)
    (d/error-deferred
     (ex-info "Tool not found in context"
              {:tool-name tool-name
               :available-tools (list-tools-in-context context)
               :context context}))))

;; Tool information and introspection

(defn tool-info
  "Get comprehensive information about a tool.

   Args:
     tool - Tool instance

   Returns:
     Map with tool details including schemas and examples"
  [tool]
  {:name (-name tool)
   :description (-description tool)
   :input-schema (-input-schema tool)
   :output-schema (-output-schema tool)
   :input-example (when-let [schema (-input-schema tool)]
                    (try (mg/generate schema) (catch Exception _ nil)))
   :output-example (when-let [schema (-output-schema tool)]
                     (try (mg/generate schema) (catch Exception _ nil)))})

(defn describe-tool
  "Get a human-readable description of a tool including its interface.

   Args:
     tool - Tool instance

   Returns:
     String description suitable for LLM context"
  [tool]
  (let [info (tool-info tool)]
    (str "Tool: " (:name info) "\n"
         "Description: " (:description info) "\n"
         "Input Schema: " (:input-schema info) "\n"
         "Output Schema: " (:output-schema info) "\n"
         (when (:input-example info)
           (str "Example Input: " (:input-example info) "\n"))
         (when (:output-example info)
           (str "Example Output: " (:output-example info) "\n")))))

(defn describe-tools-in-context
  "Get descriptions of all tools in a context.

   Args:
     context - ToolContext instance

   Returns:
     String with descriptions of all tools"
  [context]
  (let [tools (vals (:tools context))]
    (str/join "\n\n" (map describe-tool tools))))

;; Utility functions for tool creation

(defn simple-tool
  "Create a simple tool from a function.

   Args:
     name - Keyword name for the tool
     description - String description
     input-schema - Malli schema for inputs
     output-schema - Malli schema for outputs
     fn - Function that takes inputs and returns outputs (can return deferred)

   Returns:
     Tool instance implementing ITool"
  [name description input-schema output-schema fn]
  (reify ITool
    (-name [this] name)
    (-description [this] description)
    (-input-schema [this] input-schema)
    (-output-schema [this] output-schema)
    (-invoke [this inputs]
      (try
        (let [result (fn inputs)]
          (if (d/deferred? result)
            result
            (d/success-deferred result)))
        (catch Exception e
          (d/error-deferred e))))))

(defn echo-tool
  "Create a simple echo tool for testing.

   Returns:
     Tool that echoes its input as output"
  []
  (simple-tool
   :echo
   "Echo tool that returns its input unchanged"
   [:map [:message :string]]
   [:map [:echo :string]]
   (fn [inputs] {:echo (:message inputs)})))

(defn math-tool
  "Create a simple math evaluation tool for testing.

   Returns:
     Tool that evaluates basic math expressions"
  []
  (simple-tool
   :math
   "Math tool that evaluates simple arithmetic expressions"
   [:map [:expression :string]]
   [:map [:result :any] [:error [:maybe :string]]]
   (fn [inputs]
     (try
       (let [expr (:expression inputs)
             ;; Simple evaluation - only allow basic operations
             result (cond
                      (re-matches #"\d+\s*\+\s*\d+" expr)
                      (let [[a b] (map #(Integer/parseInt (str/trim %)) (str/split expr #"\+"))]
                        (+ a b))

                      (re-matches #"\d+\s*-\s*\d+" expr)
                      (let [[a b] (map #(Integer/parseInt (str/trim %)) (str/split expr #"-"))]
                        (- a b))

                      (re-matches #"\d+\s*\*\s*\d+" expr)
                      (let [[a b] (map #(Integer/parseInt (str/trim %)) (str/split expr #"\*"))]
                        (* a b))

                      (re-matches #"\d+\s*/\s*\d+" expr)
                      (let [[a b] (map #(Integer/parseInt (str/trim %)) (str/split expr #"/"))]
                        (if (zero? b)
                          (throw (ex-info "Division by zero" {}))
                          (/ a b)))

                      :else
                      (throw (ex-info "Unsupported expression" {:expression expr})))]
         {:result result :error nil})
       (catch Exception e
         {:result nil :error (ex-message e)})))))

;; Tool execution statistics and monitoring

(defonce ^:private tool-stats (atom {}))

(defn get-tool-stats
  "Get execution statistics for a tool.

   Args:
     tool-name - Keyword name of the tool

   Returns:
     Map with execution statistics"
  [tool-name]
  (get @tool-stats tool-name {:invocations 0 :errors 0 :total-time-ms 0}))

(defn reset-tool-stats!
  "Reset execution statistics for all tools."
  []
  (reset! tool-stats {}))

(defn- update-tool-stats!
  "Update execution statistics for a tool."
  [tool-name success? duration-ms]
  (swap! tool-stats update tool-name
         (fn [stats]
           (let [current (or stats {:invocations 0 :errors 0 :total-time-ms 0})]
             (-> current
                 (update :invocations inc)
                 (update :errors (if success? identity inc))
                 (update :total-time-ms + duration-ms))))))

(defn monitored-invoke-tool
  "Invoke a tool with execution monitoring and statistics.

   Same interface as invoke-tool but tracks execution statistics.

   Returns:
     Deferred containing validated output or error"
  [tool inputs & opts]
  (let [start-time (System/currentTimeMillis)
        tool-name (-name tool)]
    (d/catch
     (d/chain
      (apply invoke-tool tool inputs opts)
      (fn [result]
        (let [duration (- (System/currentTimeMillis) start-time)]
          (update-tool-stats! tool-name true duration)
          result)))
     (fn [error]
       (let [duration (- (System/currentTimeMillis) start-time)]
         (update-tool-stats! tool-name false duration)
         (d/error-deferred error))))))

(defn get-all-tool-stats
  "Get execution statistics for all tools.

   Returns:
     Map of tool-name -> statistics"
  []
  @tool-stats)