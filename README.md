# desic
**Declarative Self-Improving Clojure**

> **A pure-Clojure implementation of DSPy** — systematic optimization for LLM pipelines without the prompt engineering chaos.

⚠️ **Alpha Stage**: This project is in active development and not yet production-ready.

## What is desic?

**desic** (Declarative Self-Improving Clojure) brings DSPy's revolutionary approach to Clojure: **programming**—not prompting—language models. Instead of manually crafting fragile prompt strings, you write declarative code that gets automatically optimized for reliability and performance.

## Why desic?

### **Systematic Optimization**
Stop guessing at prompts. desic's optimizers automatically tune your LLM parameters using beam search, few-shot learning, and metric-driven compilation—often producing better results than hand-crafted prompts.

### **Separation of Concerns** 
Write your pipeline logic once. Let desic handle the LLM parameters (prompts, examples, weights) separately, so changes to models or data don't break your entire system.

### **Built for Concurrency**
Native async support with **Manifold** enables efficient parallel LLM calls with automatic rate limiting, retries, and backpressure—essential for production workloads.

### **Schema-First Reliability**
**Malli** schemas validate inputs/outputs at every stage. No more surprise hallucinations breaking downstream processing.

### **Live Introspection**
**Portal** integration provides real-time visibility into optimization runs, module execution, and pipeline performance—debug complex LLM behavior with ease.

### **Resumable Optimization**
Persistent storage (SQLite/EDN) means expensive optimization runs can be paused, resumed, and incrementally improved without starting over.

### **Zero-Dependency Deployment**
Compile to a single uberjar that runs anywhere with Java. No Python runtime, no virtual environments, no dependency hell.

## Core Concepts

```clojure
;; Define behavior declaratively
(defsignature QA (question => answer))

;; Compose into pipelines  
(defn my-rag [question]
  (-> question
      retrieve-context
      (generate-answer :sig QA)))

;; Optimize automatically
(optimise my-rag trainset exact-match-metric)
```

## Key Differentiators

- **Pure Clojure**: Leverage JVM ecosystem without Python interop complexity
- **Functional**: Immutable pipelines, composable modules, predictable behavior  
- **Production-Ready Design**: Rate limiting, circuit breakers, monitoring, and deployment tools included
- **Extensible**: Plugin architecture for custom backends, optimizers, and storage layers

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Signatures    │───▶│     Modules      │───▶│   Optimizers    │
│  (what to do)   │    │  (how to do it)  │    │ (make it better)│
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    LLM Backend Abstraction                     │
│          (OpenAI, Anthropic, local models, etc.)               │
└─────────────────────────────────────────────────────────────────┘
```

Perfect for building reliable RAG systems, multi-agent workflows, and complex reasoning pipelines that need to work consistently at scale.

---

**Alpha Status**: Early development stage • See [PLAN.md](PLAN.md) for complete implementation roadmap 