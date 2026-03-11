# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during implementation. Updated as work progresses for easy reference and review.

---

## How to Read This Document

Each entry follows this format:

- **Date** — What changed and why
- **Repo** — Which repository was modified
- **Decision** — Key design decisions and their reasoning
- **Files** — Links to modified files

---

## Planning Phase (2026-03-05)

### Audit Completed — Implementation Plan Finalized

**Repos involved:** All 5 (EDDI, EDDI-Manager, eddi-chat-ui, eddi-website, EDDI-integration-tests)

**Key decisions made:**

| #   | Decision                                                   | Reasoning                                                                                | Appendix |
| --- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------- | -------- |
| 1   | UI framework: **React + Vite + shadcn/ui + Tailwind CSS**  | AI-friendly (components are plain files), no dependency rot, accessible (Radix), fast DX | J.1a     |
| 2   | Keep Chat UI **standalone** + extract **shared component** | EDDI has a dedicated single-bot chat endpoint; standalone deployment is needed           | M.1      |
| 3   | Website: **Astro** on GitHub Pages                         | Static output, built-in i18n routing, zero JS by default, Tailwind integration           | L        |
| 4   | **Skip API versioning**                                    | Only clients are Manager + Chat UI, both first-party controlled                          | M.7      |
| 5   | **Remove internal snapshot tests**                         | Never production-ready; integration tests provide sufficient coverage                    | K.1      |
| 6   | **Trunk-based branching**                                  | Short-lived feature branches, squash merge, clean main history                           | N.1      |
| 7   | **Mobile-first responsive** is Phase 1                     | Core requirement, not afterthought; Tailwind breakpoints make this natural               | J.4      |

**Biggest gap discovered:** No CI/CD anywhere — all builds, tests, and deployments are manual.

**Strongest existing areas:** Security (SSRF, rate limiting, cost tracking), Monitoring (30+ Prometheus metrics, Grafana dashboard), Documentation (40 markdown files published via docs.labs.ai).

---

## Implementation Log

### 2026-03-11 — Phase 6 Item #32: Full Store Migration (5 SP)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #32 (5 SP)

**What changed:**

Completed the full migration of ALL remaining stores from MongoDB-only to DB-agnostic, eliminating the hybrid approach. Every datastore now transparently supports MongoDB or PostgreSQL based on `eddi.datastore.type` configuration.

| Component | Change |
|---|---|
| `IResourceStorage` | Added `findResourceIdsContaining()`, `findHistoryResourceIdsContaining()`, `findResources()` query methods |
| `MongoResourceStorage` | Implements new queries with MongoDB `$in`, regex, pagination |
| `PostgresResourceStorage` | Implements new queries with JSONB `@>`, `~`, SQL pagination |
| `BotStore` | Migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory` |
| `PackageStore` | Same migration, removed inner MongoDB utility classes |
| `IDeploymentStorage` | New DB-agnostic interface for deployment persistence |
| `MongoDeploymentStorage` | New `@DefaultBean` — extracted MongoDB logic from `DeploymentStore` |
| `PostgresDeploymentStorage` | New `@LookupIfProperty` — JDBC with `INSERT...ON CONFLICT`, dedicated `deployments` table |
| `DeploymentStore` | Refactored to thin delegate to `IDeploymentStorage` |
| `DescriptorStore` (datastore pkg) | New DB-agnostic descriptor store using `IResourceStorageFactory` + `findResources()` |
| `DocumentDescriptorStore` | Injects `IResourceStorageFactory` instead of `MongoDatabase` |
| `ConversationDescriptorStore` | Same — `updateTimeStamp()` reads/modifies/saves via abstraction |
| `TestCaseDescriptorStore` | Same migration |
| `PostgresConversationMemoryStore` | New — JSONB with indexed columns (bot_id, bot_version, conversation_state) |

**Design decisions:**

| # | Decision | Reasoning |
|---|---|---|
| 1 | Add query methods to `IResourceStorage` | `BotStore`/`PackageStore` used custom MongoDB containment queries; abstracting these makes them DB-agnostic |
| 2 | `IDeploymentStorage` as separate interface | DeploymentStore has its own data model (not versioned resources) — doesn't fit `IResourceStorage` |
| 3 | `PostgresConversationMemoryStore` with extracted indexed columns | JSONB for full snapshot data, but bot_id/bot_version/conversation_state extracted as columns for indexed queries |
| 4 | `DescriptorStore` moved to `datastore` package | Was in `datastore.mongo` — breaking the package dependency to make it framework-agnostic |

**Tests:** All 701 tests pass (0 failures, 0 errors, 4 skipped). `mvnw verify` succeeds.

---

### 2026-03-11 — Phase 6 Item #31: PostgreSQL Adapter (8 SP)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #31 (8 SP)

**What changed:**

Implemented a PostgreSQL storage backend as an alternative to MongoDB for EDDI's configuration stores. All 7 "simple" stores now use the factory pattern and automatically work with either MongoDB or PostgreSQL depending on configuration.

| Component | Change |
|---|---|
| `PostgresResourceStorage<T>` | New — JDBC + JSONB implementation of `IResourceStorage<T>` |
| `PostgresResourceStorageFactory` | New — `@LookupIfProperty(eddi.datastore.type=postgres)`, creates PostgresResourceStorage |
| `PostgresHealthCheck` | New — `@Readiness` health check for PostgreSQL connection |
| 7 config stores | Migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory` |
| `pom.xml` | Added `quarkus-jdbc-postgresql`, `quarkus-agroal`, `testcontainers:postgresql` |
| `application.properties` | Added PostgreSQL datasource config (inactive by default) |
| `docker-compose.postgres.yml` | New — PostgreSQL 16 + MongoDB + EDDI for local development |

