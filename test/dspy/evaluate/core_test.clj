(ns dspy.evaluate.core-test
  (:require [clojure.test :refer :all]
            [dspy.evaluate.core :as eval-core]
            [dspy.evaluate.metrics :as metrics]
            [manifold.deferred :as d]))

(deftest test-format-dataset
  (testing "Already formatted dataset"
    (let [dataset [{:question "What is 2+2?" :answer "4"}
                   {:question "What is 3+3?" :answer "6"}]]
      (is (= dataset (eval-core/format-dataset dataset)))))

  (testing "Input/output format"
    (let [dataset [{:input "What is 2+2?" :output "4"}
                   {:input "What is 3+3?" :output "6"}]
          result (eval-core/format-dataset dataset)]
      ;; Should have :question and :answer keys
      (is (= "What is 2+2?" (:question (first result))))
      (is (= "4" (:answer (first result))))
      (is (= "What is 3+3?" (:question (second result))))
      (is (= "6" (:answer (second result))))))

  (testing "Vector pairs format"
    (let [dataset [["What is 2+2?" "4"]
                   ["What is 3+3?" "6"]]
          expected [{:question "What is 2+2?" :answer "4"}
                    {:question "What is 3+3?" :answer "6"}]]
      (is (= expected (eval-core/format-dataset dataset)))))

  (testing "Custom key names"
    (let [dataset [{:query "What is 2+2?" :expected "4"}
                   {:query "What is 3+3?" :expected "6"}]
          expected [{:question "What is 2+2?" :answer "4" :query "What is 2+2?" :expected "4"}
                    {:question "What is 3+3?" :answer "6" :query "What is 3+3?" :expected "6"}]]
      (is (= expected (eval-core/format-dataset dataset)))))

  (testing "Unsupported format"
    (is (thrown-with-msg? Exception #"Unsupported dataset format"
                          (eval-core/format-dataset "not-a-dataset")))))

(deftest test-evaluate-with-mock-program
  (testing "Basic evaluation with perfect program"
    (let [perfect-program (fn [inputs]
                            (d/success-deferred {:answer (:question inputs)}))
          dataset [{:question "Paris" :answer "Paris"}
                   {:question "London" :answer "London"}]
          result @(eval-core/evaluate perfect-program dataset metrics/exact-match)]
      (is (= 1.0 (:score result)))
      (is (= 2 (:count result)))
      (is (= 2 (:total result)))
      (is (empty? (:errors result)))))

  (testing "Basic evaluation with imperfect program"
    (let [imperfect-program (fn [inputs]
                              (d/success-deferred {:answer "Wrong"}))
          dataset [{:question "Paris" :answer "Paris"}
                   {:question "London" :answer "London"}]
          result @(eval-core/evaluate imperfect-program dataset metrics/exact-match)]
      (is (= 0.0 (:score result)))
      (is (= 2 (:count result)))
      (is (= 2 (:total result)))
      (is (empty? (:errors result)))))

  (testing "Evaluation with mixed results"
    (let [mixed-program (fn [inputs]
                          (if (= (:question inputs) "Paris")
                            (d/success-deferred {:answer "Paris"})
                            (d/success-deferred {:answer "Wrong"})))
          dataset [{:question "Paris" :answer "Paris"}
                   {:question "London" :answer "London"}]
          result @(eval-core/evaluate mixed-program dataset metrics/exact-match)]
      (is (= 0.5 (:score result)))
      (is (= 2 (:count result)))
      (is (= 2 (:total result)))
      (is (empty? (:errors result)))))

  (testing "Evaluation with program errors"
    (let [error-program (fn [inputs]
                          (d/error-deferred (ex-info "Program error" {})))
          dataset [{:question "Paris" :answer "Paris"}]
          result @(eval-core/evaluate error-program dataset metrics/exact-match)]
      (is (= 0.0 (:score result)))
      (is (= 0 (:count result)))
      (is (= 1 (:total result)))
      (is (= 1 (count (:errors result)))))))

