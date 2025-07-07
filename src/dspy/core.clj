(ns dspy.core
  "Public façade for the delic library.")

(defn version
  "Returns the current version of delic"
  []
  "0.1.0-SNAPSHOT")

;; TODO: Re-export main API functions when they're implemented
;; (def defsignature sig/defsignature)
;; (def compile-pipeline pipe/compile-pipeline)
;; (def load-pipeline pipe/load-pipeline)