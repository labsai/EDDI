# EDDI Backend — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI** (Enhanced Dialog Driven Interface) is a multi-agent orchestration middleware for conversational AI. This repo is the **Java/Quarkus backend**.

EDDI is a **config-driven engine**, not a monolithic application. Bot behavior lives in JSON configurations; Java code builds the _components_ and _infrastructure_ (the "engine") that reads and executes those configurations.

### Ecosystem (5 repos, all under `c:\dev\git\`)

| Repo                       | Tech                      | Purpose                                                    |
| -------------------------- | ------------------------- | ---------------------------------------------------------- |
| **EDDI** (this repo)       | Java 21, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline               |
| **EDDI-Manager**           | React 19, Vite, Tailwind  | Admin dashboard (served from EDDI at `/chat/unrestricted`) |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                                     |
| **eddi-website**           | HTML → migrating to Astro | Marketing site at eddi.labs.ai                             |
| **EDDI-integration-tests** | Java                      | End-to-end API tests                                       |

### Key Architecture

- **Config-driven engine**: Bot logic is JSON configs, Java is the processing engine
- **Lifecycle pipeline**: Input → Parse → Behavior Rules → Actions → Tasks → Output
- **Stateless tasks, stateful memory**: `ILifecycleTask` implementations are singletons; all state lives in `IConversationMemory`
- **Action-based orchestration**: Tasks emit/listen for string-based actions, never call each other directly
- **CI**: CircleCI (compile → test → Docker build → integration tests → push to Docker Hub), migrating to GitHub Actions

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work

1. **Read the planning docs**:
   - [`docs/project-philosophy.md`](docs/project-philosophy.md) — **Supreme directive.** 7 architectural pillars governing all EDDI development
   - [`docs/changelog.md`](docs/changelog.md) — **READ FIRST.** Running log of all changes, decisions, and reasoning across ALL repos and sessions
   - [`docs/v6-planning/implementation_plan.md`](docs/v6-planning/implementation_plan.md) — Full architecture audit (14 appendices, A-N) and phased roadmap
   - [`docs/v6-planning/business-logic-analysis.md`](docs/v6-planning/business-logic-analysis.md) — Configuration model, Bot Father, parser/expression deep dive
   - If working on **EDDI-Manager**: also read `c:\dev\git\EDDI-Manager\HANDOFF.md` and `c:\dev\git\EDDI-Manager\AGENTS.md`
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

## 3. Development Order (Master Plan)

Follow this order unless the user explicitly requests something different.
**Backend first, then testing, then frontend. Website last.**