(deftest test-evaluate-dataset-convenience
  (testing "Evaluate with keyword metric"
    (let [perfect-program (fn [inputs]
                            (d/success-deferred {:answer (:question inputs)}))
          dataset [{:question "Paris" :answer "Paris"}]
          result @(eval-core/evaluate-dataset perfect-program dataset :exact-match)]
      (is (= 1.0 (:score result)))))

  (testing "Evaluate with custom metric function"
    (let [perfect-program (fn [inputs]
                            (d/success-deferred {:answer (:question inputs)}))
          dataset [{:question "Paris" :answer "Paris"}]
          result @(eval-core/evaluate-dataset perfect-program dataset metrics/exact-match)]
      (is (= 1.0 (:score result)))))

  (testing "Evaluate with unknown metric keyword"
    (let [perfect-program (fn [inputs]
                            (d/success-deferred {:answer (:question inputs)}))
          dataset [{:question "Paris" :answer "Paris"}]]
      (is (thrown-with-msg? Exception #"Unknown metric"
                            @(eval-core/evaluate-dataset perfect-program dataset :unknown-metric)))))

  (testing "Evaluate with vector pairs dataset"
    (let [perfect-program (fn [inputs]
                            (d/success-deferred {:answer (:question inputs)}))
          dataset [["Paris" "Paris"] ["London" "London"]]
          result @(eval-core/evaluate-dataset perfect-program dataset :exact-match)]
      (is (= 1.0 (:score result)))
      (is (= 2 (:count result))))))

(deftest test-print-evaluation-results
  (testing "Print results without errors"
    (let [results {:score 0.75 :count 3 :total 4 :errors [] :results []}
          output (with-out-str (eval-core/print-evaluation-results results))]
      (is (re-find #"Score: 0\.750 \(3/4 examples\)" output))))

  (testing "Print results with errors"
    (let [results {:score 0.5 :count 1 :total 2
                   :errors [{:error (ex-info "Test error" {})}]
                   :results []}
          output (with-out-str (eval-core/print-evaluation-results results))]
      (is (re-find #"Score: 0\.500 \(1/2 examples\)" output))
      (is (re-find #"Errors:" output))
      (is (re-find #"Test error" output)))))

(deftest test-evaluation-options
  (testing "Evaluation with timeout"
    (let [slow-program (fn [inputs]
                         (d/chain
                          (d/timeout! (d/deferred) 100) ; Will timeout after 100ms
                          (fn [_] {:answer "slow"})))
          dataset [{:question "Paris" :answer "Paris"}]
          result @(eval-core/evaluate slow-program dataset metrics/exact-match
                                      {:timeout-ms 50})] ; Timeout after 50ms
      (is (= 0.0 (:score result)))
      (is (= 0 (:count result)))
      (is (>= (count (:errors result)) 1)))))

(deftest test-different-ground-truth-formats
  (testing "Ground truth in :ground-truth key"
    (let [program (fn [inputs] (d/success-deferred {:answer "Paris"}))
          dataset [{:question "What is the capital?" :ground-truth {:answer "Paris"}}]
          result @(eval-core/evaluate program dataset metrics/exact-match)]
      (is (= 1.0 (:score result)))))

  (testing "Ground truth in :expected key"
    (let [program (fn [inputs] (d/success-deferred {:answer "Paris"}))
          dataset [{:question "What is the capital?" :expected {:answer "Paris"}}]
          result @(eval-core/evaluate program dataset metrics/exact-match)]
      (is (= 1.0 (:score result)))))

  (testing "Ground truth in example itself"
    (let [program (fn [inputs] (d/success-deferred {:answer "Paris"}))
          dataset [{:question "What is the capital?" :answer "Paris"}]
          result @(eval-core/evaluate program dataset metrics/exact-match)]
      (is (= 1.0 (:score result))))))