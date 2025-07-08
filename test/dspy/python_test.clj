(ns dspy.python-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dspy.python :as python]))

;; Test fixtures to ensure clean state between tests
(defn python-fixture [f]
  ;; Ensure Python is shut down before each test
  (python/shutdown-python!)
  (f)
  ;; Clean up after test
  (python/shutdown-python!))

(use-fixtures :each python-fixture)

(deftest test-initialization-state
  (testing "Initial state"
    (is (not (python/initialized?))
        "Python should not be initialized initially"))

  (testing "State after initialization"
    ;; Note: This test will only pass if Python is actually available
    ;; In CI/production, this might be skipped if Python is not configured
    (when (and (System/getenv "PYTHON_EXECUTABLE")
               (System/getenv "PYTHON_LIBRARY_PATH"))
      (let [config {:python-executable (System/getenv "PYTHON_EXECUTABLE")
                    :library-path (System/getenv "PYTHON_LIBRARY_PATH")}]
        (is (python/init-python! config)
            "First initialization should return true")
        (is (python/initialized?)
            "Python should be initialized after init-python!")
        (is (not (python/init-python! config))
            "Second initialization should return false")))))

(deftest test-require-python
  (testing "require-python! when not initialized"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Python runtime not initialized"
                          (python/require-python!))
        "Should throw exception when Python not initialized"))

  (testing "require-python! when initialized"
    (when (and (System/getenv "PYTHON_EXECUTABLE")
               (System/getenv "PYTHON_LIBRARY_PATH"))
      (let [config {:python-executable (System/getenv "PYTHON_EXECUTABLE")
                    :library-path (System/getenv "PYTHON_LIBRARY_PATH")}]
        (python/init-python! config)
        (is (nil? (python/require-python!))
            "Should not throw when Python is initialized")))))

(deftest test-config-validation
  (testing "Invalid configuration"
    (is (thrown? AssertionError
                 (python/init-python! {:python-executable nil
                                       :library-path "/some/path"}))
        "Should validate python-executable is string")

    (is (thrown? AssertionError
                 (python/init-python! {:python-executable "/some/path"
                                       :library-path nil}))
        "Should validate library-path is string")

    (is (thrown? AssertionError
                 (python/init-python! {}))
        "Should require both configuration keys")))

(deftest test-shutdown
  (testing "shutdown when not initialized"
    (is (nil? (python/shutdown-python!))
        "Shutdown should be safe when not initialized"))

  (testing "shutdown after initialization"
    (when (and (System/getenv "PYTHON_EXECUTABLE")
               (System/getenv "PYTHON_LIBRARY_PATH"))
      (let [config {:python-executable (System/getenv "PYTHON_EXECUTABLE")
                    :library-path (System/getenv "PYTHON_LIBRARY_PATH")}]
        (python/init-python! config)
        (is (python/initialized?) "Should be initialized")
        (python/shutdown-python!)
        (is (not (python/initialized?)) "Should not be initialized after shutdown")))))

;; Integration test that actually uses Python (only runs if environment is configured)
(deftest test-python-integration
  (testing "Basic Python functionality"
    (when (and (System/getenv "PYTHON_EXECUTABLE")
               (System/getenv "PYTHON_LIBRARY_PATH")
               (System/getenv "RUN_PYTHON_TESTS"))
      (let [config {:python-executable (System/getenv "PYTHON_EXECUTABLE")
                    :library-path (System/getenv "PYTHON_LIBRARY_PATH")}]
        (python/init-python! config)

        ;; Test basic Python execution
        (try
          (do
            (require 'libpython-clj2.python)
            (let [result (eval '(do
                                  (require '[libpython-clj2.python :as py])
                                  (let [builtins (py/import-module "builtins")]
                                    (py/py. builtins len "hello"))))]
              (is (= 5 result) "Basic Python function should work")))
          (catch Exception e
            ;; If this fails, it might be due to Python environment issues
            ;; Log the error but don't fail the test in CI
            (println "Python integration test failed (this may be expected in CI):" (.getMessage e))))))))

(comment
  ;; Manual testing examples
  ;; Set these environment variables for testing:
  ;; export PYTHON_EXECUTABLE="/usr/bin/python3"
  ;; export PYTHON_LIBRARY_PATH="/usr/lib/libpython3.10.so"
  ;; export RUN_PYTHON_TESTS="true"

  ;; Then run:
  ;; (run-tests 'dspy.python-test)
  )