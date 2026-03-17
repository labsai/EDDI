# EDDI v6.0 — Current Status

> **Last updated:** 2026-03-17 (Audit Ledger complete)
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

### Phase 6A: MongoDB Sync Driver Migration ✅

- [x] Replaced `mongodb-driver-reactivestreams` + RxJava3 with `mongodb-driver-sync`
- [x] Migrated 13 files from `Observable.fromPublisher(...).blocking*()` to direct sync API calls
- [x] Removed `io.reactivex.rxjava3` dependency
- [x] All tests pass

### Phase 6B: PostgreSQL Integration Test Parity ✅ (commit `0eda70d9`, `e77b6f23`)

- [x] Created `PostgresIntegrationTestProfile` with `eddi.datastore.type=postgres` + Testcontainers DevServices
- [x] Created 8 PostgreSQL IT subclasses — all 48/48 tests pass, full parity with MongoDB:
  - `PostgresBehaviorCrudIT` (4/4), `PostgresOutputCrudIT` (5/5), `PostgresDictionaryCrudIT` (5/5)
  - `PostgresBotEngineIT` (11/11), `PostgresBotDeploymentComponentIT` (4/4)
  - `PostgresConversationServiceComponentIT` (7/7), `PostgresApiContractIT` (10/10)
  - `PostgresBotUseCaseIT` (2/2)
- [x] Fixed `RestUtilities.isValidId()` — added `-` for UUID dashes (`e5c68a0b`)
- [x] Fixed `DocumentDescriptorFilter` — added `isResourceIdValid()` guard on PUT/PATCH (`e5c68a0b`)
- [x] Added `#uuidUtils.extractId()` + `extractVersion()` to `UUIDWrapper` — DB-agnostic URI parsing (`7fb79bfa`)
- [x] Updated all 7 Bot Father httpcalls JSONs (70 replacements) + re-zipped `Bot+Father-4.0.0.zip`
- [x] Added 12 unit tests in `UUIDWrapperTest`
- [x] Documented `#uuidUtils` in `docs/output-templating.md`
- [x] Fixed `PostgresIntegrationTestProfile` — added `quarkus.http.port=8082` for `RestInterfaceFactory` (`0eda70d9`)
- [x] Fixed `PostgresApiContractIT` — overrode `readNonExistent_returns404` with UUID-formatted ID (`0eda70d9`)
- [x] Fixed `BotUseCaseIT.useBotManagement` — stale trigger cleanup + status assertions (`e77b6f23`)
- [x] All 96 integration tests pass (48 MongoDB + 48 PostgreSQL)

**Key files:**

- `src/main/java/ai/labs/eddi/utils/RestUtilities.java` — `isValidId()` UUID fix
- `src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java` — `isResourceIdValid` guard
- `src/main/java/ai/labs/eddi/modules/templating/impl/dialects/uuid/UUIDWrapper.java` — `extractId/extractVersion`
- `src/main/resources/initial-bots/Bot+Father-4.0.0.zip` — re-zipped with updated httpcalls
- `src/test/java/ai/labs/eddi/integration/PostgresIntegrationTestProfile.java` — PG test profile
- `src/test/java/ai/labs/eddi/integration/postgres/` — 8 PG IT subclasses
- `src/test/java/ai/labs/eddi/integration/BotUseCaseIT.java` — stale trigger cleanup
- `docs/output-templating.md` — `#uuidUtils` documentation

### Bot Lifecycle API: Cascade Delete + Orphan Detection ✅

- [x] Added `?cascade=true` to `DELETE /botstore/bots/{id}` and `DELETE /packagestore/packages/{id}`
- [x] Cascade walks bot → packages → extensions and deletes all children
- [x] **Shared-resource safety**: checks references before deleting — skips packages used by other bots, extensions used by other packages
- [x] Added `IResourceClientLibrary.deleteResource(URI, permanent)` for cascade use
- [x] New admin endpoint: `GET/DELETE /administration/orphans`
  - GET: scans all 8 store types for unreferenced resources (dry-run report)
  - DELETE: permanently purges all orphans
  - Algorithm: enumerate bots → packages → extensions → compare against all descriptors per store
