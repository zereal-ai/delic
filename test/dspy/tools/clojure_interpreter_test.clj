(ns dspy.tools.clojure-interpreter-test
  "Tests for ClojureInterpreter tool."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [dspy.tools.clojure-interpreter :as ci]
   [dspy.tool :as tool]
   [manifold.deferred :as d]
   [clojure.string :as str]))

;; Test fixtures
(defn reset-fixture [f]
  (f))

(use-fixtures :each reset-fixture)

(deftest test-safe-eval
  (testing "Basic safe evaluation"
    (let [result (ci/safe-eval "(+ 1 2 3)")]
      (is (= 6 (:result result)))
      (is (nil? (:error result)))
      (is (>= (:execution-time-ms result) 0))))

  (testing "Evaluation with output capture"
    (let [result (ci/safe-eval "(println \"Hello, World!\") 42")]
      (is (= 42 (:result result)))
      (is (= "Hello, World!\n" (:output result)))
      (is (nil? (:error result)))))

  (testing "Evaluation without output capture"
    (let [result (ci/safe-eval "(println \"Hello, World!\") 42" :capture-output? false)]
      (is (= 42 (:result result)))
      (is (nil? (:output result)))
      (is (nil? (:error result)))))

  (testing "Error handling"
    (let [result (ci/safe-eval "(/ 1 0)")]
      (is (nil? (:result result)))
      (is (some? (:error result)))
      (is (>= (:execution-time-ms result) 0))))

  (testing "String operations"
    (let [result (ci/safe-eval "(clojure.string/upper-case \"hello\")")]
      (is (= "HELLO" (:result result)))
      (is (nil? (:error result)))))

  (testing "Collection operations"
    (let [result (ci/safe-eval "(map inc [1 2 3])")]
      (is (= '(2 3 4) (:result result)))
      (is (nil? (:error result)))))

  (testing "Complex data structures"
    (let [result (ci/safe-eval "{:a 1 :b [2 3 4] :c #{5 6 7}}")]
      (is (= {:a 1 :b [2 3 4] :c #{5 6 7}} (:result result)))
      (is (nil? (:error result))))))

(deftest test-format-result
  (testing "Format successful result"
    (let [eval-result {:result 42 :output nil :error nil :execution-time-ms 10}
          formatted (ci/format-result eval-result)]
      (is (str/includes? formatted "Result: 42"))
      (is (str/includes? formatted "Execution time: 10ms"))))

  (testing "Format result with output"
    (let [eval-result {:result 42 :output "Hello\n" :error nil :execution-time-ms 10}
          formatted (ci/format-result eval-result)]
      (is (str/includes? formatted "Output:\nHello"))
      (is (str/includes? formatted "Result: 42"))
      (is (str/includes? formatted "Execution time: 10ms"))))

  (testing "Format error result"
    (let [eval-result {:result nil :output nil :error "Division by zero" :execution-time-ms 5}
          formatted (ci/format-result eval-result)]
      (is (str/includes? formatted "Error: Division by zero"))
      (is (str/includes? formatted "Execution time: 5ms")))))

(deftest test-create-clojure-interpreter
  (testing "Create default interpreter"
    (let [interpreter (ci/create-clojure-interpreter)]
      (is (tool/tool? interpreter))
      (is (= :clojure-interpreter (tool/-name interpreter)))
      (is (str/includes? (tool/-description interpreter) "Clojure interpreter"))))

  (testing "Create custom interpreter"
    (let [interpreter (ci/create-clojure-interpreter
                       :name :custom-interpreter
                       :description "Custom test interpreter"
                       :timeout-ms 10000)]
      (is (= :custom-interpreter (tool/-name interpreter)))
      (is (= "Custom test interpreter" (tool/-description interpreter)))))

  (testing "Interpreter execution"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(+ 1 2 3)"})]
      (is (= 6 (:result result)))
      (is (nil? (:error result)))
      (is (some? (:formatted-result result)))
      (is (>= (:execution-time-ms result) 0))))

  (testing "Interpreter with custom timeout"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(+ 1 2 3)" :timeout-ms 1000})]
      (is (= 6 (:result result)))
      (is (nil? (:error result)))))

  (testing "Interpreter with output capture control"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(println \"test\") 42" :capture-output false})]
      (is (= 42 (:result result)))
      (is (nil? (:output result)))))

  (testing "Interpreter error handling"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(/ 1 0)"})]
      (is (nil? (:result result)))
      (is (some? (:error result)))
      (is (str/includes? (:formatted-result result) "Error:")))))

