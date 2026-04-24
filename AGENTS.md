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
- **CI/CD**: GitHub Actions (compile → test → Docker build → smoke test → push to Docker Hub). `[skip docker]` in commit message skips image builds. Tag-based releases (`v6.0.0-RC2` → `labsai/eddi:6.0.0-RC2`)

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work

1. **Read the key docs**:
   - [`docs/project-philosophy.md`](docs/project-philosophy.md) — **Supreme directive.** 9 architectural pillars governing all EDDI development
   - [`docs/changelog.md`](docs/changelog.md) — **READ FIRST.** Running log of all changes, decisions, and reasoning across ALL repos and sessions
   - [`docs/architecture.md`](docs/architecture.md) — Architecture overview, configuration model, pipeline, and DB-agnostic design
   - If working on **EDDI-Manager**: also read `EDDI-Manager/AGENTS.md` in the Manager repo
2. **Check git status**: Run `git status` and `git log -5 --oneline` to see current branch state and recent work.

### During Work

3. **Branching**: Check `git branch --show-current` and `git log -5 --oneline` to understand the current branch context. **Do NOT commit directly to `main`.** If unsure which branch to use, ask the user.
4. **Never force-push**: `git push --force` and `git push --force-with-lease` are **forbidden**. To avoid ever needing them, follow these sub-rules:
   - **Never `git commit --amend` after pushing.** Amend only works on unpushed commits. If you already pushed, make a new commit instead.
   - **Never `git rebase -i` on a pushed branch.** Interactive rebase rewrites history. If the branch is pushed, history is immutable.
   - **Never `git reset` on a pushed branch.** Use `git revert` to undo pushed commits (it creates a new forward commit).
   - **Always `git pull --rebase` before pushing** if the remote has new commits.
   - A `.githooks/pre-push` hook will block non-fast-forward pushes as a safety net.
5. **Commit often**: Every working unit gets a commit. Use conventional commits:
   ```
   feat(scope): description
   fix(scope): description
   chore(scope): description
   refactor(scope): description
   ```
6. **Each commit must build**: Run `./mvnw compile` (or `./mvnw test` for backend) before committing. Never commit broken code.
7. **Verify factual claims against authoritative sources**: When writing documentation about the project's technology stack, dependencies, or CI configuration, **always verify against the canonical source** (`pom.xml` for dependencies, `ci.yml` for CI behavior, `Dockerfile` for container config). **Never infer from codebase grep results** — migration code, comments about "previous implementations," and backward-compatibility references describe what the project *used to* use, not what it currently uses. If a term appears 40 times in the codebase but zero times in `pom.xml`, the project does not use it.

#### Git Recovery — What To Do Instead of Force-Push

| Situation | ❌ Wrong (rewrites history) | ✅ Correct (moves forward) |
|-----------|---------------------------|---------------------------|
| Committed to wrong branch, **not yet pushed** | — | `git branch fix/my-work` → `git checkout main` → `git reset --hard origin/main` (safe: nothing was pushed) |
| Committed to wrong branch, **already pushed** | `git reset && git push --force` | `git revert <sha>` on wrong branch, then cherry-pick onto correct branch |
| Need to undo a pushed commit | `git reset --hard HEAD~1 && git push --force` | `git revert <sha> && git push` (new commit that undoes the change) |
| Local branch diverged from remote | `git push --force` | `git pull --rebase` then `git push` |
| Want to clean up commits before merge | `git rebase -i && git push --force` | Use GitHub's "Squash and merge" button on the PR instead |

### After Completing Work (or if interrupted/switching sessions)

