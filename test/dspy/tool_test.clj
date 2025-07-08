(ns dspy.tool-test
  "Tests for tool infrastructure and protocol."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [dspy.tool :as tool]
   [manifold.deferred :as d]
   [malli.core :as m]))

;; Test fixtures
(defn reset-registry-fixture [f]
  (tool/reset-tool-stats!)
  (doseq [tool-name (tool/list-tools)]
    (tool/unregister-tool! tool-name))
  (f))

(use-fixtures :each reset-registry-fixture)

;; Test tools
(defn create-test-tool []
  (tool/simple-tool
   :test-tool
   "A test tool for unit testing"
   [:map [:input :string]]
   [:map [:output :string]]
   (fn [inputs] {:output (str "processed: " (:input inputs))})))

(defn create-failing-tool []
  (tool/simple-tool
   :failing-tool
   "A tool that always fails"
   [:map [:input :string]]
   [:map [:output :string]]
   (fn [inputs] (throw (ex-info "Tool failure" {:input inputs})))))

(defn create-async-tool []
  (tool/simple-tool
   :async-tool
   "A tool that returns a deferred"
   [:map [:delay-ms :int]]
   [:map [:result :string]]
   (fn [inputs]
     (d/future
       (Thread/sleep (:delay-ms inputs))
       {:result "async-complete"}))))

(deftest test-tool-protocol
  (testing "Tool protocol implementation"
    (let [tool (create-test-tool)]
      (is (tool/tool? tool))
      (is (= :test-tool (tool/-name tool)))
      (is (= "A test tool for unit testing" (tool/-description tool)))
      (is (some? (tool/-input-schema tool)))
      (is (some? (tool/-output-schema tool)))

      ;; Test invocation
      (let [result @(tool/-invoke tool {:input "test"})]
        (is (= {:output "processed: test"} result)))))

  (testing "Tool protocol with non-tool object"
    (is (not (tool/tool? "not a tool")))
    (is (not (tool/tool? {})))
    (is (not (tool/tool? nil)))))

(deftest test-tool-registry
  (testing "Tool registration and retrieval"
    (let [tool (create-test-tool)]
      ;; Register tool
      (is (= tool (tool/register-tool! tool)))
      (is (= tool (tool/get-tool :test-tool)))
      (is (contains? (set (tool/list-tools)) :test-tool))

      ;; Unregister tool
      (tool/unregister-tool! :test-tool)
      (is (nil? (tool/get-tool :test-tool)))
      (is (not (contains? (set (tool/list-tools)) :test-tool)))))

  (testing "Multiple tool registration"
    (let [tool1 (create-test-tool)
          tool2 (tool/echo-tool)]
      (tool/register-tool! tool1)
      (tool/register-tool! tool2)

      (is (= 2 (count (tool/list-tools))))
      (is (contains? (set (tool/list-tools)) :test-tool))
      (is (contains? (set (tool/list-tools)) :echo))

      (let [all-tools (tool/get-all-tools)]
        (is (= tool1 (:test-tool all-tools)))
        (is (= tool2 (:echo all-tools)))))))

(deftest test-tool-validation
  (testing "Input validation"
    (let [tool (create-test-tool)]
      (is (true? (tool/validate-tool-input tool {:input "valid"})))
      (is (false? (tool/validate-tool-input tool {:wrong-key "invalid"})))
      (is (false? (tool/validate-tool-input tool {:input 123}))) ; wrong type

      ;; Test explanation
      (let [explanation (tool/explain-tool-input tool {:wrong-key "invalid"})]
        (is (some? explanation)))))

  (testing "Output validation"
    (let [tool (create-test-tool)]
      (is (true? (tool/validate-tool-output tool {:output "valid"})))
      (is (false? (tool/validate-tool-output tool {:wrong-key "invalid"})))
      (is (false? (tool/validate-tool-output tool {:output 123}))) ; wrong type

      ;; Test explanation
      (let [explanation (tool/explain-tool-output tool {:wrong-key "invalid"})]
        (is (some? explanation))))))