**Design decisions:**

| # | Decision | Reasoning |
|---|---|---|
| 1 | Single `resources` + `resources_history` tables with JSONB `data` column | Keeps the generic `IResourceStorage` contract intact without per-type schema complexity |
| 2 | Uses `IJsonSerialization` instead of `IDocumentBuilder` | `IDocumentBuilder.toDocument()` returns `org.bson.Document` — MongoDB-specific; `IJsonSerialization` is pure JSON |
| 3 | BotStore/PackageStore stayed on `AbstractMongoResourceStore` initially | Custom containment queries needed SQL equivalents — resolved in Phase 6.32 |
| 4 | ConversationMemoryStore stayed MongoDB initially | Complex aggregation, custom interface — resolved in Phase 6.32 |
| 5 | `@LookupIfProperty` for activation | Same pattern as NATS (`eddi.messaging.type`), no code changes to switch backends |

**Tests:** 15 new (12 PostgresResourceStorageTest, 3 PostgresResourceStorageFactoryTest). All 699 tests pass.

---

### 2026-03-11 — Phase 6 Item #30: Repository Interface Abstraction (DB-Agnostic)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #30 (5 SP)

**What changed:**

Introduced a factory-based abstraction layer so that the datastore can support multiple database backends. The core change is a new `IResourceStorageFactory` interface that replaces direct `MongoDatabase` injection in config stores.

| Component | Change |
|---|---|
| `IResourceStorageFactory` | New interface — single injection point for storage creation |
| `MongoResourceStorageFactory` | `@DefaultBean` — creates `MongoResourceStorage`, exposes `getDatabase()` |
| `AbstractResourceStore<T>` | New DB-agnostic base class (in `datastore` package) |
| `HistorizedResourceStore<T>` | Moved from `datastore.mongo` → `datastore` (zero MongoDB deps) |
| `ModifiableHistorizedResourceStore<T>` | Same move |
| `AbstractMongoResourceStore<T>` | Now extends `AbstractResourceStore`, `@Deprecated` |
| `ConversationMemoryStore` | Added `@DefaultBean` for future override |
| `application.properties` | New `eddi.datastore.type=mongodb` config |

**Design decisions:**
- Factory pattern (vs CDI alternatives) — mirrors the `@LookupIfProperty` pattern used for NATS
- Backward-compatible wrappers in `mongo` package — all 9 config stores continue working unchanged
- `ConversationMemoryStore` gets `@DefaultBean` only — its `IConversationMemoryStore` interface is already well-defined
- `BotStore`/`PackageStore` inner classes with custom queries remain MongoDB-specific for now

**Tests:** 684 total (0 failures, 0 errors, 4 skipped) — 19 new tests added

---

### 2026-03-11 — Phase 5 Item #30: Coordinator Dashboard + Dead-Letter Admin

**Repos:** EDDI + EDDI-Manager
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Item #30