7. **Update the changelog**: Edit [`docs/changelog.md`](docs/changelog.md) and add an entry with:
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
| 8c    | RAG Foundation           | Config-driven vector store retrieval, pgvector, httpCall RAG                                        |
| 10    | Group Conversations      | Multi-agent debate orchestration, 5 styles, group-of-groups                                         |
| —     | A2A Protocol             | Agent-to-Agent peer communication, Agent Cards, skill discovery                                     |
| —     | Multi-Model Cascading    | Sequential model escalation with confidence routing                                                 |
| —     | LLM Provider Expansion   | 7 → 12 providers (Mistral, Azure OpenAI, Bedrock, Oracle GenAI)                                     |
| —     | Quarkus 3.34.1           | LTS upgrade, Java 25 module fix                                                                     |
| 12    | CI/CD                    | GitHub Actions unified pipeline, Docker Hub push, CircleCI removed                                  |
| 11a   | Persistent Memory        | IUserMemoryStore, UserMemoryTool, DreamService, McpMemoryTools, Property.Visibility                 |
| —     | Conversation Windows     | Token-aware windowing, rolling summary, ConversationRecallTool                                      |
| —     | Agentic Improvements 1–5 | Counterweights, MCP governance, capability registry, multimodal attachments, agent signing          |
| —     | Compliance Hardening     | HIPAA, EU AI Act, international privacy docs + ComplianceStartupChecks                              |
| —     | Prompt Snippets          | Config-driven system prompt building blocks, Caffeine-cached, REST CRUD                             |
| —     | Agent Sync               | Granular export/import, structural matching, live instance-to-instance sync                         |
| —     | GDPR/CCPA Framework      | Cascading erasure, data portability, Art. 18 restriction, per-category retention                    |
| —     | Commit Flags             | Strict write discipline for memory — uncommit failed task data, error digest injection              |
| —     | Template Preview         | REST endpoint for previewing resolved system prompts with sample/live data                          |
| —     | RC2 Hardening            | 3,500+ unit tests, 550+ integration tests, branding overhaul, rules deserialization fix             |
| —     | Security Hardening v6.0.2 | SSRF prevention, SafeHttpClient, auth guard, vault salt, security headers, CodeQL + Trivy CI       |

### In Progress / Upcoming

| Phase | Area                      | Description                                                                                                                               |
| ----- | ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| —     | Memory Architecture       | Commit flags, RAG threshold, context selection, auto-compaction, property consolidation (see `docs/planning/memory-architecture-plan.md`) |
| —     | Session Forking           | State snapshotting, conversation forking (see `planning/agentic-improvements-plan.md` §7)                                                 |
| —     | Conversation Chaining     | Cross-session context carry-over (see `docs/planning/conversation-window-management.md` Strategy 3)                                       |
| 9     | DAG Pipeline              | Parallel tasks, circuit breakers, OpenTelemetry tracing                                                                                   |
| 9b    | HITL Framework            | Human-in-the-loop pause/resume/approve                                                                                                    |
| —     | Guardrails                | Config-driven input/output guardrails in LlmTask (see `docs/planning/guardrails-architecture.md`)                                         |
| 11b   | Multi-Channel             | Slack, Teams adapters (see `docs/planning/multi-agent-ux-improvements.md`)                                                                |
| 13    | Debugging & Visualization | Time-traveling debugger, visual pipeline builder                                                                                          |
| 14    | Website                   | Astro + Starlight documentation site                                                                                                      |
| —     | Native Image              | GraalVM native compilation (see `docs/planning/native-image-migration.md`)                                                                |

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

| Key                | Type                                         | Source                                                                     | Example Access                                   |
| ------------------ | -------------------------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------ |
| `context`          | `Map<String, Object>`                        | Input context variables set per turn                                       | `{{context.language}}`                           |
| `properties`       | `Map<String, Object>`                        | Conversation properties — raw values from `ConversationProperties.toMap()` | `{properties.preferred_language}`                |
| `memory`           | `Map` with `current`, `last`, `past`         | Conversation step data from the pipeline                                   | `{memory.current.output}`, `{memory.last.input}` |
| `userInfo`         | `Map` with `userId`                          | Authenticated user identity                                                | `{{userInfo.userId}}`                            |
| `conversationInfo` | `Map` with `conversationId`, `agentId`, etc. | Conversation metadata                                                      | `{{conversationInfo.agentId}}`                   |
| `conversationLog`  | `String`                                     | Formatted conversation history                                             | `{{conversationLog}}`                            |

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

| Question                                                 | If yes →                                             | If no →    |
| -------------------------------------------------------- | ---------------------------------------------------- | ---------- |
| Does it process data **during** a pipeline turn?         | `ILifecycleTask`                                     | Not a task |
| Does it need to react to **actions** from BehaviorRules? | `ILifecycleTask`                                     | Not a task |
| Does it load/save state at **session boundaries**?       | Extend `Conversation.java` init/teardown             | —          |
| Is it background/scheduled work?                         | Use `ScheduleFireExecutor`                           | —          |
| Is it a new LLM capability?                              | Add to `builtInTools` in existing `LlmConfiguration` | —          |
| Is it a new REST/MCP endpoint?                           | Add REST resource or MCP tool class                  | —          |
| Does it add a new agent-level setting?                   | Add field to `AgentConfiguration`                    | —          |

#### Reusable Infrastructure — Use Before Building

