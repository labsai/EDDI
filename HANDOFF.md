# EDDI v6.0 — Current Status

> **Last updated:** 2026-03-11
> **Branch:** `feature/version-6.0.0`

## Completed

### Phase 0: Security Quick Wins ✅ (commit `71448a89`)

- [x] CORS restricted to `localhost:3000,localhost:7070`
- [x] `PathNavigator` replaces all 5 explicit `Ognl.getValue()`/`Ognl.setValue()` calls
- [x] 27 new PathNavigator tests, all 499 tests pass

### Phase 1, Item 1: Extract `ConversationService` from `RestBotEngine` ✅ (commit `7dd1488e`)

- [x] Created `IConversationService` interface with domain exceptions (no JAX-RS deps)
- [x] Created `ConversationService` implementation with all business logic (~565 lines)
- [x] Refactored `RestBotEngine` from 668 to ~230 lines (thin REST adapter)
- [x] 16 new unit tests for `ConversationService`
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/IConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestBotEngine.java`
- `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceTest.java`

### Phase 1, Item 2: Typed Memory Accessors ✅

- [x] Created `MemoryKey<T>` — type-safe key class with phantom type parameter and `isPublic` flag
- [x] Created `MemoryKeys` — central registry of well-known keys (`ACTIONS`, `INPUT`, `EXPRESSIONS_PARSED`, etc.)
- [x] Added typed accessor methods (`get`, `getData`, `getLatestData`, `set`) to `IConversationStep`, `IWritableConversationStep`, `IConversationStepStack`
- [x] Migrated 10 production files to use `MemoryKeys.*` constants
- [x] 15 new tests in `MemoryKeyTest`, updated 4 existing test files
- [x] All 533 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/memory/MemoryKey.java`
- `src/main/java/ai/labs/eddi/engine/memory/MemoryKeys.java`
- `src/main/java/ai/labs/eddi/engine/memory/IConversationMemory.java`
- `src/test/java/ai/labs/eddi/engine/memory/MemoryKeyTest.java`

### Phase 1, Item 3: Consolidate Configuration Stores ✅ (commit `201c5f99`)

- [x] Created `AbstractMongoResourceStore<T>` — generic base class with two constructors and 7 shared CRUD methods
- [x] Refactored 8 stores to extend base class: `LangChainStore`, `ParserStore`, `PropertySetterStore`, `HttpCallsStore`, `BehaviorStore`, `OutputStore`, `RegularDictionaryStore`, `BotStore`, `PackageStore`
- [x] ~350 lines of duplicated delegation code eliminated
- [x] 7 new tests in `AbstractMongoResourceStoreTest`
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/datastore/mongo/AbstractMongoResourceStore.java`
- `src/test/java/ai/labs/eddi/datastore/mongo/AbstractMongoResourceStoreTest.java`

### Phase 1, Item 4: Centralize REST Error Handling ✅ (commit `d4ab62bd`)

- [x] Created 4 JAX-RS `ExceptionMapper`s: `ResourceNotFoundExceptionMapper` (404), `ResourceStoreExceptionMapper` (500), `ResourceModifiedExceptionMapper` (409), `ResourceAlreadyExistsExceptionMapper` (409)
- [x] Created `SneakyThrow` utility for rethrowing checked exceptions as unchecked (avoids touching 50+ files to change exception hierarchy)
- [x] Refactored 22 REST classes to use `sneakyThrow` instead of manual exception translation
- [x] Net reduction of 91 lines of boilerplate error handling code
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/exception/SneakyThrow.java`
- `src/main/java/ai/labs/eddi/engine/exception/Resource*ExceptionMapper.java` (4 files)

### Phase 1, Item 5: Streaming API (SSE endpoint, `StreamingChatModel`) ✅

