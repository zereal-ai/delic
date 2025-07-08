(ns dspy.evaluate.metrics-test
  (:require [clojure.test :refer :all]
            [dspy.evaluate.metrics :as metrics]))

(deftest test-answer-exact-match
  (testing "String inputs"
    (is (= 1.0 (metrics/answer-exact-match "Paris" "paris")))
    (is (= 1.0 (metrics/answer-exact-match "Paris" "Paris")))
    (is (= 0.0 (metrics/answer-exact-match "Paris" "London")))
    (is (= 1.0 (metrics/answer-exact-match "  Paris  " "paris"))))

  (testing "Map inputs"
    (is (= 1.0 (metrics/answer-exact-match {:answer "Paris"} {:answer "paris"})))
    (is (= 0.0 (metrics/answer-exact-match {:answer "Paris"} {:answer "London"})))
    (is (= 1.0 (metrics/answer-exact-match {:answer "  Paris  "} {:answer "paris"}))))

  (testing "Mixed inputs"
    (is (= 1.0 (metrics/answer-exact-match "Paris" {:answer "paris"})))
    (is (= 1.0 (metrics/answer-exact-match {:answer "Paris"} "paris"))))

  (testing "Edge cases"
    (is (= 0.0 (metrics/answer-exact-match nil "Paris")))
    (is (= 0.0 (metrics/answer-exact-match "Paris" nil)))
    (is (= 0.0 (metrics/answer-exact-match {} {:answer "Paris"})))
    (is (= 0.0 (metrics/answer-exact-match {:answer "Paris"} {})))
    (is (= 0.0 (metrics/answer-exact-match "" "Paris")))
    (is (= 1.0 (metrics/answer-exact-match "" "")))))

(deftest test-answer-passage-match
  (testing "String prediction, map ground truth"
    (is (= 1.0 (metrics/answer-passage-match "Paris" {:context "Paris is the capital of France"})))
    (is (= 1.0 (metrics/answer-passage-match "paris" {:context "Paris is the capital of France"})))
    (is (= 0.0 (metrics/answer-passage-match "Tokyo" {:context "Paris is the capital of France"}))))

  (testing "Map prediction, map ground truth"
    (is (= 1.0 (metrics/answer-passage-match {:answer "Paris"} {:context "Paris is the capital of France"})))
    (is (= 0.0 (metrics/answer-passage-match {:answer "Tokyo"} {:context "Paris is the capital of France"}))))

  (testing "Different passage keys"
    (is (= 1.0 (metrics/answer-passage-match "Paris" {:passage "Paris is the capital of France"})))
    (is (= 1.0 (metrics/answer-passage-match "Paris" {:answer "Paris is the capital of France"}))))

  (testing "Edge cases"
    (is (= 0.0 (metrics/answer-passage-match nil {:context "Paris is the capital"})))
    (is (= 0.0 (metrics/answer-passage-match "Paris" {})))
    (is (= 0.0 (metrics/answer-passage-match "" {:context "Paris is the capital"})))
    (is (= 0.0 (metrics/answer-passage-match "" {:context ""})))))

(deftest test-semantic-f1
  (testing "Placeholder implementation"
    ;; Since semantic-f1 is currently a placeholder that falls back to exact match
    (is (= 1.0 (metrics/semantic-f1 "Paris" "paris")))
    (is (= 0.0 (metrics/semantic-f1 "Paris" "London")))))

(deftest test-create-metric
  (testing "Valid metric creation"
    (let [custom-metric (metrics/create-metric "test-metric"
                                               (fn [pred gt] 0.5))]
      (is (= 0.5 (custom-metric "test" "test")))
      (is (= "test-metric" (:metric-name (meta custom-metric))))))

  (testing "Invalid metric validation"
    (let [invalid-metric (metrics/create-metric "invalid"
                                                (fn [pred gt] 1.5))]
      (is (thrown-with-msg? Exception #"Metric must return a number between 0.0 and 1.0"
                            (invalid-metric "test" "test")))))

  (testing "Non-numeric metric validation"
    (let [invalid-metric (metrics/create-metric "invalid"
                                                (fn [pred gt] "not-a-number"))]
      (is (thrown-with-msg? Exception #"Metric must return a number between 0.0 and 1.0"
                            (invalid-metric "test" "test"))))))

(deftest test-predefined-metrics
  (testing "Exact match metric"
    (is (= 1.0 (metrics/exact-match "Paris" "paris")))
    (is (= "exact-match" (:metric-name (meta metrics/exact-match)))))

  (testing "Passage match metric"
    (is (= 1.0 (metrics/passage-match "Paris" {:context "Paris is the capital"})))
    (is (= "passage-match" (:metric-name (meta metrics/passage-match)))))

  (testing "Semantic F1 metric"
    (is (= 1.0 (metrics/semantic-f1-metric "Paris" "paris")))
    (is (= "semantic-f1" (:metric-name (meta metrics/semantic-f1-metric)))))) 