**What changed:**
- **Backend REST API** — `IRestCoordinatorAdmin` + `RestCoordinatorAdmin` under `/administration/coordinator/` with SSE endpoint for live status streaming
- **IConversationCoordinator** — Added default methods for status introspection (type, connection, queue depths, totals) and dead-letter management (list, replay, discard, purge)
- **InMemoryConversationCoordinator** — Added retry logic (3 attempts), in-memory dead-letter queue (`ConcurrentLinkedDeque`), processed/dead-lettered counters, full dead-letter CRUD
- **NatsConversationCoordinator** — Added status methods, dead-letter listing (from JetStream), purge (stream purge), counters
- **DTOs** — `CoordinatorStatus` + `DeadLetterEntry` records in `engine.model`
- **Manager UI** — Coordinator page at `/manage/coordinator` with status cards, active queue visualization, dead-letter admin table (replay/discard/purge)
- **Manager Infrastructure** — API module, TanStack Query hooks with SSE, sidebar nav item (Activity icon under Operations), MSW handlers, i18n (11 locales)

**Tests:**
- Backend: 665 total (22 new — `RestCoordinatorAdminTest` 10 + `InMemoryConversationCoordinatorTest` 12)
- Manager: 176 total (12 new — `coordinator.test.tsx`)

**Design decisions:**
- Dead-letter works for **both** coordinator types (user requirement: not NATS-only)
- SSE poller broadcasts status every 2s (balances responsiveness with overhead)
- In-memory dead-letter uses `ConcurrentLinkedDeque` (bounded only by JVM memory)

---

### 2026-03-11 — Phase 5 Item #29: Async Conversation Processing (Dead-Letter + Metrics + Testcontainers IT)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Item #29

**What changed:**

1. **Dead-letter handling** (`NatsConversationCoordinator.java`) — Tasks that fail are now retried up to `maxRetries` (configurable, default 3). After all retries exhausted, the message is published to a dead-letter JetStream stream (`EDDI_DEAD_LETTERS`) with 30-day `Limits` retention for operator inspection and replay. Payload includes conversationId, error message, and timestamp.
2. **`NatsMetrics` wiring** — coordinator now injects `NatsMetrics` via `Instance<>` (optional CDI). Publish operations record `eddi_nats_publish_count` + `eddi_nats_publish_duration`. Task completions record `eddi_nats_consume_count` + `eddi_nats_consume_duration`. Dead-letter routing increments `eddi_nats_dead_letter_count`.
3. **`RetryableCallable` wrapper** — inner class tracks per-callable retry attempt count, enabling retry-then-dead-letter behavior without extra state maps.
4. **Dead-letter stream creation** — `start()` method now creates/updates both the main conversation stream and the dead-letter stream during NATS initialization.
5. **`application.properties`** — Added `eddi.nats.dead-letter-stream-name=EDDI_DEAD_LETTERS`.
6. **`pom.xml`** — Added `org.testcontainers:testcontainers:2.0.3` and `org.testcontainers:testcontainers-junit-jupiter:2.0.3` (test scope).
7. **Unit tests** — 12 tests in `NatsConversationCoordinatorTest` (was 8): added `shouldRetryTaskBeforeDeadLettering`, `shouldDeadLetterAfterMaxRetries`, `shouldIncrementPublishMetricsOnSubmit`, `shouldIncrementConsumeMetricsOnCompletion`. Existing `shouldProcessNextTaskAfterFailure` updated for retry behavior.
8. **Integration test** (`NatsConversationCoordinatorIT.java`) — 5 Testcontainers tests with real NATS 2.10-alpine: sequential execution, concurrent conversations, dead-letter routing, dead-letter payload verification, connection status.

**Key decisions:**

- **`Instance<NatsMetrics>` over direct injection** — keeps metrics optional and avoids CDI resolution errors when NATS is disabled
- **Per-callable `RetryableCallable` over Map** — simpler lifecycle, no cleanup needed, GC-friendly
- **30-day dead-letter retention** — gives operators ample time for inspection; main stream keeps 24h WorkQueue retention
- **Testcontainers 2.x naming** — `testcontainers-junit-jupiter` (not `junit-jupiter`) per the 2.0 migration guide

**Testing:** ✅ All 643 tests pass (0 failures, 0 errors, 4 skipped). +4 new unit tests vs previous 639.

---

### 2026-03-10 — Phase 5: Event Bus Abstraction + NATS JetStream Adapter

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Items #27-28

**What changed:**

