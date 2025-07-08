(ns dspy.modules.chain-of-thought-test
  "Tests for Chain of Thought module."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dspy.modules.chain-of-thought :as cot]
   [dspy.signature :as sig]
   [dspy.module :as mod]
   [manifold.deferred :as d]
   [malli.core]))

;; Test signatures
(sig/defsignature QA (question => answer))
(sig/defsignature MathProblem (problem => solution))
(sig/defsignature MultiOutput (input => output1 output2))

;; Mock backend for testing
(defrecord MockBackend [responses]
  dspy.backend.protocol/ILlmBackend
  (-generate [this prompt options]
    (d/success-deferred (first @responses)))
  (-embeddings [this text options]
    (d/success-deferred {:vector [0.1 0.2 0.3]}))
  (-stream [this prompt options]
    nil))

(defn mock-backend [& responses]
  (->MockBackend (atom responses)))

(deftest test-transform-signature
  (testing "Transform signature adds rationale field"
    (let [original-sig {:inputs [:question] :outputs [:answer]}
          transformed (cot/transform-signature original-sig)]
      (is (= [:question] (:inputs transformed)))
      (is (= [:rationale :answer] (:outputs transformed)))))

  (testing "Transform signature with multiple outputs"
    (let [original-sig {:inputs [:input] :outputs [:output1 :output2]}
          transformed (cot/transform-signature original-sig)]
      (is (= [:input] (:inputs transformed)))
      (is (= [:rationale :output1 :output2] (:outputs transformed)))))

  (testing "Transform signature preserves input order"
    (let [original-sig {:inputs [:a :b :c] :outputs [:x :y]}
          transformed (cot/transform-signature original-sig)]
      (is (= [:a :b :c] (:inputs transformed)))
      (is (= [:rationale :x :y] (:outputs transformed))))))

(deftest test-create-cot-prompt
  (testing "Creates proper CoT prompt"
    (let [inputs {:question "What is 2+2?"}
          signature {:inputs [:question] :outputs [:answer]}
          prompt (cot/create-cot-prompt inputs signature)]
      (is (string? prompt))
      (is (.contains prompt "Think step-by-step"))
      (is (.contains prompt "rationale"))
      (is (.contains prompt "answer"))
      (is (.contains prompt "What is 2+2?"))))

  (testing "Handles multiple input fields"
    (let [inputs {:context "Math basics" :question "What is 2+2?"}
          signature {:inputs [:context :question] :outputs [:answer]}
          prompt (cot/create-cot-prompt inputs signature)]
      (is (.contains prompt "context: Math basics"))
      (is (.contains prompt "question: What is 2+2?"))))

  (testing "Uses first output field in instructions"
    (let [inputs {:problem "2+2"}
          signature {:inputs [:problem] :outputs [:solution :explanation]}
          prompt (cot/create-cot-prompt inputs signature)]
      (is (.contains prompt "solution")))))

(deftest test-parse-cot-response
  (testing "Parses complete CoT response"
    (let [response {:rationale "First I add 2+2" :answer "4"}
          signature {:inputs [:question] :outputs [:answer]}
          parsed (cot/parse-cot-response response signature)]
      (is (= "First I add 2+2" (:rationale parsed)))
      (is (= "4" (:answer parsed)))))

  (testing "Handles missing rationale"
    (let [response {:answer "4"}
          signature {:inputs [:question] :outputs [:answer]}
          parsed (cot/parse-cot-response response signature)]
      (is (= "No explicit reasoning provided." (:rationale parsed)))
      (is (= "4" (:answer parsed)))))

  (testing "Handles multiple outputs"
    (let [response {:rationale "Step by step" :output1 "A" :output2 "B"}
          signature {:inputs [:input] :outputs [:output1 :output2]}
          parsed (cot/parse-cot-response response signature)]
      (is (= "Step by step" (:rationale parsed)))
      (is (= "A" (:output1 parsed)))
      (is (= "B" (:output2 parsed)))))

  (testing "Defaults missing outputs to empty string"
    (let [response {:rationale "Thinking..."}
          signature {:inputs [:input] :outputs [:output1 :output2]}
          parsed (cot/parse-cot-response response signature)]
      (is (= "Thinking..." (:rationale parsed)))
      (is (= "" (:output1 parsed)))
      (is (= "" (:output2 parsed))))))