(deftest test-tool-invocation
  (testing "Successful tool invocation"
    (let [tool (create-test-tool)
          result @(tool/invoke-tool tool {:input "test"})]
      (is (= {:output "processed: test"} result))))

  (testing "Tool invocation with input validation failure"
    (let [tool (create-test-tool)]
      (is (thrown? Exception @(tool/invoke-tool tool {:wrong-key "test"})))))

  (testing "Tool invocation without input validation"
    (let [tool (create-test-tool)
          result @(tool/invoke-tool tool {:wrong-key "test"} :validate-input? false)]
      ;; Should still work since the tool function handles the input
      (is (= {:output "processed: "} result))))

  (testing "Tool invocation with output validation failure"
    (let [bad-tool (tool/simple-tool
                    :bad-tool
                    "Tool with bad output"
                    [:map [:input :string]]
                    [:map [:output :string]]
                    (fn [inputs] {:wrong-output "bad"}))]
      (is (thrown? Exception @(tool/invoke-tool bad-tool {:input "test"})))))

  (testing "Tool invocation without output validation"
    (let [bad-tool (tool/simple-tool
                    :bad-tool
                    "Tool with bad output"
                    [:map [:input :string]]
                    [:map [:output :string]]
                    (fn [inputs] {:wrong-output "bad"}))]
      (let [result @(tool/invoke-tool bad-tool {:input "test"} :validate-output? false)]
        (is (= {:wrong-output "bad"} result)))))

  (testing "Tool invocation with timeout"
    (let [slow-tool (create-async-tool)]
      ;; Should timeout
      (is (thrown? Exception @(tool/invoke-tool slow-tool {:delay-ms 100} :timeout-ms 10)))

      ;; Should succeed
      (let [result @(tool/invoke-tool slow-tool {:delay-ms 10} :timeout-ms 100)]
        (is (= {:result "async-complete"} result)))))

  (testing "Tool invocation with error"
    (let [tool (create-failing-tool)]
      (is (thrown? Exception @(tool/invoke-tool tool {:input "test"}))))))

(deftest test-tool-invocation-by-name
  (testing "Invoke registered tool by name"
    (let [tool (create-test-tool)]
      (tool/register-tool! tool)
      (let [result @(tool/invoke-tool-by-name :test-tool {:input "test"})]
        (is (= {:output "processed: test"} result)))))

  (testing "Invoke non-existent tool by name"
    (is (thrown? Exception @(tool/invoke-tool-by-name :non-existent {:input "test"})))))

(deftest test-tool-context
  (testing "Create tool context from map"
    (let [tool1 (create-test-tool)
          tool2 (tool/echo-tool)
          context (tool/create-tool-context {:test-tool tool1 :echo tool2})]

      (is (= tool1 (tool/get-tool-from-context context :test-tool)))
      (is (= tool2 (tool/get-tool-from-context context :echo)))
      (is (nil? (tool/get-tool-from-context context :non-existent)))

      (is (= 2 (count (tool/list-tools-in-context context))))
      (is (contains? (set (tool/list-tools-in-context context)) :test-tool))
      (is (contains? (set (tool/list-tools-in-context context)) :echo))))

  (testing "Create tool context from vector"
    (let [tool1 (create-test-tool)
          tool2 (tool/echo-tool)
          context (tool/create-tool-context [tool1 tool2])]

      (is (= tool1 (tool/get-tool-from-context context :test-tool)))
      (is (= tool2 (tool/get-tool-from-context context :echo)))
      (is (= 2 (count (tool/list-tools-in-context context))))))

  (testing "Create tool context with metadata"
    (let [tool (create-test-tool)
          metadata {:version "1.0" :description "Test context"}
          context (tool/create-tool-context [tool] :metadata metadata)]

      (is (= metadata (:metadata context)))))

  (testing "Invoke tool from context"
    (let [tool (create-test-tool)
          context (tool/create-tool-context [tool])
          result @(tool/invoke-tool-from-context context :test-tool {:input "test"})]

      (is (= {:output "processed: test"} result))))

  (testing "Invoke non-existent tool from context"
    (let [context (tool/create-tool-context [])]
      (is (thrown? Exception @(tool/invoke-tool-from-context context :non-existent {:input "test"}))))))

(deftest test-tool-introspection
  (testing "Tool info"
    (let [tool (create-test-tool)
          info (tool/tool-info tool)]

      (is (= :test-tool (:name info)))
      (is (= "A test tool for unit testing" (:description info)))
      (is (some? (:input-schema info)))
      (is (some? (:output-schema info)))
      (is (map? (:input-example info)))
      (is (map? (:output-example info)))))

  (testing "Tool description"
    (let [tool (create-test-tool)
          description (tool/describe-tool tool)]

      (is (string? description))
      (is (.contains description "test-tool"))
      (is (.contains description "A test tool for unit testing"))))

  (testing "Describe tools in context"
    (let [tool1 (create-test-tool)
          tool2 (tool/echo-tool)
          context (tool/create-tool-context [tool1 tool2])
          description (tool/describe-tools-in-context context)]

      (is (string? description))
      (is (.contains description "test-tool"))
      (is (.contains description "echo")))))

(deftest test-utility-tools
  (testing "Echo tool"
    (let [tool (tool/echo-tool)
          result @(tool/invoke-tool tool {:message "hello"})]

      (is (= :echo (tool/-name tool)))
      (is (= {:echo "hello"} result))))

  (testing "Math tool"
    (let [tool (tool/math-tool)]

      (is (= :math (tool/-name tool)))

      ;; Test addition
      (let [result @(tool/invoke-tool tool {:expression "5 + 3"})]
        (is (= {:result 8 :error nil} result)))

      ;; Test subtraction
      (let [result @(tool/invoke-tool tool {:expression "10 - 4"})]
        (is (= {:result 6 :error nil} result)))

      ;; Test multiplication
      (let [result @(tool/invoke-tool tool {:expression "6 * 7"})]
        (is (= {:result 42 :error nil} result)))

      ;; Test division
      (let [result @(tool/invoke-tool tool {:expression "15 / 3"})]
        (is (= {:result 5 :error nil} result)))

      ;; Test division by zero
      (let [result @(tool/invoke-tool tool {:expression "10 / 0"})]
        (is (nil? (:result result)))
        (is (some? (:error result))))

      ;; Test unsupported expression
      (let [result @(tool/invoke-tool tool {:expression "sqrt(16)"})]
        (is (nil? (:result result)))
        (is (some? (:error result)))))))

