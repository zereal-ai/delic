(ns dspy.tools.python-tools
  "Example Python-based tools for delic.

   This namespace demonstrates how to create tools that use Python libraries
   like NumPy, OpenCV, etc. without exposing libpython-clj details to users.

   All functions in this namespace assume that Python has been initialized
   via (dspy.python/init-python! config)."
  (:require [libpython-clj2.python :as py]
            [dspy.python :as python]
            [dspy.tool :as tool]
            [malli.core :as m]
            [manifold.deferred :as d]))

(defn numpy-add
  "Adds two numbers using NumPy.

   This is a simple example of calling Python functionality from Clojure.
   Requires Python runtime to be initialized."
  [a b]
  (python/require-python!)
  (let [np (py/import-module "numpy")]
    (py/py. np add a b)))

(defn numpy-array-stats
  "Calculates statistics for a numeric array using NumPy.

   Returns a map with mean, std, min, max values.
   Requires Python runtime to be initialized."
  [numbers]
  (python/require-python!)
  (let [np (py/import-module "numpy")
        arr (py/py. np array numbers)]
    {:mean (py/py. np mean arr)
     :std (py/py. np std arr)
     :min (py/py. np min arr)
     :max (py/py. np max arr)
     :sum (py/py. np sum arr)}))

(defn create-numpy-tool
  "Creates a delic tool that provides NumPy array statistics.

   This demonstrates how to wrap Python functionality as a delic tool
   that can be used in ReAct modules and other tool-based workflows."
  []
  (reify tool/ITool
    (-name [_] :numpy-stats)
    (-description [_] "Calculate statistics (mean, std, min, max, sum) for a numeric array using NumPy")
    (-input-schema [_]
      [:map
       [:numbers [:vector number?]]])
    (-output-schema [_]
      [:map
       [:mean number?]
       [:std number?]
       [:min number?]
       [:max number?]
       [:sum number?]])
    (-invoke [_ {:keys [numbers]}]
      (try
        (d/success-deferred (numpy-array-stats numbers))
        (catch Exception e
          (d/error-deferred e))))))

(defn pandas-dataframe-info
  "Analyzes a dataset using pandas DataFrame.

   Takes a vector of maps (records) and returns basic info about the dataset.
   Requires Python runtime to be initialized."
  [records]
  (python/require-python!)
  (let [pd (py/import-module "pandas")
        df (py/py. pd DataFrame records)]
    {:shape (vec (py/py.- df shape))
     :columns (vec (py/py.- df columns))
     :dtypes (into {} (py/py. df dtypes))
     :info (py/py. df info)}))

(defn create-pandas-tool
  "Creates a delic tool that provides pandas DataFrame analysis.

   This tool can analyze tabular data and provide insights about
   the structure and content of datasets."
  []
  (reify tool/ITool
    (-name [_] :pandas-analyze)
    (-description [_] "Analyze tabular data using pandas DataFrame to get shape, columns, and data types")
    (-input-schema [_]
      [:map
       [:records [:vector [:map [:string :any]]]]])
    (-output-schema [_]
      [:map
       [:shape [:vector int?]]
       [:columns [:vector string?]]
       [:dtypes [:map [:string :string]]]
       [:info :any]])
    (-invoke [_ {:keys [records]}]
      (try
        (d/success-deferred (pandas-dataframe-info records))
        (catch Exception e
          (d/error-deferred e))))))

;; Example usage functions that demonstrate the integration

(comment
  ;; First, initialize Python
  (require '[dspy.python :as python])
  (python/init-python! {:python-executable "/usr/bin/python3"
                        :library-path "/usr/lib/libpython3.10.so"})

  ;; Then use Python functionality
  (numpy-add 5 3)
  ;; => 8

  (numpy-array-stats [1 2 3 4 5])
  ;; => {:mean 3.0, :std 1.58..., :min 1, :max 5, :sum 15}

  ;; Use as tools in delic workflows
  (def numpy-tool (create-numpy-tool))
  (def pandas-tool (create-pandas-tool))

  ;; Execute tools
  (require '[dspy.tool :as tool])
  @(tool/execute numpy-tool {:numbers [10 20 30 40 50]})
  ;; => {:result {:mean 30.0, :std 15.81..., :min 10, :max 50, :sum 150}}

  @(tool/execute pandas-tool {:records [{:name "Alice" :age 30}
                                        {:name "Bob" :age 25}
                                        {:name "Carol" :age 35}]}))
;; => {:result {:shape [3 2], :columns ["name" "age"], :dtypes {...}, :info ...}})