(ns dspy.evaluate.metrics
  "Core evaluation metrics for DSPy programs.

  This namespace provides functions for scoring predictions against ground truth data.
  All metrics return a score between 0.0 and 1.0, where 1.0 represents a perfect match."
  (:require [clojure.string :as str]))

(defn answer-exact-match
  "Checks for exact string equality between prediction and ground truth answers.

  Args:
    prediction - Either a string or a map with an :answer key
    ground-truth - Either a string or a map with an :answer key

  Returns:
    1.0 for case-insensitive exact match, 0.0 otherwise

  Examples:
    (answer-exact-match \"Paris\" \"paris\") => 1.0
    (answer-exact-match {:answer \"Paris\"} {:answer \"London\"}) => 0.0"
  [prediction ground-truth]
  (let [pred-answer (if (string? prediction)
                      prediction
                      (:answer prediction))
        gt-answer (if (string? ground-truth)
                    ground-truth
                    (:answer ground-truth))]
    (if (and pred-answer gt-answer)
      (if (= (str/lower-case (str/trim pred-answer))
             (str/lower-case (str/trim gt-answer)))
        1.0
        0.0)
      0.0)))

(defn answer-passage-match
  "Checks if the prediction answer appears as a substring in the ground truth passage.

  Args:
    prediction - Either a string or a map with an :answer key
    ground-truth - A map with :context, :passage, or :answer key to search in

  Returns:
    1.0 if prediction answer is found in ground truth passage, 0.0 otherwise

  Examples:
    (answer-passage-match \"Paris\" {:context \"Paris is the capital of France\"}) => 1.0
    (answer-passage-match {:answer \"Tokyo\"} {:passage \"London is in England\"}) => 0.0"
  [prediction ground-truth]
  (let [pred-answer (if (string? prediction)
                      prediction
                      (:answer prediction))
        passage (or (:context ground-truth)
                    (:passage ground-truth)
                    (:answer ground-truth))]
    (if (and pred-answer passage (not (str/blank? pred-answer)))
      (if (str/includes? (str/lower-case passage)
                         (str/lower-case (str/trim pred-answer)))
        1.0
        0.0)
      0.0)))

(defn semantic-f1
  "Calculates F1 score based on semantic similarity between prediction and ground truth.

  NOTE: This is a placeholder implementation that falls back to exact match.
  A full implementation would require embeddings and semantic similarity computation.

  Args:
    prediction - Either a string or a map with an :answer key
    ground-truth - Either a string or a map with an :answer key

  Returns:
    F1 score between 0.0 and 1.0

  TODO: Implement proper semantic similarity using embeddings"
  [prediction ground-truth]
  ;; For now, fall back to exact match
  ;; TODO: Implement proper semantic F1 with embeddings
  (answer-exact-match prediction ground-truth))

;; Utility function for creating custom metrics
(defn create-metric
  "Creates a custom metric function with validation.

  Args:
    name - String name for the metric
    metric-fn - Function that takes [prediction ground-truth] and returns 0.0-1.0

  Returns:
    A validated metric function with metadata"
  [name metric-fn]
  (with-meta
    (fn [prediction ground-truth]
      (let [score (metric-fn prediction ground-truth)]
        (if (and (number? score) (<= 0.0 score 1.0))
          score
          (throw (ex-info "Metric must return a number between 0.0 and 1.0"
                          {:metric name
                           :score score
                           :prediction prediction
                           :ground-truth ground-truth})))))
    {:metric-name name}))

;; Pre-defined metrics with metadata
(def exact-match
  "Exact string match metric"
  (create-metric "exact-match" answer-exact-match))

(def passage-match
  "Passage substring match metric"
  (create-metric "passage-match" answer-passage-match))

(def semantic-f1-metric
  "Semantic F1 score metric (placeholder)"
  (create-metric "semantic-f1" semantic-f1))