Several infrastructure components are already built and should be reused, not duplicated:

| Infrastructure                                           | What it does                                                                                                                    | Use it for                                                                                                                                                                                                   |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`ScheduleFireExecutor`** + **`SchedulePollerService`** | Cluster-aware scheduled task execution with fire logging, retries, and configurable conversation strategies (persistent vs new) | ANY background/scheduled work: Dream consolidation, async summarization, maintenance jobs. Never build custom schedulers.                                                                                    |
| **`ToolExecutionService.executeToolWrapped()`**          | Rate limiting → cache check → execute → cost tracking pipeline for LLM tool calls                                               | Any operation that needs rate limiting, caching, or cost tracking.                                                                                                                                           |
| **`CostTracker`** (via ToolExecutionService)             | Dollar-based LLM cost tracking per conversation                                                                                 | Cost ceilings for background LLM jobs (use `maxCostPerRun` instead of `maxLlmCallsPerRun` — dollar amounts are more meaningful than call counts because different operations cost vastly different amounts). |
| **`SecretResolver`**                                     | Vault-based secret resolution for API keys and credentials                                                                      | Any feature that needs secrets (LLM providers, external APIs).                                                                                                                                               |
| **Micrometer `MeterRegistry`**                           | Metrics collection (counters, timers, gauges) exposed at `/q/metrics`                                                           | Always add metrics to new features for observability.                                                                                                                                                        |
| **`SafeHttpClient`**                                     | SSRF-safe HTTP wrapper — `Redirect.NEVER` + per-hop validated redirects, configurable timeout                                   | ALL outbound HTTP from LLM tools and integrations. Never create `HttpClient.newBuilder()` in tool code.                                                                                                      |
| **`UrlValidationUtils`**                                 | Blocks private IPs, loopback, link-local, cloud metadata, non-HTTP schemes                                                     | Always call before fetching user-controlled URLs.                                                                                                                                                            |
| **`AuthStartupGuard`**                                   | Fails startup if OIDC disabled in prod without explicit opt-out                                                                 | Automatic — operators only need `QUARKUS_OIDC_TENANT_ENABLED=true`.                                                                                                                                          |
| **`VaultSaltManager`**                                   | Per-deployment PBKDF2 salt for KEK derivation                                                                                   | Managed by `VaultSecretProvider` — no direct usage needed.                                                                                                                                                   |

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

- **Use `SafeHttpClient`** (`@Inject SafeHttpClient`) for ALL outbound HTTP — never create `HttpClient.newBuilder()` directly
- **Always validate URLs** with `UrlValidationUtils.validateUrl(url)` before fetching user-controlled input
- **Only allow `http`/`https`** — never `file://`, `ftp://`, etc.
- **Block private/internal addresses** — handled automatically by `SafeHttpClient.sendValidated()`
- **Never use `ScriptEngine`** — use `SafeMathParser` (recursive-descent)