1. **`IEventBus` interface** — abstract event bus with `submitInOrder`, `start`, `shutdown` methods. Decouples conversation ordering from transport.
2. **`IConversationCoordinator` refactored** — now extends `IEventBus`, preserving backward compatibility. All injection sites continue working without changes.
3. **`InMemoryConversationCoordinator`** — renamed from `ConversationCoordinator`, annotated `@DefaultBean` so it's the default when no NATS config exists. Zero behavior change.
4. **`NatsConversationCoordinator`** — JetStream-based implementation, activated via `@LookupIfProperty(eddi.messaging.type=nats)`. Uses NATS for durable ordering while executing callables in-process. Handles NATS publish failures gracefully (falls back to local execution).
5. **`NatsHealthCheck`** — Quarkus `@Readiness` health check at `/q/health/ready`, reports NATS connection status.
6. **`NatsMetrics`** — Micrometer counters/timers: `eddi_nats_publish_count`, `eddi_nats_publish_duration`, `eddi_nats_consume_count`, `eddi_nats_consume_duration`, `eddi_nats_dead_letter_count`.
7. **`pom.xml`** — Added `io.nats:jnats:2.25.2` dependency.
8. **`application.properties`** — Added `eddi.messaging.type=in-memory` (default), `eddi.nats.url`, `eddi.nats.stream-name`, `eddi.nats.max-retries`, `eddi.nats.ack-wait-seconds`.
9. **`docker-compose.nats.yml`** — NATS 2.10-alpine + MongoDB + EDDI for local development.
10. **Fix** — Removed stale `javax.validation.constraints.NotNull` import from `RegularDictionaryConfiguration.java` (pre-existing issue).
11. **Tests** — 8 new `NatsConversationCoordinatorTest` unit tests covering ordering, multi-conversation independence, NATS failure resilience, subject sanitization, and health status.

**Key decisions:**

- **Direct `jnats` over SmallRye Reactive Messaging** — more control over JetStream stream configuration, no extra Quarkus extension overhead
- **`@LookupIfProperty` over CDI `@Alternative`** — cleaner activation, no `beans.xml` needed, Quarkus-idiomatic
- **In-process callable execution** — NATS serves as distributed ordering primitive now; full message serialization for cross-instance consumption is a future enhancement
- **Subject-per-conversation** — `eddi.conversation.<sanitizedId>` ensures per-conversation ordering without shared queue contention
- **WorkQueue retention** — messages auto-deleted after consumption, 24h TTL, file-based storage

**Testing:** ✅ All 639 tests pass (0 failures, 0 errors, 4 skipped)
**Commit:** `e220f4c0`

---

### 2026-03-09 — Backend API Consistency Fixes (N.7)

**Repo:** EDDI + EDDI-Manager
**Branch:** `feature/version-6.0.0`
**Phase:** N.7 (Backend fixes discovered during Phase 4.3 integration testing)

**What changed:**

1. **N.7.1 — Duplicate POST status code**: Verified `RestVersionInfo.create()` returns 201. The 200 observed in Manager tests was caused by Vite dev proxy stripping 201→200. No backend change needed.
2. **N.7.2 — DELETE `?permanent=true`**: Added optional `?permanent=true` query parameter to all 8 store DELETE endpoints. Soft delete remains default. When `permanent=true`, calls `resourceStore.deleteAllPermanently(id)`.
   - Modified: `RestVersionInfo.java`, all 8 `IRest*Store` interfaces and `Rest*Store` implementations
3. **N.7.3 — Deployment status JSON** (**BREAKING**): `GET /administration/{env}/deploymentstatus/{botId}` now returns `{"status":"READY"}` (JSON) instead of plain text `READY`. Use `?format=text` for backward compatibility (deprecated).
   - Modified: `IRestBotAdministration.java`, `RestBotAdministration.java`, `TestCaseRuntime.java`
   - Tests: `BotDeploymentComponentIT`, `BaseIntegrationIT`, `BotUseCaseIT`, `BotEngineIT`
   - Manager: `integration-helpers.ts`, `deployment.integration.spec.ts`
4. **N.7.4 — Health endpoint**: Deferred — Quarkus dev-mode issue, `/q/health/live` workaround sufficient.

**Key decisions:**

- **`?format` query param over Accept header** — simpler for curl/debugging, avoids Content-Negotiation complexity
- **`?permanent=false` default** — backward compatible, no existing client behavior changes

**Testing:** Maven `compile -q` passes (exit 0). Manager TypeScript compiles clean. Full integration tests pending.

---

### 2026-03-07 — Manager UI: Bot Editor (Version Picker, Env Badges, Duplicate)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #15

**What changed:**

