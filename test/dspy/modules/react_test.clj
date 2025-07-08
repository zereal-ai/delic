(ns dspy.modules.react-test
  "Tests for ReAct (Reasoning and Acting) module."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dspy.modules.react :as react]
   [dspy.signature :as sig]
   [dspy.module :as mod]
   [dspy.tool :as tool]
   [dspy.backend.protocol :as bp]
   [manifold.deferred :as d]
   [malli.core]))

;; Test signatures
(sig/defsignature QA (question => answer))
(sig/defsignature MathProblem (problem => solution))

;; Mock backend for testing
(defrecord MockBackend [responses call-count]
  bp/ILlmBackend
  (-generate [this prompt options]
    (let [count (swap! call-count inc)
          response (nth @responses (dec count) (last @responses))]
      (d/success-deferred response))))

(defn mock-backend [& responses]
  (->MockBackend (atom responses) (atom 0)))

;; Test tools
(def test-math-tool
  (tool/simple-tool
   :math-tool
   "Simple math calculator"
   [:map [:expression :string]]
   [:map [:result :any]]
   (fn [inputs]
     (let [expr (:expression inputs)]
       (try
         {:result (eval (read-string expr))}
         (catch Exception e
           {:result nil :error (.getMessage e)}))))))

(def test-error-tool
  (tool/simple-tool
   :error-tool
   "Tool that always fails"
   [:map [:input :string]]
   [:map [:result {:optional true} :any] [:error {:optional true} :string]]
   (fn [inputs]
     {:error "Tool execution failed"})))

(deftest test-react-signature
  (testing "ReActSignature is properly defined"
    (is (some? react/ReActSignature))
    (is (= [:question] (:inputs react/ReActSignature)))
    (is (= [:answer] (:outputs react/ReActSignature)))))

