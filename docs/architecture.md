# EDDI Architecture

**Version: 6.0.0**

This document provides a comprehensive overview of EDDI's architecture, design principles, and internal workflow.

## Table of Contents

1. [Overview](#overview)
2. [What EDDI Is (and Isn't)](#what-eddi-is-and-isnt)
3. [Core Architecture](#core-architecture)
4. [The Lifecycle Pipeline](#the-lifecycle-pipeline)
5. [Conversation Flow](#conversation-flow)
6. [Agent Composition Model](#agent-composition-model)
7. [Key Components](#key-components)
8. [Technology Stack](#technology-stack)

---

## Overview

E.D.D.I. (Enhanced Dialog Driven Interface) is a **multi-agent orchestration middleware** for conversational AI systems, not a standalone chatagent or language model. It sits between user-facing applications and multiple AI agents (LLMs like OpenAI, Claude, Gemini, or traditional REST APIs), intelligently routing requests, coordinating responses, and maintaining conversation state across agent interactions.

**Core Purpose**: Orchestrate multiple AI agents and business systems in complex conversational workflows without writing code.

## What EDDI Is (and Isn't)

- **A Multi-Agent Orchestration Middleware**: Coordinates multiple AI agents (LLMs, APIs) in complex workflows
- **An Intelligent Router**: Directs requests to appropriate agents based on patterns, rules, and context
- **A Conversation Coordinator**: Maintains stateful conversations across multiple agent interactions
- **A Configuration Engine**: Agent orchestration defined through JSON configurations, not code
- **A Middleware Service**: Acts as an intermediary that adds intelligence and control to conversation flows
- **Business System Integrator**: Connects AI agents with your existing APIs, databases, and services
- **Cloud-Native**: Built with Quarkus for fast startup, low memory footprint, and containerized deployment
- **Stateful**: Maintains complete conversation history and context throughout interactions

### EDDI Is Not:

- **Not a standalone LLM**: It doesn't train or run machine learning models
- **Not a chatbot platform**: It's the infrastructure that powers conversational agents
- **Not just a proxy**: It provides orchestration, state management, and complex behavior rules beyond simple API forwarding

---

## Core Architecture

### Architectural Principles

EDDI's architecture is built on several key principles:

1. **Modularity**: Every component is pluggable and replaceable
2. **Composability**: Agents are assembled from reusable packages and extensions
3. **Asynchronous Processing**: Non-blocking I/O for handling concurrent conversations
4. **State-Driven**: All operations transform or query the conversation state
5. **Cloud-Native**: Designed for containerized, distributed deployments

### High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      User Application                        │
│                  (Web, Mobile, Chat Client)                  │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTP/REST API
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                      RestAgentEngine                           │
│          (Entry Point - JAX-RS AsyncResponse)                │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                  ConversationCoordinator                     │
│           (Ensures Sequential Processing per                 │
│            Conversation, Concurrent Across)                  │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                   IConversationMemory                        │
│       (Stateful Object - Complete Conversation Context)      │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                     LifecycleManager                         │
│          (Executes Sequential Pipeline of Tasks)             │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌──────────────┐   ┌──────────────┐    ┌──────────────┐
│Input Parsing │   │Behavior Rules│    │LLM/API Calls │
│  (NLP, etc)  │   │(IF-THEN Logic│    │(LangChain4j, │
│              │   │              │    │ HTTP Calls)  │
└──────────────┘   └──────────────┘    └──────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             ▼
                   ┌──────────────────┐
                   │Output Generation │
                   │  (Templating)    │
                   └──────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    MongoDB + Cache                           │
│         (Persistent Storage + Fast Retrieval)                │
└─────────────────────────────────────────────────────────────┘
```

---

## The Lifecycle Pipeline

The **Lifecycle** is EDDI's most distinctive architectural feature. Instead of hard-coded agent logic, EDDI processes every user interaction through a **configurable, sequential pipeline of tasks** called the **Lifecycle**.

### How the Lifecycle Works

1. **Pipeline Composition**: Each agent defines a sequence of `ILifecycleTask` components
2. **Sequential Execution**: Tasks execute one after another, each transforming the `IConversationMemory`
3. **Stateless Tasks**: Each task is stateless; all state resides in the memory object passed through
4. **Interruptible**: The pipeline can be stopped early based on conditions (e.g., `STOP_CONVERSATION`)

### Standard Lifecycle Tasks

A typical agent lifecycle includes these task types:

| Task Type               | Purpose                                         | Example                                     |
| ----------------------- | ----------------------------------------------- | ------------------------------------------- |
| **Input Parsing**       | Normalizes and understands user input           | Extracting entities, intents from text      |
| **Semantic Parsing**    | Uses dictionaries to parse expressions          | Matching "hello" → `greeting(hello)`        |
| **Behavior Rules**      | Evaluates IF-THEN rules to decide actions       | "If `greeting(*)` then `action(welcome)`"   |
| **Property Extraction** | Extracts and stores data in conversation memory | Saving user name, preferences               |
| **HTTP Calls**          | Calls external REST APIs                        | Weather API, CRM systems                    |
| **LangChain Task**      | Invokes LLM APIs (OpenAI, Claude, etc.)         | Conversational AI responses                 |
| **Output Generation**   | Formats final response using templates          | Thymeleaf templating with conversation data |

### Lifecycle Task Interface

```java
public interface ILifecycleTask {
    String getId();
    String getType();
    void execute(IConversationMemory memory, Object component)
        throws LifecycleException;
}
```

Every task receives:

- **IConversationMemory**: Complete conversation state
- **component**: Task-specific configuration/resources

---

## Conversation Flow

### Step-by-Step: User Interaction Flow

Here's what happens when a user sends a message to an EDDI agent:

#### 1. API Request

```
POST /agents/{environment}/{agentId}/{conversationId}
Body: { "input": "Hello, what's the weather?", "context": {...} }
```

#### 2. RestAgentEngine Receives Request

- Validates agent ID and environment
- Wraps response in `AsyncResponse` for non-blocking processing
- Increments metrics counters

#### 3. ConversationCoordinator Queues Message

- Ensures messages for the same conversation are processed sequentially
- Allows different conversations to process concurrently
- Prevents race conditions in conversation state

#### 4. IConversationMemory Loaded/Created

- If existing conversation: Loads from MongoDB
- If new conversation: Creates fresh memory object
- Includes all previous steps, user data, context

#### 5. LifecycleManager Executes Pipeline

```
Input → Parser → Behavior Rules → API/LLM → Output → Save
```

Each task in sequence:

- Reads current conversation state
- Performs its operation (parsing, rule evaluation, API call, etc.)
- Writes results back to conversation memory
- Passes control to next task

#### 6. State Persistence

- Updated `IConversationMemory` saved to MongoDB
- Cache updated with latest conversation state
- Metrics recorded (duration, success/failure)

#### 7. Response Returned

```json
{
  "conversationState": "READY",
  "conversationOutputs": [
    {
      "output": ["The weather today is sunny with a high of 75°F"],
      "actions": ["weather_response"]
    }
  ]
}
```

---

## Agent Composition Model

EDDI agents are **not monolithic**. They are **composite objects** assembled from version-controlled, reusable components.

### Hierarchy: Agent → Workflow → Extensions

```
Agent (.agent.json)
  ├─ Workflow 1 (.package.json)
  │   ├─ Behavior Rules Extension (.behavior.json)
  │   ├─ HTTP Calls Extension (.httpcalls.json)
  │   └─ Output Extension (.output.json)
  ├─ Workflow 2 (.package.json)
  │   ├─ Dictionary Extension (.dictionary.json)
  │   └─ LangChain Extension (.langchain.json)
  └─ Workflow 3 (.package.json)
      └─ Property Extension (.property.json)
```

### 1. Agent Level

**File**: `{agentId}.agent.json`

A agent is simply a **list of package references**:

```json
{
  "packages": [
    "eddi://ai.labs.package/packagestore/packages/{workflowId}?version={version}",
    "eddi://ai.labs.package/packagestore/packages/{anotherWorkflowId}?version={version}"
  ]
}
```

### 2. Workflow Level

**File**: `{workflowId}.package.json`

A package is a **container of functionality** with a list of extensions:

```json
{
  "packageExtensions": [
    {
      "type": "eddi://ai.labs.behavior",
      "extensions": {
        "uri": "eddi://ai.labs.behavior/behaviorstore/behaviorsets/{behaviorId}?version={version}"
      },
      "config": {
        "appendActions": true
      }
    },
    {
      "type": "eddi://ai.labs.httpcalls",
      "extensions": {
        "uri": "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/{httpCallsId}?version={version}"
      }
    }
  ]
}
```

### 3. Extension Level

**Files**: `{extensionId}.{type}.json`

Extensions are the **actual agent logic**:

#### Behavior Rules Extension

```json
{
  "behaviorGroups": [
    {
      "name": "Greetings",
      "behaviorRules": [
        {
          "name": "Welcome User",
          "conditions": [
            {
              "type": "inputmatcher",
              "configs": {
                "expressions": "greeting(*)",
                "occurrence": "currentStep"
              }
            }
          ],
          "actions": ["welcome_action"]
        }
      ]
    }
  ]
}
```

#### HTTP Calls Extension

```json
{
  "targetServerUrl": "https://api.weather.com",
  "httpCalls": [
    {
      "name": "getWeather",
      "actions": ["fetch_weather"],
      "request": {
        "method": "GET",
        "path": "/current?location=${context.userLocation}"
      },
      "postResponse": {
        "propertyInstructions": [
          {
            "name": "currentWeather",
            "fromObjectPath": "weatherResponse.temperature",
            "scope": "conversation"
          }
        ]
      }
    }
  ]
}
```

#### LangChain Extension

```json
{
  "tasks": [
    {
      "actions": ["send_to_ai"],
      "id": "openaiChat",
      "type": "openai",
      "parameters": {
        "apiKey": "...",
        "modelName": "gpt-4o",
        "systemMessage": "You are a helpful assistant",
        "sendConversation": "true",
        "addToOutput": "true"
      }
    }
  ]
}
```

---

## Key Components

### RestAgentEngine

**Location**: `ai.labs.eddi.engine.internal.RestAgentEngine`

**Purpose**: Main entry point for all agent interactions

**Responsibilities**:

- Receives HTTP requests via JAX-RS
- Validates agent and conversation IDs
- Handles async responses
- Records metrics
- Coordinates with `IConversationCoordinator`

### ConversationCoordinator

**Location**: `ai.labs.eddi.engine.runtime.internal.ConversationCoordinator`

**Purpose**: Ensures proper message ordering and concurrency control

**Key Feature**: Uses a queue system to guarantee that:

- Messages within the same conversation are processed sequentially
- Different conversations can be processed in parallel
- No race conditions occur in conversation state updates

### IConversationMemory

**Location**: `ai.labs.eddi.engine.memory.IConversationMemory`

**Purpose**: The stateful object representing a complete conversation

**Contains**:

- Conversation ID, agent ID, user ID
- All previous conversation steps (history)
- Current step being processed
- User properties (name, preferences, etc.)
- Context data (passed with each request)
- Actions and outputs generated

**Key Methods**:

```java
String getConversationId();
IWritableConversationStep getCurrentStep();
IConversationStepStack getPreviousSteps();
ConversationState getConversationState();
void undoLastStep();
void redoLastStep();
```

### LifecycleManager

**Location**: `ai.labs.eddi.engine.lifecycle.internal.LifecycleManager`

**Purpose**: Executes the lifecycle pipeline

**Key Method**:

```java
void executeLifecycle(
    IConversationMemory conversationMemory,
    List<String> lifecycleTaskTypes
) throws LifecycleException
```

**How It Works**:

1. Iterates through registered `ILifecycleTask` instances
2. For each task, calls `task.execute(conversationMemory, component)`
3. Checks for interruption or stop conditions
4. Continues until all tasks complete or stop condition is met

### WorkflowConfiguration

**Location**: `ai.labs.eddi.configs.packages.model.WorkflowConfiguration`

**Purpose**: Defines the structure of a agent package

**Model**:

```java
public class WorkflowConfiguration {
    private List<WorkflowExtension> packageExtensions;

    public static class WorkflowExtension {
        private URI type;
        private Map<String, Object> extensions;
        private Map<String, Object> config;
    }
}
```

### ToolExecutionService

**Location**: `ai.labs.eddi.modules.langchain.tools.ToolExecutionService`

**Purpose**: Unified execution pipeline for all AI agent tool invocations

**Pipeline**:

```
Tool Call ──▶ Rate Limiter ──▶ Cache Check ──▶ Execute ──▶ Cost Tracker ──▶ Result
```

**Features**:

- Token-bucket rate limiting per tool (configurable per-tool or global default)
- Smart caching — deduplicates calls with identical arguments
- Cost tracking with per-conversation budgets and automatic eviction
- Security: tools that accept URLs are validated against private/internal addresses (SSRF protection via `UrlValidationUtils`)
- Security: math expressions are evaluated in a sandboxed parser (`SafeMathParser`)

See the [Security documentation](security.md) for details.

---

## Technology Stack

### Core Framework

- **Quarkus**: Supersonic, subatomic Java framework
  - Fast startup times (~0.05s)
  - Low memory footprint
  - Native compilation support
  - Built-in observability (metrics, health checks)

### Language & Runtime

- **Java 25**: Latest LTS with modern language features
- **GraalVM**: Optional native compilation for even faster startup

### Dependency Injection

- **CDI (Contexts and Dependency Injection)**: Jakarta EE standard
- **@ApplicationScoped, @Inject**: Clean, testable component wiring

### REST Framework

- **JAX-RS**: Jakarta REST API standard
- **AsyncResponse**: Non-blocking, scalable request handling
- **JSON-B**: JSON binding for serialization/deserialization

### Database (DB-Agnostic)

- **MongoDB 6.0+** (default): Document store for agent configurations and conversation logs
- **PostgreSQL** (alternative): JDBC + JSONB storage, switchable via `eddi.datastore.type=postgres`
- Both backends support:
  - Agent, workflow, and extension configuration storage
  - Conversation history persistence
  - Version control of agent components
  - Automatic schema migration on startup

### Caching

- **Caffeine**: High-performance in-memory cache (replaced Infinispan in v6)
  - Caches conversation state and agent configurations
  - Configurable size limits per cache type
  - Zero external dependencies — provided transitively by `quarkus-cache`

### LLM Integration

- **LangChain4j**: Java library for LLM orchestration
  - Unified interface to multiple LLM providers
  - Supports OpenAI, Claude, Gemini, Ollama, Hugging Face, etc.
  - Handles chat message formatting, streaming, tool calling

### Observability

- **Micrometer**: Metrics collection
- **Prometheus**: Metrics exposition
- **Kubernetes Probes**: Liveness and readiness endpoints

### Security

- **OAuth 2.0**: Authentication and authorization
- **Keycloak**: Identity and access management

### Templating

- **Thymeleaf**: Output templating engine
  - Dynamic output generation
  - Access to conversation memory in templates
  - Expression language support

---

## Design Patterns Used

### 1. Strategy Pattern

- **Where**: Lifecycle tasks
- **Why**: Different behaviors (parsing, rules, API calls) implement the same `ILifecycleTask` interface

### 2. Chain of Responsibility

- **Where**: Lifecycle pipeline
- **Why**: Each task processes the memory object and passes it to the next task

### 3. Composite Pattern

- **Where**: Agent composition (Agent → Workflows → Extensions)
- **Why**: Agents are built from hierarchical, reusable components

### 4. Repository Pattern

- **Where**: Data access (stores: agentstore, packagestore, etc.)
- **Why**: Abstracts data persistence from business logic

### 5. Factory Pattern

- **Where**: `IAgentFactory`
- **Why**: Complex agent instantiation from multiple packages and configurations

### 6. Coordinator Pattern

- **Where**: `ConversationCoordinator`
- **Why**: Manages concurrent access to shared conversation state

---

## Performance Characteristics

### Startup Time

- **JVM mode**: < 2 seconds
- **Native mode**: < 50ms (with GraalVM)

### Memory Footprint

- **JVM mode**: ~200MB baseline
- **Native mode**: ~50MB baseline

### Request Latency

- **Without LLM**: 10-50ms (parsing, rules, simple API calls)
- **With LLM**: 500-5000ms (depends on LLM provider)

### Scalability

- **Vertical**: Handles thousands of concurrent conversations per instance
- **Horizontal**: Stateless design allows infinite horizontal scaling
- **Agenttleneck**: MongoDB becomes agenttleneck; use replica sets and sharding

---

## Cloud-Native Features

### Containerization

- Official Docker images: `labsai/eddi`
- Certified by IBM/Red Hat
- Multi-stage builds for minimal image size

### Orchestration

- Kubernetes-ready
- OpenShift certified
- Health checks built-in

### Configuration

- Externalized configuration via environment variables
- ConfigMaps and Secrets support
- No rebuild needed for configuration changes

### Observability

- Prometheus metrics endpoint: `/q/metrics`
- Health checks: `/q/health/live`, `/q/health/ready`
- Structured logging with correlation IDs

---

## Case Study: The "Agent Father"

The **Agent Father** is a meta-agent that demonstrates EDDI's architecture in action. It's a agent that creates other agents.

> **For a comprehensive, step-by-step walkthrough of Agent Father, see [Agent Father: A Deep Dive](agent-father-deep-dive.md)**

### How It Works

1. **Conversation Start**: User starts chat with Agent Father
2. **Information Gathering**: Agent Father asks questions:
   - "What do you want to call your agent?"
   - "What should it do?"
   - "Which LLM API should it use?"
3. **Memory Storage**: Property setters save answers to conversation memory:
   - `context.agentName`
   - `context.agentDescription`
   - `context.llmType`
4. **Condition Triggers**: Behavior rule monitors memory:
   ```json
   {
     "conditions": [
       {
         "type": "contextmatcher",
         "configs": {
           "contextKey": "agentName",
           "contextType": "string"
         }
       }
     ],
     "actions": ["httpcall(create-agent)"]
   }
   ```
5. **API Call Execution**: HTTP Calls extension triggers:
   ```json
   {
     "name": "create-agent",
     "request": {
       "method": "POST",
       "path": "/agentstore/agents",
       "body": "{\"agentName\": \"${context.agentName}\"}"
     }
   }
   ```
6. **Self-Modification**: Agent Father calls EDDI's own API to create a new agent configuration

### Key Insight

Agent Father isn't special code—it's a **regular EDDI agent** that uses:

- Behavior rules to control conversation flow
- Property extraction to gather data
- HTTP Calls to invoke EDDI's REST API
- Output templates to guide the user

This demonstrates EDDI's power: **the same architecture that powers conversational agents can orchestrate complex, multi-step workflows**, even self-modifying the system itself.

**See the [Agent Father Deep Dive](agent-father-deep-dive.md) for complete implementation details, code examples, and real-world applications.**

---

## Summary

EDDI's architecture is built on principles of **modularity**, **composability**, and **orchestration**. It's not a chatbot—it's the **infrastructure for building sophisticated conversational AI systems** that can:

- Orchestrate multiple APIs and LLMs
- Apply complex business logic through configurable rules
- Maintain stateful, context-aware conversations
- Scale horizontally in cloud environments
- Be assembled from reusable, version-controlled components

The **Lifecycle Pipeline** is the heart of this architecture, providing a flexible, pluggable system where agent behavior is configuration, not code.

---

## Configuration Model Deep Dive

EDDI's configuration model is a 4-level tree:

```mermaid
graph TD
    Agent["🤖 Agent (.agent.json)"] --> P1["📦 Workflow 1"]
    Agent --> P2["📦 Workflow 2"]
    Agent --> PN["📦 Workflow N"]
    P1 --> Parser1["🔤 Parser (dictionaries)"]
    P1 --> Behavior1["🧠 Behavior Rules"]
    P1 --> Property1["📝 Property Setter"]
    P1 --> Output1["💬 Output Templates"]
    P2 --> Parser2["🔤 Parser"]
    P2 --> Behavior2["🧠 Behavior Rules"]
    P2 --> HttpCalls2["🌐 HTTP Calls"]
    P2 --> Property2["📝 Property Setter"]
    P2 --> Output2["💬 Output Templates"]
```

### Agent → Workflows → Extensions

| Level          | Purpose                                                                                                                        |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Agent**      | List of workflow URIs + channels. The top-level container.                                                                      |
| **Workflow**   | Ordered list of workflow extensions — each extension = one lifecycle task type. **Order matters**: tasks execute sequentially. |
| **Extension**  | The actual configuration that drives each `ILifecycleTask`. Referenced by URI from the workflow.                                |
| **Descriptor** | Metadata (name, description, timestamps) for any resource. Not functional, purely for UI/management.                           |

### URI-Based References

Every resource references its dependencies by `eddi://` URI:

```
Agent → Workflow: "eddi://ai.labs.workflow/workflowstore/workflows/{id}?version=1"
Workflow → Rules: "eddi://ai.labs.rules/rulestore/rulesets/{id}?version=1"
Workflow → ApiCalls: "eddi://ai.labs.apicalls/apicallstore/apicalls/{id}?version=1"
Workflow → LLM: "eddi://ai.labs.llm/llmstore/llmconfigs/{id}?version=1"
```

### Extension Types & Their Pipeline Role

Each workflow runs its extensions in order: **Parser → Behavior → Property → HttpCalls → LLM → Output** (typical order).

| Extension Type | Input | Output | Key Feature |
|---|---|---|---|
| **Parser** | Raw user text | Expressions (semantic representation) | `expressionsAsActions: true` — parser expressions become actions |
| **Behavior Rules** | Actions and expressions | New actions that drive subsequent tasks | IF-THEN condition engine — the routing logic |
| **Property Setter** | Current memory data | Stored properties (conversation-scoped or long-term) | Slot-filling using `[[${memory.current.input}]]` templates |
| **HTTP Calls** | Actions, template variables | Response data stored in memory | Pre/post request property instructions, retry support |
| **LLM** | Conversation memory, system prompt, tools | LLM response text | Legacy chat (simple) or Agent mode (tool-calling loop) |
| **Output Templates** | Actions from current step | Text responses + quickReplies | Template variables, response variation via `valueAlternatives` |

### Parser & Expression System

The parser uses a recursive expression model with Prolog heritage:

```
greeting                         → simple expression (no args)
greeting(hello)                  → expression with sub-expression
intent(weather, location(NYC))   → nested sub-expressions
*                                → wildcard (matches anything)
```

**QuickReply → Expression → Action flow**: When a user clicks a quickReply, the parser matches the text against the previous step's quickReply `value` fields, extracts the corresponding `expressions`, and (if `expressionsAsActions` is enabled) converts them to actions that drive behavior rules.

### Available NLP Extensions

| Type                 | Extensions                                                                                               |
| -------------------- | -------------------------------------------------------------------------------------------------------- |
| **Dictionaries** (7) | `RegularDictionary`, `IntegerDictionary`, `DecimalDictionary`, `EmailDictionary`, `TimeExpressionDictionary`, `OrdinalNumbersDictionary`, `PunctuationDictionary` |
| **Normalizers** (4)  | `ContractedWordNormalizer`, `ConvertSpecialCharacterNormalizer`, `PunctuationNormalizer`, `RemoveUndefinedCharacterNormalizer` |
| **Corrections** (3)  | `DamerauLevenshteinCorrection`, `MergedTermsCorrection`, `PhoneticCorrection` |

---

## Database Architecture

EDDI's data layer is fully DB-agnostic via the `IResourceStorageFactory` SPI:

```
REST API → Store Interface (IResourceStore<T>)
         → HistorizedResourceStore<T> (versioning, history, soft-delete)
         → IResourceStorage<T> (SPI — Storage Provider Interface)
         ├── MongoResourceStorage<T> (MongoDB implementation)
         └── PostgresResourceStorage<T> (PostgreSQL + JSONB implementation)
```

Switching databases requires only a config change:
```properties
eddi.datastore.type=mongodb   # default
# eddi.datastore.type=postgres  # alternative
```

---

## Related Documentation

- [Getting Started](getting-started.md) - Setup and installation
- [Conversation Memory & State Management](conversation-memory.md) - Deep dive into conversation state
- [Agent Father: A Deep Dive](agent-father-deep-dive.md) - Complete walkthrough of a real-world example
- [Behavior Rules](behavior-rules.md) - Configure decision logic
- [HTTP Calls](httpcalls.md) - External API integration
- [LLM Integration](langchain.md) - Connect to LLM APIs
- [Extensions](extensions.md) - Available agent components
- [Security](security.md) - Authentication, authorization, and tool security
- [Secrets Vault](secrets-vault.md) - Encrypted secret management
- [Audit Ledger](audit-ledger.md) - EU AI Act compliance
- [MCP Server](mcp-server.md) - Model Context Protocol integration