1. **Bot API** (`bots.ts`) — Added `getBotDescriptorsWithVersions()` for fetching all versions of a bot, `getDeploymentStatuses()` for fetching deployment status across unrestricted/restricted/test environments simultaneously, plus `ENVIRONMENTS` and `Environment` type exports
2. **Bot hooks** (`use-bots.ts`) — Added `useBotVersions` (version picker data with sort), `useUpdateBot` (save mutation), `useDeploymentStatuses` (multi-env polling)
3. **Bot Detail page** (`bot-detail.tsx`) — Major rewrite from read-only page to full editor:
   - **Version picker** with relative timestamps (replaces hardcoded v1)
   - **Environment status badges** — 3-column grid showing unrestricted/restricted/test deploy states with per-env deploy/undeploy buttons
   - **Duplicate button** with deep copy and auto-navigation to the clone
   - **Save feedback toast** with auto-dismiss
   - All existing functionality preserved (deploy/undeploy, export, delete, package add/remove)
4. **MSW handlers** — Added bot PUT (returns incremented version), duplicate POST (returns new bot ID), undeploy POST, delete handlers
5. **i18n** — 23 new keys under `botDetail.*` in all 11 locale files (env labels, duplicate, save feedback)
6. **Tests** — 9 new tests for BotDetailPage (bot-detail.test.tsx): renders title, status badge, all action buttons, env badges, package section

**Key decisions:**

- **Environment badges vs duplicate cards** — Per UX research, show a single card with environment columns instead of duplicating bot cards per environment. Each environment row has its own deploy/undeploy button
- **Version picker is local state** — Switching versions re-fetches bot data via `useBot(id, version)` query. No URL param for version to keep URLs clean
- **Test uses Routes wrapper** — Component requires `useParams()`, so tests wrap in `<Routes><Route path="...">` for proper param injection

