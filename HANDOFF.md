# EDDI v6.0 — Current Status

> [!IMPORTANT]
> **This file is a historical snapshot from early v6.0 development and is no longer actively maintained.**
> For the authoritative, up-to-date log of all changes, decisions, and reasoning, see [`docs/changelog.md`](docs/changelog.md).
> This file contains class names and package paths that have since been renamed (e.g., `LangchainTask` → `LlmTask`, `HttpCallsTask` → `ApiCallsTask`).

> **Last updated:** 2026-03-30

## Completed

### Phase 0: Security Quick Wins ✅ (commit `71448a89`)

- [x] CORS production to `localhost:3000,localhost:7070`
- [x] `PathNavigator` replaces all 5 explicit `Ognl.getValue()`/`Ognl.setValue()` calls
- [x] 27 new PathNavigator tests, all 499 tests pass

### Phase 1, Item 1: Extract `ConversationService` from `RestAgentEngine` ✅ (commit `7dd1488e`)

- [x] Created `IConversationService` interface with domain exceptions (no JAX-RS deps)
- [x] Created `ConversationService` implementation with all business logic (~565 lines)
- [x] Refactored `RestAgentEngine` from 668 to ~230 lines (thin REST adapter)
- [x] 16 new unit tests for `ConversationService`
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/IConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java`
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
- [x] Refactored 8 stores to extend base class: `LangChainStore`, `ParserStore`, `PropertySetterStore`, `HttpCallsStore`, `BehaviorStore`, `OutputStore`, `RegularDictionaryStore`, `AgentStore`, `WorkflowStore`
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
- [x] `POST /agents/{env}/{agentId}/{convId}/stream` SSE endpoint (`RestAgentEngineStreaming`)
- [x] `ConversationService.sayStreaming()` with event sink adapter
- [x] 22 new tests across 5 test files
- [x] All ~562 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java`
- `src/main/java/ai/labs/eddi/modules/langchain/impl/StreamingLegacyChatExecutor.java`
- `src/main/java/ai/labs/eddi/engine/IRestAgentEngineStreaming.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java`
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
- [x] `AgentEngineIT` (11 tests): welcome, input, context, output, undo/redo, templating, property
- [x] `AgentDeploymentComponentIT` (4 tests): deploy, undeploy, status transitions
- [x] `ConversationServiceComponentIT` (7 tests): lifecycle, undo/redo, concurrent conversations
- [x] `ApiContractIT` (10 tests): CRUD contracts for behavior, dictionary, output, package, agent stores
- [x] `AgentUseCaseIT` (2 tests): weather agent import + managed agent API
- [x] CRUD ITs: `BehaviorCrudIT` (4), `DictionaryCrudIT` (5), `OutputCrudIT` (5)
- [x] Unit test gaps: `AgentFactoryTest`, `BehaviorRulesEvaluationTaskTest`, `RestAgentEngineTest`,
      `ConversationHistoryBuilderTest`, `LegacyChatExecutorTest`
- [x] Fixed `RestInterfaceFactory` port hardcoded to 7070 → now `@ConfigProperty` injected
- [x] Weather agent analysis: **confirmed v6.0 compatible** (no backwards compat issue, no migration needed)
- [x] All 48 integration tests + 620 unit tests = 668 tests pass

> **Note:** `EDDI-integration-tests` repo is now fully superseded. All tests migrated into main repo.

### Phase 3: API Consistency Fixes (N.7) ✅

- [x] **N.7.1** — Verified `restVersionInfo.create()` returns 201; duplicate POST 200 was Vite proxy issue. No backend change needed.
- [x] **N.7.2** — Added `?permanent=true` query parameter to all 8 store DELETE endpoints (interfaces + implementations). Default stays soft-delete.
- [x] **N.7.3** — `getDeploymentStatus` now returns JSON `{"status":"READY"}` by default. `?format=text` backward compat. Updated `TestCaseRuntime`, all integration tests, and Manager integration tests.
- [x] **N.7.4** — Deferred — Quarkus dev-mode HTML health response; `/q/health/live` workaround sufficient.

**Key files:**

- `IRestAgentAdministration.java`, `RestAgentAdministration.java` — deployment status JSON
- `RestVersionInfo.java` — `delete(id, version, permanent)` overload
- All 8 `IRest*Store` interfaces and `Rest*Store` implementations — `?permanent` param
- All integration tests updated for JSON deployment status

### Chat UI Vite Build + Agent Father Enhancements ✅

- [x] Deployed new Vite chat-ui production build to `META-INF/resources/` (replaces old CRA build)
- [x] `chat.html` updated for new Vite bundle entry points
- [x] Agent Father OpenAI flow: added timeout, built-in tools (whitelist), and conversation history limit steps
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
- [x] `AgentStore`/`WorkflowStore` now also migrated to `AbstractResourceStore` + `IResourceStorageFactory`
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
- [x] `AgentStore` migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory`
- [x] `WorkflowStore` — same migration pattern, removed inner MongoDB classes
- [x] Created `IDeploymentStorage` interface (DB-agnostic)
- [x] Created `MongoDeploymentStorage` (`@DefaultBean`) — extracted MongoDB logic from DeploymentStore
- [x] Created `PostgresDeploymentStorage` (`@LookupIfProperty`) — JDBC with `INSERT...ON CONFLICT`, dedicated `deployments` table
- [x] `DeploymentStore` — refactored to thin delegate to `IDeploymentStorage`
- [x] Created DB-agnostic `DescriptorStore` in `datastore` package (uses `IResourceStorageFactory` + `IResourceStorage.findResources()`)
- [x] Updated `DocumentDescriptorStore`, `ConversationDescriptorStore`, `TestCaseDescriptorStore` to use `IResourceStorageFactory`
- [x] Created `PostgresConversationMemoryStore` — JSONB storage with indexed columns (agent_id, agent_version, conversation_state)
- [x] All 701 tests pass (0 failures, 0 errors, 4 skipped). `mvnw verify` succeeds.

**Key files (new):**

- `src/main/java/ai/labs/eddi/configs/deployment/IDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/datastore/DescriptorStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresConversationMemoryStore.java`

**Key files (modified):**

- `AgentStore.java`, `WorkflowStore.java` — extends `AbstractResourceStore`
- `DeploymentStore.java` — thin delegate to `IDeploymentStorage`
- `DocumentDescriptorStore.java`, `ConversationDescriptorStore.java`, `TestCaseDescriptorStore.java` — use `IResourceStorageFactory`
- `IResourceStorage.java`, `MongoResourceStorage.java`, `PostgresResourceStorage.java` — new query methods

### Phase 6A: MongoDB Sync Driver Migration ✅

- [x] Replaced `mongodb-driver-reactivestreams` + RxJava3 with `mongodb-driver-sync`
- [x] Migrated 13 files from `Observable.fromPublisher(...).blocking*()` to direct sync API calls
- [x] Removed `io.reactivex.rxjava3` dependency
- [x] All tests pass

### Phase 6B: PostgreSQL Integration Test Parity ✅ (commit `0eda70d9`, `e77b6f23`)

- [x] Created `PostgresIntegrationTestProfile` with `eddi.datastore.type=postgres` + Testcontainers DevServices
- [x] Created 8 PostgreSQL IT subclasses — all 48/48 tests pass, full parity with MongoDB:
  - `PostgresBehaviorCrudIT` (4/4), `PostgresOutputCrudIT` (5/5), `PostgresDictionaryCrudIT` (5/5)
  - `PostgresAgentEngineIT` (11/11), `PostgresAgentDeploymentComponentIT` (4/4)
  - `PostgresConversationServiceComponentIT` (7/7), `PostgresApiContractIT` (10/10)
  - `PostgresAgentUseCaseIT` (2/2)
- [x] Fixed `RestUtilities.isValidId()` — added `-` for UUID dashes (`e5c68a0b`)
- [x] Fixed `DocumentDescriptorFilter` — added `isResourceIdValid()` guard on PUT/PATCH (`e5c68a0b`)
- [x] Added `#uuidUtils.extractId()` + `extractVersion()` to `UUIDWrapper` — DB-agnostic URI parsing (`7fb79bfa`)
- [x] Updated all 7 Agent Father httpcalls JSONs (70 replacements) + re-zipped `Agent+Father-4.0.0.zip`
- [x] Added 12 unit tests in `UUIDWrapperTest`
- [x] Documented `#uuidUtils` in `docs/output-templating.md`
- [x] Fixed `PostgresIntegrationTestProfile` — added `quarkus.http.port=8082` for `RestInterfaceFactory` (`0eda70d9`)
- [x] Fixed `PostgresApiContractIT` — overrode `readNonExistent_returns404` with UUID-formatted ID (`0eda70d9`)
- [x] Fixed `AgentUseCaseIT.useAgentManagement` — stale trigger cleanup + status assertions (`e77b6f23`)
- [x] All 96 integration tests pass (48 MongoDB + 48 PostgreSQL)