- [x] Two-layer SSE streaming: pipeline step events (`task_start`/`task_complete`) + LLM token events (`token`)
- [x] `ConversationEventSink` callback interface for all SSE event types
- [x] `ILanguageModelBuilder.buildStreaming()` in 4 builders (OpenAI, Anthropic, Gemini, Ollama)
- [x] `ChatModelRegistry.getOrCreateStreaming()` with separate streaming cache
- [x] `LifecycleManager` emits step events around each `task.execute()` call
- [x] `StreamingLegacyChatExecutor` bridges langchain4j async streaming with sync lifecycle (CountDownLatch)
- [x] `LangchainTask` detects streaming via `memory.getEventSink()`, delegates to streaming or sync path
- [x] `POST /bots/{env}/{botId}/{convId}/stream` SSE endpoint (`RestBotEngineStreaming`)
- [x] `ConversationService.sayStreaming()` with event sink adapter
- [x] 22 new tests across 5 test files
- [x] All ~562 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/StreamingLegacyChatExecutor.java`
- `src/main/java/ai/labs/eddi/engine/IRestBotEngineStreaming.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestBotEngineStreaming.java`
- `src/test/java/ai/labs/eddi/modules/langchain/impl/StreamingLegacyChatExecutorTest.java`
- `src/test/java/ai/labs/eddi/modules/langchain/impl/ChatModelRegistryTest.java`
- `src/test/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManagerStreamingTest.java`

### Phase 1, Item 6: Decompose `LangchainTask` ✅

- [x] Created `ChatModelRegistry` — model creation, caching, and lookup
- [x] Created `ConversationHistoryBuilder` — memory → ChatMessage list conversion
- [x] Created `LegacyChatExecutor` — simple chat mode (no tools)
- [x] Created `AgentOrchestrator` — tool-calling agent loop with budget/rate-limiting
- [x] Refactored `LangchainTask` from 592→252 lines (thin orchestrator)
- [x] Trimmed `AgentExecutionHelper` from 223→140 lines (retry-only utility)
- [x] 16 new tests in `AgentOrchestratorTest`
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/modules/langchain/impl/ChatModelRegistry.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/ConversationHistoryBuilder.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/LegacyChatExecutor.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/AgentOrchestrator.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/LangchainTask.java`

### Phase 2: Testing Infrastructure ✅ (commit `0956cefd`)

- [x] Migrated all integration tests from `EDDI-integration-tests` repo into main EDDI repo
- [x] Created `BaseIntegrationIT` with shared helpers (resource creation, deployment, cleanup)
- [x] Created `IntegrationTestProfile` with DevServices MongoDB (Testcontainers)
- [x] `BotEngineIT` (11 tests): welcome, input, context, output, undo/redo, templating, property
- [x] `BotDeploymentComponentIT` (4 tests): deploy, undeploy, status transitions
- [x] `ConversationServiceComponentIT` (7 tests): lifecycle, undo/redo, concurrent conversations
- [x] `ApiContractIT` (10 tests): CRUD contracts for behavior, dictionary, output, package, bot stores
- [x] `BotUseCaseIT` (2 tests): weather bot import + managed bot API
- [x] CRUD ITs: `BehaviorCrudIT` (4), `DictionaryCrudIT` (5), `OutputCrudIT` (5)
- [x] Unit test gaps: `BotFactoryTest`, `BehaviorRulesEvaluationTaskTest`, `RestBotEngineTest`,
      `ConversationHistoryBuilderTest`, `LegacyChatExecutorTest`
- [x] Fixed `RestInterfaceFactory` port hardcoded to 7070 → now `@ConfigProperty` injected
- [x] Weather bot analysis: **confirmed v6.0 compatible** (no backwards compat issue, no migration needed)
- [x] All 48 integration tests + 620 unit tests = 668 tests pass

> **Note:** `EDDI-integration-tests` repo is now fully superseded. All tests migrated into main repo.

### Phase 3: API Consistency Fixes (N.7) ✅

