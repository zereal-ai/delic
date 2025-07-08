(ns dspy.modules.chain-of-thought
  "Chain of Thought module for step-by-step reasoning."
  (:require
   [dspy.module :as mod]
   [dspy.signature :as sig]
   [dspy.backend.protocol :as bp]
   [manifold.deferred :as d]
   [clojure.string :as str]))

(defn transform-signature
  "Transform a signature to add a rationale field for step-by-step reasoning.

   Takes a signature map and returns a new signature with:
   - Original inputs unchanged
   - Added :rationale output field (before other outputs)
   - Original outputs after rationale"
  [signature]
  (let [inputs (:inputs signature)
        outputs (:outputs signature)
        new-outputs (vec (concat [:rationale] outputs))]
    {:inputs inputs
     :outputs new-outputs}))

(defn create-cot-prompt
  "Create a Chain of Thought prompt that encourages step-by-step reasoning.

   Takes the original inputs and creates a prompt that asks the LLM to:
   1. Think step-by-step (rationale)
   2. Provide the final answer"
  [inputs signature]
  (let [input-fields (:inputs signature)
        output-fields (remove #{:rationale} (:outputs signature))

        ;; Build the instruction part
        instruction (str "Think step-by-step to solve this problem.\n\n"
                         "First, provide your reasoning in the 'rationale' field.\n"
                         "Then, provide your final answer in the '"
                         (first output-fields) "' field.\n\n")

        ;; Build the input part
        input-text (str/join "\n"
                             (map (fn [field]
                                    (str (name field) ": " (get inputs field)))
                                  input-fields))]

    (str instruction input-text)))

(defn parse-cot-response
  "Parse the LLM response to extract rationale and final answer.

   Expects the response to contain both rationale and answer fields.
   If rationale is missing, uses a default message."
  [response signature]
  (let [output-fields (remove #{:rationale} (:outputs signature))
        rationale (or (:rationale response)
                      "No explicit reasoning provided.")

        ;; Extract other outputs, defaulting to empty string if missing
        other-outputs (into {}
                            (map (fn [field]
                                   [field (get response field "")])
                                 output-fields))]

    (merge {:rationale rationale} other-outputs)))

(defrecord ChainOfThought [backend signature metadata]
  mod/ILlmModule
  (call [this inputs]
    (try
      ;; Validate inputs against original signature
      (when signature
        (when-not (sig/validate-input signature inputs)
          (throw (ex-info "Input validation failed for ChainOfThought"
                          {:signature signature
                           :inputs inputs
                           :module this}))))

      ;; Transform signature to include rationale
      (let [cot-signature (transform-signature signature)
            prompt (create-cot-prompt inputs signature)]

        ;; Call the backend with the CoT prompt
        (d/chain
         (bp/generate backend prompt {})
         (fn [response]
           ;; For now, simulate structured response by parsing text
           ;; TODO: Implement proper structured output when backend supports it
           (let [text-response (if (string? response) response (:text response))
                 ;; Simple parsing - look for rationale and answer patterns
                 lines (str/split-lines text-response)
                 rationale-line (first (filter #(str/starts-with? % "Reasoning:") lines))
                 answer-line (first (filter #(str/starts-with? % "Answer:") lines))

                 rationale (if rationale-line
                             (str/trim (subs rationale-line 10))
                             text-response)
                 answer (if answer-line
                          (str/trim (subs answer-line 7))
                          text-response)]

             ;; Create structured response
             (let [output-fields (remove #{:rationale} (:outputs signature))
                   ;; Create a map with all output fields, defaulting to empty string
                   output-map (into {} (map (fn [field] [field ""]) output-fields))
                   ;; Set the first output field to the answer
                   output-with-answer (if (seq output-fields)
                                        (assoc output-map (first output-fields) answer)
                                        output-map)]
               (merge {:rationale rationale} output-with-answer))))))

      (catch Exception e
        (d/error-deferred e)))))

(defn chain-of-thought
  "Create a Chain of Thought module.

   Takes a signature and backend, returns a module that:
   1. Transforms the signature to add a :rationale field
   2. Prompts the LLM to think step-by-step
   3. Returns both the reasoning and the final answer

   Args:
     signature - The original signature (e.g., question => answer)
     backend - The LLM backend to use

   Options:
     :metadata - Additional metadata about the module

   Usage:
     (chain-of-thought QA-signature my-backend)
     (chain-of-thought QA-signature my-backend :metadata {:description \"CoT QA\"})"
  [signature backend & {:keys [metadata]}]
  (->ChainOfThought backend signature
                    (merge {:type :chain-of-thought
                            :original-signature signature
                            :transformed-signature (transform-signature signature)}
                           metadata)))

(defn create-cot-module
  "Convenience function to create a Chain of Thought module.

   This is an alias for chain-of-thought with a more descriptive name."
  [signature backend & opts]
  (apply chain-of-thought signature backend opts))

;; Utility functions for working with Chain of Thought results

(defn extract-reasoning
  "Extract the reasoning/rationale from a Chain of Thought result."
  [cot-result]
  (:rationale cot-result))

(defn extract-answer
  "Extract the final answer from a Chain of Thought result.

   Returns the first non-rationale field from the result."
  [cot-result]
  (let [answer-keys (remove #{:rationale} (keys cot-result))]
    (when (seq answer-keys)
      (get cot-result (first answer-keys)))))

(defn format-cot-result
  "Format a Chain of Thought result for display.

   Returns a formatted string showing both reasoning and answer."
  [cot-result]
  (let [reasoning (extract-reasoning cot-result)
        answer (extract-answer cot-result)]
    (str "Reasoning: " reasoning "\n"
         "Answer: " answer)))