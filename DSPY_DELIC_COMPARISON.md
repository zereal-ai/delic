# DSPy vs. delic: Feature Comparison

This document provides a side-by-side comparison of the Python-based DSPy framework and the pure-Clojure `delic` project. It highlights implemented features, key differences, and current gaps in `delic`.

## Feature Comparison Table

| Feature Area | DSPy | `delic` | Status in `delic` |
| :--- | :--- | :--- | :--- |
| **Core Programming** | | | |
| Language Models (LMs) | `dspy.LM` abstract base class, supports many models via LiteLLM. | `backend/protocol.clj`, `providers/openai.clj` | **Partial** |
| Signatures | `dspy.Signature` with `InputField`, `OutputField`. | `signature.clj` | **Implemented** |
| Modules | `dspy.Module` base class. Rich set of built-in modules (`Predict`, `ChainOfThought`, `ReAct`, etc.). | `module.clj` | **Partial** |
| Primitives | `Example`, `Prediction`, `History`, `Tool`, `Image`. | Core concepts likely exist internally. | **Partial** |
| Tool Use | `dspy.Tool` primitive, `ReAct` module, `PythonInterpreter`. | No explicit tool support found. | **Missing** |
| **Evaluation** | | | |
| Data Handling | Manages dev/test datasets. | No explicit data handling namespaces found. | **Missing** |
| Metrics | `dspy.Evaluate`, built-in metrics (`answer_exact_match`, `SemanticF1`). | No explicit metrics implementations found. | **Missing** |
| **Optimization** | | | |
| Optimizers | Large suite of "teleprompters" (`BootstrapFewShot`, `MIPROv2`, `COPRO`, `BootstrapFinetune`). | `optimize/beam.clj` | **Partial** |
| **Development** | | | |
| Persistence | `dspy.save` / `dspy.load` | `storage/` with `edn.clj` and `sqlite.clj` | **Implemented** |
| Async | `dspy.asyncify` wrapper. | `util/manifold.clj` (core architecture) | **Implemented** |
| Debugging | `inspect_history()` | `tap.clj` for Portal integration. | **Implemented** |
| Caching | `dspy.configure_cache` | Not explicitly mentioned. | **Missing** |
| **Project Specific** | | | |
| Language | Python | Clojure | **N/A** |
| Type Safety | Dynamic typing. | `malli` for schema validation. | **Implemented (Enhancement)** |
| Ecosystem | Python ML/AI ecosystem. | JVM/Clojure ecosystem. | **N/A** |

---

## Summary of Key Differences & Missing Features in `delic`

### 1. Optimizers are the Biggest Gap
`delic` has implemented `beam search`, which is a foundational optimization strategy. However, DSPy's main power comes from its diverse suite of "teleprompters" that automate complex prompt engineering and even model finetuning (`BootstrapFewShot`, `MIPROv2`, `BootstrapFinetune`, etc.). **This is the most significant feature gap.** The `delic` project brief lists "advanced optimization strategies" as future scope.

### 2. Evaluation Framework is Missing
DSPy relies on a robust evaluation framework with defined `Metrics` to drive its optimizers. `delic` is described as "metric-driven," but the actual implementation of metrics (`answer_exact_match`, `SemanticF1`, etc.) and the data handling for evaluation sets appears to be missing from the current source code. An optimization process cannot be started without a metric to optimize for.

### 3. Pre-built Modules and Tool Use
DSPy provides a rich library of pre-built modules that represent common reasoning patterns (`ChainOfThought`, `ReAct`, `ProgramOfThought`). `delic` provides the base `module.clj` but seems to lack these higher-level, off-the-shelf components.

Furthermore, `delic` currently has no infrastructure for `Tool` use, which is essential for agent-like modules such as `ReAct`.

### 4. Limited Language Model Support
`delic` has a provider for OpenAI, but has not yet built out the broader support for multiple LLMs that DSPy achieves through its abstractions and integrations like LiteLLM.

## `delic`'s Unique Strengths

It's also important to note where `delic` improves upon the DSPy model for its target ecosystem:

-   **Schema-driven Development**: Using `malli` for validation is a significant enhancement that provides type safety and data integrity, something not present in the Python version.
-   **Superior Debugging**: Live introspection via Clojure's Portal is a more powerful and interactive debugging experience than DSPy's `inspect_history`.
-   **Pure-JVM Solution**: The core value proposition of `delic` is removing the need for Python interop, which it achieves.
-   **Idiomatic Async**: Asynchronous operations are a core part of the architecture via Manifold, not a wrapper, leading to a more robust concurrent design. 