(deftest test-parse-react-response
  (testing "Parses simple thought and answer"
    (let [response "Thought: I need to think about this.\nAnswer: 42"
          parsed (react/parse-react-response response)]
      (is (= 2 (count parsed)))
      (is (= :thought (:type (first parsed))))
      (is (= "I need to think about this." (:content (first parsed))))
      (is (= :answer (:type (second parsed))))
      (is (= "42" (:content (second parsed))))))

  (testing "Parses action and action input"
    (let [response "Thought: I need to use a tool.\nAction: math-tool\nAction Input: {:expression \"(+ 2 2)\"}"
          parsed (react/parse-react-response response)]
      (is (= 3 (count parsed)))
      (is (= :thought (:type (first parsed))))
      (is (= :action (:type (second parsed))))
      (is (= "math-tool" (:content (second parsed))))
      (is (= :action-input (:type (nth parsed 2))))
      (is (= "{:expression \"(+ 2 2)\"}" (:content (nth parsed 2))))))

  (testing "Handles multiple thoughts"
    (let [response "Thought: First thought.\nThought: Second thought.\nAnswer: Done"
          parsed (react/parse-react-response response)]
      (is (= 3 (count parsed)))
      (is (every? #(#{:thought :answer} (:type %)) parsed))))

  (testing "Handles empty lines and whitespace"
    (let [response "\n\nThought: Clean thought.\n\n\nAnswer: Clean answer.\n\n"
          parsed (react/parse-react-response response)]
      (is (= 2 (count parsed)))
      (is (= "Clean thought." (:content (first parsed))))
      (is (= "Clean answer." (:content (second parsed)))))))

(deftest test-find-final-answer
  (testing "Finds final answer from steps"
    (let [steps [{:type :thought :content "Thinking"}
                 {:type :action :content "tool"}
                 {:type :answer :content "Final answer"}]]
      (is (= "Final answer" (react/find-final-answer steps)))))

  (testing "Returns nil when no answer found"
    (let [steps [{:type :thought :content "Thinking"}
                 {:type :action :content "tool"}]]
      (is (nil? (react/find-final-answer steps)))))

  (testing "Handles nil content in answer step"
    (let [steps [{:type :answer :content nil}]]
      (is (nil? (react/find-final-answer steps)))))

  (testing "Trims whitespace from answer"
    (let [steps [{:type :answer :content "  Final answer  "}]]
      (is (= "Final answer" (react/find-final-answer steps))))))

(deftest test-extract-action-steps
  (testing "Extracts action steps needing execution"
    (let [steps [{:type :thought :content "Thinking"}
                 {:type :action :content "math-tool"}
                 {:type :action-input :content "{:expression \\\"(+ 2 2)\\\"}"}
                 {:type :thought :content "More thinking"}]]
      (is (= 1 (count (react/extract-action-steps steps))))))

  (testing "Returns empty when no actions"
    (let [steps [{:type :thought :content "Thinking"}
                 {:type :answer :content "Done"}]]
      (is (empty? (react/extract-action-steps steps)))))

  (testing "Groups action with action-input"
    (let [steps [{:type :action :content "math-tool"}
                 {:type :action-input :content "{:expression \"(+ 2 2)\"}"}]
          actions (react/extract-action-steps steps)]
      (is (= 1 (count actions)))
      (is (= :math-tool (:action-name (first actions))))
      (is (= {:expression "(+ 2 2)"} (:action-input (first actions)))))))

(deftest test-execute-action-step
  (testing "Executes tool successfully"
    (tool/register-tool! test-math-tool)
    (let [tools (tool/create-tool-context [test-math-tool])
          result @(react/execute-action-step tools :math-tool {:expression "(+ 2 2)"})]
      (is (true? (:success result)))
      (is (= :math-tool (:tool result)))
      (is (= {:result 4} (:output result)))))

  (testing "Handles tool not found"
    (let [tools (tool/create-tool-context [])
          result @(react/execute-action-step tools :non-existent {})]
      (is (false? (:success result)))
      (is (.contains (:error result) "Tool not found"))))

  (testing "Handles tool execution error"
    (tool/register-tool! test-error-tool)
    (let [tools (tool/create-tool-context [test-error-tool])
          result @(react/execute-action-step tools :error-tool {:input "test"})]
      (is (true? (:success result))) ; Tool execution succeeds, but returns error in output
      (is (= {:error "Tool execution failed"} (:output result))))))

(deftest test-react-creation
  (testing "Creates ReAct module with default signature"
    (let [backend (mock-backend "Answer: test")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)]
      (is (some? react-module))
      (is (satisfies? mod/ILlmModule react-module))))

  (testing "Creates ReAct module with custom signature"
    (let [backend (mock-backend "Answer: test")
          tools (tool/create-tool-context [])
          react-module (react/react QA backend tools)]
      (is (some? react-module))
      (is (satisfies? mod/ILlmModule react-module))))

  (testing "Creates ReAct module with options"
    (let [backend (mock-backend "Answer: test")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools {:max-iterations 5 :include-examples? false})]
      (is (some? react-module))
      (is (satisfies? mod/ILlmModule react-module)))))

(deftest test-react-execution-simple
  (testing "Executes simple ReAct without tools"
    (let [backend (mock-backend "Thought: Simple reasoning.\nAnswer: 42")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "What is the answer?"}
          result @(mod/call react-module inputs)]
      (is (= "What is the answer?" (:question result)))
      (is (= "42" (:answer result)))
      (is (= 2 (count (:react-steps result))))
      (is (false? (:react-truncated result)))))

  (testing "Validates inputs against signature"
    (let [backend (mock-backend "Answer: test")
          tools (tool/create-tool-context [])
          react-module (react/react QA backend tools)
          invalid-inputs {:wrong-field "value"}]
      ;; Note: Currently ReAct doesn't validate inputs, but this test is here for future enhancement
      (is (some? @(mod/call react-module invalid-inputs))))))

(deftest test-react-execution-with-tools
  (testing "Executes ReAct with successful tool usage"
    (tool/register-tool! test-math-tool)
    (let [backend (mock-backend
                   "Thought: I need to calculate 2+2.\nAction: math-tool\nAction Input: {:expression \"(+ 2 2)\"}"
                   "Thought: The result is 4.\nAnswer: 4")
          tools (tool/create-tool-context [test-math-tool])
          react-module (react/react backend tools)
          inputs {:question "What is 2+2?"}
          result @(mod/call react-module inputs)]
      (is (= "What is 2+2?" (:question result)))
      (is (= "4" (:answer result)))
      (is (> (count (:react-steps result)) 3)) ; Should have thought, action, action-input, thought, answer
      (is (.contains (:react-conversation result) "Observation: {:result 4}"))
      (is (false? (:react-truncated result)))))

  (testing "Handles tool errors gracefully"
    (tool/register-tool! test-error-tool)
    (let [backend (mock-backend
                   "Thought: I'll use the error tool.\nAction: error-tool\nAction Input: {:input \"test\"}"
                   "Thought: The tool failed.\nAnswer: Tool execution failed")
          tools (tool/create-tool-context [test-error-tool])
          react-module (react/react backend tools)
          inputs {:question "Test error handling"}
          result @(mod/call react-module inputs)]
      (is (= "Tool execution failed" (:answer result)))
      (is (.contains (:react-conversation result) "Tool execution failed"))
      (is (false? (:react-truncated result)))))

  (testing "Handles non-existent tools"
    (let [backend (mock-backend
                   "Thought: I'll use a non-existent tool.\nAction: fake-tool\nAction Input: {}"
                   "Thought: Tool not found.\nAnswer: Tool is not available")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "Test non-existent tool"}
          result @(mod/call react-module inputs)]
      (is (= "Tool is not available" (:answer result)))
      (is (.contains (:react-conversation result) "Tool not found"))
      (is (false? (:react-truncated result))))))

