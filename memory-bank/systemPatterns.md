# System Patterns: delic

## Core Architecture Principles

### 1. Protocol-Driven Design
- **Interface Segregation**: Clear separation between abstract protocols and concrete implementations
- **Plugin Architecture**: Extensible system where new backends, optimizers, and storage can be added without changing core code
- **Dependency Inversion**: High-level modules don't depend on low-level modules; both depend on abstractions

### 2. Async-First Architecture
- **Manifold Deferreds**: All I/O operations return deferreds for non-blocking execution
- **Composable Async**: Deferreds chain naturally with `d/chain`, `d/catch`, `d/zip` for complex workflows
- **Resource Management**: Proper cleanup and cancellation throughout async operations

### 3. Schema-Driven Development
- **Runtime Validation**: Malli schemas validate all inputs and outputs at runtime
- **Type Safety**: Clear contracts between components with descriptive error messages
- **Configuration Validation**: All configuration options validated against schemas

## Core Protocols & Abstractions

### ILlmBackend Protocol
```clojure
(defprotocol ILlmBackend
  (-generate [backend prompt options])
  (-embeddings [backend texts options])
  (-stream [backend prompt options]))
```

**Design Patterns**:
- **Provider Abstraction**: Same interface works with OpenAI, Anthropic, local models
- **Wrapper Pattern**: Middleware can wrap any backend (throttling, retry, logging)
- **Factory Pattern**: `create-backend` function creates backends from configuration

### ILlmModule Protocol
```clojure
(defprotocol ILlmModule
  (-call [module inputs])
  (-signature [module]))
```

**Design Patterns**:
- **Strategy Pattern**: Different module implementations (Predict, ChainOfThought, ReAct)
- **Decorator Pattern**: Modules can wrap other modules for enhanced functionality
- **Composite Pattern**: Pipeline composer combines multiple modules

### ITool Protocol
```clojure
(defprotocol ITool
  (-name [tool])
  (-description [tool])
  (-input-schema [tool])
  (-output-schema [tool])
  (-execute [tool inputs]))
```

**Design Patterns**:
- **Command Pattern**: Tools encapsulate operations that can be executed
- **Registry Pattern**: Global tool registry for discovery and execution
- **Validation Pattern**: Schema-based input/output validation

### Storage Protocol
```clojure
(defprotocol Storage
  (create-run! [storage pipeline-data])
  (append-metric! [storage run-id metric-data])
  (load-run [storage run-id])
  (load-history [storage run-id]))
```

