(ns dspy.modules.react
  "ReAct (Reasoning and Acting) module implementation.

   ReAct combines reasoning and acting in an iterative loop:
   1. Thought: LLM reasons about the current situation
   2. Action: LLM decides to use a tool
   3. Observation: Tool execution result is observed
   4. Repeat until final answer is reached"
  (:require
   [dspy.module :as mod]
   [dspy.signature :as sig]
   [dspy.tool :as tool]
   [dspy.backend.protocol :as bp]
   [manifold.deferred :as d]
   [clojure.string :as str]
   [clojure.edn :as edn]))

;; ReAct Signature Definition
(sig/defsignature ReActSignature
  (question => answer))

;; ReAct Prompt Templates
(def react-prompt-template
  "You are solving a problem step by step using tools when needed.

Available tools:
%s

Question: %s

You should think step-by-step and use tools when necessary. Follow this format:

Thought: [your reasoning about what to do next]
Action: [tool_name]
Action Input: [input as valid EDN data]
Observation: [tool execution result - this will be filled automatically]

If you can answer the question without using any tools, provide:
Thought: [your reasoning]
Answer: [your final answer]

Begin:")

(def react-examples
  "Example ReAct reasoning:

Question: What is 15 * 23?

Thought: I need to calculate 15 * 23. I can use the math tool for this calculation.
Action: math-tool
Action Input: {:expression \"(* 15 23)\"}
Observation: {:result 345}

Thought: The calculation shows that 15 * 23 equals 345.
Answer: 345")

;; Response Parsing Functions
(defn parse-react-response
  "Parse LLM response into ReAct steps."
  [response-text]
  (let [lines (str/split-lines response-text)
        steps []]
    (loop [remaining-lines lines
           current-steps steps
           current-type nil
           current-content ""]
      (if (empty? remaining-lines)
        ;; End of input - add final step if any
        (if (and current-type (not (str/blank? current-content)))
          (conj current-steps {:type current-type :content (str/trim current-content)})
          current-steps)

        (let [line (first remaining-lines)
              rest-lines (rest remaining-lines)]
          (cond
            ;; Thought line
            (str/starts-with? line "Thought:")
            (let [new-steps (if (and current-type (not (str/blank? current-content)))
                              (conj current-steps {:type current-type :content (str/trim current-content)})
                              current-steps)]
              (recur rest-lines new-steps :thought (subs line 8)))

            ;; Action line
            (str/starts-with? line "Action:")
            (let [new-steps (if (and current-type (not (str/blank? current-content)))
                              (conj current-steps {:type current-type :content (str/trim current-content)})
                              current-steps)]
              (recur rest-lines new-steps :action (subs line 7)))

            ;; Action Input line
            (str/starts-with? line "Action Input:")
            (let [new-steps (if (and current-type (not (str/blank? current-content)))
                              (conj current-steps {:type current-type :content (str/trim current-content)})
                              current-steps)]
              (recur rest-lines new-steps :action-input (subs line 13)))

            ;; Observation line
            (str/starts-with? line "Observation:")
            (let [new-steps (if (and current-type (not (str/blank? current-content)))
                              (conj current-steps {:type current-type :content (str/trim current-content)})
                              current-steps)]
              (recur rest-lines new-steps :observation (subs line 12)))

            ;; Answer line
            (str/starts-with? line "Answer:")
            (let [new-steps (if (and current-type (not (str/blank? current-content)))
                              (conj current-steps {:type current-type :content (str/trim current-content)})
                              current-steps)]
              (recur rest-lines new-steps :answer (subs line 7)))

            ;; Continuation line
            :else
            (recur rest-lines current-steps current-type (str current-content "\n" line))))))))

(defn find-final-answer
  "Extract final answer from parsed ReAct steps."
  [steps]
  (when-let [answer-step (->> steps
                              (filter #(= :answer (:type %)))
                              first)]
    (when-let [content (:content answer-step)]
      (str/trim content))))

(defn extract-action-steps
  "Extract action steps that need tool execution."
  [steps]
  (let [actions (filter #(= :action (:type %)) steps)
        action-inputs (filter #(= :action-input (:type %)) steps)]
    (map (fn [action action-input]
           (let [action-name (str/trim (:content action))
                 action-input-text (str/trim (:content action-input))]
             (try
               {:action-name (keyword action-name)
                :action-input (edn/read-string action-input-text)
                :parse-error false}
               (catch Exception e
                 {:action-name (keyword action-name)
                  :action-input action-input-text
                  :parse-error true
                  :error (.getMessage e)}))))
         actions action-inputs)))

;; Tool Execution Functions
(defn execute-action-step
  "Execute a single action step using available tools."
  [tools action-name action-input]
  (if-let [tool-obj (tool/get-tool-from-context tools action-name)]
    (d/chain
     (tool/invoke-tool-from-context tools action-name action-input)
     (fn [result]
       {:success true
        :tool action-name
        :input action-input
        :output result}))
    (d/success-deferred
     {:success false
      :error (str "Tool not found: " action-name)
      :available-tools (tool/list-tools-in-context tools)})))

(defn format-observation
  "Format tool execution result as observation text."
  [result]
  (if (:success result)
    (str "Observation: " (pr-str (:output result)))
    (str "Observation: Error - " (:error result))))

;; Main ReAct Execution Function
(defn execute-react-step
  "Execute a complete ReAct reasoning step."
  [backend tools conversation-history max-iterations]
  (letfn [(step-loop [history iteration all-steps]
            (if (>= iteration max-iterations)
              (d/success-deferred
               {:final-answer "Maximum iterations reached without final answer."
                :steps all-steps
                :conversation history
                :truncated true})

              (d/chain
               ;; Generate LLM response
               (bp/generate backend history {})

               (fn [llm-response]
                 (let [response-text (if (string? llm-response) llm-response (:text llm-response))
                       parsed-steps (parse-react-response response-text)
                       updated-history (str history "\n" response-text)]

                   ;; Check for final answer
                   (if-let [final-answer (find-final-answer parsed-steps)]
                     (d/success-deferred
                      {:final-answer final-answer
                       :steps (concat all-steps parsed-steps)
                       :conversation updated-history
                       :truncated false})

                     ;; Execute actions
                     (let [action-steps (extract-action-steps parsed-steps)]
                       (if (empty? action-steps)
                         ;; No actions found, continue reasoning
                         (step-loop updated-history (inc iteration) (concat all-steps parsed-steps))

                         ;; Execute the first action step
                         (let [{:keys [action-name action-input parse-error]} (first action-steps)]
                           (if parse-error
                             ;; Action input parse error
                             (let [error-obs (format-observation {:success false :error "Failed to parse action input as EDN"})
                                   error-history (str updated-history "\n" error-obs)]
                               (step-loop error-history (inc iteration) (concat all-steps parsed-steps)))

                             ;; Execute action
                             (d/chain
                              (execute-action-step tools action-name action-input)
                              (fn [action-result]
                                (let [observation (format-observation action-result)
                                      action-history (str updated-history "\n" observation)]
                                  (step-loop action-history (inc iteration) (concat all-steps parsed-steps)))))))))))))))]
    (step-loop conversation-history 0 [])))

;; ReAct Module Implementation
(defrecord ReAct [backend tools max-iterations include-examples? original-signature]
  mod/ILlmModule

  (call [this inputs]
    (let [question (:question inputs)
          tool-descriptions (tool/describe-tools-in-context tools)
          initial-prompt (format react-prompt-template tool-descriptions question)
          conversation-history (if include-examples?
                                 (str react-examples "\n\n" initial-prompt)
                                 initial-prompt)]

      (d/chain
       (execute-react-step backend tools conversation-history max-iterations)
       (fn [react-result]
         {:question question
          :answer (:final-answer react-result)
          :react-steps (:steps react-result)
          :react-conversation (:conversation react-result)
          :react-truncated (:truncated react-result)})))))

;; ReAct Module Constructor
(defn react
  "Create a ReAct module with tool integration.

   Args:
     signature - Input/output signature (default: ReActSignature)
     backend - LLM backend for generating responses
     tools - ToolContext with available tools
     options - Configuration options:
       :max-iterations - Maximum reasoning iterations (default: 10)
       :include-examples? - Include examples in prompt (default: true)

   Returns:
     ReAct module implementing ILlmModule"
  ([backend tools] (react ReActSignature backend tools {}))
  ([signature backend tools] (react signature backend tools {}))
  ([signature backend tools options]
   (let [config (merge {:max-iterations 10
                        :include-examples? true}
                       options)]
     (->ReAct backend
              tools
              (:max-iterations config)
              (:include-examples? config)
              signature))))

;; Utility Functions for ReAct Output Analysis
(defn extract-react-steps
  "Extract ReAct steps from module output."
  [react-output]
  (:react-steps react-output))

(defn extract-react-conversation
  "Extract complete conversation from ReAct output."
  [react-output]
  (:react-conversation react-output))

(defn was-truncated?
  "Check if ReAct execution was truncated due to max iterations."
  [react-output]
  (:react-truncated react-output false))

(defn count-action-steps
  "Count the number of action steps executed."
  [react-output]
  (->> (extract-react-steps react-output)
       (filter #(= :action (:type %)))
       count))

(defn extract-tool-usage
  "Extract tool usage statistics from ReAct output."
  [react-output]
  (->> (extract-react-steps react-output)
       (filter #(= :action (:type %)))
       (map :action-name)
       frequencies))

(defn format-react-trace
  "Format ReAct execution trace for debugging/logging."
  [react-output]
  (let [steps (extract-react-steps react-output)
        conversation (extract-react-conversation react-output)
        truncated (was-truncated? react-output)
        action-count (count-action-steps react-output)
        tool-usage (extract-tool-usage react-output)]

    {:summary {:action-steps action-count
               :total-steps (count steps)
               :truncated truncated
               :tool-usage tool-usage}
     :steps steps
     :conversation conversation}))