**Key files:**

- `src/main/java/ai/labs/eddi/utils/RestUtilities.java` — `isValidId()` UUID fix
- `src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java` — `isResourceIdValid` guard
- `src/main/java/ai/labs/eddi/modules/templating/impl/dialects/uuid/UUIDWrapper.java` — `extractId/extractVersion`
- `src/main/resources/initial-agents/Agent+Father-4.0.0.zip` — re-zipped with updated httpcalls
- `src/test/java/ai/labs/eddi/integration/PostgresIntegrationTestProfile.java` — PG test profile
- `src/test/java/ai/labs/eddi/integration/postgres/` — 8 PG IT subclasses
- `src/test/java/ai/labs/eddi/integration/AgentUseCaseIT.java` — stale trigger cleanup
- `docs/output-templating.md` — `#uuidUtils` documentation

### Agent Lifecycle API: Cascade Delete + Orphan Detection ✅

- [x] Added `?cascade=true` to `DELETE /agentstore/agents/{id}` and `DELETE /packagestore/packages/{id}`
- [x] Cascade walks agent → packages → extensions and deletes all children
- [x] **Shared-resource safety**: checks references before deleting — skips packages used by other agents, extensions used by other packages
- [x] Added `IResourceClientLibrary.deleteResource(URI, permanent)` for cascade use
- [x] New admin endpoint: `GET/DELETE /administration/orphans`
  - GET: scans all 8 store types for unreferenced resources (dry-run report)
  - DELETE: permanently purges all orphans
  - Algorithm: enumerate agents → packages → extensions → compare against all descriptors per store
- [x] DTOs: `OrphanInfo`, `OrphanReport`; interfaces: `IRestOrphanAdmin`, `IRestAgentStore`, `IRestWorkflowStore` updated
- [x] Enriched OpenAPI annotations with `@Operation`, `@Parameter`, `@APIResponse` descriptions
- [x] Documentation: deletion + orphan sections in `docs/deployment-management-of-agents.md`
- [x] Tests: `RestAgentStoreTest` (6/6), `RestWorkflowStoreTest` (7/7), `RestOrphanAdminTest` (4/4) — 17 new tests total
- [x] Full test suite passes

**Key files (new):**

- `src/main/java/ai/labs/eddi/configs/admin/model/OrphanInfo.java`
- `src/main/java/ai/labs/eddi/configs/admin/model/OrphanReport.java`
- `src/main/java/ai/labs/eddi/configs/admin/IRestOrphanAdmin.java`
- `src/main/java/ai/labs/eddi/configs/admin/rest/RestOrphanAdmin.java`
- `src/test/java/ai/labs/eddi/configs/admin/rest/RestOrphanAdminTest.java`
- `src/test/java/ai/labs/eddi/configs/agents/rest/RestAgentStoreTest.java`
- `src/test/java/ai/labs/eddi/configs/packages/rest/RestWorkflowStoreTest.java`

**Key files (modified):**

- `IRestAgentStore.java`, `RestAgentStore.java` — cascade + shared-resource check
- `IRestWorkflowStore.java`, `RestWorkflowStore.java` — cascade + shared-resource check
- `IResourceClientLibrary.java`, `ResourceClientLibrary.java` — `deleteResource` method
- `docs/deployment-management-of-agents.md` — deletion + orphan docs

### Phase 6C: Infinispan → Caffeine ✅

