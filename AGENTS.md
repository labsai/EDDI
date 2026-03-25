# EDDI Backend — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI** (Enhanced Dialog Driven Interface) is a multi-agent orchestration middleware for conversational AI. This repo is the **Java/Quarkus backend**.

EDDI is a **config-driven engine**, not a monolithic application. Agent behavior lives in JSON configurations; Java code builds the _components_ and _infrastructure_ (the "engine") that reads and executes those configurations.

### Ecosystem

| Repo                       | Tech                      | Purpose                                                  |
| -------------------------- | ------------------------- | -------------------------------------------------------- |
| **EDDI** (this repo)       | Java 25, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline             |
| **EDDI-Manager**           | React 19, Vite, Tailwind  | Admin dashboard (served from EDDI at `/chat/production`) |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                                   |
| **eddi-website**           | Astro, Starlight          | Marketing site + documentation at eddi.labs.ai           |

### Key Architecture

- **Config-driven engine**: Agent logic is JSON configs, Java is the processing engine
- **Lifecycle pipeline**: Input → Parse → Behavior Rules → Actions → Tasks → Output
- **Stateless tasks, stateful memory**: `ILifecycleTask` implementations are singletons; all state lives in `IConversationMemory`
- **Action-based orchestration**: Tasks emit/listen for string-based actions, never call each other directly
- **CI**: CircleCI (compile → test → Docker build → integration tests → push to Docker Hub), migrating to GitHub Actions

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

3. **Branch: `feature/version-6.0.0`**: All v6.0 work branches from and merges back to `feature/version-6.0.0`. **Do NOT commit directly to `main`.**
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

| Phase | Area | Highlights |
|---|---|---|
| 0 | Security Quick Wins | CORS lockdown, PathNavigator (replaced OGNL) |
| 1 | Backend Foundation | ConversationService extraction, SSE streaming, typed memory, LangchainTask decomposition |
| 2 | Testing Infrastructure | Integration tests migrated to main repo, Testcontainers, API contract tests |
| 3 | Manager UI | Greenfield React 19 + Vite + Tailwind rewrite |
| 4 | Chat-UI | CRA→Vite, SSE streaming, Keycloak auth |
| 5 | NATS JetStream | Event bus abstraction, async processing, coordinator dashboard |
| 6 | DB-Agnostic Architecture | PostgreSQL adapter, MongoDB sync driver, Caffeine cache, Lombok removal, langchain4j core migration |
| 7 | Security & Compliance | Secrets Vault, Audit Ledger (EU AI Act), tenant quota stub |
| 8 | MCP Integration | MCP Server (33 tools), MCP Client, agent discovery, managed conversations |

### In Progress / Upcoming

| Phase | Area | Description |
|---|---|---|
| 8c | RAG Foundation | Config-driven vector store retrieval via langchain4j |
| 9 | DAG Pipeline | Parallel tasks, circuit breakers, OpenTelemetry tracing |
| 9b | HITL Framework | Human-in-the-loop pause/resume/approve |
| 10 | Group Conversations | Multi-agent orchestration, debate rounds, NATS-backed |
| 11a | Persistent Memory | Cross-conversation user memory, scheduled triggers |
| 11b | Multi-Channel | WhatsApp, Telegram, Slack adapters |
| 12 | CI/CD | GitHub Actions migration (from CircleCI) |
| 13 | Debugging & Visualization | Time-traveling debugger, visual pipeline builder |
| 14 | Website | Astro + Starlight documentation site |



---

## 4. Backend Java Guidelines

### 4.1 Golden Rules (Non-Negotiable)

1. **Logic is Configuration, Java is the Engine** — Agent behavior (e.g., "if user says 'hello', call API 'X'") MUST NOT be hard-coded in Java. Agent logic belongs in **JSON configurations** (`behavior.json`, `httpcalls.json`, `langchain.json`). Java code creates the `ILifecycleTask` components that _read and execute_ this configuration.
2. **Stateless Tasks, Stateful Memory** — `ILifecycleTask` implementations MUST be stateless. They are singletons shared by all conversations. All conversational state MUST be read from and written to the `IConversationMemory` object passed into the `execute` method.
3. **Action-Based Orchestration** — Tasks MUST NOT call other tasks directly. The system is event-driven. Tasks are orchestrated by string-based **actions**. A task (like `BehaviorRulesEvaluationTask`) emits actions, and other tasks (like `OutputGenerationTask` or `HttpCallsTask`) listen for them.
4. **Dependency Injection via Quarkus CDI** — All components (`ILifecycleTask`s, `IResourceStore`s) use `@ApplicationScoped` and `@Inject`. No manual module registration — Quarkus auto-discovers beans.
5. **Thread Safety** — The `ConversationCoordinator` handles concurrency _between_ conversations. Code must be thread-safe and non-blocking. REST endpoints use JAX-RS `AsyncResponse`. Tasks execute synchronously but must not block for extended periods.

### 4.2 Core Architecture

#### The Conversation Lifecycle

The `LifecycleManager` is the heart of EDDI. It processes a conversation turn by running a pipeline of `ILifecycleTask` implementations.

A **new feature** (e.g., "Langchain Agents") is implemented as a **new `ILifecycleTask`**:

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

#### The Configuration-as-Code Model

Agent definitions are versioned MongoDB documents. A "Agent" is a list of "Workflows". A "Workflow" bundles "Workflow Extensions" (JSON configs).

#### Core Workflow Extensions

- **`behavior.json`** → `BehaviorRulesEvaluationTask` — the **primary orchestrator**. Its `actions` list is the event that triggers other tasks.
- **`httpcalls.json`** → `HttpCallsTask` — **Tool Definitions** with templated API calls.
- **`property.json`** → `PropertySetterTask` — **Memory I/O** for slot-filling.
- **`langchain.json`** → `LangchainTask` — **Agent Definition** (prompt, model, tools, or legacy chat).

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
import static ai.labs.eddi.modules.langchain.tools.UrlValidationUtils.validateUrl;

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
- Track metrics to identify agenttlenecks
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

### Key Files

| File                                        | Purpose                                                   |
| ------------------------------------------- | --------------------------------------------------------- |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, MongoDB)           |
| `src/main/resources/initial-agents/`        | Agent Father and sample agent configs                     |
| `.circleci/config.yml`                      | Current CI (migrating to GitHub Actions)                  |
| `docs/`                                     | 40 markdown files, published at docs.labs.ai              |
| `docs/v6-planning/`                         | Architecture analysis, changelog, business logic analysis |
| `docker-compose.yml`                        | EDDI + MongoDB local setup                                |

---

## 5. Session Protocol

**If picking up from a previous session:**

1. Run `git log -5 --oneline` on `feature/version-6.0.0` to see recent commits
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
