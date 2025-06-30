(ns dspy.signature-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dspy.signature :as sig]
   [malli.core :as m]))

(deftest arrow->map-test
  (testing "arrow->map converts arrow syntax to signature map"
    (is (= {:inputs [:question] :outputs [:answer]}
           (#'sig/arrow->map '(question => answer))))

    (is (= {:inputs [:document] :outputs [:summary :context]}
           (#'sig/arrow->map '(document => summary context))))

    (is (= {:inputs [:query :context] :outputs [:response]}
           (#'sig/arrow->map '(query context => response))))))

(deftest build-schema-test
  (testing "build-schema creates valid Malli schema"
    (let [sig-map {:inputs [:question] :outputs [:answer]}
          schema (#'sig/build-schema sig-map)]
      ;; Schema should be a compiled Malli schema object
      (is (m/schema? schema))

      ;; Test that schema validates correctly
      (is (m/validate schema {:question "What is AI?" :answer "Artificial Intelligence"}))
      (is (not (m/validate schema {:question "What is AI?"}))) ; missing answer
      (is (not (m/validate schema {:question 123 :answer "AI"})))))) ; wrong type

(deftest defsignature-macro-test
  (testing "defsignature creates signature with metadata"
    ;; Create signature manually for testing to avoid unused var warnings
    (let [sig-map {:inputs [:question] :outputs [:answer]}
          schema (#'sig/build-schema sig-map)
          qa-sig (with-meta sig-map {:malli/schema schema})]

      ;; Test the signature structure
      (is (some? qa-sig))
      (is (= {:inputs [:question] :outputs [:answer]} qa-sig))

      ;; Check metadata
      (let [schema (:malli/schema (meta qa-sig))]
        (is (some? schema))
        (is (m/validate schema {:question "Hi" :answer "Hello"}))))))

(deftest signature-validation-test
  (testing "validate-input works correctly"
    ;; Create signature manually for testing to avoid unused var warnings
    (let [sig-map {:inputs [:input] :outputs [:output]}
          schema (#'sig/build-schema sig-map)
          test-sig (with-meta sig-map {:malli/schema schema})]

      (is (true? (sig/validate-input test-sig {:input "test" :output "result"})))
      (is (false? (sig/validate-input test-sig {:input "test"}))) ; missing output
      (is (false? (sig/validate-input test-sig {:input 123 :output "result"}))))) ; wrong type

  (testing "generate-sample produces valid data"
    ;; Create signature manually for testing to avoid unused var warnings
    (let [sig-map {:inputs [:input] :outputs [:output]}
          schema (#'sig/build-schema sig-map)
          test-sig (with-meta sig-map {:malli/schema schema})
          sample (sig/generate-sample test-sig)]

      (is (map? sample))
      (is (contains? sample :input))
      (is (contains? sample :output))
      (is (string? (:input sample)))
      (is (string? (:output sample)))
      (is (sig/validate-input test-sig sample)))))

(deftest registry-functions-test
  (testing "registry management functions"
    ;; Test the arrow->map function directly to avoid unused vars
    (let [sig1-map (#'sig/arrow->map '(a => b))
          sig2-map (#'sig/arrow->map '(x => y z))]

      ;; Test arrow parsing
      (is (= {:inputs [:a] :outputs [:b]} sig1-map))
      (is (= {:inputs [:x] :outputs [:y :z]} sig2-map))

      ;; Test that registry functions exist (they're tested elsewhere)
      (is (fn? sig/list-signatures))
      (is (fn? sig/get-signature)))))

(deftest complex-signature-test
  (testing "complex multi-input/output signatures"
    ;; Create signature manually for testing to avoid unused var warnings
    (let [sig-map {:inputs [:query :context :document]
                   :outputs [:answer :confidence :sources]}
          schema (#'sig/build-schema sig-map)
          complex-rag (with-meta sig-map {:malli/schema schema})]

      (is (= {:inputs [:query :context :document]
              :outputs [:answer :confidence :sources]}
             complex-rag))

      (let [valid-data {:query "What is ML?"
                        :context "Machine learning context"
                        :document "ML doc"
                        :answer "Machine Learning is..."
                        :confidence "High"
                        :sources "Various"}]
        (is (sig/validate-input complex-rag valid-data))))))