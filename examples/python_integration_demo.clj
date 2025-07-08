(ns examples.python-integration-demo
  "Demonstration of Python integration with delic.

   This example shows how to:
   1. Initialize the Python runtime
   2. Use Python libraries from Clojure
   3. Create Python-based tools for delic workflows
   4. Integrate with ReAct modules for tool-based reasoning

   To run this demo:
   1. Ensure Python 3.x is installed with numpy and pandas
   2. Set environment variables for your Python installation
   3. Load this namespace in your REPL
   4. Run the demo functions"
  (:require [dspy.python :as python]
            [dspy.tools.python-tools :as py-tools]
            [dspy.tool :as tool]
            [dspy.module :as mod]
            [dspy.modules.react :as react]
            [dspy.backend.protocol :as bp]
            [manifold.deferred :as d]))

;; Configuration for common Python installations
(def common-python-configs
  {:macos-homebrew {:python-executable "/opt/homebrew/bin/python3"
                    :library-path "/opt/homebrew/lib/libpython3.11.dylib"}
   :macos-system {:python-executable "/usr/bin/python3"
                  :library-path "/usr/lib/libpython3.9.dylib"}
   :ubuntu-20 {:python-executable "/usr/bin/python3"
               :library-path "/usr/lib/x86_64-linux-gnu/libpython3.8.so"}
   :ubuntu-22 {:python-executable "/usr/bin/python3"
               :library-path "/usr/lib/x86_64-linux-gnu/libpython3.10.so"}
   :centos-8 {:python-executable "/usr/bin/python3"
              :library-path "/usr/lib64/libpython3.6m.so"}})

(defn demo-basic-python-usage
  "Demonstrates basic Python functionality integration."
  []
  (println "=== Basic Python Usage Demo ===")

  ;; Check if Python is initialized
  (if (python/initialized?)
    (println "✓ Python runtime is already initialized")
    (println "✗ Python runtime not initialized. Call (init-python-runtime!) first."))

  (when (python/initialized?)
    (println "\n1. Testing NumPy addition:")
    (let [result (py-tools/numpy-add 15 25)]
      (println "  NumPy add(15, 25) =" result))

    (println "\n2. Testing NumPy array statistics:")
    (let [numbers [1 2 3 4 5 6 7 8 9 10]
          stats (py-tools/numpy-array-stats numbers)]
      (println "  Array:" numbers)
      (println "  Statistics:" stats))

    (println "\n3. Testing pandas DataFrame analysis:")
    (let [data [{:name "Alice" :age 30 :salary 50000}
                {:name "Bob" :age 25 :salary 45000}
                {:name "Carol" :age 35 :salary 60000}
                {:name "David" :age 28 :salary 48000}]
          analysis (py-tools/pandas-dataframe-info data)]
      (println "  Data:" data)
      (println "  Analysis:" analysis))))

(defn demo-python-tools
  "Demonstrates Python-based delic tools."
  []
  (println "\n=== Python Tools Demo ===")

  (when (python/initialized?)
    (let [numpy-tool (py-tools/create-numpy-tool)
          pandas-tool (py-tools/create-pandas-tool)]

      (println "\n1. NumPy tool execution:")
      (let [result @(tool/invoke-tool numpy-tool {:numbers [10 20 30 40 50]})]
        (println "  Input: [10 20 30 40 50]")
        (println "  Result:" result))

      (println "\n2. Pandas tool execution:")
      (let [records [{:product "Widget A" :price 10.99 :quantity 100}
                     {:product "Widget B" :price 15.49 :quantity 75}
                     {:product "Widget C" :price 8.25 :quantity 150}]
            result @(tool/invoke-tool pandas-tool {:records records})]
        (println "  Input records:" (count records) "items")
        (println "  Result:" result)))))

(defn demo-react-with-python-tools
  "Demonstrates ReAct module with Python-based tools."
  [backend]
  (println "\n=== ReAct with Python Tools Demo ===")

  (when (python/initialized?)
    (let [numpy-tool (py-tools/create-numpy-tool)
          pandas-tool (py-tools/create-pandas-tool)
          tool-context (tool/create-tool-context [numpy-tool pandas-tool])
          react-module (react/react backend tool-context)]

      (println "\n1. ReAct reasoning with NumPy tool:")
      (let [question "Calculate the mean and standard deviation of the numbers [5, 10, 15, 20, 25] using the numpy-stats tool."
            result @(d/timeout!
                     (mod/call react-module {:question question})
                     30000
                     {:error "Timeout"})]
        (println "  Question:" question)
        (println "  Result:" (:answer result))
        (when (:react-steps result)
          (println "  Steps taken:" (count (:react-steps result)))))

      (println "\n2. ReAct reasoning with Pandas tool:")
      (let [question "Analyze this sales data using pandas: [{:product \"A\" :sales 100} {:product \"B\" :sales 150} {:product \"C\" :sales 75}]"
            result @(d/timeout!
                     (mod/call react-module {:question question})
                     30000
                     {:error "Timeout"})]
        (println "  Question:" question)
        (println "  Result:" (:answer result))
        (when (:react-steps result)
          (println "  Steps taken:" (count (:react-steps result))))))))

(defn init-python-runtime!
  "Initialize Python runtime with automatic configuration detection.

   This function tries common Python configurations and uses the first one that works."
  ([]
   (init-python-runtime! nil))
  ([config-key]
   (if (python/initialized?)
     (println "Python runtime already initialized.")
     (let [configs (if config-key
                     [(get common-python-configs config-key)]
                     (vals common-python-configs))]
       (loop [remaining configs]
         (if (empty? remaining)
           (println "Failed to initialize Python with any common configuration.")
           (let [config (first remaining)
                 success? (try
                            (python/init-python! config)
                            (println "✓ Python runtime initialized successfully with config:" config)
                            true
                            (catch Exception e
                              (println "✗ Failed with config" config ":" (.getMessage e))
                              false))]
             (if success?
               nil ; Exit the loop
               (recur (rest remaining))))))))))

(defn run-full-demo
  "Runs the complete Python integration demonstration.

   Optionally takes a backend for ReAct demonstrations.
   If no backend is provided, only basic Python functionality is demonstrated."
  ([]
   (run-full-demo nil))
  ([backend]
   (println "🐍 Python Integration Demo for delic")
   (println "=====================================")

   ;; Initialize Python if needed
   (when-not (python/initialized?)
     (println "\nInitializing Python runtime...")
     (init-python-runtime!))

   ;; Run demos
   (demo-basic-python-usage)
   (demo-python-tools)

   ;; Only run ReAct demo if backend is provided
   (when backend
     (demo-react-with-python-tools backend))

   (println "\n✓ Demo complete!")
   (println "\nTo run ReAct demonstrations, provide a backend:")
   (println "  (run-full-demo your-backend)")))

(comment
  ;; Example usage:

  ;; 1. Initialize Python runtime
  (init-python-runtime!)

  ;; 2. Run basic demos
  (run-full-demo)

  ;; 3. With backend for ReAct demos
  (require '[dspy.backend.providers.openai :as openai])
  (def backend (openai/create-openai-backend {:model "gpt-4o-mini"}))
  (run-full-demo backend)

  ;; 4. Manual Python configuration
  (python/init-python! {:python-executable "/your/python/path"
                        :library-path "/your/libpython/path"})

  ;; 5. Check Python status
  (python/initialized?)

  ;; 6. Shutdown Python (for testing)
  (python/shutdown-python!))