**Tests:** ✅ 99/99 passing (13 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: JSON Editor, Version Picker & Config Editor Layout

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #14

**What changed:**

1. **Monaco JSON Editor** (`json-editor.tsx`) — Monaco-based editor wrapper with EDDI dark/light theme auto-detection via `useTheme()`, auto-format on mount, configurable read-only mode, loading spinner
2. **Version Picker** (`version-picker.tsx`) — Dropdown showing version numbers with relative timestamps ("3h ago"). Renders as badge when only one version exists, select when multiple
3. **Config Editor Layout** (`config-editor-layout.tsx`) — Shared editor chrome with `[ Form | JSON ]` tab toggle. Both tabs share a single `useState` object for synchronized editing. Header shows type icon, resource name, version picker, save/discard buttons. Dirty-state detection via deep comparison. Form tab shows placeholder ("Visual editor coming soon") for Phase 3.17–3.18
4. **`useUpdateResource` hook** — Mutation calling `PUT /{store}/{plural}/{id}?version={version}`, invalidates queries on success
5. **Resource Detail page** — Rewrote from `<pre>` JSON dump to full `ConfigEditorLayout` with save/discard/dirty-state. All hooks moved above early returns for Rules of Hooks compliance
6. **i18n** — 15 new keys under `editor.*` in all 11 locale files (formTab, jsonTab, save, discard, dirty, etc.)
7. **Tests** — 16 new tests: VersionPicker (3), ConfigEditorLayout (7), ResourceDetailPage integration (4), updated resources.test.tsx (2)
8. **Dependency** — `@monaco-editor/react` installed (brings in `monaco-editor` as peer)

**Key decisions:**

- **Monaco mocked in tests** — JSDOM can't render the Monaco canvas, so tests use a `<textarea>` mock via `vi.mock("@monaco-editor/react")`
- **Form↔JSON toggle architecture** — `config-editor-layout.tsx` is the foundation for all Phases 3.15–3.18 editors. Extension-specific form editors will be passed as `children` prop
- **JSON tab default** — Since no form editors exist yet, JSON tab is the default active tab

**Tests:** ✅ 90/90 passing (12 files), TypeScript zero errors, build succeeds

#### Phase 3.14b — Version Cascade & Deferred Items

9. **Location header fix** (`api-client.ts`) — Capture Location header on `200 OK` responses (not just `201`), enabling version tracking for PUT updates
10. **Cascade save** (`cascade-save.ts`) — Saves config, then walks package→bot chain updating version URIs. Parses new versions from Location headers
11. **Resource usage scanner** (`resource-usage.ts`) — Finds all packages/bots referencing a given config, enabling the "update in bots" post-save dialog
12. **Update usage dialog** (`update-usage-dialog.tsx`) — Amber-themed post-save dialog showing affected bots/packages with checkboxes for selective cascade
13. **Version picker data** (`getResourceVersions`) — API function calling descriptors with `includePreviousVersions=true`
14. **Hooks** — `useResourceVersions` (version picker), `useCascadeSave` (cascade mutation)
15. **Dual-path cascade** in `resource-detail.tsx`:
    - **Path A** (from bot/package): auto-cascade via `?pkgId=…&pkgVer=…&botId=…&botVer=…` query params
    - **Path B** (from resource view): post-save usage dialog showing affected bots
16. **MSW handlers** — PUT returns Location header with incremented version; GET descriptors supports `includePreviousVersions` + `filter` params
17. **i18n** — 7 new cascade keys in all 11 locales

**Return types updated:** `updateResource`, `updatePackage`, `updateBot` now return `{ location: string }` to capture backend version URIs.

---

### 2026-03-06 — Manager UI: EDDI Branding, Theme & Font

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #13

**What changed:**

1. **Brand theme restored** — replaced indigo/violet with original EDDI black and gold palette. Primary `#f59e0b` (amber), accent `#fbbf24` (gold), sidebar always dark `#1c1917`, dark mode true blacks `#0c0a09`, light mode warm stone `#fafaf9`
2. **Noto Sans font** — replaced Inter with Noto Sans + script variants (Arabic, Thai, Devanagari, CJK, Korean) via Google Fonts for universal language coverage
3. **Original E.D.D.I logo** — copied `logo_eddi.png` from EDDI backend repo to `public/`; sidebar shows image when expanded, compact gold "E." badge SVG when collapsed
4. **System theme fix** — theme provider now has `matchMedia("prefers-color-scheme: dark")` change listener so "system" mode tracks OS preference in real time (was only checking once on mount)
5. **Wide-screen constraint** — main content area capped at `max-w-screen-2xl` (1536px) to prevent infinite stretching on ultrawide monitors
6. **Test setup** — added `window.matchMedia` mock to `setup.ts` for JSDOM compatibility

**Key decisions:**

- **Noto Sans over Inter** — single font family covers all 11 supported languages' scripts without missing glyphs
- **SVG brand mark for collapsed sidebar** — gold rounded square with "E." matches the logo's style at 28×28px

**Tests:** ✅ 74/74 passing, TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Finalize i18n (11 Languages)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #12

**What changed:**

1. **8 new locale files** — `fr.json` (French), `es.json` (Spanish), `zh.json` (Chinese Simplified), `th.json` (Thai), `ja.json` (Japanese), `ko.json` (Korean), `pt.json` (Portuguese BR), `hi.json` (Hindi)
2. **2 completed locale files** — `de.json` and `ar.json` expanded from ~57 keys to full 219-key parity with `en.json`
3. **`en.json`** — added language labels for all 8 new locales
4. **`config.ts`** — registered all 11 locales with imports and resource entries
5. **`top-bar.tsx`** — language selector expanded from 3 to 11 options
6. **`config.test.ts`** — added key parity regression tests: recursively compares every locale against `en.json` to prevent future key drift (10 new tests)

**Key decisions:**

- **11 languages chosen for global coverage** — en, de, fr, es, ar (RTL), zh, th, ja, ko, pt, hi (~4.5 billion native speakers)
- **Key parity test as regression guard** — any new key added to en.json will cause tests to fail until all 10 locales are updated
- **Hindi uses Devanagari script** — no special rendering needed, standard Unicode

**Tests:** ✅ 74/74 passing (11 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Import/Export + Bot Wizard

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #11

**What changed:**

1. **Backup API module** (`backup.ts`) — typed functions for `exportBot` (2-step: POST to create zip, GET to download), `downloadBotZip` (triggers browser file save via `<a download>`), `importBot` (POST with `application/zip` body)
2. **TanStack Query hooks** (`use-backup.ts`) — `useExportBot` (chained export + download), `useImportBot` (upload zip, invalidates bots cache)
3. **Bots page** — "Import Bot" button with hidden file input (.zip), "Bot Wizard" CTA link alongside existing "Create Bot"
4. **Bot card** — "Export" added to context menu dropdown (between Duplicate and Delete)
5. **Bot detail page** — "Export" button in header actions area
6. **Bot Wizard page** (`bot-wizard.tsx`) — 4-step guided creation: Template (3 presets: Blank, Q&A, Weather), Info (name/description), Packages (default package toggle), Review & Create/Deploy
7. **Step progress indicator** — animated circles with checkmarks for completed steps, connecting lines
8. **Routing** — `/manage/bots/wizard` → BotWizardPage (placed before `/manage/botview/:id` for correct matching)
9. **i18n** — 40+ new keys under `bots.*` (export/import) and `wizard.*` (all step labels, template names/descriptions)
10. **MSW handlers** — 3 new handlers for `POST /backup/export/:botId`, `GET /backup/export/:filename`, `POST /backup/import`
11. **Tests** — 11 new tests: 4 for import/export UI (backup.test.tsx), 7 for wizard flow (bot-wizard.test.tsx)

**Key decisions:**

- **Export is a 2-step flow** — POST triggers backend zip creation, response Location header contains the download URL, second GET fetches the binary
- **Import uses raw fetch** — `Content-Type: application/zip` requires bypassing the JSON api-client
- **Wizard is page-internal state** — no separate routes per step, single component with step counter, keeps back/forward simple
- **Templates are cosmetic placeholders** — all currently create blank bots; future phases can wire template-specific package presets

**Tests:** ✅ 64/64 passing (11 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Resources Pages (Generic CRUD)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #10

**What changed:**

1. **Generic API layer** (`resources.ts`) — single parameterized CRUD module that drives all 6 resource types: Behavior Rules (`/behaviorstore/behaviorsets`), HTTP Calls (`/httpcallsstore/httpcalls`), Output Sets (`/outputstore/outputsets`), Dictionaries (`/regulardictionarystore/regulardictionaries`), LangChain (`/langchainstore/langchains`), Property Setter (`/propertysetterstore/propertysetters`)
2. **TanStack Query hooks** (`use-resources.ts`) — `useResourceDescriptors`, `useResource`, `useCreateResource`, `useDeleteResource`, `useDuplicateResource` — all parameterized by type slug, with graceful handling of unknown types (disabled queries instead of throwing)
3. **Resource Card** (`resource-card.tsx`) — reusable card with dynamic icon mapping, context menu (duplicate/delete)
4. **Create Resource Dialog** (`create-resource-dialog.tsx`) — creates empty config, navigates to detail page
5. **Hub Page** (`resources.tsx`) — 6 category cards with icons, descriptions, and item counts
6. **List Page** (`resource-list.tsx`) — generic: search, card grid, create button, error/empty states
7. **Detail Page** (`resource-detail.tsx`) — raw JSON viewer, duplicate/delete actions
8. **Routing** — `/manage/resources/:type` → ResourceListPage, `/manage/resources/:type/:id` → ResourceDetailPage
9. **i18n** — 20+ new keys under `resources.*` including all 6 type names and descriptions
10. **MSW handlers** — `createResourceHandlers()` helper generates mock endpoints for all 6 types
11. **Tests** — 15 new tests for hub, list, and detail pages

**Key decisions:**

- **One solution, six types** — all 6 resource types share identical backend API shape, so a single `ResourceTypeConfig` object drives the entire stack (API → hooks → pages)
- **Hooks handle unknown types gracefully** — queries are disabled (not thrown) for invalid slugs, allowing pages to render error UI

**Tests:** ✅ 53/53 passing (9 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Chat Panel

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #9

**What changed:**

1. **Chat API module** (`chat.ts`) — typed functions for `startConversation` (POST), `readConversation` (GET, for welcome messages + resume), `sendMessage` (text/plain), `sendMessageWithContext` (JSON), `sendMessageStreaming` (SSE async generator), `endConversation`
2. **Zustand store + TanStack Query hooks** (`use-chat.ts`) — `useChatStore` for local state (messages, bot selection, streaming toggle persisted to localStorage), `useDeployedBots`, `useStartConversation` (auto-GETs welcome message), `useSendMessage` (auto-branches streaming/non-streaming), `useConversationHistory`, `useLoadConversation`, `useEndConversation`
3. **Chat components** — `chat-message.tsx` (markdown bubbles via react-markdown + remark-gfm), `chat-input.tsx` (auto-grow textarea), `chat-history.tsx` (conversation history sidebar with resume), `streaming-toggle.tsx` (Zap toggle), `chat-panel.tsx` (main container with bot selector dropdown, history panel, message list, input)
4. **Chat page** (`chat.tsx`) — full-height layout with `ChatPanel`
5. **Routing** — `/manage/chat` → ChatPage
6. **Sidebar** — "Chat" nav item with `MessageCircle` icon between Conversations and Resources
7. **i18n** — 16 new keys under `nav.chat`, `pages.chat.*`, `chat.*`
8. **MSW handlers** — start conversation (201 + Location), send message (snapshot), read conversation (welcome snapshot)
9. **CSS** — chat prose overrides for markdown code blocks and links
10. **Tests** — 7 new tests for ChatPage (heading, subtitle, bot selector, input, streaming toggle, history toggle, empty state)

**Key decisions:**

- After `startConversation` (POST), immediately GETs the conversation to pick up any welcome message
- Streaming mode is **configurable via UI toggle** (persisted to localStorage), not hardcoded
- Conversation history sidebar allows resuming past conversations — loads full conversation via GET
- Uses raw `fetch` for text/plain and SSE endpoints (api-client defaults to JSON)

**Tests:** ✅ 38/38 passing (8 files), TypeScript zero errors, build succeeds (754KB JS, 33KB CSS)

---

### 2026-03-06 — Manager UI: Packages + Conversations Pages

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #6-8

**What changed:**

1. **Packages List Page** — Full rewrite of placeholder: cards grid, search/filter, create dialog, context menu (duplicate/delete)
2. **Package Detail Page** — Extensions list with type labels, config URI, add/remove, expandable raw JSON, delete
3. **Conversations List Page** — Table layout with state filter pills (All/Active/In Progress/Ended/Error), search, delete, links to detail view
4. **Conversation Detail Page** — Step-by-step memory viewer showing user input, actions, bot output per turn, expandable raw JSON per step, conversation properties section
5. **API modules** — `conversations.ts` (GET descriptors, simple log, raw log, DELETE)
6. **TanStack Query hooks** — `useConversationDescriptors`, `useSimpleConversation`, `useRawConversation`, `useDeleteConversation`, `useCreatePackage`, `useUpdatePackage`, `useDeletePackage`
7. **MSW handlers** — Package descriptors, package detail, package CRUD, conversation descriptors, conversation logs
8. **i18n** — Added all `packages.*`, `packageDetail.*`, `conversations.*`, `conversationDetail.*` keys to en.json
9. **Routes** — `/manage/packageview/:id` → PackageDetailPage, `/manage/conversationview/:id` → ConversationDetailPage
10. **Vite proxy** — Added `/managedbots` proxy for future Chat Panel

**Tests:** 31/31 passing (7 files), TypeScript zero errors, build succeeds (421KB JS, 29KB CSS)

**Key decisions:**

- Conversations page uses low-level `/conversationstore/conversations` API for browsing/inspecting
- Chat Panel (future Phase 3.9) will use `/managedbots/{intent}/{userId}` (managed) or `/bots/{env}/{botId}` (direct)

---

### 2026-03-06 — Manager UI: Greenfield Scaffold + Layout Shell

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #2-3

**What changed:**

- Replaced entire Webpack + MUI v4 + Redux + TSLint codebase with Vite 6 + React 19 + Tailwind v4 + TanStack Query + Zustand + ESLint
- 28 new files: config, layout components, i18n (en/de/ar with auto RTL), 5 placeholder pages
- Testing pyramid: Vitest + RTL + MSW (unit/component) + Playwright (E2E config)

**Testing:** ✅ `npx tsc -b` zero errors, `npm run build` succeeds, 14/14 tests pass  
**Commit:** `020007e`

---

### 2026-03-06 — Manager UI: Bots Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #4

**What changed:**

- Bot card component with deployment status badges (auto-polled via TanStack Query)
- Deploy/undeploy actions, context menu (duplicate, delete), create bot dialog
- Search/filter, version deduplication via `groupBotsByName`

**Testing:** ✅ 23/23 tests pass (9 new)  
**Commit:** `e47b0fb`

---

### 2026-03-06 — Manager UI: Bot Detail Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #5

**What changed:**

- Bot Detail page: deployment status + deploy/undeploy, package list with add/remove
- Searchable package selector, raw JSON config viewer, delete with navigation
- Packages API, descriptors API, TanStack Query hooks for packages

**Testing:** ✅ 23/23 tests pass, zero TypeScript errors  
**Commit:** `dadc669`

---

### 2026-03-06 — Handoff Prep

**Repo:** EDDI-Manager  
**What changed:** Updated AGENTS.md, created HANDOFF.md  
**Commit:** `6fc510e`

### Template for Each Entry

```markdown
### [DATE] — [SHORT TITLE]

**Repo:** [repo name]  
**Branch:** `feat/...` or `fix/...`  
**Phase:** [1/2/3] — Item #[number]

**What changed:**

- [file 1]: [what and why]
- [file 2]: [what and why]

**Design decision (if any):**
[Why this approach was chosen over alternatives]

**Testing:**

- [ ] Builds cleanly
- [ ] Verified in browser
- [ ] No regressions

**Commit:** `feat(scope): message`
```

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
|            |                                                                       |                                       |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
|      |            |       |     |        |