- [x] **N.7.1** — Verified `restVersionInfo.create()` returns 201; duplicate POST 200 was Vite proxy issue. No backend change needed.
- [x] **N.7.2** — Added `?permanent=true` query parameter to all 8 store DELETE endpoints (interfaces + implementations). Default stays soft-delete.
- [x] **N.7.3** — `getDeploymentStatus` now returns JSON `{"status":"READY"}` by default. `?format=text` backward compat. Updated `TestCaseRuntime`, all integration tests, and Manager integration tests.
- [x] **N.7.4** — Deferred — Quarkus dev-mode HTML health response; `/q/health/live` workaround sufficient.

**Key files:**

- `IRestBotAdministration.java`, `RestBotAdministration.java` — deployment status JSON
- `RestVersionInfo.java` — `delete(id, version, permanent)` overload
- All 8 `IRest*Store` interfaces and `Rest*Store` implementations — `?permanent` param
- All integration tests updated for JSON deployment status

### Chat UI Vite Build + Bot Father Enhancements ✅

- [x] Deployed new Vite chat-ui production build to `META-INF/resources/` (replaces old CRA build)
- [x] `chat.html` updated for new Vite bundle entry points
- [x] Bot Father OpenAI flow: added timeout, built-in tools (whitelist), and conversation history limit steps
- [x] `OpenAILanguageModelBuilder`: migrated to `JdkHttpClient.builder()`
- [x] `GeminiLanguageModelBuilder`, `OllamaLanguageModelBuilder`: also migrated to JDK HttpClient

### Phase 5, Items 5.27-5.28: Event Bus Abstraction + NATS JetStream Adapter ✅ (commit `e220f4c0`)