(deftest test-react-execution-limits
  (testing "Respects max iterations limit"
    (let [backend (mock-backend "Thought: Keep thinking.\nAction: math-tool\nAction Input: {}")
          tools (tool/create-tool-context [])
          react-module (react/react react/ReActSignature backend tools {:max-iterations 2})
          inputs {:question "Infinite loop test"}
          result @(mod/call react-module inputs)]
      (is (.contains (:answer result) "Maximum iterations reached"))
      (is (true? (:react-truncated result)))))

  (testing "Works with custom max iterations"
    (tool/register-tool! test-math-tool)
    (let [backend (mock-backend
                   "Thought: First.\nAction: math-tool\nAction Input: {:expression \"1\"}"
                   "Thought: Second.\nAction: math-tool\nAction Input: {:expression \"2\"}"
                   "Thought: Third.\nAnswer: Done")
          tools (tool/create-tool-context [test-math-tool])
          react-module (react/react react/ReActSignature backend tools {:max-iterations 5})
          inputs {:question "Multi-step test"}
          result @(mod/call react-module inputs)]
      (is (= "Done" (:answer result)))
      (is (false? (:react-truncated result))))))

(deftest test-react-configuration
  (testing "Include examples configuration"
    (let [backend (mock-backend "Answer: test")
          tools (tool/create-tool-context [])
          react-with-examples (react/react react/ReActSignature backend tools {:include-examples? true})
          react-without-examples (react/react react/ReActSignature backend tools {:include-examples? false})
          inputs {:question "Test"}
          result-with @(mod/call react-with-examples inputs)
          result-without @(mod/call react-without-examples inputs)]
      ;; With examples should have longer conversation history
      (is (> (count (:react-conversation result-with))
             (count (:react-conversation result-without))))))

  (testing "Max iterations configuration"
    (let [backend (mock-backend "Thought: Loop")
          tools (tool/create-tool-context [])
          react-short (react/react react/ReActSignature backend tools {:max-iterations 1})
          react-long (react/react react/ReActSignature backend tools {:max-iterations 10})
          inputs {:question "Test"}
          result-short @(mod/call react-short inputs)
          result-long @(mod/call react-long inputs)]
      ;; Both should truncate, but at different points
      (is (true? (:react-truncated result-short)))
      (is (true? (:react-truncated result-long)))
      ;; Short should have fewer steps
      (is (< (count (:react-steps result-short))
             (count (:react-steps result-long)))))))

(deftest test-integration-with-module-system
  (testing "ReAct module works with module composition"
    (let [backend (mock-backend "Answer: composed")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          composed (mod/compose-modules react-module (mod/fn-module identity))]
      (is (some? composed))))

  (testing "ReAct module works in parallel"
    (let [backend1 (mock-backend "Answer: first")
          backend2 (mock-backend "Answer: second")
          tools (tool/create-tool-context [])
          react1 (react/react backend1 tools)
          react2 (react/react backend2 tools)
          parallel (mod/parallel-modules react1 react2)]
      (is (some? parallel)))))

(deftest test-edge-cases
  (testing "Handles empty response"
    (let [backend (mock-backend "")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "Empty test"}
          result @(mod/call react-module inputs)]
      (is (= "Maximum iterations reached without final answer." (:answer result)))))

  (testing "Handles malformed response"
    (let [backend (mock-backend "Not a valid ReAct format")
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "Malformed test"}
          result @(mod/call react-module inputs)]
      ;; Should handle gracefully without crashing
      (is (some? result))))

  (testing "Handles backend errors"
    (let [backend (reify bp/ILlmBackend
                    (-generate [this prompt options]
                      (d/error-deferred (ex-info "Backend error" {}))))
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "Error test"}]
      (is (thrown? Exception @(mod/call react-module inputs)))))

  (testing "Handles very long responses"
    (let [long-response (str "Thought: " (apply str (repeat 1000 "very long thought ")) "\nAnswer: done")
          backend (mock-backend long-response)
          tools (tool/create-tool-context [])
          react-module (react/react backend tools)
          inputs {:question "Long response test"}
          result @(mod/call react-module inputs)]
      (is (= "done" (:answer result)))
      (is (.contains (:react-conversation result) "very long thought")))))