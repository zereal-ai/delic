(ns build
  (:require
   [clojure.tools.build.api :as b])
  (:refer-clojure :exclude [test]))

(def lib 'delic/dspy-clj)
(def version (or (System/getenv "DSPY_VERSION")
                 (format "SNAPSHOT.%s" (b/git-count-revs nil))))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar"
                       (name lib) version))

(defn ^:export clean [_]
  (b/delete {:path "target"}))

(defn ^:export uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  ;; Skip compilation for now due to Malli schema serialization issues
  ;; (b/compile-clj {:basis basis :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           :main      'dspy.cli})
  (println "Built" uber-file))

(defn ^:export test [_]
  (b/process {:command-args ["clojure" "-M:test"]}))

(defn ^:export lint [_]
  (b/process {:command-args ["clojure" "-M:lint"]}))

(defn ^:export ci [_]
  (lint nil)
  (test nil)
  (uber nil))