(deftest test-utility-functions
  (testing "extract-result"
    (let [interpreter-result {:result 42 :output "test" :error nil :execution-time-ms 10}]
      (is (= 42 (ci/extract-result interpreter-result)))))

  (testing "extract-output"
    (let [interpreter-result {:result 42 :output "test output" :error nil :execution-time-ms 10}]
      (is (= "test output" (ci/extract-output interpreter-result)))))

  (testing "extract-error"
    (let [interpreter-result {:result nil :output nil :error "test error" :execution-time-ms 10}]
      (is (= "test error" (ci/extract-error interpreter-result)))))

  (testing "successful?"
    (let [success-result {:result 42 :output nil :error nil :execution-time-ms 10}
          error-result {:result nil :output nil :error "test error" :execution-time-ms 10}]
      (is (true? (ci/successful? success-result)))
      (is (false? (ci/successful? error-result)))))

  (testing "execution-time"
    (let [interpreter-result {:result 42 :output nil :error nil :execution-time-ms 123}]
      (is (= 123 (ci/execution-time interpreter-result)))))

  (testing "format-for-llm"
    (let [success-result {:result 42 :output "Hello" :error nil :execution-time-ms 10}
          error-result {:result nil :output nil :error "Division by zero" :execution-time-ms 5}]

      (let [formatted-success (ci/format-for-llm success-result)]
        (is (str/includes? formatted-success "Execution successful"))
        (is (str/includes? formatted-success "Output: Hello"))
        (is (str/includes? formatted-success "Result: 42"))
        (is (str/includes? formatted-success "Execution time: 10ms")))

      (let [formatted-error (ci/format-for-llm error-result)]
        (is (str/includes? formatted-error "Execution failed"))
        (is (str/includes? formatted-error "Division by zero"))))))

(deftest test-convenience-functions
  (testing "eval-clojure"
    (let [result @(ci/eval-clojure "(+ 1 2 3)")]
      (is (= 6 (:result result)))
      (is (nil? (:error result))))))

(deftest test-error-scenarios
  (testing "Syntax error"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(+ 1 2"})]
      (is (nil? (:result result)))
      (is (some? (:error result)))
      (is (str/includes? (:formatted-result result) "Error:"))))

  (testing "Undefined function"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(undefined-function 1 2)"})]
      (is (nil? (:result result)))
      (is (some? (:error result)))))

  (testing "Type error"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(+ \"hello\" 5)"})]
      (is (nil? (:result result)))
      (is (some? (:error result)))))

  (testing "Runtime error"
    (let [interpreter (ci/create-clojure-interpreter)
          result @(tool/invoke-tool interpreter {:code "(.length nil)"})]
      (is (nil? (:result result)))
      (is (some? (:error result))))))

(deftest test-output-capture
  (testing "Multiple println statements"
    (let [result (ci/safe-eval "(println \"Line 1\") (println \"Line 2\") (println \"Line 3\") 42")]
      (is (= 42 (:result result)))
      (is (= "Line 1\nLine 2\nLine 3\n" (:output result)))
      (is (nil? (:error result)))))

  (testing "Mixed print and println"
    (let [result (ci/safe-eval "(print \"Hello \") (println \"World\") 42")]
      (is (= 42 (:result result)))
      (is (= "Hello World\n" (:output result)))
      (is (nil? (:error result)))))

  (testing "No output"
    (let [result (ci/safe-eval "(+ 1 2 3)")]
      (is (= 6 (:result result)))
      (is (= "" (:output result)))
      (is (nil? (:error result))))))

(deftest test-tool-integration
  (testing "Tool validation"
    (let [interpreter (ci/create-clojure-interpreter)]
      (is (tool/validate-tool-input interpreter {:code "(+ 1 2)"}))
      (is (not (tool/validate-tool-input interpreter {:wrong-key "value"})))

      (let [result @(tool/invoke-tool interpreter {:code "(+ 1 2)"})]
        (is (tool/validate-tool-output interpreter result)))))

  (testing "Tool registration"
    (let [interpreter (ci/create-clojure-interpreter)]
      (tool/register-tool! interpreter)
      (is (= interpreter (tool/get-tool :clojure-interpreter)))
      (tool/unregister-tool! :clojure-interpreter)))

  (testing "Tool context usage"
    (let [interpreter (ci/create-clojure-interpreter)
          context (tool/create-tool-context [interpreter])
          result @(tool/invoke-tool-from-context context :clojure-interpreter {:code "(+ 1 2 3)"})]

      (is (= 6 (:result result)))
      (is (nil? (:error result))))))