- [x] Created `IEventBus` interface — abstract event bus for conversation ordering
- [x] Refactored `IConversationCoordinator` to extend `IEventBus` (backward compatible)
- [x] Renamed `ConversationCoordinator` → `InMemoryConversationCoordinator` (`@DefaultBean`)
- [x] Created `NatsConversationCoordinator` — JetStream durable ordering, `@LookupIfProperty(eddi.messaging.type=nats)`
- [x] Created `NatsHealthCheck` — Quarkus readiness check at `/q/health/ready`
- [x] Created `NatsMetrics` — Micrometer counters/timers for publish/consume
- [x] Added `io.nats:jnats` 2.25.2 dependency
- [x] Added `eddi.messaging.type` config (`in-memory` default, `nats` option)
- [x] Added `docker-compose.nats.yml` for local NATS JetStream development
- [x] Fixed pre-existing `javax.validation.constraints.NotNull` import in `RegularDictionaryConfiguration`
- [x] 8 new `NatsConversationCoordinatorTest` unit tests
- [x] All 639 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/runtime/IEventBus.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/InMemoryConversationCoordinator.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/NatsHealthCheck.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/NatsMetrics.java`
- `docker-compose.nats.yml`

### Phase 5, Item 5.29: Async Conversation Processing ✅

- [x] Dead-letter handling: retry up to `maxRetries`, then route to `EDDI_DEAD_LETTERS` stream (30-day retention)
- [x] `NatsMetrics` wired into coordinator (publish/consume/dead-letter counters and timers)
- [x] `RetryableCallable` wrapper for per-task retry tracking
- [x] Dead-letter stream created during `start()` with `Limits` retention
- [x] `eddi.nats.dead-letter-stream-name` config property added
- [x] `org.testcontainers:testcontainers:2.0.3` + `testcontainers-junit-jupiter:2.0.3` added
- [x] 12 unit tests in `NatsConversationCoordinatorTest` (+4 new)
- [x] 5 Testcontainers integration tests in `NatsConversationCoordinatorIT`
- [x] All 643 tests pass (0 failures, 0 errors, 4 skipped)

### Phase 5, Item 5.30: Coordinator Dashboard + Dead-Letter Admin ✅

- [x] `IConversationCoordinator` — default methods for status introspection + dead-letter management
- [x] `InMemoryConversationCoordinator` — retry logic (3 attempts), dead-letter queue, processed/dead-lettered counters
- [x] `NatsConversationCoordinator` — status methods, dead-letter listing (JetStream), stream purge
- [x] `CoordinatorStatus` + `DeadLetterEntry` DTO records
- [x] `IRestCoordinatorAdmin` + `RestCoordinatorAdmin` — REST API + SSE stream at `/administration/coordinator/`
- [x] Manager: Coordinator page at `/manage/coordinator` (status cards, queue depths, dead-letter admin table)
- [x] Manager: API module, TanStack Query hooks with SSE, sidebar nav, MSW handlers, i18n (11 locales)
- [x] `RestCoordinatorAdminTest` (10 tests), `InMemoryConversationCoordinatorTest` (12 tests), `coordinator.test.tsx` (12 tests)
- [x] Backend: 665 tests pass, Manager: 176 tests pass

**Key files (backend):**

- `src/main/java/ai/labs/eddi/engine/IRestCoordinatorAdmin.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestCoordinatorAdmin.java`
- `src/main/java/ai/labs/eddi/engine/model/CoordinatorStatus.java`
- `src/main/java/ai/labs/eddi/engine/model/DeadLetterEntry.java`
- `src/main/java/ai/labs/eddi/engine/runtime/IConversationCoordinator.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/InMemoryConversationCoordinator.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java`

**Key files (Manager):**

- `src/pages/coordinator.tsx`, `src/lib/api/coordinator.ts`, `src/hooks/use-coordinator.ts`

### Phase 6, Item 6.30: Repository Interface Abstraction ✅ (commit `e8d09f77`)

- [x] Created `IResourceStorageFactory` — factory interface for creating DB-agnostic storage backends
- [x] Created `MongoResourceStorageFactory` — MongoDB impl (`@DefaultBean`), single injection point for `MongoDatabase`
- [x] Created `AbstractResourceStore<T>` — DB-agnostic base class for config stores (replaces `AbstractMongoResourceStore`)
- [x] Relocated `HistorizedResourceStore<T>` from `datastore.mongo` → `datastore` package (zero MongoDB deps)
- [x] Relocated `ModifiableHistorizedResourceStore<T>` from `datastore.mongo` → `datastore` package
- [x] `AbstractMongoResourceStore<T>` — now extends `AbstractResourceStore`, marked `@Deprecated`
- [x] `mongo.HistorizedResourceStore` / `mongo.ModifiableHistorizedResourceStore` — thin backward-compat wrappers
- [x] Added `@DefaultBean` to `ConversationMemoryStore` (allows future PostgreSQL override)
- [x] Added `eddi.datastore.type=mongodb` config property to `application.properties`
- [x] 19 new tests: `HistorizedResourceStoreTest` (10), `AbstractResourceStoreTest` (7), `MongoResourceStorageFactoryTest` (2)
- [x] All 684 tests pass (0 failures, 0 errors, 4 skipped)

**Key files (new):**

- `src/main/java/ai/labs/eddi/datastore/IResourceStorageFactory.java`
- `src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageFactory.java`
- `src/main/java/ai/labs/eddi/datastore/AbstractResourceStore.java`
- `src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java`
- `src/main/java/ai/labs/eddi/datastore/ModifiableHistorizedResourceStore.java`

**Key files (modified):**

- `src/main/java/ai/labs/eddi/datastore/mongo/AbstractMongoResourceStore.java` — extends AbstractResourceStore, @Deprecated
- `src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryStore.java` — added @DefaultBean

### Phase 6, Item 6.31: PostgreSQL Adapter ✅

- [x] Added Maven dependencies: `quarkus-jdbc-postgresql`, `quarkus-agroal`, `testcontainers:postgresql`
- [x] Created `PostgresResourceStorage<T>` — JDBC + JSONB implementation of `IResourceStorage<T>`
  - Auto-creates `resources` + `resources_history` tables via `CREATE TABLE IF NOT EXISTS`
  - Uses `IJsonSerialization` for clean JSON↔object conversion
  - UUID-based IDs, version tracking, soft-delete support
- [x] Created `PostgresResourceStorageFactory` — `@LookupIfProperty(eddi.datastore.type=postgres)`
- [x] Created `PostgresHealthCheck` — readiness check at `/q/health/ready`
- [x] Migrated 7 stores from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory`:
  `LangChainStore`, `ParserStore`, `PropertySetterStore`, `HttpCallsStore`, `BehaviorStore`, `OutputStore`, `RegularDictionaryStore`