- [x] Rewrote `CacheFactory.java` — Caffeine builder with inline size configs (replaced `EmbeddedCacheManager`)
- [x] Rewrote `CacheImpl.java` — wraps Caffeine `Cache<K,V>` (replaced Infinispan `Cache`)
- [x] Removed 4 Infinispan dependencies + `infinispan.version` property from POM
- [x] Caffeine provided transitively by `quarkus-cache` (already in POM)
- [x] Deleted `infinispan-embedded.xml` config
- [x] Cleaned `application.properties` (removed 2 Infinispan config lines)
- [x] Updated `ToolCacheService` Javadoc + log message
- [x] Verified: multi-instance agent deployment does NOT use Infinispan (uses DB-backed `IDeploymentStore` + `@Scheduled` polling)
- [x] 729 unit tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java` — rewritten
- `src/main/java/ai/labs/eddi/engine/caching/CacheImpl.java` — rewritten
- `pom.xml` — 4 deps removed, caffeine via quarkus-cache
- `src/main/resources/infinispan-embedded.xml` — deleted
- `src/main/resources/application.properties` — cleaned

### Phase 6E: quarkus-langchain4j → langchain4j Core ✅

- [x] Removed 6 `io.quarkiverse.langchain4j` dependencies from POM
- [x] Added 7 core `dev.langchain4j` deps (langchain4j, open-ai, anthropic, ollama, vertex-ai-gemini, hugging-face, jlama)
- [x] Removed `quarkus.langchain4j.version` property, added `langchain4j-beta.version` (1.11.0-beta19) for beta-versioned modules
- [x] Migrated `VertexGeminiLanguageModelBuilder`: new package (`vertexai.gemini`), API renames, removed `timeout()` (now EDDI-level)
- [x] Migrated `HuggingFaceLanguageModelBuilder`: removed quarkiverse-only methods (`topK`, `topP`, `doSample`, `repetitionPenalty`), kept core methods only
- [x] Migrated `JlamaLanguageModelBuilder`: package change only, removed unsupported `logRequests`/`logResponses`
- [x] Created `ObservableChatModel` — provider-agnostic ChatModel decorator with configurable timeout + request/response logging
- [x] Wired into `ChatModelRegistry.getOrCreate()` — all providers get timeout+logging automatically via `timeout`, `logRequests`, `logResponses` config params
- [x] Zero `quarkiverse` references in dependency tree
- [x] 753 unit tests pass (0 failures, 0 errors, 4 skipped) — 19 `ObservableChatModelTest` + 5 `ChatModelRegistryTest` observability tests

**Key files:**

- `pom.xml` — 6 deps removed, 7 added, version properties updated
- `src/main/java/.../impl/ObservableChatModel.java` — **NEW** provider-agnostic timeout + logging decorator
- `src/main/java/.../impl/ChatModelRegistry.java` — wires ObservableChatModel
- `src/main/java/.../builder/VertexGeminiLanguageModelBuilder.java` — rewritten
- `src/main/java/.../builder/HuggingFaceLanguageModelBuilder.java` — rewritten
- `src/main/java/.../builder/JlamaLanguageModelBuilder.java` — updated
- `src/test/java/.../impl/ObservableChatModelTest.java` — **NEW** 19 tests
- `src/test/java/.../impl/ChatModelRegistryTest.java` — 5 new observability tests

### Phase 6D: Lombok Removal ✅ (commit `ca3e45da`)

- [x] IntelliJ delombok expanded all 114 files (107 main + 7 test)
- [x] Fixed 11 structurally corrupted files from field-level annotation script
- [x] `isIsPublic` → `isPublic` naming fix in `Data.java` and `ResultSnapshot`
- [x] Removed Lombok dependency, version property, and annotation processor from POM
- [x] All 775 tests pass, 0 Lombok annotations/imports remain

### Phase 7, Item 33: Secrets Vault — Security Remediation ✅

- [x] R1: Fixed CRITICAL memory leakage — PropertySetterTask no longer resolves vault refs to plaintext, HttpCallExecutor scrubs sensitive headers
- [x] R2: Complete URL/body/query resolution in HttpCallExecutor
- [x] R3: Secret input mechanism — `Property.Scope.secret` + auto-vault + `InputFieldOutputItem` with `subType: password`
- [x] R4: PBKDF2 key derivation — upgraded EnvelopeCrypto from SHA-256 to PBKDF2 (600K iterations)
- [x] R5: REST input validation — path param regex in RestSecretStore
- [x] R6: 32 unit tests across 5 test classes (9+7+6+4+6)
- [x] Fixed SecretRedactionFilter regex replacement bug ($ interpreted as group reference)
- [x] Fixed 8 pre-existing Lombok ghost-method bugs in OutputItem subclasses + OutputConfiguration
- [x] Cleanup: removed dead secretResolver, unused imports
- [x] Documentation: `docs/secrets-vault.md`
- [x] Installer integration: vault master key auto-generated during install, stored in `~/.eddi/.env`
- [x] All 810 tests pass (0 failures, 0 errors, 4 skipped)

**Key files (new):**

- `src/main/java/ai/labs/eddi/secrets/` — model, crypto, sanitize, rest packages
- `src/test/java/ai/labs/eddi/secrets/` — EnvelopeCryptoTest, SecretResolverTest, SecretRedactionFilterTest, SecretScrubberTest, SecretReferenceTest
- `docs/secrets-vault.md` — full feature documentation

**Key files (modified):**

- `PropertySetterTask.java` — secret scope auto-vault, removed dead secretResolver
- `HttpCallExecutor.java` — header scrubbing, vault ref resolution
- `RestExportService.java` — export sanitization
- `OutputConfiguration.java` + 6 OutputItem subclasses — Lombok bug fixes

### Phase 7, Item 34: Immutable Audit Ledger — EU AI Act Compliance ✅

- [x] Created `AuditEntry` record — per-task audit data with 18 fields (input, output, LLM detail, actions, cost, timing, HMAC)
- [x] Created `IAuditStore` interface — write-once contract (NO update/delete methods)
- [x] Created `AuditStore` (MongoDB) — `audit_ledger` collection, `insertOne`/`insertMany` only, 3 indexes
- [x] Created `AuditHmac` — HMAC-SHA256 integrity signing, PBKDF2 key derivation from vault master key (independent salt)
- [x] Created `AuditLedgerService` — async batch writer with re-queue retry (3 attempts), secret scrubbing, HMAC signing
- [x] Created `IAuditEntryCollector` — functional interface decoupling `LifecycleManager` from storage
- [x] Integrated into `LifecycleManager` — `buildAuditEntry()` emits audit entry per task completion
- [x] Integrated into `ConversationService` — both `say` and `sayStreaming` paths set audit collector with environment enrichment
- [x] Created `IRestAuditStore` / `RestAuditStore` — read-only REST API (`/auditstore/{conversationId}`, `/auditstore/agent/{agentId}`)
- [x] Added `AuditEntry.withEnvironment()` — environment enrichment at the ConversationService layer
- [x] Secret redaction via `SecretRedactionFilter.redact()` — recurses into nested maps and lists
- [x] HMAC determinism — `buildCanonicalString()` sorts map keys via `TreeMap`
- [x] Flush retry — entries re-queued on DB failure, capped at 3 retries before dropping
- [x] Created `PostgresAuditStore` — JDBC+JSONB hybrid storage, `@IfBuildProfile("postgres")`, same insert-only contract
- [x] LangchainTask audit integration — writes `audit:compiled_prompt`, `audit:model_response`, `audit:model_name` memory keys
- [x] Documentation: `docs/audit-ledger.md`
- [x] 20 unit tests in `AuditLedgerServiceTest`: queue/flush (4), HMAC (5), scrubbing (3), retry (3), entry helpers (2), determinism (1), canonical (2)

**Key files (new):**

- `src/main/java/ai/labs/eddi/engine/audit/model/AuditEntry.java`
- `src/main/java/ai/labs/eddi/engine/audit/IAuditStore.java`
- `src/main/java/ai/labs/eddi/engine/audit/IAuditEntryCollector.java`
- `src/main/java/ai/labs/eddi/engine/audit/AuditHmac.java`
- `src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java`
- `src/main/java/ai/labs/eddi/engine/audit/AuditStore.java` (MongoDB)
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresAuditStore.java` (PostgreSQL)
- `src/main/java/ai/labs/eddi/engine/audit/rest/IRestAuditStore.java`
- `src/main/java/ai/labs/eddi/engine/audit/rest/RestAuditStore.java`
- `src/test/java/ai/labs/eddi/engine/audit/AuditLedgerServiceTest.java`
- `docs/audit-ledger.md`

**Key files (modified):**

- `IConversationMemory.java` / `ConversationMemory.java` — `getAuditCollector` / `setAuditCollector`
- `LifecycleManager.java` — `buildAuditEntry()` + audit hook
- `ConversationService.java` — injected `AuditLedgerService`, environment-enriched audit collector
- `LangchainTask.java` — writes `audit:*` memory keys after each LLM call
- `LangchainTaskTest.java` — updated storeData expected counts
- `ConversationServiceTest.java` — updated constructor

### Scheduled Triggers & Heartbeats ✅

Two trigger types on a shared engine:

