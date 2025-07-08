(ns dspy.evaluate.core
  "Core evaluation framework for DSPy programs.

  This namespace provides the main evaluation orchestration, taking programs,
  datasets, and metrics to produce performance scores."
  (:require [dspy.evaluate.metrics :as metrics]
            [manifold.deferred :as d]
            [clojure.tools.logging :as log]))

(defn- evaluate-single
  "Evaluates a single example and returns a result map."
  [program-or-pipeline example metric-fn timeout-ms]
  (d/catch
   (d/timeout!
    (d/chain
     ;; Extract input fields from the example (everything except expected outputs)
     (let [input-fields (dissoc example :answer :expected :ground-truth)]
       ;; Invoke the program with the input fields
       (if (fn? program-or-pipeline)
         (program-or-pipeline input-fields)
         ;; If it's a pipeline, we might need different invocation
         (program-or-pipeline input-fields)))
     (fn [prediction]
       ;; Score the prediction against ground truth
       (let [ground-truth (or (:ground-truth example)
                              (:expected example)
                              example)
             score (metric-fn prediction ground-truth)]
         {:success true
          :example example
          :prediction prediction
          :ground-truth ground-truth
          :score score
          :metric (or (:metric-name (meta metric-fn)) "unknown")})))
    timeout-ms)
   (fn [error]
     (log/warn "Evaluation error for example" (:id example) ":" (ex-message error))
     {:success false
      :error error
      :example example
      :score 0.0})))

(defn- evaluate-sequential
  "Evaluates examples sequentially."
  [program-or-pipeline dataset metric-fn timeout-ms]
  (reduce (fn [results-deferred example]
            (d/chain
             results-deferred
             (fn [results]
               (d/chain
                (evaluate-single program-or-pipeline example metric-fn timeout-ms)
                (fn [result]
                  (conj results result))))))
          (d/success-deferred [])
          dataset))

(defn- evaluate-parallel
  "Evaluates examples in parallel with controlled concurrency."
  [program-or-pipeline dataset metric-fn max-concurrency timeout-ms]
  ;; For now, fall back to sequential evaluation to avoid resource issues
  ;; TODO: Implement proper parallel evaluation using dspy.util.manifold utilities
  (evaluate-sequential program-or-pipeline dataset metric-fn timeout-ms))

(defn evaluate
  "Evaluates a DSPy program or pipeline against a dataset using a specified metric.

  Args:
    program-or-pipeline - A compiled DSPy program/pipeline that can be invoked
    dataset - A sequence of examples, where each example is a map with input/output data
    metric-fn - A metric function that takes [prediction ground-truth] and returns 0.0-1.0
    options - Optional map with:
      :parallel? - Whether to evaluate examples in parallel (default: false)
      :max-concurrency - Maximum concurrent evaluations (default: 4)
      :timeout-ms - Timeout per evaluation in milliseconds (default: 30000)

  Returns:
    A deferred that resolves to a map with:
      :score - Average score across all examples (0.0-1.0)
      :count - Number of examples evaluated
      :results - Detailed results for each example
      :errors - Any errors that occurred during evaluation

  Examples:
    @(evaluate my-program dataset metrics/exact-match)
    @(evaluate my-pipeline dataset metrics/passage-match {:parallel? true})"
  ([program-or-pipeline dataset metric-fn]
   (evaluate program-or-pipeline dataset metric-fn {}))
  ([program-or-pipeline dataset metric-fn options]
   (let [{:keys [parallel? max-concurrency timeout-ms]
          :or {parallel? false
               max-concurrency 4
               timeout-ms 30000}} options]
     (d/chain
      (if parallel?
        (evaluate-parallel program-or-pipeline dataset metric-fn max-concurrency timeout-ms)
        (evaluate-sequential program-or-pipeline dataset metric-fn timeout-ms))
      (fn [results]
        (let [successful-results (filter :success results)
              scores (map :score successful-results)
              errors (filter :error results)]
          {:score (if (empty? scores) 0.0 (/ (reduce + scores) (count scores)))
           :count (count successful-results)
           :total (count results)
           :results results
           :errors errors}))))))

(defn format-dataset
  "Formats a dataset into the standard evaluation format.

  Handles various input formats:
  - Vector of maps with :question/:answer keys
  - Vector of maps with :input/:output keys
  - Vector of vectors [input output]
  - Already formatted dataset

  Returns:
    Sequence of maps with consistent field names"
  [dataset]
  (cond
    ;; Already formatted (has both :question and :answer keys)
    (and (sequential? dataset)
         (map? (first dataset))
         (:question (first dataset))
         (:answer (first dataset)))
    dataset

    ;; Vector of [input output] pairs
    (and (sequential? dataset)
         (vector? (first dataset))
         (= 2 (count (first dataset))))
    (map (fn [[input output]]
           {:question input :answer output})
         dataset)

    ;; Vector of maps with other key names - try to standardize
    (and (sequential? dataset)
         (map? (first dataset)))
    (map (fn [example]
           (let [input-key (or (when (:question example) :question)
                               (when (:input example) :input)
                               (when (:query example) :query)
                               :question)
                 output-key (or (when (:answer example) :answer)
                                (when (:output example) :output)
                                (when (:expected example) :expected)
                                :answer)]
             (-> example
                 (assoc :question (get example input-key))
                 (assoc :answer (get example output-key)))))
         dataset)

    :else
    (throw (ex-info "Unsupported dataset format" {:dataset-sample (take 2 dataset)}))))

(defn evaluate-dataset
  "Convenience function to evaluate with common dataset formats.

  Automatically handles different dataset formats and provides sensible defaults.

  Args:
    program-or-pipeline - The program to evaluate
    dataset - Dataset in various formats (see format-dataset)
    metric-name - Keyword for built-in metric (:exact-match, :passage-match, :semantic-f1)
                  or a custom metric function
    options - Optional evaluation options

  Returns:
    Deferred evaluation result"
  [program-or-pipeline dataset metric-name & [options]]
  (let [formatted-dataset (format-dataset dataset)
        metric-fn (if (keyword? metric-name)
                    (case metric-name
                      :exact-match metrics/exact-match
                      :passage-match metrics/passage-match
                      :semantic-f1 metrics/semantic-f1-metric
                      (throw (ex-info "Unknown metric" {:metric metric-name})))
                    metric-name)]
    (evaluate program-or-pipeline formatted-dataset metric-fn options)))

(defn print-evaluation-results
  "Pretty prints evaluation results for debugging and analysis."
  [results]
  (println "\n=== Evaluation Results ===")
  (println (String/format java.util.Locale/US "Score: %.3f (%d/%d examples)"
                          (to-array [(:score results)
                                     (:count results)
                                     (:total results)])))

  (when (seq (:errors results))
    (println "\nErrors:")
    (doseq [error (:errors results)]
      (println "  -" (ex-message (:error error)))))

  (when (and (:results results) (< (:count results) 10))
    (println "\nDetailed Results:")
    (doseq [result (:results results)]
      (if (:success result)
        (println (String/format java.util.Locale/US "  ✓ Score: %.3f - %s -> %s"
                                (to-array [(:score result)
                                           (pr-str (:question (:example result)))
                                           (pr-str (:prediction result))])))
        (println (format "  ✗ Error: %s"
                         (ex-message (:error result)))))))

  results)