- [x] `BotStore`/`PackageStore` now also migrated to `AbstractResourceStore` + `IResourceStorageFactory`
- [x] Added PostgreSQL datasource config to `application.properties` (inactive by default)
- [x] Created `docker-compose.postgres.yml` for local development
- [x] 15 new tests: `PostgresResourceStorageTest` (12), `PostgresResourceStorageFactoryTest` (3)
- [x] All 699 tests pass (0 failures, 0 errors, 4 skipped)

**Key files (new):**

- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorageFactory.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresHealthCheck.java`
- `docker-compose.postgres.yml`

**Key files (migrated):**

- `src/main/java/ai/labs/eddi/configs/langchain/mongo/LangChainStore.java`
- `src/main/java/ai/labs/eddi/configs/parser/mongo/ParserStore.java`
- `src/main/java/ai/labs/eddi/configs/propertysetter/mongo/PropertySetterStore.java`
- `src/main/java/ai/labs/eddi/configs/http/mongo/HttpCallsStore.java`
- `src/main/java/ai/labs/eddi/configs/behavior/mongo/BehaviorStore.java`
- `src/main/java/ai/labs/eddi/configs/output/mongo/OutputStore.java`
- `src/main/java/ai/labs/eddi/configs/regulardictionary/mongo/RegularDictionaryStore.java`

### Phase 6, Item 6.32: Full Store Migration — All Stores DB-Agnostic ✅

- [x] Added `findResourceIdsContaining()`, `findHistoryResourceIdsContaining()`, `findResources()` to `IResourceStorage`
- [x] Implemented in `MongoResourceStorage` (MongoDB `$in`, regex, pagination)
- [x] Implemented in `PostgresResourceStorage` (JSONB `@>`, `~`, SQL pagination)
- [x] `BotStore` migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory`
- [x] `PackageStore` — same migration pattern, removed inner MongoDB classes
- [x] Created `IDeploymentStorage` interface (DB-agnostic)
- [x] Created `MongoDeploymentStorage` (`@DefaultBean`) — extracted MongoDB logic from DeploymentStore
- [x] Created `PostgresDeploymentStorage` (`@LookupIfProperty`) — JDBC with `INSERT...ON CONFLICT`, dedicated `deployments` table
- [x] `DeploymentStore` — refactored to thin delegate to `IDeploymentStorage`
- [x] Created DB-agnostic `DescriptorStore` in `datastore` package (uses `IResourceStorageFactory` + `IResourceStorage.findResources()`)
- [x] Updated `DocumentDescriptorStore`, `ConversationDescriptorStore`, `TestCaseDescriptorStore` to use `IResourceStorageFactory`
- [x] Created `PostgresConversationMemoryStore` — JSONB storage with indexed columns (bot_id, bot_version, conversation_state)
- [x] All 701 tests pass (0 failures, 0 errors, 4 skipped). `mvnw verify` succeeds.

**Key files (new):**

- `src/main/java/ai/labs/eddi/configs/deployment/IDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/datastore/DescriptorStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java`

**Key files (modified):**

- `BotStore.java`, `PackageStore.java` — extends `AbstractResourceStore`
- `DeploymentStore.java` — thin delegate to `IDeploymentStorage`
- `DocumentDescriptorStore.java`, `ConversationDescriptorStore.java`, `TestCaseDescriptorStore.java` — use `IResourceStorageFactory`
- `IResourceStorage.java`, `MongoResourceStorage.java`, `PostgresResourceStorage.java` — new query methods

## Next Up

Phase 6 Item #33 (Migration Tooling — MongoDB → PostgreSQL), Phase 7 (MCP Server + Client), etc. See `docs/v6-planning/implementation_plan.md` and `AGENTS.md` for the full roadmap.

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