- [x] DTOs: `OrphanInfo`, `OrphanReport`; interfaces: `IRestOrphanAdmin`, `IRestBotStore`, `IRestPackageStore` updated
- [x] Enriched OpenAPI annotations with `@Operation`, `@Parameter`, `@APIResponse` descriptions
- [x] Documentation: deletion + orphan sections in `docs/deployment-management-of-chatbots.md`
- [x] Tests: `RestBotStoreTest` (6/6), `RestPackageStoreTest` (7/7), `RestOrphanAdminTest` (4/4) — 17 new tests total
- [x] Full test suite passes

**Key files (new):**

- `src/main/java/ai/labs/eddi/configs/admin/model/OrphanInfo.java`
- `src/main/java/ai/labs/eddi/configs/admin/model/OrphanReport.java`
- `src/main/java/ai/labs/eddi/configs/admin/IRestOrphanAdmin.java`
- `src/main/java/ai/labs/eddi/configs/admin/rest/RestOrphanAdmin.java`
- `src/test/java/ai/labs/eddi/configs/admin/rest/RestOrphanAdminTest.java`
- `src/test/java/ai/labs/eddi/configs/bots/rest/RestBotStoreTest.java`
- `src/test/java/ai/labs/eddi/configs/packages/rest/RestPackageStoreTest.java`

**Key files (modified):**

- `IRestBotStore.java`, `RestBotStore.java` — cascade + shared-resource check
- `IRestPackageStore.java`, `RestPackageStore.java` — cascade + shared-resource check
- `IResourceClientLibrary.java`, `ResourceClientLibrary.java` — `deleteResource` method
- `docs/deployment-management-of-chatbots.md` — deletion + orphan docs

### Phase 6C: Infinispan → Caffeine ✅

- [x] Rewrote `CacheFactory.java` — Caffeine builder with inline size configs (replaced `EmbeddedCacheManager`)
- [x] Rewrote `CacheImpl.java` — wraps Caffeine `Cache<K,V>` (replaced Infinispan `Cache`)
- [x] Removed 4 Infinispan dependencies + `infinispan.version` property from POM
- [x] Caffeine provided transitively by `quarkus-cache` (already in POM)
- [x] Deleted `infinispan-embedded.xml` config
- [x] Cleaned `application.properties` (removed 2 Infinispan config lines)
- [x] Updated `ToolCacheService` Javadoc + log message
- [x] Verified: multi-instance bot deployment does NOT use Infinispan (uses DB-backed `IDeploymentStore` + `@Scheduled` polling)
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
- [x] Created `IRestAuditStore` / `RestAuditStore` — read-only REST API (`/auditstore/{conversationId}`, `/auditstore/bot/{botId}`)
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

## Next Up

### Quick Wins

- [x] ~~**Phase 6E: quarkus-langchain4j → langchain4j Core**~~ ✅
- [x] ~~**Phase 6D: Lombok Removal**~~ ✅
- [ ] **Quarkus 3.33 LTS Upgrade** — waiting for GA (March 25, 2026). 3.32.3 has Java 25 `ALL-UNNAMED` module issue.

### Phase 7 (continued)

- [x] ~~Item 33: Secrets Vault~~ ✅
- [ ] Item 33b: Chat UI password field + Manager integration (remaining vault work)
- [x] ~~Item 34: Immutable Audit Ledger~~ ✅
- [ ] Item 34b: Tenant Quota Stub — per-tenant rate limits, usage metering (2 SP)

### Phase 8: MCP + RAG Foundation

- [ ] Phase 8a: MCP Servers — bot conversations, admin API (8 SP)
- [ ] Phase 8b: MCP Client + RAG Lifecycle Task + docs MCP (10 SP)

See `AGENTS.md` for the full roadmap (Phases 7–14b) and `docs/project-philosophy.md` for the 7 architectural pillars.

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `docs/project-philosophy.md` for architectural pillars
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