```
Phase 0: Security Quick Wins (6 SP) ✅
  0a. Restrict CORS origins                          1 SP
  0b. Create PathNavigator (replace OGNL calls)      5 SP

Phase 1: Backend Foundation (20 SP) ✅
  1. Extract ConversationService from RestBotEngine   5 SP
  2. Decompose LangchainTask into focused classes     5 SP
  3. Add SSE streaming API endpoint                   5 SP
  4. Typed memory accessors (MemoryKey<T>)            3 SP
  5. Extract ConfigurationLoader utility              2 SP

Phase 2: Testing Infrastructure (14 SP) ✅
  6. Migrate integration tests to main repo (JUnit5) 3 SP
  7. Add @QuarkusTest + Testcontainers component tests 3 SP
  8. Fill unit test gaps (SizeMatcher, tasks, etc.)   3 SP
  9. API contract tests (JSON Schema)                 2 SP
  10. Langchain/agent integration test (WireMock)     3 SP

Phase 3: Manager (Greenfield Rewrite) (36 SP) ✅
  11-20. Full Manager UI rewrite (3.1-3.21)

Phase 4: Chat-UI Rewrite + Hardening ✅
  21. CRA → Vite migration                            2 SP
  22. SSE streaming support                           3 SP
  23. Manager chat panel SSE + undo/redo              3 SP
  24. Keycloak Auth, E2E tests, integration tests     8 SP
  25. JSON Schema Enrichment                          2 SP  ✅
  26. Production Build + Dashboard Replacement          3 SP  ✅

Phase 5: NATS JetStream Message Queue ✅
  27. Event bus abstraction over current in-process    3 SP  ✅
  28. NATS JetStream adapter                           5 SP  ✅
  29. Async conversation processing                    3 SP  ✅
  30. Coordinator Dashboard + Dead-Letter Admin         5 SP  ✅

Phase 6: PostgreSQL / DB-Agnostic Architecture ✅
  30. Repository interface abstraction                 5 SP  ✅
  31. PostgreSQL adapter (Panache or JDBC)              8 SP  ✅
  32. Migration tooling (MongoDB → PostgreSQL)          5 SP  ✅
  6A. MongoDB sync driver migration                    5 SP
      (replace reactive+blocking with sync driver, 13 files)
  6B. PostgreSQL integration test parity               3 SP
      (run all 48 ITs against both MongoDB and PostgreSQL)

Phase 6C: Infinispan → Caffeine (2 SP)   [QUICK WIN]  ✅
  6C. Replace Infinispan with Caffeine (2 files, POM cleanup)   2 SP

Phase 6E: quarkus-langchain4j → langchain4j Core (2 SP)   [QUICK WIN]
  6E. Remove io.quarkiverse.langchain4j, migrate 3 builders to core   2 SP

Phase 6D: Lombok Removal (5 SP)   [QUICK WIN]
  6D. Delombok 114 files, @Value→records, @Slf4j→Logger    5 SP

Phase 7: Secrets, Audit + Tenant Foundation (12 SP)
  33. Secrets Vault — ${vault:key} references, export sanitization  5 SP
  34. Immutable Audit Ledger — write-once trail, EU AI Act          5 SP
  34b. Tenant Quota Stub — per-tenant rate limits, usage metering   2 SP

Phase 8a: MCP Servers (8 SP)
  35. MCP Server: Bot Conversations (talk_to_bot, list_bots)       5 SP
  36. MCP Server: EDDI Admin API (manage bots/packages/deploy)     3 SP

Phase 8b: MCP Client + RAG Foundation (10 SP)
  37. MCP Server: EDDI Documentation (docs as MCP resources)       2 SP
  38. MCP Client — bots consume external MCP tools                 5 SP
  38b. RAG Lifecycle Task — config-driven vector store retrieval    3 SP
      (langchain4j EmbeddingStore/EmbeddingModel abstractions)
      (Phase 8b includes design doc for Workspace AI Operator)

Phase 9: DAG Pipeline + Governance (10 SP)
  39. 3-Tier State Architecture (CQRS memory partitioning)         5 SP
  40. DAG Pipeline (parallel tasks, circuit breakers, budget)       5 SP
  40b. OpenTelemetry Tracing (distributed traces through pipeline)

Phase 9b: HITL Framework (5 SP)
  41. HITL Framework (pause/resume/approve for MCP + budget)       3 SP
  42. Workspace AI Operator — system bot with admin API access     2 SP

Phase 10a: Multi-Bot Orchestration (8 SP)
  43. Bot-to-bot routing + orchestrator pattern                    5 SP
  44. Cascading model routing (small→better, consensus)            3 SP

Phase 10b: Advanced RAG + Debate (8 SP)
  45. Advanced RAG (ingestion, provenance, tenant RLS, re-ranking) 5 SP
      (builds on basic RAG task from Phase 8b)
  46. Group-of-Experts / Debate Pattern                             3 SP

Phase 11a: Persistent Memory + Heartbeat (8 SP)
  47. Cross-conversation persistent user memory                    5 SP
  48. Heartbeat / Scheduled Triggers (cluster-safe via NATS,       3 SP
      exactly-once, bot self-scheduling via tool)

Phase 11b: Multi-Channel Adapters (5 SP)
  49. Multi-channel adapters (WhatsApp/Telegram/Slack)             5 SP

Phase 12: CI/CD — GitHub Actions Migration (8 SP)
  50. GitHub Actions for EDDI (migrate from CircleCI)              3 SP
  51. GitHub Actions for Manager + Chat-UI + Website               5 SP

Phase 13a: Time-Traveling Debugger (5 SP)
  52. Time-Traveling Debugger (audit ledger replay)                5 SP

Phase 13b: Visual Pipeline Builder + Taint Tracking (8 SP)
  53. Visual Pipeline Builder (Linear/Block Hybrid)                5 SP
  54. Visual Taint Tracking (data provenance indicators)           3 SP

Phase 14a: Website — Astro Setup (5 SP)
  55. Scaffold Astro + Tailwind + i18n                             2 SP
  56. Dark/light + RTL                                             3 SP

Phase 14b: Website — Content + Deployment (9 SP)
  57. Migrate content into components                              3 SP
  58. Documentation pages (Content Collections)                    5 SP
  59. GitHub Actions deployment                                    1 SP

Deferred (post v6.0):
  - Redis distributed cache
  - Helm chart
  - Self-improving skills (bots that learn from interactions)
```