(deftest test-tool-monitoring
  (testing "Tool execution statistics"
    (let [tool (create-test-tool)]

      ;; Initial stats should be empty
      (is (= {:invocations 0 :errors 0 :total-time-ms 0}
             (tool/get-tool-stats :test-tool)))

      ;; Execute tool successfully
      @(tool/monitored-invoke-tool tool {:input "test"})

      (let [stats (tool/get-tool-stats :test-tool)]
        (is (= 1 (:invocations stats)))
        (is (= 0 (:errors stats)))
        (is (>= (:total-time-ms stats) 0)))

      ;; Execute tool with error
      (let [failing-tool (create-failing-tool)]
        (try
          @(tool/monitored-invoke-tool failing-tool {:input "test"})
          (catch Exception _))

        (let [stats (tool/get-tool-stats :failing-tool)]
          (is (= 1 (:invocations stats)))
          (is (= 1 (:errors stats)))
          (is (>= (:total-time-ms stats) 0))))))

  (testing "Reset tool statistics"
    (let [tool (create-test-tool)]
      ;; Execute tool to generate stats
      @(tool/monitored-invoke-tool tool {:input "test"})

      ;; Verify stats exist
      (let [stats (tool/get-tool-stats :test-tool)]
        (is (> (:invocations stats) 0)))

      ;; Reset stats
      (tool/reset-tool-stats!)

      ;; Verify stats are reset
      (let [stats (tool/get-tool-stats :test-tool)]
        (is (= {:invocations 0 :errors 0 :total-time-ms 0} stats)))))

  (testing "Get all tool statistics"
    (let [tool1 (create-test-tool)
          tool2 (tool/echo-tool)]

      ;; Execute both tools
      @(tool/monitored-invoke-tool tool1 {:input "test"})
      @(tool/monitored-invoke-tool tool2 {:message "hello"})

      (let [all-stats (tool/get-all-tool-stats)]
        (is (contains? all-stats :test-tool))
        (is (contains? all-stats :echo))
        (is (= 1 (get-in all-stats [:test-tool :invocations])))
        (is (= 1 (get-in all-stats [:echo :invocations])))))))

(deftest test-simple-tool-creation
  (testing "Simple tool creation"
    (let [custom-tool (tool/simple-tool
                       :custom
                       "Custom test tool"
                       [:map [:x :int] [:y :int]]
                       [:map [:sum :int]]
                       (fn [inputs] {:sum (+ (:x inputs) (:y inputs))}))]

      (is (= :custom (tool/-name custom-tool)))
      (is (= "Custom test tool" (tool/-description custom-tool)))

      (let [result @(tool/invoke-tool custom-tool {:x 5 :y 3})]
        (is (= {:sum 8} result)))))

  (testing "Simple tool with async function"
    (let [async-tool (tool/simple-tool
                      :async-custom
                      "Async custom tool"
                      [:map [:value :string]]
                      [:map [:result :string]]
                      (fn [inputs]
                        (d/success-deferred {:result (str "async-" (:value inputs))})))]

      (let [result @(tool/invoke-tool async-tool {:value "test"})]
        (is (= {:result "async-test"} result)))))

  (testing "Simple tool with error"
    (let [error-tool (tool/simple-tool
                      :error-tool
                      "Tool that throws error"
                      [:map [:input :string]]
                      [:map [:output :string]]
                      (fn [inputs] (throw (ex-info "Custom error" {}))))]

      (is (thrown? Exception @(tool/invoke-tool error-tool {:input "test"}))))))

(deftest test-edge-cases
  (testing "Tool with nil schemas"
    (let [no-schema-tool (reify tool/ITool
                           (tool/-name [this] :no-schema)
                           (tool/-description [this] "No schema tool")
                           (tool/-input-schema [this] nil)
                           (tool/-output-schema [this] nil)
                           (tool/-invoke [this inputs]
                             (d/success-deferred {:result "ok"})))]

      ;; Should work without validation
      (let [result @(tool/invoke-tool no-schema-tool {:anything "works"} :validate-input? false :validate-output? false)]
        (is (= {:result "ok"} result)))))

  (testing "Empty tool context"
    (let [context (tool/create-tool-context [])]
      (is (empty? (tool/list-tools-in-context context)))
      (is (nil? (tool/get-tool-from-context context :any-tool)))))

  (testing "Tool context toString"
    (let [tool (create-test-tool)
          context (tool/create-tool-context [tool])]
      (is (string? (str context)))
      (is (.contains (str context) "test-tool")))))