(deftest test-chain-of-thought-creation
  (testing "Creates ChainOfThought module"
    (let [backend (mock-backend {:text "Reasoning: Thinking\nAnswer: 42"})
          cot-module (cot/chain-of-thought QA backend)]
      (is (instance? dspy.modules.chain_of_thought.ChainOfThought cot-module))
      (is (mod/module? cot-module))
      (is (= QA (:signature cot-module)))
      (is (= backend (:backend cot-module)))))

  (testing "Creates module with metadata"
    (let [backend (mock-backend {:text "Reasoning: Thinking\nAnswer: 42"})
          metadata {:description "Test CoT" :version "1.0"}
          cot-module (cot/chain-of-thought QA backend :metadata metadata)]
      (is (= :chain-of-thought (get-in cot-module [:metadata :type])))
      (is (= "Test CoT" (get-in cot-module [:metadata :description])))
      (is (= "1.0" (get-in cot-module [:metadata :version])))))

  (testing "create-cot-module is alias for chain-of-thought"
    (let [backend (mock-backend {:text "Reasoning: Thinking\nAnswer: 42"})
          cot1 (cot/chain-of-thought QA backend)
          cot2 (cot/create-cot-module QA backend)]
      (is (= (type cot1) (type cot2))))))

(deftest test-chain-of-thought-execution
  (testing "Executes Chain of Thought successfully"
    (let [backend (mock-backend {:text "Reasoning: 2+2 equals 4\nAnswer: 4"})
          cot-module (cot/chain-of-thought QA backend)
          inputs {:question "What is 2+2?"}
          result @(mod/call cot-module inputs)]
      (is (= "2+2 equals 4" (:rationale result)))
      (is (= "4" (:answer result)))))

  (testing "Validates inputs against signature"
    (let [backend (mock-backend {:text "Reasoning: Thinking\nAnswer: 42"})
          cot-module (cot/chain-of-thought QA backend)
          invalid-inputs {:wrong-field "value"}]
      (is (thrown? Exception @(mod/call cot-module invalid-inputs)))))

  (testing "Handles backend errors gracefully"
    (let [backend (reify dspy.backend.protocol/ILlmBackend
                    (-generate [this prompt options]
                      (d/error-deferred (ex-info "Backend error" {})))
                    (-embeddings [this text options]
                      (d/error-deferred (ex-info "Backend error" {})))
                    (-stream [this prompt options]
                      nil))
          cot-module (cot/chain-of-thought QA backend)
          inputs {:question "Test"}]
      (is (thrown? Exception @(mod/call cot-module inputs)))))

  (testing "Works with multiple output fields"
    (let [backend (mock-backend {:text "Reasoning: Step by step\nAnswer: A"})
          cot-module (cot/chain-of-thought MultiOutput backend)
          inputs {:input "test"}
          result @(mod/call cot-module inputs)]
      (is (= "Step by step" (:rationale result)))
      (is (= "A" (:output1 result)))
      ;; output2 will be empty since we only have one answer
      (is (= "" (:output2 result))))))