```java
@Inject SafeHttpClient httpClient;

@Tool("Fetches data from a URL")
public String fetchData(@P("URL (http or https)") String url) {
    try {
        var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var response = httpClient.sendValidated(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    } catch (Exception e) {
        return "Error fetching URL: " + e.getMessage();
    }
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
- **Full data is never deleted by optimization**: Context management strategies (summarization, windowing) are about what the LLM _sees_, not what is _stored_. The full conversation is always preserved in MongoDB. Summaries are derived views, not destructive transformations. If an agent needs to access the full original, it should be able to (via tools, REST API, or debugger).

### Key Files

| File                                        | Purpose                                                     |
| ------------------------------------------- | ----------------------------------------------------------- |
| `src/main/docker/Dockerfile`                | Production JVM container image (digest-pinned base)         |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, MongoDB)             |
| `src/main/resources/initial-agents/`        | Agent Father and sample agent configs                       |
| `.github/workflows/ci.yml`                  | CI/CD pipeline (build, test, Docker push, smoke test)       |
| `docs/`                                     | 40 markdown files, published at docs.labs.ai                |
| `docker-compose.yml`                        | EDDI + MongoDB local setup                                  |
| `docs/agent-configs/`                       | Agent config sources (e.g. Agent Father) — reference for AI |
| `src/main/java/.../httpclient/SafeHttpClient.java` | Centralized SSRF-safe HTTP client wrapper              |
| `src/main/java/.../security/AuthStartupGuard.java` | Production auth enforcement guard                      |
| `.env.example`                              | Required environment variables                              |

### Docker & Container Security

#### Base Image Management

The production image (`Dockerfile`) uses a Red Hat UBI 9 base pinned by **SHA256 digest** for OpenSSF supply-chain compliance. This means:

- The `FROM` line must always include `@sha256:...` — never use a bare tag like `:1.24`
- Red Hat periodically republishes the same tag with security patches baked in

#### Trivy CVE Remediation Procedure

When Trivy (CI container scan) flags a base image CVE:

1. **Check for a newer digest first** — pull the latest image for the same tag and compare:
   ```bash
   docker pull registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24
   # Check the digest in the pull output
   docker run --rm <image> rpm -q <vulnerable-package>
   ```
2. **If the newer digest includes the fix**: update the `@sha256:...` in `Dockerfile` — done
3. **If no fixed digest exists yet**: use `microdnf update -y <package> && microdnf clean all` as a **temporary stopgap** in the `USER root` section, with a CVE comment. Remove it once a fixed base image is available
4. **Never remove the digest pin** to "auto-fix" CVEs — this violates OpenSSF supply-chain requirements

> **Key principle**: Digest update is the clean fix. `microdnf update` is the escape hatch.

---

## 5. Agent Config Authoring Reference

> **This section prevents wrong assumptions when writing agent JSON configs.** Always consult this when building behavior rules, property setters, output configs, or HTTP calls.

### 5.1 Template Syntax

EDDI v6 uses **Qute templates** with `{expression}` syntax, NOT Thymeleaf `[[${expression}]]`.

#### ⚠️ Critical: `properties` returns RAW values, NOT Property objects

`MemoryItemConverter.convert()` puts `ConversationProperties.toMap()` into the template context. The `toMap()` method returns **raw Java values** (String, Integer, Map, etc.), NOT `Property` objects.

```
✅ CORRECT:   {properties.agentName}        → returns the String value
❌ WRONG:     {properties.agentName.valueString}  → fails at runtime (String has no .valueString)
```

This is because `ConversationProperties.put()` stores `property.getValueString()` (or `getValueObject()`, etc.) directly into the internal `propertiesMap`. By the time templates see it, the Property wrapper is gone.

#### Template variables available in all contexts

| Variable                            | Returns                            | Example                                      |
| ----------------------------------- | ---------------------------------- | -------------------------------------------- |
| `{properties.key}`                  | Raw value (string, int, map)       | `{properties.agentName}`                     |
| `{memory.current.input}`            | User's input text for current step | Used in property setter to capture free-text |
| `{memory.current.output}`           | Output text for current step       |                                              |
| `{memory.last.input}`               | Previous step's input              |                                              |
| `{context.key}`                     | Context variable set by client     | `{context.language}`                         |
| `{userInfo.userId}`                 | Authenticated user ID              |                                              |
| `{conversationInfo.agentId}`        | Current agent ID                   |                                              |
| `{conversationInfo.conversationId}` | Current conversation ID            |                                              |
| `{conversationLog}`                 | Formatted conversation history     |                                              |

### 5.2 Conversation Lifecycle for Rule-Based Agents

```
1. Conversation.init()
   └─→ Step 0 created
   └─→ CONVERSATION_START action added to step 0
   └─→ Pipeline runs with empty input ("")
   └─→ Output for CONVERSATION_START fires (greeting shown BEFORE user says anything)