| Trigger       | Config                     | Default Strategy                    | Next Fire Logic                |
| ------------- | -------------------------- | ----------------------------------- | ------------------------------ |
| **CRON**      | `cronExpression` (5-field) | `new` (fresh conversation per fire) | Recomputed from cron           |
| **HEARTBEAT** | `heartbeatIntervalSeconds` | `persistent` (single conversation)  | `now + interval` (drift-proof) |

**Exactly-once execution:** Atomic CAS via MongoDB `findOneAndUpdate` with `PENDING` + `nextRetryAt ≤ now` guards. Dead-lettering after configurable `maxRetries` with exponential backoff (`base × multiplier^failCount`).

**Engine flow:** `SchedulePollerService.poll()` → `tryClaim()` → `ScheduleFireExecutor.fire()` → `ConversationService.say()` → `markCompleted()` / `markFailed()` / `markDeadLettered()`

**MCP tools (6):** `create_schedule`, `list_schedules`, `read_schedule`, `delete_schedule`, `fire_schedule_now`, `retry_failed_schedule`

**Key files:**

- `src/main/java/ai/labs/eddi/configs/schedule/model/ScheduleConfiguration.java` — `TriggerType`, `FireStatus` enums
- `src/main/java/ai/labs/eddi/configs/schedule/mongo/MongoScheduleStore.java` — CAS, atomic ops, epoch-millis
- `src/main/java/ai/labs/eddi/engine/runtime/internal/SchedulePollerService.java` — poll + claim + backoff
- `src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java` — fire + context injection
- `src/main/java/ai/labs/eddi/configs/schedule/rest/RestScheduleStore.java` — REST + validation
- `src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java` — 6 schedule MCP tools
- Tests: `SchedulePollerServiceTest` (12), `ScheduleFireExecutorTest` (9), `RestScheduleStoreTest` (13), `McpScheduleToolsTest` (21), `CronParserTest` (16), `CronDescriberTest` (8)

**Configuration (`application.properties`):**

```properties
eddi.schedule.enabled=true
eddi.schedule.poll-interval=30s
eddi.schedule.lease-timeout=5m
eddi.schedule.max-retries=5
eddi.schedule.backoff-base-seconds=15
eddi.schedule.backoff-multiplier=4
eddi.schedule.min-interval-seconds=60
```

**Agent ↔ Schedule Lifecycle Hooks:**

| Agent Event          | Schedule Effect                                           | Where                                                                       |
| -------------------- | --------------------------------------------------------- | --------------------------------------------------------------------------- |
| **Deploy**           | Auto-enable all disabled schedules for agentId            | `RestAgentAdministration.enableSchedulesForAgent()`                         |
| **Undeploy**         | Auto-disable all enabled schedules for agentId            | `RestAgentAdministration.disableSchedulesForAgent()`                        |
| **Delete (cascade)** | Delete all schedules for agentId (before package cascade) | `RestAgentStore.deleteAgent()` → `scheduleStore.deleteSchedulesByAgentId()` |
| **Export**           | Include schedules as `{id}.schedule.json` in export ZIP   | `RestExportService.exportSchedules()`                                       |

All hooks are **non-fatal** — if schedule operations fail, the primary agent operation still succeeds (logged as warning).

**EDDI Manager:** No schedule UI yet. Backend REST API + MCP tools are the only interfaces.

### Phase 10: Group Conversations — Multi-Agent Debate Orchestration ✅

Phase-based orchestration engine enabling structured multi-agent discussions with 5 preset styles, nested group-of-groups support, and moderator synthesis.

| Sub-Phase | Deliverables |
|---|---|
| **10.1** Data Models + Stores | `AgentGroupConfiguration`, `GroupConversation`, group/conversation stores (DB-agnostic) |
| **10.2** Orchestration Service | `GroupConversationService` (~680 lines) — phases, context scoping, templates |
| **10.3** REST + SSE + MCP | REST endpoints, SSE streaming, `McpGroupTools` (9 tools) |
| **10.5** Discussion Styles | `DiscussionPhase`, `DiscussionStylePresets` — 5 styles + custom phases |
| **10.5b** MCP/REST Usability | `describe_discussion_styles`, `list_group_conversations`, `GET /styles` |
| **10.6** Group-of-Groups | `MemberType.GROUP`, recursive `executeGroupMemberTurn()`, `memberTypes` param |

**Discussion styles:** `ROUND_TABLE`, `PEER_REVIEW`, `DEVIL_ADVOCATE`, `DELPHI`, `DEBATE` (+ fully custom phases)

**Group-of-Groups:** Members can be other groups — sub-group synthesized answer becomes member's response. Depth tracking prevents infinite recursion (`eddi.groups.max-depth`, default: 3).

**Tests:** 58 total
- `GroupConversationServiceTest` (17) — orchestration, all styles, recursion, error handling
- `McpGroupToolsTest` (22) — MCP tool layer
- `DiscussionStylePresetsTest` (19) — style expansion

**Key files:**
- `src/main/java/ai/labs/eddi/configs/groups/model/AgentGroupConfiguration.java`
- `src/main/java/ai/labs/eddi/configs/groups/model/GroupConversation.java`
- `src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java`
- `src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpGroupTools.java`
- `src/test/java/ai/labs/eddi/engine/internal/GroupConversationServiceTest.java`

### Templating Engine Migration: Thymeleaf → Quarkus Qute ✅

Replaced Thymeleaf 3.1.3 + OGNL 3.3.4 with Quarkus Qute for native image compatibility and CVE remediation.

| Component | Key Files |
|---|---|
| **Core Engine** | `TemplatingEngine.java` — Qute `Engine` API with null-safety |
| **Extensions** | `EddiTemplateExtensions.java` (UUID, JSON, Encoder namespaces), `StringTemplateExtensions.java` (15 String methods) |
| **Migrator** | `TemplateSyntaxMigrator.java` — 10 regex patterns + close-tag scanner + string concat handler |
| **Startup Migration** | `V6QuteMigration.java` — idempotent startup hook for 4 MongoDB collections (apicalls, outputs, propertysetter, llms + history) |
| **Import Migration** | `RestImportService.java` — `TemplateSyntaxMigrator` wired as final-pass before deserialization |

**Migration patterns:** `[[${var}]]` → `{var}`, `th:each` → `{#for}`, `th:if` → `{#if}`, `#strings.*` → native method calls, `#uuidUtils.`/`#json.`/`#encoder.` → namespace, `a + '/' + b` → `{a}/{b}`.

**Consumers updated:** `McpApiToolBuilder`, `AgentSetupService`, `DiscussionStylePresets` (10 templates), `PrePostUtils`, `ChatModelRegistry`, `MigrationManager`.

**Tests:** `TemplatingEngineTest` (20), `TemplateSyntaxMigratorTest` (29), `OutputTemplateTaskTest` (2), `McpApiToolBuilderTest` (14), `CreateApiAgentIT` (10).

**Docs updated:** `output-templating.md` (full rewrite), `architecture.md`, `httpcalls.md`, `conversation-memory.md`, `agent-father-deep-dive.md`, `changelog.md`.

**Config:**
```properties
eddi.migration.v6-qute.enabled=true   # Enable startup migration
quarkus.qute.strict-rendering=false    # Lenient variable handling
```

**Key files:**