(deftest test-utility-functions
  (testing "extract-reasoning"
    (let [result {:rationale "My reasoning" :answer "42"}]
      (is (= "My reasoning" (cot/extract-reasoning result)))))

  (testing "extract-answer"
    (let [result {:rationale "My reasoning" :answer "42" :confidence "high"}]
      (is (= "42" (cot/extract-answer result)))))

  (testing "extract-answer with multiple non-rationale fields"
    (let [result {:rationale "Reasoning" :first-answer "A" :second-answer "B"}]
      ;; Should return the first non-rationale field
      (is (= "A" (cot/extract-answer result)))))

  (testing "format-cot-result"
    (let [result {:rationale "Step by step thinking" :answer "The answer is 42"}
          formatted (cot/format-cot-result result)]
      (is (.contains formatted "Reasoning: Step by step thinking"))
      (is (.contains formatted "Answer: The answer is 42"))))

  (testing "extract-answer returns nil for rationale-only result"
    (let [result {:rationale "Only reasoning"}]
      (is (nil? (cot/extract-answer result))))))

(deftest test-integration-with-module-system
  (testing "Chain of Thought integrates with module composition"
    (let [backend (mock-backend {:text "Reasoning: Thinking about math\nAnswer: 4"})
          cot-module (cot/chain-of-thought QA backend)

          ;; Create a post-processing module
          post-process (mod/fn-module
                        (fn [inputs]
                          {:final-answer (str "Final: " (:answer inputs))
                           :reasoning-summary (str "Summary: " (:rationale inputs))}))

          ;; Compose them
          composed (mod/compose-modules cot-module post-process)

          inputs {:question "What is 2+2?"}
          result @(mod/call composed inputs)]

      (is (= "Final: 4" (:final-answer result)))
      (is (= "Summary: Thinking about math" (:reasoning-summary result)))))

  (testing "Chain of Thought works in parallel composition"
    (let [backend1 (mock-backend {:text "Reasoning: Method 1\nAnswer: 4"})
          backend2 (mock-backend {:text "Reasoning: Method 2\nAnswer: 4"})
          cot1 (cot/chain-of-thought QA backend1)
          cot2 (cot/chain-of-thought QA backend2)

          ;; This won't work directly since both return the same keys
          ;; But we can test that they both execute
          inputs {:question "What is 2+2?"}
          result1 @(mod/call cot1 inputs)
          result2 @(mod/call cot2 inputs)]

      (is (= "Method 1" (:rationale result1)))
      (is (= "Method 2" (:rationale result2)))
      (is (= "4" (:answer result1)))
      (is (= "4" (:answer result2))))))

(deftest test-edge-cases
  (testing "Empty signature outputs"
    (let [empty-sig {:inputs [:input] :outputs []}
          transformed (cot/transform-signature empty-sig)]
      (is (= [:rationale] (:outputs transformed)))))

  (testing "Single input single output"
    (let [backend (mock-backend {:text "Reasoning: Simple thinking\nAnswer: done"})
          ;; Create a proper signature with metadata
          simple-sig (with-meta {:inputs [:input] :outputs [:result]}
                       {:malli/input-schema (malli.core/schema [:map [:input :string]])
                        :malli/output-schema (malli.core/schema [:map [:result :string]])})
          cot-module (cot/->ChainOfThought backend simple-sig {})
          inputs {:input "test"}
          result @(mod/call cot-module inputs)]
      (is (= "Simple thinking" (:rationale result)))
      (is (= "done" (:result result)))))

  (testing "No signature provided"
    (let [backend (mock-backend {:text "Reasoning: No validation\nAnswer: 42"})
          cot-module (cot/->ChainOfThought backend nil {})
          inputs {:anything "works"}]
      ;; Should not throw validation error when no signature
      (is (d/deferred? (mod/call cot-module inputs))))))

(deftest test-metadata-handling
  (testing "Module metadata includes transformation info"
    (let [backend (mock-backend {:text "Reasoning: Test\nAnswer: 42"})
          cot-module (cot/chain-of-thought QA backend :metadata {:custom "value"})]
      (is (= :chain-of-thought (get-in cot-module [:metadata :type])))
      (is (= QA (get-in cot-module [:metadata :original-signature])))
      (is (= [:rationale :answer] (get-in cot-module [:metadata :transformed-signature :outputs])))
      (is (= "value" (get-in cot-module [:metadata :custom]))))))