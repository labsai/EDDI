# EDDI Backend — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI** (Enhanced Dialog Driven Interface) is a multi-agent orchestration middleware for conversational AI. This repo is the **Java/Quarkus backend**.

EDDI is a **config-driven engine**, not a monolithic application. Agent behavior lives in JSON configurations; Java code builds the _components_ and _infrastructure_ (the "engine") that reads and executes those configurations.

### Ecosystem

| Repo                                                            | Tech                       | Purpose                                                      |
| --------------------------------------------------------------- | -------------------------- | ------------------------------------------------------------ |
| **EDDI** (this repo)                                            | Java 25, Quarkus, MongoDB  | Backend engine, REST API, lifecycle pipeline                 |
| **[quarkus-eddi](https://github.com/quarkiverse/quarkus-eddi)** | Java 21, Quarkus Extension | Quarkus SDK — `@Inject EddiClient`, Dev Services, MCP bridge |
| **EDDI-Manager**                                                | React 19, Vite, Tailwind   | Admin dashboard (served from EDDI at `/chat/production`)     |
| **eddi-chat-ui**                                                | React, TypeScript          | Standalone chat widget                                       |
| **eddi-website**                                                | Astro, Starlight           | Marketing site + documentation at eddi.labs.ai               |

### Key Architecture

- **Config-driven engine**: Agent logic is JSON configs, Java is the processing engine. When designing a new feature, always ask: "should this be configurable by the agent designer?" If yes, expose it as a config field with sensible defaults — don't hardcode behavior or pick a single "best" approach.
- **Lifecycle pipeline**: Input → Parse → Behavior Rules → Actions → Tasks → Output
- **Stateless tasks, stateful memory**: `ILifecycleTask` implementations are singletons; all state lives in `IConversationMemory`
- **Action-based orchestration**: Tasks emit/listen for string-based actions, never call each other directly
- **Self-contained platform**: EDDI is a closed platform, not a library consumed by third-party code. Internal interfaces (`IUserMemoryStore`, `IResourceStore`, etc.) have no external consumers. Deprecation and replacement of internal APIs is safe — the only backward-compat concern is old JSON configs stored in MongoDB or imported via ZIP.
- **CI/CD**: GitHub Actions (compile → test → Docker build → smoke test → push to Docker Hub). `[skip docker]` in commit message skips image builds. Tag-based releases (`v6.0.0-RC1` → `labsai/eddi:6.0.0-RC1`)

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work

1. **Read the key docs**:
   - [`docs/project-philosophy.md`](docs/project-philosophy.md) — **Supreme directive.** 7 architectural pillars governing all EDDI development
   - [`docs/changelog.md`](docs/changelog.md) — **READ FIRST.** Running log of all changes, decisions, and reasoning across ALL repos and sessions
   - [`docs/architecture.md`](docs/architecture.md) — Architecture overview, configuration model, pipeline, and DB-agnostic design
   - If working on **EDDI-Manager**: also read `EDDI-Manager/AGENTS.md` in the Manager repo
2. **Check git status**: Run `git status` and `git log -5 --oneline` to see current branch state and recent work.

### During Work

3. **Branching**: Check `git branch --show-current` and `git log -5 --oneline` to understand the current branch context. **Do NOT commit directly to `main`.** If unsure which branch to use, ask the user.
4. **Commit often**: Every working unit gets a commit. Use conventional commits:
   ```
   feat(scope): description
   fix(scope): description
   chore(scope): description
   refactor(scope): description
   ```
5. **Each commit must build**: Run `./mvnw compile` (or `./mvnw test` for backend) before committing. Never commit broken code.

### After Completing Work (or if interrupted/switching sessions)

6. **Update the changelog**: Edit [`docs/changelog.md`](docs/changelog.md) and add an entry with:
   - Date and short title
   - Repo and branch
   - What changed (files + reasoning)
   - Design decisions made
   - What's in progress / what's next (if interrupted mid-task)

---

## 3. Development Roadmap

Follow this order unless the user explicitly requests something different.
**Backend first, then testing, then frontend. Website last.**

### Completed ✅

| Phase | Area                     | Highlights                                                                                          |
| ----- | ------------------------ | --------------------------------------------------------------------------------------------------- |
| 0     | Security Quick Wins      | CORS lockdown, PathNavigator (replaced OGNL)                                                        |
| 1     | Backend Foundation       | ConversationService extraction, SSE streaming, typed memory, LlmTask decomposition                  |
| 2     | Testing Infrastructure   | Integration tests migrated to main repo, Testcontainers, API contract tests                         |
| 3     | Manager UI               | Greenfield React 19 + Vite + Tailwind rewrite                                                       |
| 4     | Chat-UI                  | CRA→Vite, SSE streaming, Keycloak auth                                                              |
| 5     | NATS JetStream           | Event bus abstraction, async processing, coordinator dashboard                                      |
| 6     | DB-Agnostic Architecture | PostgreSQL adapter, MongoDB sync driver, Caffeine cache, Lombok removal, langchain4j core migration |
| 7     | Security & Compliance    | Secrets Vault, Audit Ledger (EU AI Act), tenant quota stub                                          |
| 8     | MCP Integration          | MCP Server (33 tools), MCP Client, agent discovery, managed conversations                           |
| 8c    | RAG Foundation            | Config-driven vector store retrieval, pgvector, httpCall RAG                                         |
| 10    | Group Conversations       | Multi-agent debate orchestration, 5 styles, group-of-groups                                          |
| —     | A2A Protocol              | Agent-to-Agent peer communication, Agent Cards, skill discovery                                      |
| —     | Multi-Model Cascading     | Sequential model escalation with confidence routing                                                  |
| —     | LLM Provider Expansion    | 7 → 12 providers (Mistral, Azure OpenAI, Bedrock, Oracle GenAI)                                      |
| —     | Quarkus 3.34.1            | LTS upgrade, Java 25 module fix                                                                      |
| 12    | CI/CD                     | GitHub Actions unified pipeline, Docker Hub push, CircleCI removed                                    |
| 11a   | Persistent Memory         | IUserMemoryStore, UserMemoryTool, DreamService, McpMemoryTools, Property.Visibility                  |
| —     | Conversation Windows      | Token-aware windowing, rolling summary, ConversationRecallTool                                        |
| —     | Agentic Improvements 1–5  | Counterweights, MCP governance, capability registry, multimodal attachments, agent signing            |
| —     | Compliance Hardening      | HIPAA, EU AI Act, international privacy docs + ComplianceStartupChecks                               |
| —     | Prompt Snippets           | Config-driven system prompt building blocks, Caffeine-cached, REST CRUD                              |
| —     | Agent Sync                | Granular export/import, structural matching, live instance-to-instance sync                          |
| —     | GDPR/CCPA Framework       | Cascading erasure, data portability, Art. 18 restriction, per-category retention                     |
| —     | Commit Flags              | Strict write discipline for memory — uncommit failed task data, error digest injection                |
| —     | Template Preview          | REST endpoint for previewing resolved system prompts with sample/live data                           |
| —     | RC2 Hardening             | 2,000+ unit tests, 250+ integration tests, branding overhaul, rules deserialization fix              |

### In Progress / Upcoming

| Phase | Area                      | Description                                                           |
| ----- | ------------------------- | --------------------------------------------------------------------- |
| —     | Memory Architecture       | Commit flags, RAG threshold, context selection, auto-compaction, property consolidation (see `docs/planning/memory-architecture-plan.md`) |
| —     | Session Forking           | State snapshotting, conversation forking (see `docs/planning/agentic-improvements-plan.md` §7)       |
| —     | Conversation Chaining     | Cross-session context carry-over (see `docs/planning/conversation-window-management.md` Strategy 3)  |
| 9     | DAG Pipeline              | Parallel tasks, circuit breakers, OpenTelemetry tracing               |
| 9b    | HITL Framework            | Human-in-the-loop pause/resume/approve                                |
| —     | Guardrails                | Config-driven input/output guardrails in LlmTask (see `docs/planning/guardrails-architecture.md`)    |
| 11b   | Multi-Channel             | Slack, Teams adapters (see `docs/planning/multi-agent-ux-improvements.md`)                           |
| 13    | Debugging & Visualization | Time-traveling debugger, visual pipeline builder                      |
| 14    | Website                   | Astro + Starlight documentation site                                  |
| —     | Native Image              | GraalVM native compilation (see `docs/planning/native-image-migration.md`)                           |

---

## 4. Backend Java Guidelines

### 4.1 Golden Rules (Non-Negotiable)

1. **Logic is Configuration, Java is the Engine** — Agent behavior (e.g., "if user says 'hello', call API 'X'") MUST NOT be hard-coded in Java. Agent logic belongs in **JSON configurations** (`behavior.json`, `httpcalls.json`, `langchain.json`). Java code creates the `ILifecycleTask` components that _read and execute_ this configuration. (Note: the config file is still named `langchain.json` but the implementing class is `LlmTask`.)
2. **Stateless Tasks, Stateful Memory** — `ILifecycleTask` implementations MUST be stateless. They are singletons shared by all conversations. All conversational state MUST be read from and written to the `IConversationMemory` object passed into the `execute` method.
3. **Action-Based Orchestration** — Tasks MUST NOT call other tasks directly. The system is event-driven. Tasks are orchestrated by string-based **actions**. A task (like `RulesEvaluationTask`) emits actions, and other tasks (like `OutputGenerationTask` or `ApiCallsTask`) listen for them.
4. **Dependency Injection via Quarkus CDI** — All components (`ILifecycleTask`s, `IResourceStore`s) use `@ApplicationScoped` and `@Inject`. No manual module registration — Quarkus auto-discovers beans.
5. **Thread Safety** — The `ConversationCoordinator` handles concurrency _between_ conversations. Code must be thread-safe and non-blocking. REST endpoints use JAX-RS `AsyncResponse`. Tasks execute synchronously but must not block for extended periods.

### 4.2 Core Architecture

#### The Conversation Lifecycle

The `LifecycleManager` is the heart of EDDI. It processes a conversation turn by running a pipeline of `ILifecycleTask` implementations.

A **new feature** (e.g., "LLM Agents") is implemented as a **new `ILifecycleTask`**:

1. Create the task class implementing `ILifecycleTask`
2. Implement `execute(IConversationMemory memory)`
3. Read from `memory` (e.g., `memory.getCurrentData("input")`)
4. Perform task logic (e.g., call an LLM)
5. Write results back to memory (e.g., `memory.getCurrentStep().addConversationOutput(...)`)

#### The Conversation Memory (`IConversationMemory`)

The **single source of truth** for a conversation:

- **`IConversationMemoryStore`** — loads/saves memory from MongoDB
- **`IConversationMemory`** — the "live" object for a conversation
- **`ConversationStep`** — an entry in the stack, holding `IData` objects for that turn
- **`IData<T>`** — generic wrapper for data in a step. Use `Data<T>` to create new objects
- **Reading**: `currentStep.getLatestData("key")` → check for null → `.getResult()`
- **Writing**: `currentStep.storeData(new Data<>("key", value))`. Set `data.setPublic(true)` for output-visible data
- **`ConversationProperties`** — long-term state (e.g., `agentName`, `userId`). Slot-filling uses `PropertySetterTask`

> **Critical distinction**: Conversation memory has **two audiences**. (1) **Pipeline tasks** (BehaviorRules, PropertySetter, etc.) see the **full memory** — all steps, all data keys. (2) **The LLM** sees only a **windowed view** assembled by `ConversationHistoryBuilder` — last N conversationOutputs converted to ChatMessages. When you think about "conversation too long," these are two different problems: the LLM context window (what the model sees) and the MongoDB document size (storage/load time). Most context management strategies only affect #1.

#### The Configuration-as-Code Model

Agent definitions are versioned MongoDB documents. A "Agent" is a list of "Workflows". A "Workflow" bundles "Workflow Extensions" (JSON configs).

#### Core Workflow Extensions

- **`behavior.json`** → `RulesEvaluationTask` — the **primary orchestrator**. Its `actions` list is the event that triggers other tasks.
- **`httpcalls.json`** → `ApiCallsTask` — **Tool Definitions** with templated API calls.
- **`property.json`** → `PropertySetterTask` — **EDDI's importance extraction mechanism.** Config-driven slot-filling that explicitly selects which data to preserve as `longTerm` properties. These properties survive the LLM context window boundary — they're loaded at conversation init and available in all templates regardless of how many turns have passed. When designing context management or memory features, PropertySetter is **not just slot-filling** — it's how EDDI ensures critical facts outlive the conversation window.
- **`langchain.json`** → `LlmTask` — **Agent Definition** (prompt, model, tools, or legacy chat). (Config file retains the `langchain` name for backward compatibility.)

#### The Template Data Model

When tasks process templates (system prompts, HTTP call bodies, property instructions), `MemoryItemConverter.convert(memory)` produces a map with these top-level keys:

| Key | Type | Source | Example Access |
|---|---|---|---|
| `context` | `Map<String, Object>` | Input context variables set per turn | `{{context.language}}` |
| `properties` | `Map<String, Property>` | **All conversation properties** — includes both session-scoped and `longTerm` properties loaded from persistent storage | `{{properties.preferred_language.valueString}}` |
| `memory` | `Map` with `current`, `last`, `past` | Conversation step data from the pipeline | `{{memory.current.output}}`, `{{memory.last.input}}` |
| `userInfo` | `Map` with `userId` | Authenticated user identity | `{{userInfo.userId}}` |
| `conversationInfo` | `Map` with `conversationId`, `agentId`, etc. | Conversation metadata | `{{conversationInfo.agentId}}` |
| `conversationLog` | `String` | Formatted conversation history | `{{conversationLog}}` |

> **Key insight**: `longTerm` properties are loaded into `conversationProperties` at conversation init and are immediately available via `{properties.key}` in any template. You do NOT need a separate template namespace for persistent data — properties IS the namespace.

#### The Property Lifecycle

Properties have a well-defined lifecycle managed by `Conversation.java`:

```
1. Conversation.init()
   └─→ loadUserProperties()
       └─→ IUserMemoryStore.getVisibleEntries(userId, agentId, groupIds, recallOrder, maxEntries)
       └─→ Visibility scoping: self + group + global entries are loaded
       └─→ Converted to Property objects with scope=longTerm
       └─→ Available as {{properties.key}} in all templates

2. Pipeline runs
   └─→ PropertySetterTask sets properties based on actions
       └─→ scope=step (cleared per turn)
       └─→ scope=conversation (lives for session)
       └─→ scope=longTerm (persisted across conversations)
       └─→ scope=secret (auto-vaulted via SecretsVault)

3. Conversation turn ends
   └─→ storePropertiesPermanently()
       └─→ All longTerm properties saved via IUserMemoryStore.upsert()
       └─→ Visibility applied at persistence boundary (explicit or config default)
```

> **Key insight**: Persistent state is a **session concern** handled in `Conversation.java` init/teardown — NOT a pipeline task. If your feature needs to load/save cross-conversation state, extend the Conversation init/teardown logic. Do NOT create a new `ILifecycleTask` for session-level concerns.

#### Built-in Tool Execution Context

LLM tools (annotated with `@Tool` from langchain4j) always execute **inside a conversation pipeline**. The execution path is:

```
LlmTask.execute(memory)
  └─→ AgentOrchestrator.buildToolList(memory, config)
      └─→ Constructs tool instances with conversation context
  └─→ LLM invokes tool
  └─→ ToolExecutionService.executeToolWrapped()
      └─→ Rate Limiter → Cache Check → Execute → Cost Tracker → Result
```

`IConversationMemory` is always available when tools execute. Tools that need conversation state (e.g., `userId`, `agentId`) can receive it via constructor injection from `AgentOrchestrator`, which has the memory object at tool-list build time. There is no need for `ThreadLocal`, request-scoped beans, or tool parameters for implicit context.

> **Key insight**: LLM tools operate inside a conversation — they should NEVER take `userId` as a parameter. The conversation always knows who the user is. Only external interfaces (MCP, REST) that operate outside a conversation need explicit user identification.

#### Not Everything Is a Lifecycle Task

A common mistake when adding new features is to reflexively create a new `ILifecycleTask`. Before doing so, ask:

| Question | If yes → | If no → |
|---|---|---|
| Does it process data **during** a pipeline turn? | `ILifecycleTask` | Not a task |
| Does it need to react to **actions** from BehaviorRules? | `ILifecycleTask` | Not a task |
| Does it load/save state at **session boundaries**? | Extend `Conversation.java` init/teardown | — |
| Is it background/scheduled work? | Use `ScheduleFireExecutor` | — |
| Is it a new LLM capability? | Add to `builtInTools` in existing `LlmConfiguration` | — |
| Is it a new REST/MCP endpoint? | Add REST resource or MCP tool class | — |
| Does it add a new agent-level setting? | Add field to `AgentConfiguration` | — |

#### Reusable Infrastructure — Use Before Building

Several infrastructure components are already built and should be reused, not duplicated:

| Infrastructure | What it does | Use it for |
|---|---|---|
| **`ScheduleFireExecutor`** + **`SchedulePollerService`** | Cluster-aware scheduled task execution with fire logging, retries, and configurable conversation strategies (persistent vs new) | ANY background/scheduled work: Dream consolidation, async summarization, maintenance jobs. Never build custom schedulers. |
| **`ToolExecutionService.executeToolWrapped()`** | Rate limiting → cache check → execute → cost tracking pipeline for LLM tool calls | Any operation that needs rate limiting, caching, or cost tracking. |
| **`CostTracker`** (via ToolExecutionService) | Dollar-based LLM cost tracking per conversation | Cost ceilings for background LLM jobs (use `maxCostPerRun` instead of `maxLlmCallsPerRun` — dollar amounts are more meaningful than call counts because different operations cost vastly different amounts). |
| **`SecretResolver`** | Vault-based secret resolution for API keys and credentials | Any feature that needs secrets (LLM providers, external APIs). |
| **Micrometer `MeterRegistry`** | Metrics collection (counters, timers, gauges) exposed at `/q/metrics` | Always add metrics to new features for observability. |

#### Group Conversations — Context Flow

`GroupConversationService` orchestrates multi-agent discussions. When an agent participates in a group, the group context flows into its conversation:

- `AgentGroupConfiguration` defines members (agents or nested groups), discussion style, and phases
- `GroupConversationService.discuss()` creates individual conversations for each member agent
- Group context (groupId, discussion phase, peer responses) is injected via the conversation's `Context` map
- `GroupConversationEventSink` streams SSE events for real-time group discussion visibility

When a feature needs to know which group an agent belongs to (e.g., persistent memory with `group` visibility), the groupId comes from the `GroupConversation` context — not from `AgentConfiguration`. The group is a runtime concern, not a static configuration.

Adding a new `ILifecycleTask` is the **heaviest** option — it requires a configuration POJO, store interface, MongoDB store, REST interface, REST implementation, ExtensionDescriptor, and unit tests. Many features fit better as extensions to existing infrastructure.

### 4.3 New Feature Checklist

A new `ILifecycleTask` requires ALL of:

- [ ] Configuration POJO (`*Configuration.java`, use Java records)
- [ ] Store interface (`IResourceStore<T>`)
- [ ] MongoDB store (`@ApplicationScoped`, `@ConfigurationUpdate` on update/delete)
- [ ] REST interface (JAX-RS, extends `IRestVersionInfo`)
- [ ] REST implementation (`@ApplicationScoped`)
- [ ] `ExtensionDescriptor` (UI field definitions via `getExtensionDescriptor()`)
- [ ] Unit test with Mockito

All task implementations MUST implement: `getId()`, `getType()`, `execute()`, `configure()`, `getExtensionDescriptor()`.

### 4.4 Code Patterns

#### Action Matching

```java
IData<List<String>> latestData = currentStep.getLatestData("actions");
if (latestData == null) return;

List<String> actions = latestData.getResult();
for (MyTask task : configuration.tasks()) {
    if (task.getActions().contains("*") ||
        task.getActions().stream().anyMatch(actions::contains)) {
        executeTask(memory, task, currentStep, templateDataObjects);
    }
}
```

#### Configuration Loading

```java
@Override
public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
        throws WorkflowConfigurationException {
    Object uriObj = configuration.get("uri");
    if (isNullOrEmpty(uriObj)) {
        throw new WorkflowConfigurationException("No resource URI has been defined!");
    }
    URI uri = URI.create(uriObj.toString());
    try {
        return resourceClientLibrary.getResource(uri, MyFeatureConfiguration.class);
    } catch (ServiceException e) {
        throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
    }
}
```

#### Template Data Conversion

```java
@Inject IMemoryItemConverter memoryItemConverter;

public void execute(IConversationMemory memory, Object component) {
    Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
    // Use templateDataObjects with templating engine
}
```

#### PrePostUtils

```java
@Inject PrePostUtils prePostUtils;

// Before main logic
prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.getPreRequest());
// After main logic
prePostUtils.executePostResponse(memory, templateDataObjects, response, task.getPostResponse());
```

#### Metrics (Micrometer)

```java
@Inject MeterRegistry meterRegistry;
private Counter executionCounter;
private Timer executionTimer;

@PostConstruct
void initMetrics() {
    executionCounter = meterRegistry.counter("myfeature.execution.count");
    executionTimer = meterRegistry.timer("myfeature.execution.time");
}
```

#### Built-in Tool System

Tools are injected via constructor, annotated with `@Tool` from langchain4j, and executed through `ToolExecutionService.executeToolWrapped()` (applies rate limiting, caching, cost tracking).

```java
@ApplicationScoped
public class MyTool {
    @Tool("Performs a specific operation")
    public String doSomething(String input) {
        return result;
    }
}
```

Pipeline: `Tool Call → Rate Limiter → Cache Check → Execute → Cost Tracker → Result`

#### Tool Security

- **Always validate URLs** with `UrlValidationUtils.validateUrl(url)` before fetching
- **Only allow `http`/`https`** — never `file://`, `ftp://`, etc.
- **Block private/internal addresses** (loopback, site-local, link-local, cloud metadata)
- **Never use `ScriptEngine`** — use `SafeMathParser` (recursive-descent)

```java
import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

@Tool("Fetches data from a URL")
public String fetchData(@P("URL (http or https)") String url) {
    validateUrl(url); // throws IllegalArgumentException if blocked
    // ... proceed with fetch
}
```

### 4.5 Complete Task Example

```java
@ApplicationScoped
public class MyFeatureTask implements ILifecycleTask {
    public static final String ID = "ai.labs.myfeature";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_MYFEATURE = "myfeature";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IMemoryItemConverter memoryItemConverter;
    private final IDataFactory dataFactory;
    private static final Logger LOGGER = Logger.getLogger(MyFeatureTask.class);

    @Inject
    public MyFeatureTask(IResourceClientLibrary resourceClientLibrary,
                         IMemoryItemConverter memoryItemConverter,
                         IDataFactory dataFactory) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.memoryItemConverter = memoryItemConverter;
        this.dataFactory = dataFactory;
    }

    @Override public String getId() { return ID; }
    @Override public String getType() { return KEY_MYFEATURE; }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var config = (MyFeatureConfiguration) component;
        IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(KEY_ACTIONS);
        if (latestData == null) return;

        var templateDataObjects = memoryItemConverter.convert(memory);
        var actions = latestData.getResult();

        for (var task : config.tasks()) {
            if (task.getActions().contains("*") ||
                task.getActions().stream().anyMatch(actions::contains)) {
                executeTask(memory, task, currentStep, templateDataObjects);
            }
        }
    }

    private void executeTask(IConversationMemory memory, MyFeatureTask task,
                            IWritableConversationStep currentStep,
                            Map<String, Object> templateDataObjects) {
        String result = performOperation(task);
        var data = new Data<>(KEY_MYFEATURE + ":result", result);
        data.setPublic(true);
        currentStep.storeData(data);
        currentStep.addConversationOutputString(KEY_MYFEATURE, result);
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws WorkflowConfigurationException {
        Object uriObj = configuration.get("uri");
        if (isNullOrEmpty(uriObj)) throw new WorkflowConfigurationException("No resource URI defined!");
        URI uri = URI.create(uriObj.toString());
        try {
            return resourceClientLibrary.getResource(uri, MyFeatureConfiguration.class);
        } catch (ServiceException e) {
            throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor descriptor = new ExtensionDescriptor(ID);
        descriptor.setDisplayName("My Feature");
        ConfigValue uriConfig = new ConfigValue("Resource URI", FieldType.URI, false, null);
        descriptor.getConfigs().put("uri", uriConfig);
        return descriptor;
    }
}
```

### 4.6 Output Format for New Features

When implementing a new feature, provide:

1. **Implementation plan** (2-3 bullet points)
2. **Complete code** for ALL required files:
   - `*Configuration.java` (POJO / record)
   - `*Task.java` (`ILifecycleTask` implementation)
   - `I*Store.java` (store interface)
   - `*Store.java` (MongoDB implementation)
   - `IRest*Store.java` (JAX-RS interface)
   - `Rest*Store.java` (JAX-RS implementation)
3. **Sample JSON config** showing how an agent developer uses the feature
4. **Unit test** (`@QuarkusTest`, Mockito mocks, verify memory reads/writes)

### 4.7 Best Practices & Common Pitfalls

#### Thread Safety

- Tasks are singletons — never store conversation data in instance variables
- All state must be in `IConversationMemory`
- When checking-then-acting on shared state, wrap in `synchronized` block

#### Null Safety

- Always check `getLatestData()` for null before `.getResult()`
- Handle null/empty lists when reading actions
- Use `isNullOrEmpty()` utility for string checks

#### Error Handling

- Wrap external API exceptions in `LifecycleException`
- Log errors with context (conversation ID, agent ID)
- Don't let exceptions kill the pipeline — handle gracefully

#### Performance

- Cache expensive resources (models, compiled templates)
- Use `@PostConstruct` for one-time initialization
- Track metrics to identify bottlenecks
- Avoid blocking operations in task execution

#### Memory Management

- Use `data.setPublic(true)` only for output-visible data
- Don't store large objects in conversation memory unnecessarily

#### Configuration

- Validate in `configure()` method
- Provide sensible defaults
- Use descriptive error messages in `WorkflowConfigurationException`

#### Logging

- Use JBoss Logger, not System.out
- Include conversation context in logs
- Use appropriate levels: DEBUG (verbose), INFO (important events), ERROR (failures)

#### Production-Scale Thinking

When designing any new feature, always consider these before finalizing the design:

- **Race conditions**: If multiple agents/conversations can write the same data, what happens on concurrent writes? Use appropriate upsert key granularity.
- **Unbounded growth**: Can users/LLMs create unlimited entries? Add configurable caps with clear UX for when limits are reached (actionable error messages, not silent failures).
- **LLM abuse**: If an LLM can invoke a tool, it can invoke it 100 times in one turn with garbage data. Add per-turn write limits, value size limits, and input validation.
- **Cost at scale**: If a feature uses LLM calls in background jobs, what happens with 10,000 users? Use dollar-based cost ceilings (`maxCostPerRun`) instead of call counts — call counts are meaningless because different operations cost vastly different amounts. Add incremental processing (only process what changed since last run) and round-robin fairness.
- **Implicit context**: If code runs inside a conversation, don't pass `userId`/`agentId` as explicit parameters — the conversation always knows who the user is. Only external interfaces (REST, MCP) need explicit identification.
- **Unification over duplication**: Before creating a parallel system (e.g., new store alongside old store), ask: can the new system replace the old one? Prefer unified systems with legacy compat methods over dual storage. When two features need similar infrastructure (e.g., LLM-based summarization for both Dream consolidation and conversation context), build one shared service, not two parallel implementations.
- **Full data is never deleted by optimization**: Context management strategies (summarization, windowing) are about what the LLM *sees*, not what is *stored*. The full conversation is always preserved in MongoDB. Summaries are derived views, not destructive transformations. If an agent needs to access the full original, it should be able to (via tools, REST API, or debugger).

### Key Files

| File                                        | Purpose                                                   |
| ------------------------------------------- | --------------------------------------------------------- |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, MongoDB)           |
| `src/main/resources/initial-agents/`        | Agent Father and sample agent configs                     |
| `.github/workflows/ci.yml`                  | CI/CD pipeline (build, test, Docker push, smoke test)     |
| `docs/`                                     | 40 markdown files, published at docs.labs.ai              |
| `docs/v6-planning/`                         | Architecture analysis, changelog, business logic analysis |
| `docker-compose.yml`                        | EDDI + MongoDB local setup                                |

---

## 5. Session Protocol

**If picking up from a previous session:**

1. Run `git log -5 --oneline` and `git branch --show-current` to see recent commits and the active branch
2. Run `git status` to check for uncommitted changes
3. Check which phase/item from Section 3 is currently in progress
4. Read [`docs/changelog.md`](docs/changelog.md) for latest changes and decisions

**If ending a session (or at a natural break point):**

1. Commit all working code (even partial) with `wip:` prefix if incomplete
2. Update [`docs/changelog.md`](docs/changelog.md) with:
   - What was completed (with commit hashes)
   - What's next (the specific Phase/Item from Section 3)
   - Any open questions or decisions needed
3. **Suggest a new conversation** if:
   - A phase is complete
   - A major item (3+ SP) is done and tests pass
   - Context is getting long (many files explored)