- `src/main/java/ai/labs/eddi/modules/templating/impl/TemplatingEngine.java`
- `src/main/java/ai/labs/eddi/modules/templating/impl/extensions/EddiTemplateExtensions.java`
- `src/main/java/ai/labs/eddi/modules/templating/impl/extensions/StringTemplateExtensions.java`
- `src/main/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigrator.java`
- `src/main/java/ai/labs/eddi/configs/migration/V6QuteMigration.java`
- `src/main/java/ai/labs/eddi/backup/impl/RestImportService.java`
- `src/test/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigratorTest.java`
- `src/test/java/ai/labs/eddi/modules/templating/TemplatingEngineTest.java`
- `docs/output-templating.md`

### A2A Protocol: Agent-to-Agent Communication ✅

Implements the [A2A protocol](https://github.com/google/A2A) for distributed peer-to-peer agent communication.

| Component | Key Files |
|---|---|
| **A2A Models** | `A2AModels.java` — Protocol records (AgentCard, JSON-RPC, Task, Artifact) |
| **Server** | `AgentCardService.java` — Agent Card generation from `AgentConfiguration` |
| | `A2ATaskHandler.java` — JSON-RPC → `ConversationService.say()` bridge |
| | `RestA2AEndpoint.java` — `/.well-known/agent.json`, `/a2a/agents/{id}` |
| **Client** | `A2AToolProviderManager.java` — Discovers remote agents, wraps skills as `ToolSpecification` |
| **Config** | `AgentConfiguration` — `a2aEnabled`, `a2aSkills`, `description` |
| | `LlmConfiguration.Task` — `a2aAgents[]` with `A2AAgentConfig` |
| **Integration** | `AgentOrchestrator` + `LlmTask` — A2A tools merge with built-in/MCP/httpcall tools |

**Config properties:**
```properties
eddi.a2a.enabled=true                         # Master toggle
eddi.a2a.base-url=http://localhost:7070        # Base URL for Agent Card URLs
```

**Security:** API keys must use vault references (`${vault:my-key}`). Runtime warning on raw key usage.

**Documentation:** `docs/a2a-protocol.md`

### Multi-Model Cascading Routing ✅ (commit `514821d4`)

Cost-optimized LLM execution via sequential model escalation with confidence-based routing.

- [x] `ModelCascadeConfig` + `CascadeStep` config schema on `LlmConfiguration.Task`
- [x] `CascadingModelExecutor` — cascade loop with per-step timeout, error escalation, SSE events
- [x] `ConfidenceEvaluator` — 4 strategies: `structured_output`, `heuristic`, `judge_model`, `none`
- [x] `ConversationEventSink` — `onCascadeStepStart` + `onCascadeEscalation` default methods
- [x] `LlmTask` integration — cascade branch with full backward compat
- [x] Audit trail: `audit:cascade_model`, `audit:cascade_confidence`, `cascade:trace`
- [x] 39 new tests: `ConfidenceEvaluatorTest` (22), `CascadingModelExecutorTest` (13), `LlmTaskTest` cascade (4)
- [x] All 1291 tests pass, 0 failures

**Key files:**

- `src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/impl/ConfidenceEvaluator.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java`
- `src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java`
- `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java`
- `docs/model-cascade.md`

### LLM Structured Output Hardening ✅

Production-grade reliability for LLM structured JSON output via three-layer defense:

| Layer | Mechanism |
|---|---|
| **System Prompt** | `## RESPONSE FORMAT (MANDATORY)` section appended when `convertToObject=true`. Includes exact schema if `responseSchema` parameter provided |
| **API Level** | `ChatRequest.responseFormat(ResponseFormatType.JSON)` for providers that support it (OpenAI, Gemini, Mistral). Graceful fallback for unsupported providers |
| **Validation** | Pre-parse `startsWith("{")` check before `deserialize()`. Non-JSON stored as plain string with warning |

Additional fixes:
- [x] Raw LLM response persisted BEFORE JSON conversion (debuggability)
- [x] `jsonMode` flag derived from `convertToObject` (was incorrectly using `addToOutput`)
- [x] Prometheus tag conflicts in `ToolExecutionService` — removed tagless aggregate timer/counters
- [x] QR blank expression defense in `InputParserTask`

**Key files:**
- `LlmTask.java` — prompt reinforcement, raw storage, JSON validation, `responseSchema` param
- `LegacyChatExecutor.java` — `ChatRequest` with `ResponseFormat.JSON` + graceful fallback
- `ToolExecutionService.java` — per-tool tagged metrics only
- `InputParserTask.java` — blank expression guard

**Tests:** All 17 LlmTask tests pass. Compile verified.

## Next Up

### Quick Wins

- [x] ~~**Phase 6E: quarkus-langchain4j → langchain4j Core**~~ ✅
- [x] ~~**Phase 6D: Lombok Removal**~~ ✅
- [x] ~~**Quarkus 3.34.1 Upgrade**~~ ✅ — upgraded past 3.33 LTS to 3.34.1. Java 25 `ALL-UNNAMED` module issue resolved.

### Phase 7 (continued)

- [x] ~~Item 33: Secrets Vault~~ ✅
- [x] ~~Item 33b: Chat UI password field + Manager integration~~ ✅
- [x] ~~Item 34: Immutable Audit Ledger~~ ✅
- [x] ~~Item 34b: Tenant Quota Stub~~ ✅

### Phase 8a: MCP Servers ✅ (commits `1553b40e`, `b9a9c5e1`, latest)

- [x] `quarkus-mcp-server-http` v1.10.2 dependency
- [x] `McpConversationTools` — 7 tools (list_agents, list_agent_configs, create_conversation, talk_to_agent, **chat_with_agent**, read_conversation, read_conversation_log)
- [x] `McpAdminTools` — 6 tools (deploy_agent, undeploy_agent, get_deployment_status, list_packages, create_agent, delete_agent)
- [x] `McpSetupTools` — **setup_agent** composite tool: creates full agent (behavior → langchain → output → package → agent → deploy) in a single MCP call
- [x] `McpSetupTools` — **create_api_agent** composite tool: OpenAPI spec → grouped HttpCalls → behavior → langchain → package → agent → deploy
- [x] `McpApiToolBuilder` — OpenAPI 3.0/3.1 parser: tag-based grouping, endpoint filtering, path/query param → Thymeleaf conversion, body templates, deprecated op skipping
- [x] `McpToolUtils` — shared helpers, RFC 8259 JSON escaping, `extractIdFromLocation`/`extractVersionFromLocation`
- [x] `swagger-parser` v2.1.39 dependency
- [x] Default model: `anthropic`/`claude-sonnet-4-6` (was `openai`/`gpt-4o`)
- [x] 75 unit tests (18 McpToolUtils + 16 Conversation + 16 Admin + 17 Setup + 19 ApiToolBuilder) — Code review: 5 fixes applied
- [x] `CreateApiAgentIT` — 10 ordered REST API tests (standalone, not @QuarkusTest due to MCP extension limitation)
- [x] Streamable HTTP transport at `/mcp`
- [x] `docs/mcp-server.md` with Claude Desktop config + setup tools reference
- [x] Code review fixes: `@Blocking`, error callback, typed params, `resolveParams()` extraction, static Pattern, prompt enrichment ArgumentCaptor test
- [x] **MCP Improvements** (2026-03-18):
  - AI-agent-friendly responses: `buildConversationResponse()` extracts `agentResponse`, `quickReplies`, `actions`, `conversationState` as top-level fields
  - Ollama/jlama support: all 7 providers in `setup_agent`, `baseUrl` param, `isLocalLlmProvider()` skips apiKey validation, provider-specific param mapping
  - Deploy verification: `deployAndWait()` polls deployment status for 5s, reports actual status + warning on failure
  - `OllamaLanguageModelBuilder` — added `baseUrl` support to both `build()` and `buildStreaming()`
  - `docker-compose.yml` — added `host.docker.internal:host-gateway` for Docker-hosted Ollama
  - 38 MCP tests pass (16 McpConversationToolsTest + 22 McpSetupToolsTest)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpSetupTools.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java`
- `src/test/java/ai/labs/eddi/engine/mcp/McpSetupToolsTest.java` (+ 3 other test files)
- `src/test/java/ai/labs/eddi/integration/CreateApiAgentIT.java`
- `docs/mcp-server.md`

### Phase 8a (continued): MCP Code Review, Resource Tools, Docs MCP Resources ✅

- [x] **Code review fixes**:
  - Fixed `get_agent` N+1 query → direct `readDescriptor(id, ver)` call
  - Fixed `deployAgent` response → `deployed` consistently boolean (was `"pending"` string for 202)
  - Fixed `ConversationState` import → moved to `engine.model`
  - Fixed `McpToolFilter` missing imports (`ToolManager.ToolInfo`, `FilterContext`)
  - Deduplicated `getRestStore()` → shared in `McpToolUtils` (3 classes now delegate)
- [x] **Simplified `update_agent`** — now only updates name/description via `patchDescriptor()` + optional redeploy. Removed package add/remove business logic (wrong abstraction level).
- [x] **New tools**: `read_package`, `read_resource` (thin REST delegates for inspecting agent internals)
- [x] **`McpToolFilter`** whitelist updated: 18 → 20 tools
- [x] **`McpDocResources.java`** — NEW: exposes docs as MCP resources (`eddi://docs/index`, `eddi://docs/{name}`)
- [x] **`Dockerfile.jvm`** — COPY docs into container, `eddi.docs.path=/deployments/docs`
- [x] **`mcp-server.md`** — rewritten: all 20 tools (snake_case), tool filtering, auth, sentiment monitoring, MCP resources sections
- [x] 116 MCP tests pass (0 failures)

**Key files (new):**

- `src/main/java/ai/labs/eddi/engine/mcp/McpDocResources.java`

**Key files (modified):**

- `McpConversationTools.java` — N+1 fix, ConversationState import, removed unused import
- `McpAdminTools.java` — simplified `update_agent`, added `read_package` + `read_resource`
- `McpToolUtils.java` — shared `getRestStore()`
- `McpSetupTools.java` — delegate `getRestStore()`, removed unused import
- `McpToolFilter.java` — whitelist 18→20, added missing imports
- `Dockerfile.jvm` — docs COPY + `eddi.docs.path`
- `docs/mcp-server.md` — rewritten

### Phase 8: MCP + RAG Foundation

- [x] ~~Phase 8a: MCP Servers~~ ✅
- [x] ~~Phase 8a.2: MCP Resource CRUD + Batch Cascade~~ ✅
- [x] ~~Phase 8a.3: Agent Discovery & Managed Conversations~~ ✅
- [x] ~~Phase 8b: MCP Client + RAG Foundation~~ ✅
- [x] ~~Phase 8c: RAG Foundation~~ ✅

### Phase 8c: RAG Foundation ✅ (commit `f10c0611`)

Config-driven knowledge base retrieval integrated into the LLM pipeline. Knowledge bases are first-class versioned resources with full CRUD, embedding model caching, vector store isolation, and automatic context injection.

| Component | Files | Description |
|---|---|---|
| **Resource Stack** | `RagConfiguration`, `IRagStore`, `RagStore`, `IRestRagStore`, `RestRagStore` | Full CRUD at `/ragstore/rags/`, MongoDB collection `rags` |
| **LLM Config** | `LlmConfiguration.java` | `knowledgeBases`, `enableWorkflowRag`, `ragDefaults`, `httpCallRag` |
| **Embedding Factories** | `EmbeddingModelFactory`, `EmbeddingStoreFactory` | Cached model/store creation with secret resolution |
| **Runtime** | `RagContextProvider` | Workflow discovery, retrieval, formatting, audit trace |
| **Ingestion** | `RagIngestionService` | Virtual-thread async ingestion with Caffeine status tracking |
| **Integration** | `LlmTask.java` | RAG context injection before LLM message building |

**Tests:** `RestRagStoreTest`, `EmbeddingModelFactoryTest`, `EmbeddingStoreFactoryTest`, `RagContextProviderTest`, updated `LlmTaskTest`.

**Documentation:** `docs/rag.md`

**Sub-phases completed:**
- ✅ Phase 8c-0: httpCall-based RAG — `LlmTask.executeHttpCallRag()`, audit trace, graceful failure
- ✅ Phase 8c-β: pgvector persistent vector store — `EmbeddingStoreFactory.buildPgVector()`, `SecretResolver`, `sanitizeTableName()`
- ✅ Phase 8c-γ: RAG provider expansion — 7 embedding models (openai, azure-openai, ollama, mistral, bedrock, cohere, vertex) + 5 vector stores (in-memory, pgvector, mongodb-atlas, elasticsearch, qdrant) + REST ingestion endpoint (`POST /{id}/ingest`, `GET /{id}/ingestion/{ingestionId}/status`)
- Remaining: Manager UI RAG editor (Phase 13)

### Phase 8a.2: MCP Resource CRUD + Batch Cascade ✅

5 new MCP tools for full resource lifecycle management:

| Tool                   | Description                                                                                        |
| ---------------------- | -------------------------------------------------------------------------------------------------- |
| `update_resource`      | Update any resource config (behavior, langchain, etc.) → returns new version URI                   |
| `create_resource`      | Create a new resource → returns ID + URI                                                           |
| `delete_resource`      | Delete a resource (soft or permanent)                                                              |
| `apply_agent_changes`  | Batch-cascade multiple resource URI changes through package → agent in ONE pass, optional redeploy |
| `list_agent_resources` | Walk agent → packages → extensions to get a complete resource inventory in one call                |

**Key files:**

- `McpAdminTools.java` — 5 new tools, `getRestStore()` shared helper
- `McpToolFilter.java` — whitelist 20 → 27
- `McpAdminToolsCrudTest.java` — 22 new tests
- `docs/mcp-server.md` — updated

### Phase 8a.3: Agent Discovery & Managed Conversations ✅ (commit `4ed7bce8`)

6 new MCP tools for agent discovery and intent-based managed chat:

| Tool                   | Description                                                                         |
| ---------------------- | ----------------------------------------------------------------------------------- |
| `discover_agents`      | Enriched agent list with intent cross-referencing from AgentTriggerConfiguration    |
| `chat_managed`         | Intent-based single-window conversations (one conv per intent+userId, auto-creates) |
| `list_agent_triggers`  | List all intent→agent mappings                                                      |
| `create_agent_trigger` | Create intent-to-agent trigger                                                      |
| `update_agent_trigger` | Update existing trigger                                                             |
| `delete_agent_trigger` | Remove trigger by intent                                                            |

**Key design:** Two-tier conversation model — low-level (`create_conversation` + `talk_to_agent`) for custom apps, managed (`chat_managed`) for single-window chat.

**Key files:**

- `McpConversationTools.java` — `discover_agents` + `chat_managed` + `getOrCreateManagedConversation` helper
- `McpAdminTools.java` — 4 trigger CRUD tools
- `McpToolFilter.java` — whitelist 27 → 33
- `McpConversationToolsTest.java` — 7 new tests
- `McpAdminToolsCrudTest.java` — 7 new tests
- `docs/mcp-server.md` — comprehensive Tool Reference section (parameter tables, response schemas, end-to-end examples)

**Live test results:** All 6 tools tested against running backend — discover_agents (80 agents, filter works), triggers CRUD (create/update/delete), chat_managed (conversation auto-created, reused on follow-up).

### Phase 8b: MCP Client + Quarkus 3.32.4 ✅

Agents can now consume external MCP servers as tool providers. Upgraded Quarkus to 3.32.4.

| Component                  | Change                                                                             |
| -------------------------- | ---------------------------------------------------------------------------------- |
| **POM**                    | Added `langchain4j-mcp` 1.12.2-beta22, upgraded Quarkus 3.30.8 → 3.32.4            |
| **McpToolProviderManager** | NEW — `StreamableHttpMcpTransport`, connection caching, vault-ref, graceful errors |
| **AgentOrchestrator**      | MCP tools merged into tool-calling loop with budget/rate-limiting                  |
| **LangChainConfiguration** | Added `mcpServers` + `McpServerConfig` (url, name, transport, apiKey, timeoutMs)   |
| **McpSetupTools**          | `mcpServers` param on `setup_agent` tool                                           |

**Test results:** 1045 tests, 0 failures. `McpToolProviderManagerTest` (8 tests), updated `AgentOrchestratorTest` + `LangchainTaskTest` + `McpSetupToolsTest` (21 calls). Also fixed pre-existing `AgentFactoryTest` failure.

**Key files:**

- `McpToolProviderManager.java` (NEW)
- `McpToolProviderManagerTest.java` (NEW)
- `AgentOrchestrator.java`
- `LangChainConfiguration.java` (`McpServerConfig`)
- `McpSetupTools.java`
- `pom.xml`

### Httpcall Auto-Discovery from Workflow ✅

Agents now auto-discover `httpcall` configurations from their workflow and expose them as LLM tools at runtime. No version-coupled `tools[]` array needed in LLM configuration.

**Key design:** `AgentOrchestrator.discoverHttpCallTools()` traverses agent → workflow → httpcall steps at execution time. Uses direct `IRestAgentStore`/`IRestWorkflowStore` reads (not `ResourceClientLibrary`, which lacks agent/workflow URI mappings).

**Config:** `enableHttpCallTools` defaults to `true` in `LlmConfiguration.Task`. Not a standalone agent-mode trigger — only activates when agent mode is already triggered by tools, builtInTools, or mcpServers.

**Key files:**

- `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java` — `discoverHttpCallTools()`, `HttpCallToolsResult`
- `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java` — injects stores into `AgentOrchestrator`
- `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` — `enableHttpCallTools` field
- `src/test/java/ai/labs/eddi/modules/llm/impl/AgentOrchestratorTest.java` — 7 new tests (30 total)

See `AGENTS.md` for the full roadmap (Phases 7–14b) and `docs/project-philosophy.md` for the 9 architectural pillars.

### LLM Provider Expansion: 7 → 12 Providers ✅

Expanded EDDI from 7 to 12 model providers for enterprise completeness. 4 new `ILanguageModelBuilder` implementations plus OpenAI `baseUrl` for compatible services.

| Provider | Builder | Auth Pattern | Streaming |
|---|---|---|---|
| **Mistral AI** | `MistralAiLanguageModelBuilder` | `apiKey` | ✅ |
| **Azure OpenAI** | `AzureOpenAiLanguageModelBuilder` | `apiKey` + `endpoint` + `deploymentName` | ✅ |
| **Amazon Bedrock** | `BedrockLanguageModelBuilder` | AWS credential chain (no apiKey) | ✅ |
| **Oracle GenAI** | `OracleGenAiLanguageModelBuilder` | OCI config (`~/.oci/config`) | ❌ sync-only |

**Downstream integration:**
- `AgentSetupService`: `isLocalLlmProvider` (bedrock, oracle-genai bypass apiKey), `supportsResponseFormat` (mistral, azure-openai), `createLlmConfig` (provider-specific param mapping)
- `McpSetupTools`: `@ToolArg` docs list all 11 provider types
- `LlmModule`: 4 new type keys registered

**API corrections (code review):**
- Bedrock: `temperature()`/`maxTokens()` don't exist on builder → uses `defaultRequestParameters(BedrockChatRequestParameters)`
- Oracle GenAI: package `dev.langchain4j.community.model.oracle.oci.genai` (not `.model.oci.genai`), uses `modelName` (not `modelId`)

**Key files:**

- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/MistralAiLanguageModelBuilder.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/AzureOpenAiLanguageModelBuilder.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/BedrockLanguageModelBuilder.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/OracleGenAiLanguageModelBuilder.java` (NEW)
- `src/main/java/ai/labs/eddi/modules/llm/bootstrap/LlmModule.java`
- `src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java`
- `src/main/java/ai/labs/eddi/engine/mcp/McpSetupTools.java`
- `src/test/java/ai/labs/eddi/engine/mcp/McpSetupToolsTest.java` — 11 new test cases
- `docs/langchain.md` — all 12 providers documented with config examples
- `docs/changelog.md` — full entry with design decisions

### Platform Remediation: Thread Safety, A2A Hardening, Code Quality & Audit DLQ ✅

Critical architectural fixes from thorough v6 feature review. 12 files modified, 1 new file.

| Change | Details |
|---|---|
| Virtual threads | `CascadingModelExecutor` + `GroupConversationService` — replaced `CachedThreadPool` |
| A2A security | `@Authenticated` POST, circuit breaker, 1MB limit, JSON-RPC validation |
| Code quality | `WorkflowTraversal` DRY, `JsonStringEncoder` escaping, `maxToolIterations`, `isRetryableError` HTTP status codes |
| Observability | Audit dead-letter: NATS JetStream first (`eddi.deadletter.audit`), file fallback (configurable `eddi.audit.dead-letter-path`) |
| Tests | 1291 total (1289 pass, 2 pre-existing isolation failures) |

### One-Command Install & Onboarding Wizard ✅

New users can set up EDDI with `curl ... | bash` (Linux/macOS/WSL) or `iwr ... | iex` (Windows). Interactive 5-step wizard.

| File                               | Description                                                                                                       |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `install.sh`                       | Bash installer: platform-aware Docker install, DB/Security/Auth/Monitoring/Ports wizard, `eddi` CLI wrapper, Agent Father import |
| `install.ps1`                      | PowerShell installer: `winget` Docker Desktop auto-install, same wizard flow                                      |
| `docker-compose.postgres-only.yml` | PostgreSQL-only compose (no MongoDB)                                                                              |
| `docker-compose.auth.yml`          | Keycloak overlay                                                                                                  |
| `docker-compose.monitoring.yml`    | Grafana + Prometheus overlay (placeholder)                                                                        |
| `README.md`                        | Quick Start section                                                                                               |
| `docs/getting-started.md`          | Option 0 — one-command install                                                                                    |

**Vault security:** Step 2/5 generates a unique, cryptographically random vault master key (or accepts a custom passphrase). The key is stored in `~/.eddi/.env` (chmod 600 / ACL-restricted) and passed to Docker Compose via `--env-file`. All compose files use `${EDDI_VAULT_MASTER_KEY:-}` variable substitution — no hardcoded keys.

**Edge cases handled:** Idempotent re-runs (preserves existing vault key), CTRL+C cleanup, piped stdin (`curl|bash` auto-generates key), disk space warning, input validation, macOS `wc -l` whitespace, Docker auto-install (Linux/Windows), macOS-compatible `sed` (no `grep -oP`).

### Phase 11a: Persistent User Memory ✅

Cross-conversation, agent-scoped fact retention with LLM tools, REST API, MCP management, and background consolidation.

| Component | Key Files |
|---|---|
| **Data Model** | `UserMemoryEntry.java`, `Property.java` (Visibility enum) |
| **Unified Store** | `IUserMemoryStore.java`, `MongoUserMemoryStore.java`, `PostgresUserMemoryStore.java` |
| **Agent Config** | `AgentConfiguration.java` (UserMemoryConfig, Guardrails, DreamConfig) |
| **LLM Tool** | `UserMemoryTool.java` (`@Vetoed`, 4 tools: remember/recall/search/forget) |
| **REST API** | `IRestUserMemoryStore.java`, `RestUserMemoryStore.java` (9 endpoints + validation) |
| **MCP Tools** | `McpMemoryTools.java` (8 tools, GDPR-compliant) |
| **Dream** | `DreamService.java` (stale pruning, contradiction detection, Micrometer metrics) |
| **Integration** | `AgentOrchestrator.java` (groupId extraction), `GroupConversationService.java` (groupId context) |
| **Docs** | `docs/user-memory.md`, `docs/changelog.md`, `docs/SUMMARY.md` |
| **Tests** | 45 new: `UserMemoryToolTest` (16), `DreamServiceTest` (9), `UserMemoryEntryTest` (22), `RestUserMemoryStoreTest` (15) |

**Total tests:** 1406 (all pass). **Last commit:** Phase 11a code review fixes.

### Properties Consolidation: `IPropertiesStore` → `IUserMemoryStore` ✅

Eliminated the legacy `properties` collection and unified all user-scoped persistent storage into `usermemories`. Removes a redundant storage layer.

| Change | Details |
|---|---|
| **Deleted** | `IPropertiesStore.java`, `PropertiesStore.java` (Mongo), `PostgresPropertiesStore.java` |
| **Renamed** | `enableUserMemory` → `enableMemoryTools` (gates advanced tools only, not basic persistence) |
| **Conversation.java** | Consolidated dual load/store into single unified flow via `IUserMemoryStore` |
| **REST preserved** | `RestPropertiesStore` delegates to `IUserMemoryStore` flat property methods |
| **Migration** | `PropertiesMigrationService` (MongoDB startup migration, `@IfBuildProfile("!postgres")`) |

**Code review fixes (commit `7997da96`):**
- Bug: Removed dead `DELETE FROM properties` in Postgres GDPR delete
- Bug: Guarded `PropertiesMigrationService` with `@IfBuildProfile("!postgres")` (was `@DefaultBean`)
- Fix: Added `List` type handling to `Conversation.entryToProperty()` 
- Fix: `IPropertiesHandler` javadoc consistency
- Cleanup: Moved redundant `set(FIELD_VISIBILITY)` to `setOnInsert` in MongoDB merge

**Docs updated:** `docs/user-memory.md`, `docs/changelog.md`

### Phase 11b: Token-Aware Conversation Window (Strategy 1) ✅

Config-driven token-budget windowing with anchored opening steps, replacing fixed step-count limits for LLM context management.

| Component | Key Files |
|---|---|
| **Config** | `LlmConfiguration.java` (`maxContextTokens`, `anchorFirstSteps` fields) |
| **Token Counting** | `TokenCounterFactory.java` (OpenAI tiktoken + approximate chars/4 fallback) |
| **Window Builder** | `ConversationHistoryBuilder.java` (`buildTokenAwareMessages()` — anchor + gap marker + recent) |
| **Integration** | `LlmTask.java` (branch: token-aware vs step-count based on config) |
| **Docs** | `docs/langchain.md` (new "Conversation Window Management" section) |
| **Tests** | `TokenCounterFactoryTest.java` (20 tests), `ConversationHistoryBuilderTest.java` (+16 token-aware tests), `LlmTaskTest.java` (updated constructor) |

**Design:** `maxContextTokens=-1` (default) preserves existing step-count behavior. When set > 0, first N steps are anchored, remaining budget filled from most recent backward, gap marker inserted for omitted messages.

**Code review fixes:** Model name resolution (fallback chain: modelName→model→modelId→deploymentName), anchor budget overflow warning + `Math.max(0, ...)`, gap marker shows count not indices, removed dead code, instance-level cache. 9 edge case tests added (empty conversation, anchor clamping, budget overflow, exact boundary, etc.).

**Total tests:** 1459 (all pass). **Last commit:** Phase 11b code review fixes.

### Phase 11b-S2: Rolling Summary + Conversation Recall Tool ✅

Config-driven rolling summary that compresses older conversation turns into an incremental summary, injected as a context prefix. Includes a built-in LLM tool for drill-back into summarized turns.

| Component | Key Files |
|---|---|
| **SummarizationService** | `SummarizationService.java` — stateless LLM summarization via ChatModelRegistry |
| **ConversationSummarizer** | `ConversationSummarizer.java` — incremental engine, self-correcting, conversation-property storage |
| **ConversationRecallTool** | `ConversationRecallTool.java` — built-in `@Vetoed` tool for LLM drill-back (natural language range parsing) |
| **Config** | `LlmConfiguration.java` — `ConversationSummaryConfig` inner class (provider, model, window, recall limit) |
| **History Builder** | `ConversationHistoryBuilder.java` — summary-aware `buildMessages()` / `buildTokenAwareMessages()` |
| **Orchestrator** | `AgentOrchestrator.java` — auto-registers ConversationRecallTool when summary is active |
| **LlmTask** | `LlmTask.java` — summary prefix injection before history build + post-response summarization trigger |
| **Tests** | `SummarizationServiceTest` (5), `ConversationSummarizerTest` (11), `ConversationRecallToolTest` (12) |

**Design:** Summaries stored as conversation-scoped properties (`conversation:running_summary`, `conversation:summary_through_step`) for O(1) retrieval. Synchronous trigger after LLM response. Self-correcting: if turn N fails, turn N+1 catches up. Defaults: `claude-sonnet-4-6` provider, `20` max recall turns, `5` recent window steps.

**Total tests:** 1471 (all pass).

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `docs/project-philosophy.md` for architectural pillars
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