---

## 4. Backend Java Guidelines

### 4.1 Golden Rules (Non-Negotiable)

1. **Logic is Configuration, Java is the Engine** — Bot behavior (e.g., "if user says 'hello', call API 'X'") MUST NOT be hard-coded in Java. Bot logic belongs in **JSON configurations** (`behavior.json`, `httpcalls.json`, `langchain.json`). Java code creates the `ILifecycleTask` components that _read and execute_ this configuration.
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
- **`ConversationProperties`** — long-term state (e.g., `botName`, `userId`). Slot-filling uses `PropertySetterTask`

#### The Configuration-as-Code Model

Bot definitions are versioned MongoDB documents. A "Bot" is a list of "Packages". A "Package" bundles "Package Extensions" (JSON configs).

#### Core Package Extensions

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
        throws PackageConfigurationException {
    Object uriObj = configuration.get("uri");
    if (isNullOrEmpty(uriObj)) {
        throw new PackageConfigurationException("No resource URI has been defined!");
    }
    URI uri = URI.create(uriObj.toString());
    try {
        return resourceClientLibrary.getResource(uri, MyFeatureConfiguration.class);
    } catch (ServiceException e) {
        throw new PackageConfigurationException(e.getLocalizedMessage(), e);
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
            throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        if (isNullOrEmpty(uriObj)) throw new PackageConfigurationException("No resource URI defined!");
        URI uri = URI.create(uriObj.toString());
        try {
            return resourceClientLibrary.getResource(uri, MyFeatureConfiguration.class);
        } catch (ServiceException e) {
            throw new PackageConfigurationException(e.getLocalizedMessage(), e);
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
3. **Sample JSON config** showing how a bot developer uses the feature
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
- Log errors with context (conversation ID, bot ID)
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
- Use descriptive error messages in `PackageConfigurationException`

#### Logging

- Use JBoss Logger, not System.out
- Include conversation context in logs
- Use appropriate levels: DEBUG (verbose), INFO (important events), ERROR (failures)

### Key Files

| File                                        | Purpose                                                   |
| ------------------------------------------- | --------------------------------------------------------- |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, MongoDB)           |
| `src/main/resources/initial-bots/`          | Bot Father and sample bot configs                         |
| `.circleci/config.yml`                      | Current CI (migrating to GitHub Actions)                  |
| `docs/`                                     | 40 markdown files, published at docs.labs.ai              |
| `docs/v6-planning/`                         | Architecture analysis, changelog, business logic analysis |
| `docker-compose.yml`                        | EDDI + MongoDB local setup                                |

---

## 5. Handoff Protocol

**If picking up from a previous session:**

1. Read `HANDOFF.md` — it has the current status, what's done, and what's next
2. Run `git log -5 --oneline` on `feature/version-6.0.0` to see recent commits
3. Run `git status` to check for uncommitted changes
4. Check which phase/item from Section 3 is currently in progress

**If ending a session (or at a natural break point):**

1. Commit all working code (even partial) with `wip:` prefix if incomplete
2. Update `HANDOFF.md` with:
   - What was completed (with commit hashes)
   - What's next (the specific Phase/Item from Section 3)
   - Any open questions or decisions needed
3. **Tell the user it's a good time for a new conversation** if:
   - A phase is complete
   - A major item (3+ SP) is done and tests pass
   - Context is getting long (many files explored)
