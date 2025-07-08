(ns examples.chain-of-thought-demo
  "Demo script showing Chain of Thought module in action."
  (:require
   [dspy.modules.chain-of-thought :as cot]
   [dspy.signature :as sig]
   [dspy.module :as mod]
   [manifold.deferred :as d]))

;; Define signatures for different types of problems
(sig/defsignature MathProblem (problem => solution))
(sig/defsignature QuestionAnswer (question => answer))
(sig/defsignature Reasoning (context question => conclusion))

;; Mock backend that simulates LLM responses
(defrecord DemoBackend [responses]
  dspy.backend.protocol/ILlmBackend
  (-generate [this prompt options]
    (println "\n=== LLM PROMPT ===")
    (println prompt)
    (println "=== LLM RESPONSE ===")
    (let [response (first @responses)]
      (swap! responses rest)
      (println (:text response))
      (println "==================\n")
      (d/success-deferred response)))
  (-embeddings [this text options]
    (d/success-deferred {:vector [0.1 0.2 0.3]}))
  (-stream [this prompt options]
    nil))

(defn demo-backend [& responses]
  (->DemoBackend (atom responses)))

(defn demo-math-problem []
  (println "🧮 DEMO: Math Problem with Chain of Thought")
  (println "=" (apply str (repeat 50 "=")))

  (let [backend (demo-backend {:text "Reasoning: To find 15% of 240, I need to multiply 240 by 0.15. Let me calculate: 240 × 0.15 = 24 × 1.5 = 36.\nAnswer: 36"})
        cot-module (cot/chain-of-thought MathProblem backend)
        inputs {:problem "What is 15% of 240?"}
        result @(mod/call cot-module inputs)]

    (println "📝 RESULT:")
    (println "Problem:" (:problem inputs))
    (println "Reasoning:" (:rationale result))
    (println "Solution:" (:solution result))
    (println)))

(defn demo-question-answer []
  (println "❓ DEMO: Question Answering with Chain of Thought")
  (println "=" (apply str (repeat 50 "=")))

  (let [backend (demo-backend {:text "Reasoning: Photosynthesis is the process by which plants convert light energy into chemical energy. The main inputs are carbon dioxide from the air, water from the roots, and sunlight. The outputs are glucose (sugar) and oxygen. This process occurs in the chloroplasts of plant cells.\nAnswer: Photosynthesis converts CO2, water, and sunlight into glucose and oxygen in plant chloroplasts."})
        cot-module (cot/chain-of-thought QuestionAnswer backend)
        inputs {:question "How does photosynthesis work?"}
        result @(mod/call cot-module inputs)]

    (println "📝 RESULT:")
    (println "Question:" (:question inputs))
    (println "Reasoning:" (:rationale result))
    (println "Answer:" (:answer result))
    (println)))

(defn demo-reasoning []
  (println "🤔 DEMO: Contextual Reasoning with Chain of Thought")
  (println "=" (apply str (repeat 50 "=")))

  (let [backend (demo-backend {:text "Reasoning: Looking at the context, we have a person with a fever, cough, and fatigue during flu season. These are classic symptoms of influenza. The timing (flu season) and symptom combination strongly suggest this is likely the flu rather than a common cold, which typically has milder symptoms.\nAnswer: Based on the symptoms and timing, this person likely has the flu."})
        cot-module (cot/chain-of-thought Reasoning backend)
        inputs {:context "It's flu season and many people in the office have been getting sick."
                :question "A person has a fever, cough, and fatigue. What might they have?"}
        result @(mod/call cot-module inputs)]

    (println "📝 RESULT:")
    (println "Context:" (:context inputs))
    (println "Question:" (:question inputs))
    (println "Reasoning:" (:rationale result))
    (println "Conclusion:" (:conclusion result))
    (println)))

(defn demo-module-composition []
  (println "🔗 DEMO: Chain of Thought in Module Composition")
  (println "=" (apply str (repeat 50 "=")))

  (let [backend (demo-backend {:text "Reasoning: The area of a circle is π × r². With radius 5, that's π × 5² = π × 25 = 25π ≈ 78.54 square units.\nAnswer: 25π or approximately 78.54 square units"})
        cot-module (cot/chain-of-thought MathProblem backend)

        ;; Create a post-processing module that formats the result
        formatter (mod/fn-module
                   (fn [inputs]
                     {:formatted-result (str "🎯 FINAL ANSWER: " (:solution inputs)
                                             "\n💭 REASONING: " (:rationale inputs))}))

        ;; Compose the modules
        composed (mod/compose-modules cot-module formatter)

        inputs {:problem "What is the area of a circle with radius 5?"}
        result @(mod/call composed inputs)]

    (println "📝 COMPOSED RESULT:")
    (println (:formatted-result result))
    (println)))

(defn demo-utility-functions []
  (println "🔧 DEMO: Chain of Thought Utility Functions")
  (println "=" (apply str (repeat 50 "=")))

  (let [cot-result {:rationale "First, I need to understand what a prime number is. A prime number is only divisible by 1 and itself. Let me check 17: it's not divisible by 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, or 16. Only by 1 and 17."
                    :answer "Yes, 17 is a prime number."}]

    (println "Original result:" cot-result)
    (println)
    (println "Extracted reasoning:" (cot/extract-reasoning cot-result))
    (println)
    (println "Extracted answer:" (cot/extract-answer cot-result))
    (println)
    (println "Formatted result:")
    (println (cot/format-cot-result cot-result))
    (println)))

(defn -main []
  (println "🎭 CHAIN OF THOUGHT MODULE DEMO")
  (println "=" (apply str (repeat 60 "=")))
  (println)

  (demo-math-problem)
  (demo-question-answer)
  (demo-reasoning)
  (demo-module-composition)
  (demo-utility-functions)

  (println "✅ Demo completed! The Chain of Thought module successfully:")
  (println "  • Transforms signatures to add reasoning fields")
  (println "  • Prompts LLMs for step-by-step thinking")
  (println "  • Parses and structures the responses")
  (println "  • Integrates with the module composition system")
  (println "  • Provides utility functions for result processing"))

;; Run the demo when this file is executed
(when (= *file* (System/getProperty "babashka.file"))
  (-main))