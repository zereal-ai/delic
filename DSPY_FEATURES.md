# DSPy Features

This document provides a comprehensive overview of the features available in the DSPy framework, based on its official documentation at [dspy.ai/learn/](https://dspy.ai/learn/).

## Core Concepts

DSPy is structured around three key stages for building AI systems:

1.  **DSPy Programming**: Defining tasks, constraints, and initial pipeline design.
2.  **DSPy Evaluation**: Systematically evaluating and iterating on the system using metrics and development data.
3.  **DSPy Optimization**: Tuning prompts or model weights using DSPy optimizers to improve performance.

---

## DSPy Programming

### 1. Language Models (LMs)

-   Abstract interface for interacting with various language models.
-   The `dspy.LM` class is the base for this.

### 2. Signatures

-   Declaratively define the input/output behavior of a DSPy module.
-   They specify what a module should do, not how.
-   **Components**:
    -   `dspy.Signature`: Base class for creating signatures.
    -   `dspy.InputField`: Defines an input field with a description.
    -   `dspy.OutputField`: Defines an output field with a description.

### 3. Modules

-   Building blocks of DSPy programs. They are composable and optimizable.
-   `dspy.Module`: The base class for all modules.
-   **Core Modules**:
    -   `dspy.Predict`: A basic module for simple prediction tasks.
    -   `dspy.ChainOfThought`: Guides the LM to think step-by-step.
    -   `dspy.ProgramOfThought`: Uses an external tool (like a Python interpreter) to generate programs for reasoning.
    -   `dspy.ReAct`: An agent-like module that can use tools to answer questions.
    -   `dspy.MultiChainComparison`: Compares multiple outputs to find the best one.
    -   `dspy.Refine`: Iteratively refines an output.
    -   `dspy.BestOfN`: Generates N outputs and selects the best one.
    -   `dspy.CodeAct`: A module for code generation tasks.
    -   `dspy.Parallel`: A utility for running modules in parallel.

### 4. Primitives

-   Core data structures used within DSPy.
-   `dspy.Example`: Represents a data point with inputs and outputs.
-   `dspy.Prediction`: Represents the output of a language model.
-   `dspy.History`: Represents the history of interactions for a module.
-   `dspy.Tool`: Represents an external tool that can be used by modules like `ReAct`.
-   `dspy.Image`: Primitive for handling image data.

### 5. Tools

-   Integrations with external systems.
-   `dspy.ColBERTv2`: A powerful retrieval model.
-   `dspy.PythonInterpreter`: A tool for executing Python code.
-   `dspy.Embeddings`: Tools for working with text embeddings.

---

## DSPy Evaluation

### 1. Data Handling

-   Mechanisms for managing datasets for development and evaluation.

### 2. Metrics

-   Functions for evaluating the performance of DSPy programs.
-   `dspy.Evaluate`: Core evaluation utility.
-   **Built-in Metrics**:
    -   `dspy.answer_exact_match`: Checks for exact string match.
    -   `dspy.answer_passage_match`: Checks if the answer is present in a given passage.
    -   `dspy.SemanticF1`: Calculates F1 score based on semantic similarity.
    -   `dspy.CompleteAndGrounded`: A more complex metric for evaluating groundedness.

---

## DSPy Optimization

### 1. Optimizers (Teleprompters)

-   Algorithms that tune the prompts and/or weights of DSPy modules to maximize a given metric.
-   `dspy.BootstrapFewShot`: Generates few-shot examples for prompts.
-   `dspy.BootstrapFewShotWithRandomSearch`: Combines few-shot generation with random search over templates.
-   `dspy.BootstrapFinetune`: Optimizes by finetuning the underlying LM.
-   `dspy.MIPROv2`: Multi-task Instruction and Prompt Optimization.
-   `dspy.COPRO`: Content-based Prompt Optimization.
-   `dspy.LabeledFewShot`: Uses labeled examples to create few-shot prompts.
-   `dspy.BootstrapRS`: Bootstrap-based random search.
-   `dspy.KNNFewShot`: Uses KNN to find few-shot examples.
-   `dspy.InferRules`: Infers rules for a task.
-   `dspy.SIMBA`: A simple Bayesian optimization approach.
-   `dspy.Ensemble`: Creates an ensemble of different programs.
-   `dspy.BetterTogether`: An optimizer for collaborative tasks.

---

## Development and Deployment

### 1. Utilities

-   `dspy.load` / `dspy.save`: Save and load DSPy programs.
-   `dspy.configure_cache`: Configure caching for LM calls.
-   `inspect_history`: Debugging utility to inspect the history of LM calls.
-   `asyncify`: Utility for running synchronous functions asynchronously.
-   `streamify`: Utility for streaming outputs.
-   Logging controls: `enable_logging`, `disable_logging`, etc.

### 2. Adapters

-   Adapters to handle different model API formats.
-   `dspy.ChatAdapter`: Adapts models to a chat-based format.
-   `dspy.JSONAdapter`: For models that require JSON inputs/outputs.
-   `dspy.TwoStepAdapter`: A two-step process for generation and refinement.

### 3. Use Cases & Tutorials

DSPy is versatile and can be applied to a wide range of tasks, as demonstrated by official tutorials:

-   **RAG (Retrieval-Augmented Generation)**: Basic and Multi-Hop.
-   **Agents**: Building autonomous agents with `ReAct` and tool use.
-   **Classification & Entity Extraction**.
-   **Reasoning**: Math reasoning with `ChainOfThought` and `ProgramOfThought`.
-   **Advanced Tool Use**.
-   **Code Generation**.
-   **Output Refinement**.