**Design Patterns**:
- **Repository Pattern**: Abstract storage operations from business logic
- **Factory Pattern**: Storage creation from URLs (sqlite://, file://)
- **Strategy Pattern**: Different storage backends (SQLite, EDN files)

## Module System Architecture

### Signature System
- **Declarative Specification**: `defsignature` macro for input/output definitions
- **Schema Integration**: Automatic Malli schema generation from signatures
- **Validation Separation**: Separate validation for inputs and outputs
- **Metadata Preservation**: Rich metadata for debugging and introspection

### Module Composition Patterns

#### 1. Sequential Composition
```clojure
(def pipeline (mod/compose-modules tokenizer embedder classifier))
```
- **Linear Flow**: Output of one module becomes input of next
- **Error Propagation**: Failures halt the pipeline with clear error context
- **Type Safety**: Schema validation between module boundaries

#### 2. Parallel Composition
```clojure
(def parallel-pipeline (mod/parallel-modules [embedder-1 embedder-2] merger))
```
- **Concurrent Execution**: Multiple modules run simultaneously
- **Result Aggregation**: Merger combines parallel results
- **Resource Efficiency**: Optimal utilization of async capabilities

#### 3. Conditional Composition
```clojure
(def conditional-pipeline 
  (mod/conditional-module predicate true-branch false-branch merger))
```
- **Dynamic Routing**: Runtime decisions based on data
- **Branch Isolation**: Each branch operates independently
- **Result Unification**: Consistent output format regardless of branch

## Advanced Module Patterns

### ChainOfThought Module
- **Signature Transformation**: Automatically adds `:rationale` field to signatures
- **Prompt Enhancement**: Context-aware prompting for step-by-step reasoning
- **Response Parsing**: Structured extraction of reasoning and answers
- **Fallback Handling**: Graceful degradation when reasoning is missing

### ReAct Module
- **Loop Management**: Iterative thought-action-observation cycles
- **Tool Integration**: Seamless execution of external tools
- **State Tracking**: Maintains conversation history and step progression
- **Termination Conditions**: Multiple exit strategies (answer found, max iterations, errors)

### Tool System
- **Safe Execution**: SCI-based Clojure interpreter for secure code execution
- **Schema Validation**: Input/output validation for all tool interactions
- **Error Isolation**: Tool failures don't crash the entire system
- **Context Management**: Tool discovery and execution within defined contexts

## Concurrency & Resource Management

### Rate Limiting Pattern
```clojure
(def throttled-backend (wrap-throttle backend {:rps 5 :burst 10}))
```
- **Token Bucket Algorithm**: Smooth rate distribution with burst capacity
- **Fair Queuing**: All backend methods share the same rate limit
- **Non-blocking Delays**: Manifold-based delays instead of thread blocking

### Parallel Processing Pattern
```clojure
(parallel-map-unordered process-fn collection {:concurrency 8})
```
- **Controlled Concurrency**: Environment-configurable parallelism
- **Memory Efficiency**: Chunked processing for large collections
- **Early Termination**: Cancel remaining work on first error
- **Order Variants**: Both order-preserving and unordered processing

### Resource Lifecycle Pattern
```clojure
(with-resource-cleanup
  (fn [] (create-expensive-resource))
  (fn [resource] (process-with-resource resource))
  (fn [resource] (cleanup-resource resource)))
```
- **Exception Safety**: Guaranteed cleanup even on errors
- **Async Compatibility**: Works with deferred-returning operations
- **Composability**: Can be nested and combined with other patterns

## Optimization Engine Architecture

### Strategy Pattern for Optimizers
```clojure
(defmulti optimize-strategy :strategy)
(defmethod optimize-strategy :beam [config pipeline training-data metric]
  ;; Beam search implementation
  )
```
- **Pluggable Algorithms**: Easy to add new optimization strategies
- **Configuration-Driven**: Strategy selection via configuration
- **Consistent Interface**: All strategies follow same contract

### Evaluation Framework
- **Metric Abstraction**: Pluggable scoring functions
- **Dataset Flexibility**: Multiple input formats with automatic normalization
- **Async Evaluation**: Non-blocking assessment with timeout support
- **Error Resilience**: Individual evaluation failures don't stop the process

### Persistence Integration
- **Dynamic Binding**: Storage context available throughout optimization
- **Checkpoint Strategy**: Configurable save intervals
- **Resume Capability**: Complete state restoration from storage
- **History Tracking**: Full optimization trail for analysis

## Error Handling Patterns

### Graceful Degradation
- **Fallback Strategies**: Alternative paths when primary operations fail
- **Partial Results**: Return what's available even if some operations fail
- **Error Context**: Rich error information for debugging

### Circuit Breaker Pattern
```clojure
(def protected-backend (wrap-circuit-breaker backend {:failure-threshold 5}))
```
- **Failure Detection**: Automatic detection of backend failures
- **Fast Failure**: Immediate failure when circuit is open
- **Recovery Testing**: Periodic attempts to restore service

### Retry with Exponential Backoff
```clojure
(def resilient-backend (wrap-retry backend {:max-retries 3 :initial-delay 100}))
```
- **Transient Failure Handling**: Automatic retry for temporary issues
- **Backoff Strategy**: Exponential delays with jitter
- **Retry Limits**: Bounded retry attempts to prevent infinite loops

## Live Introspection & Debugging

### Portal Integration
- **Automatic Detection**: Portal availability detected at runtime
- **Tap Installation**: Automatic `tap>` integration when available
- **Data Visualization**: Real-time data exploration and debugging
- **Graceful Degradation**: No impact when Portal unavailable

### Instrumentation Pattern
```clojure
(tap> {:event :module-execution
       :module module-name
       :inputs inputs
       :outputs outputs
       :duration duration-ms})
```
- **Event-Driven Logging**: Structured events for key operations
- **Performance Monitoring**: Timing information for all operations
- **Data Inspection**: Full input/output capture for debugging

## Testing Patterns

### Mock Backend Pattern
```clojure
(defrecord MockBackend [responses]
  ILlmBackend
  (-generate [_ prompt options]
    (d/success-deferred (first @responses))))
```
- **Deterministic Testing**: Predictable responses for test scenarios
- **State Isolation**: Each test gets fresh mock state
- **Response Cycling**: Support for multi-interaction scenarios

### Property-Based Testing
- **Schema Generation**: Automatic test data generation from Malli schemas
- **Edge Case Discovery**: Systematic exploration of input space
- **Regression Prevention**: Generated tests catch future breaking changes

### Integration Testing
- **Real Backend Testing**: Optional tests with actual LLM providers
- **Environment Gating**: Tests only run when API keys available
- **Rate Limit Respect**: Throttled testing to avoid API limits

## Configuration Management

### Environment-Driven Configuration
```clojure
{:backend {:type :openai
           :api-key (env :openai-api-key)
           :model "gpt-4o"}
 :storage {:url (env :dspy-storage "file://./runs")}
 :concurrency {:parallelism (env :dspy-parallelism 4)}}
```
- **Environment Variables**: Runtime configuration from environment
- **Sensible Defaults**: System works without configuration
- **Type Coercion**: Automatic conversion of environment strings

### Schema-Validated Configuration
- **Configuration Schemas**: All config options validated at startup
- **Clear Error Messages**: Helpful feedback for configuration errors
- **Documentation**: Schema serves as configuration documentation

## Security Patterns

### API Key Management
- **Environment Variables**: Never hardcode API keys
- **Runtime Injection**: Keys loaded only at application startup
- **Graceful Fallback**: Mock keys for development/testing

### Safe Code Execution
- **SCI Integration**: Small Clojure Interpreter for safe evaluation
- **Namespace Control**: Limited namespace access for security
- **Timeout Protection**: Execution time limits to prevent infinite loops
- **Resource Limits**: Memory and computation constraints

### Input Validation
- **Schema Validation**: All inputs validated against schemas
- **Sanitization**: Dangerous inputs rejected or cleaned
- **Error Context**: Clear feedback without exposing internals

## Performance Patterns

### Lazy Evaluation
- **Deferred Computation**: Work only done when results needed
- **Resource Conservation**: Minimal memory usage for large datasets
- **Early Termination**: Stop processing when conditions met

### Caching Strategies
- **Result Memoization**: Cache expensive computation results
- **TTL Support**: Time-based cache invalidation
- **Memory Bounds**: Configurable cache size limits

### Connection Pooling
- **HTTP Client Reuse**: Shared connections for backend requests
- **Connection Limits**: Bounded connection pools
- **Timeout Management**: Proper connection timeout handling

## Deployment Patterns

### Uberjar Packaging
- **Self-Contained Deployment**: Single JAR with all dependencies
- **Configuration Externalization**: Runtime configuration via environment
- **Health Checks**: Built-in endpoints for monitoring

### Container-Ready
- **Environment Configuration**: 12-factor app compliance
- **Signal Handling**: Graceful shutdown on SIGTERM
- **Resource Limits**: JVM tuning for container environments

### Observability
- **Structured Logging**: JSON logs for log aggregation
- **Metrics Export**: JVM metrics for monitoring systems
- **Distributed Tracing**: Request correlation across services

These patterns form the foundation of delic's architecture, enabling a robust, scalable, and maintainable LLM optimization framework that can grow with evolving requirements while maintaining clean abstractions and excellent developer experience.