2. User sends first message → say(message)
   └─→ Step 1 created (startNextStep)
   └─→ User input stored in memory
   └─→ Behavior rules evaluate:
       • lastStep = Step 0 (has CONVERSATION_START action)
       • currentStep = Step 1 (has user's input/expressions)
   └─→ Output fires for matched actions

3. User sends second message → say(message)
   └─→ Step 2 created
   └─→ lastStep = Step 1, currentStep = Step 2
   └─→ ... and so on
```

> **Key insight**: The greeting output fires automatically at `init()` — the user doesn't need to type anything first.

### 5.3 Behavior Rule Safety Rules

#### Every rule MUST have an `actionmatcher` on `lastStep`

Behavior rules within a group ALL fire if their conditions match. Rules with only `inputmatcher` conditions are dangerous — they match globally regardless of conversation state.

```
❌ DANGEROUS: Rule fires on ANY step if user somehow sends matching expression
{
  "name" : "Start over",
  "actions" : [ "ask_for_agent_name" ],
  "conditions" : [ {
    "type" : "inputmatcher",
    "configs" : { "expressions" : "start_over", "occurrence" : "currentStep" }
  } ]
}

✅ SAFE: Rule only fires when the confirmation step was the previous step
{
  "name" : "Start over",
  "actions" : [ "ask_for_agent_name" ],
  "conditions" : [ {
    "type" : "actionmatcher",
    "configs" : { "actions" : "confirm_creation", "occurrence" : "lastStep" }
  }, {
    "type" : "inputmatcher",
    "configs" : { "expressions" : "start_over", "occurrence" : "currentStep" }
  } ]
}
```

#### Quick reply expressions must be unique identifiers

Do NOT reuse system action names (like `CONVERSATION_START`) as quick reply expressions. Use dedicated identifiers:

```
❌ WRONG:  "expressions" : "CONVERSATION_START"   (system action name)
✅ RIGHT:  "expressions" : "get_started"           (dedicated identifier)
```

#### `actionmatcher` comma-separated values = AND (contiguous sublist), NOT OR

When you specify `"actions" : "action_a,action_b"`, the engine checks if BOTH actions appear as a **contiguous sublist** of the step's action list (`Collections.indexOfSubList`). This means ALL listed actions must be present — it is AND semantics, not OR.

```
❌ WRONG — tries to match any of these, but actually requires ALL FIVE contiguously:
"actions" : "ask_for_model,ask_for_model_ollama,ask_for_model_jlama,ask_for_model_bedrock,ask_for_model_oracle"

✅ RIGHT — Option A: emit a common action alongside provider-specific ones:
Rule actions: [ "set_provider_ollama", "ask_for_model", "ask_for_model_ollama" ]
Matcher:      "actions" : "ask_for_model"

✅ RIGHT — Option B: use a connector with OR operator:
{
  "type" : "connector",
  "configs" : { "operator" : "OR" },
  "conditions" : [ {
    "type" : "actionmatcher",
    "configs" : { "actions" : "ask_for_model", "occurrence" : "lastStep" }
  }, {
    "type" : "actionmatcher",
    "configs" : { "actions" : "ask_for_model_ollama", "occurrence" : "lastStep" }
  } ]
}
```

### 5.4 Property Setter Patterns

#### Scope values

| Scope          | Behavior                                                                                                                                      |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `step`         | Cleared at end of turn                                                                                                                        |
| `conversation` | Lives for the session (default for most agent-building properties)                                                                            |
| `longTerm`     | Persisted to `usermemories` collection across conversations                                                                                   |
| `secret`       | Auto-vaulted: plaintext stored in SecretsVault, raw input scrubbed from memory, vault reference (`${eddivault:...}`) stored as property value |

> **Warning**: `scope: "secret"` requires the vault to be active (`EDDI_VAULT_MASTER_KEY` env var set). If vault is disabled (common in dev mode), `autoVaultSecret()` fails and falls back to storing plaintext — but logs an ERROR that may confuse users. For wizard-style agents that collect API keys and pass them to an endpoint (like the Agent Father), prefer `scope: "conversation"` and delegate vaulting to the receiving service.

#### Capturing user input vs. setting fixed values

```json
// Capture free-text input from user
{ "name" : "agentName", "valueString" : "{memory.current.input}", "scope" : "conversation" }

// Set a fixed value (from quick reply selection)
{ "name" : "provider", "valueString" : "anthropic", "scope" : "conversation" }

// When a quick reply has a default value, use a dedicated action + fixed value
{ "name" : "baseUrl", "valueString" : "http://localhost:11434", "scope" : "conversation" }
```

#### Qute template safety in HTTP call bodies

When embedding `{properties.x}` in HTTP call body templates, be aware:
- `quarkus.qute.strict-rendering=false` renders missing properties as empty strings (no error)
- Do NOT use `.orEmpty` on properties — it's for Qute iterables, not strings, and fails on `NOT_FOUND`
- User-entered text containing `{` or `}` will be interpreted as Qute expressions, potentially eating content

#### Requesting specialized input fields from the UI

The output system supports an `inputField` output type that tells the UI to switch its input control. Both **EDDI-Manager** (`SecretInputField` in `chat-panel.tsx`) and **eddi-chat-ui** (`SecretInput.tsx`) handle this natively.

```json
{
  "valueAlternatives": [{
    "type": "inputField",
    "subType": "password",
    "placeholder": "Paste your API key here",
    "label": "API Key"
  }]
}
```

Supported `subType` values: `"password"`, `"text"`, `"email"`. When the UI receives an `inputField` in the output array, it replaces the standard text input with the appropriate specialized field for that turn. The field reverts to normal text input on the next response.

> **Key pattern**: Add the `inputField` output item alongside regular `text` outputs in the same action's output set. The text explains what the user should enter, and the `inputField` controls how the input is rendered.

### 5.5 ZIP Structure for Agent Import

Agent ZIP files are imported via `RestImportService`. **All IDs in URIs and filenames must be valid hex identifiers** (24-char hex strings like MongoDB ObjectIds, or UUIDs). The import service validates IDs via `RestUtilities.isValidId()` which requires ≥18 hex characters (`0-9a-fA-F` and dashes). Semantic names like `agent-father-wf1` will be rejected.

The file naming convention is `{id}.{type}.json` where `{id}` matches the last path segment of the resource URI:

```
{agentId}.agent.json              → Agent configuration
{agentId}.descriptor.json         → Agent descriptor (name, description, version)
{workflowId}/
  1/
    {workflowId}.workflow.json    → Workflow definition
    {workflowId}.descriptor.json
    {behaviorId}.behavior.json    → Behavior rules (file ext stays "behavior", URI uses "rules")
    {behaviorId}.descriptor.json
    {propertyId}.property.json    → Property setter
    {propertyId}.descriptor.json
    {httpcallsId}.httpcalls.json  → HTTP API calls (file ext stays "httpcalls", URI uses "apicalls")
    {httpcallsId}.descriptor.json
    {outputId}.output.json        → Output messages + quick replies
    {outputId}.descriptor.json
    {llmId}.langchain.json        → LLM configuration (file ext stays "langchain", URI uses "llm")
    {llmId}.descriptor.json
```

> **Important**: File extensions use legacy names (`behavior`, `httpcalls`, `langchain`) while URIs use v6 names (`rules`, `apicalls`, `llm`). The import service maps between them via `AbstractBackupService` constants.

> **Pipeline step**: If your output templates contain `{properties.x}` placeholders, you **must** include `eddi://ai.labs.templating` as the last workflow step. Without it, Qute expressions are not resolved.


#### URI format (v6 canonical)

Always use v6 canonical URIs in new configs:

| Resource   | URI Pattern                                                                  |
| ---------- | ---------------------------------------------------------------------------- |
| Agent      | `eddi://ai.labs.agent/agentstore/agents/{id}?version=1`                      |
| Workflow   | `eddi://ai.labs.workflow/workflowstore/workflows/{id}?version=1`             |
| Rules      | `eddi://ai.labs.rules/rulestore/rulesets/{id}?version=1`                     |
| ApiCalls   | `eddi://ai.labs.apicalls/apicallstore/apicalls/{id}?version=1`               |
| Property   | `eddi://ai.labs.property/propertysetterstore/propertysetters/{id}?version=1` |
| Output     | `eddi://ai.labs.output/outputstore/outputsets/{id}?version=1`                |
| LLM        | `eddi://ai.labs.llm/llmstore/llms/{id}?version=1`                            |
| Dictionary | `eddi://ai.labs.dictionary/dictionarystore/dictionaries/{id}?version=1`      |

> Legacy URIs (e.g. `ai.labs.bot/botstore/bots/`) are auto-normalized by `AbstractBackupService.normalizeLegacyUris()` during import, but new configs should always use v6 format.

#### Workflow step types

| Step `type`                | Config URI prefix             | Required?                   |
| -------------------------- | ----------------------------- | --------------------------- |
| `eddi://ai.labs.parser`    | — (no config URI)             | Yes — always first          |
| `eddi://ai.labs.behavior`  | `eddi://ai.labs.rules/...`    | Yes — the orchestrator      |
| `eddi://ai.labs.property`  | `eddi://ai.labs.property/...` | Optional — slot-filling     |
| `eddi://ai.labs.httpcalls` | `eddi://ai.labs.apicalls/...` | Optional — API calls        |
| `eddi://ai.labs.output`    | `eddi://ai.labs.output/...`   | Usually yes — user messages |
| `eddi://ai.labs.langchain` | `eddi://ai.labs.llm/...`      | Optional — LLM interaction  |

### 5.6 Reference Implementation

The **Agent Father** (`docs/agent-configs/agent-father/`) is a complete, working, rule-based agent config. Use it as the canonical reference for:

- Behavior rule patterns with `actionmatcher` + `inputmatcher`
- Property setter with `scope: "secret"` for auto-vault
- HTTP call template syntax
- Output with quick replies
- Provider-aware branching (local vs. cloud LLM providers)

---

## 6. Session Protocol

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
