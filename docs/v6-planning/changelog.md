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
| 2   | Keep Chat UI **standalone** + extract **shared component** | EDDI has a dedicated single-agent chat endpoint; standalone deployment is needed         | M.1      |
| 3   | Website: **Astro** on GitHub Pages                         | Static output, built-in i18n routing, zero JS by default, Tailwind integration           | L        |
| 4   | **Skip API versioning**                                    | Only clients are Manager + Chat UI, agenth first-party controlled                        | M.7      |
| 5   | **Remove internal snapshot tests**                         | Never production-ready; integration tests provide sufficient coverage                    | K.1      |
| 6   | **Trunk-based branching**                                  | Short-lived feature branches, squash merge, clean main history                           | N.1      |
| 7   | **Mobile-first responsive** is Phase 1                     | Core requirement, not afterthought; Tailwind breakpoints make this natural               | J.4      |

**Biggest gap discovered:** No CI/CD anywhere — all builds, tests, and deployments are manual.

**Strongest existing areas:** Security (SSRF, rate limiting, cost tracking), Monitoring (30+ Prometheus metrics, Grafana dashboard), Documentation (40 markdown files published via docs.labs.ai).

---

## Implementation Log

_Entries will be added here as implementation progresses._

### 2026-03-23 — V6 Rename Migration: Deployment Field Rename Fix

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**Root cause found:** Commit `b380533a` renamed Java POJO fields `botId`→`agentId` and `botVersion`→`agentVersion` in `DeploymentInfo.java`, but old MongoDB deployment documents retained the `botId`/`botVersion` BSON field names. Jackson deserialized them as `null`, causing `ServiceException: Argument must not be null (id)` every 10s in `checkDeployments()`.

**Files modified:**

- `V6RenameMigration.java` [MODIFIED]: Added `FIELD_NAME_REWRITES` for `botId`→`agentId`, `botVersion`→`agentVersion`. Applied in `migrateEnvironments()` for `deployments` and `conversationmemories` collections.
- `AgentDeploymentManagement.java` [MODIFIED]: Defensive null-check filter in `checkDeployments()` to skip records with null `agentId`/`agentVersion`.

**Code review completed (no issues):**

- `V6RenameMigration.java` (360 lines) — RESOURCE_COLLECTIONS, URI rewrites, descriptor migration
- `AbstractBackupService.java` (95 lines) — LEGACY_URI_REWRITES for ZIP import
- `RestImportService.java` (1014 lines) — normalizeLegacyUris, updateDocumentDescriptor, createOrUpdate methods
- `MigrationManager.java` (508 lines) — property/apiCalls/output format migrations
- `RestAgentAdministration.java` (342 lines) — deployment lifecycle
- `MongoDeploymentStorage.java` (103 lines) — CRUD operations

**Testing:** 1125 unit tests pass, 0 failures.

---

### 2026-03-20 — Scheduled Triggers & Heartbeats (Code Review + TriggerType)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**What changed:**

**Core model:**

- `ScheduleConfiguration.java` [MODIFIED]: `FireStatus` converted from String constants to proper enum. Added `TriggerType` enum (`CRON`, `HEARTBEAT`) and `heartbeatIntervalSeconds` field
- `IScheduleStore.java` [MODIFIED]: Added `setScheduleEnabled()` for atomic operations, `readAllSchedules(int limit)` for bounded queries
- `ScheduleFireLog.java` [MODIFIED]: `attemptNumber` now caller-provided

**Store:**

- `MongoScheduleStore.java` [MODIFIED]: 7 fixes — CAS filter guards `nextRetryAt ≤ now`, atomic `setScheduleEnabled()`, one-shot auto-disables, all `Instant` fields stored as epoch-millis `Long`, `tenantId` index, bounded queries, fire log status index

**Engine:**

- `ScheduleFireExecutor.java` [MODIFIED]: `attemptNumber` passed by caller (not stale snapshot), heartbeat context in `InputData` (trigger="heartbeat", triggerType="HEARTBEAT"), defaults message to "heartbeat" for heartbeat triggers
- `SchedulePollerService.java` [MODIFIED]: Constructor injection, heartbeat drift-proof scheduling (`nextFire = now + interval`), one-shot handling, exponential backoff
- `CronParser.java` [MODIFIED]: `DOW_NAMES`/`MONTH_NAMES` maps now package-visible
- `CronDescriber.java` [MODIFIED]: Reuses maps from `CronParser` (DRY fix)

**REST + MCP:**

- `RestScheduleStore.java` [MODIFIED]: Atomic enable/disable, heartbeat validation (minimum interval), type-aware defaults
- `McpAdminTools.java` [MODIFIED]: `create_schedule` supports CRON/HEARTBEAT + `userId` param, updated API signatures

**Tests added:**

- `SchedulePollerServiceTest.java` [NEW]: 12 tests (poll flow, claim conflicts, backoff, dead-lettering, heartbeat scheduling, one-shot)
- `ScheduleFireExecutorTest.java` [NEW]: 9 tests (fire flow, conversation strategies, heartbeat defaults, context injection, error handling)
- `RestScheduleStoreTest.java` [NEW]: 13 tests (create validation, heartbeat defaults, enable/disable, read delegation)
- `McpScheduleToolsTest.java` [NEW]: 21 tests (create cron/heartbeat, inferred type, validation, list, read, fire, delete, retry)

**Docs updated:**

- `docs/mcp-server.md`: Added Schedule Management Tools (6 tools), cron + heartbeat workflow examples
- `docs/v6-planning/changelog.md`: This entry
- `HANDOFF.md`: Schedule architecture section + lifecycle hooks table

**Lifecycle hooks (agent ↔ schedule):**

- `RestAgentAdministration.java` [MODIFIED]: `enableSchedulesForAgent()` on deploy, `disableSchedulesForAgent()` on undeploy
- `RestAgentStore.java` [MODIFIED]: `scheduleStore.deleteSchedulesByAgentId()` on cascade delete
- `RestExportService.java` [MODIFIED]: `exportSchedules()` includes schedules in agent export ZIP
- `IScheduleStore.java` [MODIFIED]: Added `deleteSchedulesByAgentId(String agentId)` method
- `MongoScheduleStore.java` [MODIFIED]: Implemented `deleteSchedulesByAgentId` via `deleteMany`
- `RestAgentStoreTest.java` [MODIFIED]: Constructor updated with `IScheduleStore` mock

**Design decisions:**

- CRON vs HEARTBEAT: Same engine, different defaults (persistent conversation, "heartbeat" message, drift-proof timing)
- Exactly-once execution: Atomic CAS via MongoDB `findOneAndUpdate` with `PENDING` + `nextRetryAt ≤ now` guards
- Exponential backoff: `base × multiplier^failCount` with configurable parameters, dead-lettering after `maxRetries`
- All Instants stored as epoch-millis `Long` to avoid BSON type comparison issues
- Lifecycle hooks: non-fatal (warn on failure, primary agent operation continues)

---

### 2026-03-17 — Phase 7 Item 34b: Tenant Quota Stub

**Repos:** EDDI, EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

**EDDI (backend):**

- `TenantQuota.java` [NEW]: Quota config record (limits, enabled flag), `unlimited()` factory
- `TenantUsage.java` [NEW]: Thread-safe atomic counters (`AtomicInteger`, `DoubleAdder`) with `UsageSnapshot` for REST
- `QuotaCheckResult.java` [NEW]: Allowed/denied result record with `OK` constant
- `ITenantQuotaStore.java` [NEW]: Store interface for CRUD
- `InMemoryTenantQuotaStore.java` [NEW]: `ConcurrentHashMap` impl with `@ConfigProperty` defaults
- `TenantQuotaService.java` [NEW]: Enforcement engine — conversation/API call/cost budget checks, sliding window resets, Micrometer metrics (`eddi.tenant.quota.allowed/denied`, `eddi.tenant.usage.*`)
- `QuotaExceededException.java` [NEW]: Runtime exception
- `QuotaExceededExceptionMapper.java` [NEW]: HTTP 429 + `Retry-After: 60`
- `IRestTenantQuota.java` [NEW]: JAX-RS interface at `/administration/quotas`
- `RestTenantQuota.java` [NEW]: REST implementation
- `ConversationService.java` [MODIFIED]: Quota hooks in `startConversation()`, `say()`, `sayStreaming()`
- `application.properties` [MODIFIED]: 8 new `eddi.tenant.*` config properties (disabled by default)
- `TenantQuotaServiceTest.java` [NEW]: 15 unit tests
- `RestTenantQuotaTest.java` [NEW]: 7 REST + exception mapper tests
- `ConversationServiceTest.java` [MODIFIED]: Updated constructor + mock stubs

**EDDI-Manager:**

- `quotas.ts` [NEW]: API module (list, get, update, usage, reset)
- `use-quotas.ts` [NEW]: 5 TanStack Query hooks with 10s usage polling
- `quotas.tsx` [NEW]: Admin page — config form (4 limits + enabled toggle) + live usage dashboard (progress bars, color thresholds)
- `quotas.test.tsx` [NEW]: 8 page-level tests
- `sidebar.tsx` [MODIFIED]: `Gauge` icon + nav entry in Operations section
- `app.tsx` [MODIFIED]: `/manage/quotas` route
- `handlers.ts` [MODIFIED]: `quotaHandlers` with mock data
- `server.ts` + `browser.ts` [MODIFIED]: Registered `quotaHandlers`
- 11 locale files [MODIFIED]: `nav.quotas` key

**Code review fixes applied:**

1. Deleted dead `TenantQuota.merge()` (broken `!= 0` sentinel, never called)
2. Added TOCTOU TODO comments for future check+record atomicity
3. Moved `quotaAllowedCounter` from `record*()` → `check*()` methods (semantically correct)
4. Documented thread-safety limitation in `TenantUsage` Javadoc
5. Switched form init in `quotas.tsx` from setState-during-render → `useEffect`

**Design decisions:**

- Disabled by default (`eddi.tenant.quota.enabled=false`) — zero impact on existing deployments
- `-1` = unlimited for all limits
- Single-tenant `"default"` ID — multi-tenant JWT extraction deferred to Phase 7.1
- In-memory store — DB-backed store planned for future

**Testing:**

- [x] Backend: 861 tests pass (22 new), 0 failures
- [x] Manager: `npx tsc -b` clean, 30 test files pass (8 new), build succeeds

---

### 2026-03-15 — Phase 6E Planned: quarkus-langchain4j → langchain4j Core

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**Decision:** Drop `quarkus-langchain4j` entirely, use core `langchain4j` only.

**Analysis:**

- EDDI has 7 model builders: 4 already use core `dev.langchain4j` (OpenAI, Anthropic, Ollama, Gemini). Only 3 use `io.quarkiverse` classes (Vertex Gemini, HuggingFace, Jlama) — and only for the model provider class, not for any quarkiverse CDI features
- quarkus-langchain4j's value proposition (`@RegisterAiService`, Dev Services, centralized config) is architecturally incompatible with EDDI: EDDI builds models at runtime from JSON config per-agent, not at compile-time from annotations
- Version conflict: quarkiverse 1.7.4 bundles older langchain4j internally alongside EDDI's explicit 1.11.0
- All 3 quarkiverse-only providers have core equivalents in `langchain4j` 1.11.0-beta19

**Jlama decision:** Keep (migrated to core). Runs in-process JVM inference — niche for edge/offline. For Docker production, Ollama in a separate container is more practical, but Jlama costs nothing to maintain.

---

### 2026-03-15 — Strategic Roadmap Reorder

**Repos:** EDDI, EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

- RAG basics pulled from Phase 10b into Phase 8b as "RAG Lifecycle Task" (#38b, 3 SP)
- Phase 7 expanded to 12 SP: added Tenant Quota Stub (#34b, 2 SP) for SaaS foundation
- OpenTelemetry Tracing (#40b) moved from "deferred" into Phase 9 (DAG)
- Phase 10b renamed to "Advanced RAG + Debate" (builds on 8b basics)
- CI/CD (Phase 12) deprioritized from 🟡High to 🟠Medium (CircleCI exists)
- langchain4j/quarkus-langchain4j leverage notes added to all future phases

**Key decisions:**

- Use langchain4j `EmbeddingStore`/`EmbeddingModel` for RAG (not custom implementations)
- Use `quarkus-langchain4j` MCP support where it fits EDDI's config-driven model
- Keep `ILifecycleTask` as the orchestration layer wrapping langchain4j components
- OTel bundled with DAG because tracing across parallel tasks is when it becomes essential

---

### 2026-03-15 — Phase 6C: Infinispan → Caffeine

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**What changed:**

- Rewrote `CacheFactory.java` — Caffeine builder replaces Infinispan `EmbeddedCacheManager`
- Rewrote `CacheImpl.java` — wraps Caffeine `Cache<K,V>` instead of Infinispan `Cache`
- Removed 4 Infinispan dependencies + `infinispan.version` property from POM
- Caffeine provided transitively by `quarkus-cache` (no new dependency needed)
- Deleted `infinispan-embedded.xml`, cleaned `application.properties`
- Updated `ToolCacheService` Javadoc + log message

**Key finding:** Infinispan was NOT used for multi-instance agent deployment coordination. Agent deployment uses DB-backed `IDeploymentStore` with `@Scheduled` 10s polling. `AgentFactory` uses plain `ConcurrentHashMap`. Cross-instance routing uses NATS JetStream.

**Decision:** Plan to replace 10s DB polling with NATS-based deployment events in a future phase.

**Quarkus upgrade:** Attempted 3.30.8→3.32.3 but blocked by Java 25 `Unsupported value type: [ALL-UNNAMED]` error in `generate-code-tests`. Will wait for Quarkus 3.33 LTS (GA ~March 25, 2026).

**729 unit tests pass.**

---

### 2026-03-15 — Roadmap Restructuring + Research Analysis

**Repos:** EDDI, EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

- Analyzed 4 research documents and their interpretations, mapped gaps to new roadmap phases
- Created `docs/project-philosophy.md` — 7 architectural pillars governing all EDDI development
- Restructured roadmap from 8 phases to 14 phases (7–14b) based on research findings
- Split all phases >10 SP into ≤10 SP chunks for single-session implementability
- Added Phase 6C (Infinispan→Caffeine, 2 SP) and Phase 6D (Lombok removal, 5 SP) as quick wins
- Updated `AGENTS.md` in agenth EDDI and EDDI-Manager with new phase structure

**Key decisions:**

- Secrets Vault + Audit Ledger promoted to Phase 7 (critical for enterprise/EU AI Act)
- MCP split into 8a (servers) + 8b (client + operator)
- Heartbeat/Scheduled Triggers: cluster-safe exactly-once via NATS
- Time-Traveling Debugger + Visual Taint Tracking added to Manager UI phases
- Website Astro migration is the final phase before v6.0 GA

---

### 2026-03-15 — Lombok Removal Analysis

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**Analysis only (no code changes):**

- 114 files use Lombok, 371 total annotation usages
- Breakdown: `@Getter`(118), `@Setter`(110), `@NoArgsConstructor`(47), `@AllArgsConstructor`(42), `@EqualsAndHashCode`(22), `@Value`(10), `@Slf4j`(9), `@ToString`(8), `@Data`(5)
- Lombok uses `sun.misc.Unsafe::objectFieldOffset` — terminally deprecated in Java 25, will be removed
- Recommendation: Phase 6D (5 SP) — IntelliJ Delombok + `@Value`→records + `@Slf4j`→JBoss Logger

---

### 2026-03-14 — Orphan Detection & Cleanup Endpoint

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**New endpoint:** `GET/DELETE /administration/orphans`

Scans all stores (packages, behavior sets, HTTP calls, output sets, langchains, property setters, dictionaries, parsers) and finds resources not referenced by any agent or package.

**Files added:**

- `OrphanInfo.java` — DTO (resourceUri, type, name, deleted)
- `OrphanReport.java` — DTO (totalOrphans, deletedCount, orphans list)
- `IRestOrphanAdmin.java` — JAX-RS interface with OpenAPI annotations
- `RestOrphanAdmin.java` — Implementation: builds referenced-URI set from all agents/packages, compares against all descriptors per store type
- `RestOrphanAdminTest.java` — 4 unit tests (no-orphans, detects orphans, no-agents, purge)

### 2026-03-14 — Cascade Delete for Agents & Workflows

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**Problem:** Deleting a agent left all its packages and extension resources (behavior sets, HTTP calls, output sets, langchains, property setters, dictionaries) orphaned in the database. Cleanups had to be done manually.

**Solution:** Added `cascade` query parameter to `DELETE /agentstore/agents/{id}` and `DELETE /packagestore/packages/{id}`.

**Files modified:**

- `IRestAgentStore.java` — added `cascade` param + enriched OpenAPI annotations
- `RestAgentStore.java` — cascade walks packages, delegates to `RestWorkflowStore`
- `IRestWorkflowStore.java` — added `cascade` param + enriched OpenAPI annotations
- `RestWorkflowStore.java` — cascade walks extensions + parser dictionaries, deletes via `ResourceClientLibrary`
- `IResourceClientLibrary.java` — added `deleteResource(URI, boolean)`
- `ResourceClientLibrary.java` — `IResourceService.delete()` for all 7 store types
- `deployment-management-of-chatagents.md` — added deletion section with cascade docs

**Design decisions:**

- Error-tolerant: partial cascade failures are logged but don't block the parent deletion
- **Shared-resource safety**: before deleting, checks if the resource is referenced by other agents/packages. If shared, it is skipped (not deleted)
- Follows same traversal pattern as existing `duplicateAgent(deepCopy=true)`

**Tests added:**

- `RestAgentStoreTest` — 6 tests (including shared-package skip)
- `RestWorkflowStoreTest` — 7 tests (including shared-resource skip)

### 2026-03-13 — Import/Export Merge Strategy + Manager Import Dialog

**Repos:** EDDI, EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

**EDDI (backend) (`b0586b2d`):**

- `DocumentDescriptor.java` [MODIFIED]: Added `originId` field to track where an imported resource came from
- `IDescriptorStore.java` [MODIFIED]: Added `findByOriginId(String)` method
- `DescriptorStore.java` [MODIFIED]: Implemented `findByOriginId` using `IResourceStorage.findResources()`
- `DocumentDescriptorStore.java`, `TestCaseDescriptorStore.java`, `ConversationDescriptorStore.java` [MODIFIED]: Added `findByOriginId` delegation
- `ImportPreview.java` [NEW]: Record for merge preview response — agent name, resource diffs (action, type, local ID)
- `IRestImportService.java` [MODIFIED]: Added `strategy` and `selectedResources` params to `importAgent`, new `previewImport` endpoint
- `RestImportService.java` [REWRITTEN]: Full merge strategy support — looks up existing resources by `originId`, updates instead of creating duplicates. Preview endpoint returns dry-run diff analysis. Selective import via comma-separated origin IDs. Original `strategy=create` behavior preserved as default

**EDDI-Manager (`b8028db`):**

- `backup.ts` [MODIFIED]: Added `previewImport()` and `importAgentMerge()` API functions, `ImportPreview`/`ResourceDiff` types
- `use-backup.ts` [MODIFIED]: Added `usePreviewImport` and `useImportAgentMerge` hooks
- `import-agent-dialog.tsx` [NEW]: Multi-step import dialog — upload (drag-and-drop), strategy selection (create new vs merge/sync), preview table with resource diffs and selective checkboxes, loading spinner
- `agents.tsx` [MODIFIED]: Replaced simple file input with `ImportAgentDialog` component
- `backup.test.tsx` [MODIFIED]: Updated test for dialog-based import flow

**Design decisions:**

- `originId` stored in `DocumentDescriptor` — reuses existing descriptor infrastructure without new tables
- Default strategy is `create` (backward compatible) — `merge` must be explicitly requested
- Preview is a separate endpoint (dry run) — never modifies data, lets users inspect what would change
- Dialog uses multi-step wizard pattern for progressive disclosure

**Testing:**

- [x] Backend: all tests pass (`.\mvnw test` exit 0)
- [x] Manager: `npx tsc -b` clean, 24/24 tests pass, `npm run build` succeeds
- [x] No regressions

### 2026-03-14 — ContentEditor: Monaco-Based Content Editing with Fullscreen Expand

**Repo:** EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

- `content-editor.tsx` [NEW]: Reusable Monaco-based editor component replacing cramped `<textarea rows={4}>` fields. Two modes: inline auto-sizing editor (4–20 lines based on content) and fullscreen expand via Radix Dialog. Features: dark/light theme integration (`useTheme()`), RTL/LTR support (logical CSS properties), full i18n (all 11 locales), ARIA accessibility (`aria-live` status bar, `sr-only` descriptions), keyboard navigation (`Ctrl+Shift+F` expand, `Escape` close, Monaco's `Ctrl+F` find). Responsive: 100% on mobile, padded modal on tablet/desktop.
- `langchain-editor.tsx` [MODIFIED]: System prompt `<textarea>` replaced with `<ContentEditor language="plaintext">`
- `httpcalls-editor.tsx` [MODIFIED]: Request body `<textarea>` replaced with `<ContentEditor language="json">`
- `content-editor.test.tsx` [NEW]: 16 unit tests covering rendering, value binding, onChange, status bar counts, expand button (a11y, readOnly, keyboard shortcut tooltip), language prop, placeholder show/hide, fullscreen dialog open/close, read-only badge, aria-live attributes
- 11 locale files [MODIFIED]: Added `contentEditor.*` namespace (expand, close, lines, chars, fullscreenTitle, fullscreenDescription)

**Design decisions:**

- Leveraged existing `@monaco-editor/react` and `@radix-ui/react-dialog` — zero new dependencies
- Expand button is visible in readOnly mode too — reviewers viewing long prompts benefit from fullscreen
- Auto-sizing uses `content.split('\n').length` clamped to `[minLines, maxLines]` — responsive without content shift

**Testing:**

- [x] TypeScript: zero errors (`npx tsc -b`)
- [x] All 208 tests pass (`npm run test`) — 26 test files
- [x] Build: succeeds (`npm run build`)
- [x] Visual verification: inline editor, fullscreen modal, RTL/Arabic, dark/light theme, French i18n — all confirmed

### 2026-03-14 — Import/Export Merge Strategy: Test Coverage

**Repos:** EDDI, EDDI-Manager
**Branch:** `feature/version-6.0.0`

**What changed:**

**EDDI (backend):**

- `ImportMergeIT.java` [NEW]: 7 ordered integration tests covering the full import/export round-trip — create import (verify originId), export to zip, preview merge (verify UPDATE actions), merge import (verify same agent ID reused with incremented version), post-merge preview (no duplicates), selective merge, and create-always-new verification. Requires Docker/Testcontainers to run.

**EDDI-Manager:**

- `handlers.ts` [MODIFIED]: Added MSW handler for `POST */backup/import/preview` (mock ImportPreview with 4 resources). Updated `POST */backup/import` to support `strategy=merge` query param and return appropriate responses.
- `import-agent-dialog.test.tsx` [NEW]: 15 component tests using `vi.hoisted()` + `vi.mock()` to isolate hook behavior. Covers: rendering (open/closed), upload (file selection, name/size display), strategy (default/switch/import/onSuccess), preview (mutation call, resource table, action badges, checkboxes, toggle), merge confirm (mutation args, selected IDs, onSuccess), navigation (back button, X close).

**Design decisions:**

- Used `vi.hoisted()` + `vi.mock()` pattern for dialog tests instead of MSW mutation testing — TanStack Query mutations with File bodies don't reliably propagate through jsdom's fetch. Direct hook mocking gives faster, deterministic tests while MSW handler tests verify network layer separately.
- Backend IT uses `@TestMethodOrder(OrderAnnotation.class)` for shared state across the import/export round-trip

**Testing:**

- [x] Backend: `mvnw test-compile` passes (ITs require Docker)
- [x] Manager: `npx tsc -b` clean, 191/191 tests pass across 25 files
- [x] No regressions

### 2026-03-13 — Phase 6B Complete: PostgreSQL IT Parity (48/48) + AgentUseCaseIT Fix

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6B — PostgreSQL Integration Test Parity

**What changed:**

**PostgreSQL IT parity (`0eda70d9`):**

- Ran all 8 PostgreSQL IT suites — 6 passed immediately, 2 needed fixes
- `PostgresApiContractIT.readNonExistent_returns404` — base test used MongoDB ObjectId `000...0` which fails PostgreSQL UUID cast → overrode with valid UUID `00000000-0000-0000-0000-000000000000`. Base method changed from package-private to `protected` for cross-package override
- `PostgresAgentUseCaseIT` — `RestInterfaceFactory` connected to port 7070 (default) instead of 8082 because `PostgresIntegrationTestProfile` only set `quarkus.http.test-port` but not `quarkus.http.port` → added `quarkus.http.port=8082`
- All 48 PostgreSQL ITs pass: Behavior (4), Output (5), Dictionary (5), AgentEngine (11), AgentDeployment (4), ConversationService (7), ApiContract (10), AgentUseCase (2)

**MongoDB AgentUseCaseIT fix (`e77b6f23`):**

- `AgentUseCaseIT.useAgentManagement` failed because a stale agent trigger from a **previous test run** persisted in MongoDB with an old `agentId`. The POST to `/agenttriggerstore/agenttriggers` returned 409 (already exists), so the managed agent API used the old trigger → 404 because that old agent was no longer deployed → NPE on null `userConversation`
- Fix: Added `DELETE /agenttriggerstore/agenttriggers/{intent}` before POST for idempotent setup
- Added `statusCode(200)` assertions for better error detection
- This bug was pre-existing but undetected because previous test runs didn't exercise `AgentUseCaseIT` in isolation after data accumulated

**Design decisions:**

- Used `@Override` + `protected` visibility for the PG-specific test rather than modifying the base test, keeping MongoDB tests unchanged
- `quarkus.http.port` must match `quarkus.http.test-port` in all test profiles because `RestInterfaceFactory` reads the config property, not the runtime-assigned port

**Testing:**

- [x] All 96 integration tests pass (48 MongoDB + 48 PostgreSQL)
- [x] No regressions

**Commits:** `0eda70d9`, `e77b6f23`

### 2026-03-12 — PostgreSQL IT Fixes + DB-Agnostic URI Parsing

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6B — PostgreSQL Integration Test Parity

**What changed:**

**Bug fix — UUID validation (`e5c68a0b`):**

- `RestUtilities.isValidId()` rejected dashes (`-`) in UUIDs, causing `DocumentDescriptorFilter` to receive null IDs on PUT/DELETE responses
- Added `-` to allowed characters in `isValidId()`
- Added `isResourceIdValid()` guard on PUT/PATCH handler in `DocumentDescriptorFilter`
- Fixed PG IT `updateBehavior` (400→200) and `deleteBehavior` (200→404)

**DB-agnostic URI parsing (`7fb79bfa`):**

- Added `extractId(locationUri)` and `extractVersion(locationUri)` to `UUIDWrapper` (Thymeleaf `#uuidUtils`)
- Replaced 70 hardcoded `#strings.substring()` offsets across all 7 Agent Father httpcalls JSONs
- Re-zipped `Agent+Father-4.0.0.zip` for deployment
- Added 12 unit tests in `UUIDWrapperTest`
- Documented `#uuidUtils` in `docs/output-templating.md`

**Design decisions:**

- Added to existing `UUIDWrapper` rather than a new dialect — functions are ID-related and the dialect was already registered
- `extractId()` uses `lastIndexOf('/')` + `indexOf('?')` — position-independent, works with any ID format

**Testing:**

- All 672 tests pass (0 failures)
- PG BehaviorCrudIT: 4/4 ✅

### 2026-03-10 — Chat UI Vite Rewrite + Agent Father Enhancements + Manager Chat SSE

**Repos:** EDDI, EDDI-Manager, eddi-chat-ui
**Branch:** `feature/version-6.0.0`

**What changed:**

**eddi-chat-ui (full CRA → Vite rewrite):**

- Migrated from Create React App to Vite 6 + React 19 + TypeScript 5.7
- New component architecture: `ChatWidget.tsx` (orchestrator), `ChatHeader.tsx`, `MessageBubble.tsx`, `ChatInput.tsx`, `QuickReplies.tsx`, `Indicators.tsx`, `ScrollToAgenttom.tsx`
- Context + useReducer state management (`chat-store.tsx`) replacing prop drilling
- SSE streaming support via `AsyncGenerator` in `chat-api.ts`
- Demo mode (`demo-api.ts`) for `/chat/demo/showcase`
- Vanilla CSS with BEM naming and CSS custom properties (dark/light tokens)
- Query parameter customization: `hideUndo`, `hideRedo`, `hideNewConversation`, `hideLogo`, `theme`, `title`
- Vitest unit tests with jsdom

**EDDI (backend):**

- Deployed new Vite chat-ui production build to `META-INF/resources/` (replaces old CRA build)
- Old CRA assets deleted: `asset-manifest.json`, `manifest.json`, `main.*.css`, `main.*.js`
- New Vite assets added: `chat-ui.*.css`, `chat-ui.*.js`
- `chat.html` updated to reference new Vite bundle entry points
- Agent Father OpenAI flow enhanced with 3 new configuration steps:
  - **Timeout**: Asks user for API timeout value after temperature
  - **Built-in Tools**: Enable/disable tools + whitelist selection (calculator, websearch, datetime, weather, etc.)
  - **Conversation History Limit**: Context window size (10/20/unlimited/custom)
- `OpenAILanguageModelBuilder.java`: Migrated to `JdkHttpClient.builder()` (native Java HTTP client)
- Property setter, behavior rules, httpcalls, and output configs all updated consistently across the OpenAI flow

**EDDI-Manager:**

- `chat.ts`: Added `undoConversation()` and `redoConversation()` API functions
- `use-chat.ts`: SSE streaming integration with real-time token rendering, undo/redo support
- `chat-panel.tsx`: Undo/redo buttons, SSE streaming toggle, improved message rendering
- `en.json`: New i18n keys for undo/redo

**Design decisions:**

- JDK HttpClient chosen over OkHttp to reduce transitive dependencies in the Quarkus native image
- Agent Father tool whitelisting uses predefined quick-reply presets (calculator+web, all tools, etc.) plus custom JSON array input
- Chat UI uses vanilla CSS over Tailwind to stay framework-agnostic for future React Native conversion

### 2026-03-10 — Phase 4.4/4.5: JSON Schema Migration, Build Optimization, Dashboard Replacement

**Repos:** EDDI, EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 4 — Items #4, #5

**What changed:**

**EDDI (backend):**

- `pom.xml`: Migrated from `kjetland/jackson-jsonSchema` to `victools/jsonschema-generator` + `jsonschema-module-jackson` v4.38.0 (Jackson 2.x compatible, Draft 2020-12)
- `JsonSchemaCreator.java` [REWRITTEN]: victools-based schema generation replacing kjetland
- `RegularDictionaryConfiguration.java` + output types: Replaced kjetland annotations (`@JsonSchemaDescription`, `@JsonSchemaDefault`, `@JsonSchemaInject`, `@JsonSchemaTitle`) with Jackson-standard (`@JsonPropertyDescription`, `@JsonClassDescription`, `@JsonProperty(defaultValue)`)
- `JsonSchemaCreatorTest.java` [NEW]: 11 parameterized tests verifying Draft 2020-12 schema generation for all 7 config types
- `index.html` [REPLACED]: Old Bootstrap/jQuery dashboard → redirect to `/manage`
- `manage.html` [REWRITTEN]: New Manager SPA entry with branded loading indicator (EDDI logo + gold spinner)
- **Asset cleanup**: Removed 60+ stale files (old webpack bundles, bootstrap, jQuery, moment, KaTeX fonts, Slick fonts, code-split chunks). Down from 80+ to 17 files in `META-INF/resources/`
- Deployed fresh Chat-UI + Manager builds to `scripts/js/` and `scripts/css/`

**EDDI-Manager:**

- `handlers.ts`: Enriched all 8 MSW mock schemas with realistic Draft 2020-12 field definitions
- `App.tsx`: Reverted React.lazy code splitting → static imports (simpler for admin dashboard)
- `vite.config.ts`: Removed manualChunks vendor splitting → single bundle output
- `sidebar.tsx`: Added external links section with OpenAPI (`/q/swagger-ui`) and Documentation (`docs.labs.ai`) links

**Design decisions:**

- **Single bundle over code splitting**: Admin dashboard prioritizes simplicity/maintainability. 1.2 MB JS (mainly Monaco Editor) gzips to ~350 KB — acceptable for admin use
- **victools 4.38.0** not 5.0.0: v5.0.0 requires Jackson 3.x (namespace `tools.jackson.*`) which is incompatible with Quarkus's Jackson 2.x
- **Dashboard replacement**: Old EDDI dashboard was duplicating functionality already in Manager; external links (Swagger, Docs) added to sidebar instead

**Testing:**

- [x] Backend: all tests pass including 11 new `JsonSchemaCreatorTest` cases
- [x] Manager: `npx tsc --noEmit` ✅, 17/17 tests pass, build succeeds
- [x] Agenth repos committed with clean working trees

### 2026-03-09 — Phase 4.3: Real-Backend Integration Testing

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 4.3

**What changed:**

- `docker-compose.integration.yml` [NEW]: Lightweight compose (EDDI latest + MongoDB) for integration test environment; alternative: run EDDI from source with `mvnw quarkus:dev`
- `e2e/integration/integration-helpers.ts` [NEW]: `waitForBackend()` (polls `/q/health/live`), `extractIdFromLocation()` (regex-based eddi:// parser), `createAndDeployAgent()` (self-contained test setup), `cleanupResource()`
- `e2e/integration/agents.integration.spec.ts` [NEW]: 5 tests — descriptors, CRUD round-trip, duplicate, delete, JSON Schema
- `e2e/integration/packages.integration.spec.ts` [NEW]: 4 tests — descriptors, CRUD round-trip, delete, JSON Schema
- `e2e/integration/resources.integration.spec.ts` [NEW]: 18 tests — CRUD + descriptors + JSON Schema for all 6 resource types
- `e2e/integration/conversations.integration.spec.ts` [NEW]: 5 tests — descriptors, create conversation, **send message (200 ✅)**, read state, filtered list
- `e2e/integration/deployment.integration.spec.ts` [NEW]: 4 tests — deploy (202), status (plain text READY), non-deployed NOT_FOUND, undeploy
- `e2e/integration/schemas.integration.spec.ts` [NEW]: 8 tests — JSON Schema for all 8 store types
- `package.json` [MODIFIED]: Added `test:integration` script

**Key findings from live testing:**

1. **POST /say returns 200 in v6 — AsyncResponse 500 is FIXED** ✅ The conversation endpoint returns a full JSON snapshot with `conversationState: "READY"` instead of the 500 timeout documented in `business-logic-analysis.md §4`
2. **Duplicate POST returns 200** — should return **201** (new resource created). Backend fix pending.
3. **DELETE is soft-delete** — returns 409 if a newer version exists (even if that version is also soft-deleted). **Inconsistent across stores**: LangChain returns 404 for older versions after deleting newer. Proposal: add `?permanent=true` query param.
4. **Deployment status returns plain text** (`READY`, `NOT_FOUND`) not JSON. May want to standardize.
5. **Health endpoint** (`/q/health`) returns HTML error page when Quarkus dev-services fails (e.g., port conflict) instead of a proper JSON error. The liveness endpoint (`/q/health/live`) is more reliable.

**Parallelism strategy:**

- 6 spec files run **in parallel across workers** (10 workers default)
- Tests within each spec run **serially** (shared cleanup state)
- Total: **44 tests, 28.8s**, all passing

---

### 2026-03-09 — Phase 4.2: E2E Test Suite (Playwright)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 4.2

**What changed:**

- `e2e/e2e-helpers.ts` [NEW]: Shared helpers (`waitForApp`, `expectHeading`) — waits for MSW worker init + skeleton loaders
- `e2e/dashboard.spec.ts` [NEW]: 6 tests — stat cards, quick actions, recent agents, navigation
- `e2e/agents.spec.ts` [NEW]: 8 tests — agent cards, search, create dialog, wizard page, import button
- `e2e/agent-detail.spec.ts` [NEW]: 9 tests — heading, version selector, deployment status, environments, packages, action buttons, raw config
- `e2e/packages.spec.ts` [NEW]: 10 tests — package cards, search, create, package detail with version selector, add extension, raw config
- `e2e/conversations.spec.ts` [NEW]: 8 tests — table, state badges, filters, conversation detail, back nav
- `e2e/chat.spec.ts` [NEW]: 5 tests — heading, agent selector (Radix Select), streaming toggle, agent dropdown
- `e2e/resources.spec.ts` [NEW]: 20 tests — hub grid (6 types), resource list, detail with back link, 6 individual type tests
- `e2e/navigation.spec.ts` [MODIFIED]: Added `waitForApp` helper, scoped links to sidebar, added chat route test (6 → 6 tests)
- `e2e/rtl.spec.ts` [MODIFIED]: Fixed strict mode violation on Arabic text (`.first()`)

**Key decisions:**

1. **MSW auto-detection** — no test fixtures needed; the Vite dev server auto-starts MSW browser worker when the real backend is unreachable
2. **Text-based selectors** — detail pages (agent-detail, package-detail) use text selectors when `data-testid` isn't present; hub pages (resources) use existing testids
3. **Scoped selectors** — sidebar nav links scoped to `getByTestId('sidebar')` to avoid strict mode violations from duplicate text in sidebar + content
4. **`.or()` pattern** — version selectors use `badge.or(picker)` to handle single-version vs multi-version rendering

**Test count:** 75 tests, 0 failures across chromium (single project for speed)

---

### 2026-03-05 — Typed Memory Accessors

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #2

**What changed:**

- `MemoryKey.java` [NEW]: Generic type-safe key class — binds a string name to a Java type and carries an `isPublic` flag
- `MemoryKeys.java` [NEW]: Central registry of well-known keys (`ACTIONS`, `INPUT`, `EXPRESSIONS_PARSED`, `INTENTS`, etc.) with prefix strings for dynamic keys (`OUTPUT_PREFIX`, `QUICK_REPLIES_PREFIX`)
- `IConversationMemory.java` [MODIFIED]: Added typed accessor methods `get(MemoryKey)`, `getData(MemoryKey)`, `getLatestData(MemoryKey)`, `set(MemoryKey, T)` to step interfaces
- `ConversationStep.java`, `ConversationMemory.java` [MODIFIED]: Implemented typed accessors
- 10 production files migrated: `Conversation`, `LifecycleManager`, `ConversationMemoryUtilities`, `InputParserTask`, `BehaviorRulesEvaluationTask`, `ActionMatcher`, `InputMatcher`, `HttpCallsTask`, `LangchainTask`, `OutputGenerationTask`
- `MemoryKeyTest.java` [NEW]: 15 tests for MemoryKey and typed accessors
- 4 existing test files updated: `ActionMatcherTest`, `InputMatcherTest`, `OutputItemContainerGenerationTaskTest`, `LangchainTaskTest`

**Design decision:**
Additive approach — typed methods sit alongside existing string-based methods for backward compatibility. Dynamic keys (output:text:action, quickReplies:action) remain as `String` prefix constants since they're used for key construction. `occurredInAnyStep()` continues to use string-based `getLatestData(String)` via `getAllLatestData(String)`.

**Testing:**

- [x] Builds cleanly
- [x] All 533 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

### 2026-03-05 — Consolidate Configuration Stores

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #3

**What changed:**

- `AbstractMongoResourceStore.java` [NEW]: Generic abstract base class that encapsulates the shared `MongoResourceStorage` + `HistorizedResourceStore` constructor pattern and 7 CRUD delegation methods. Two constructors: standard (creates storage internally) and custom (accepts pre-built `HistorizedResourceStore` for stores with inner class extensions)
- `LangChainStore.java` [MODIFIED]: 72→23 lines. Extends `AbstractMongoResourceStore`, all CRUD delegation removed
- `ParserStore.java` [MODIFIED]: 69→23 lines. Same pattern
- `PropertySetterStore.java` [MODIFIED]: 68→23 lines. Same pattern
- `HttpCallsStore.java` [MODIFIED]: 91→41 lines. CRUD removed; `readActions()` retained
- `BehaviorStore.java` [MODIFIED]: 98→57 lines. CRUD removed; `readActions()` + validation overrides retained
- `OutputStore.java` [MODIFIED]: 127→93 lines. CRUD removed; filtering/sorting `read()` + `readActions()` retained
- `RegularDictionaryStore.java` [MODIFIED]: 162→127 lines. CRUD removed; filtering/sorting + comparators retained
- `AgentStore.java` [MODIFIED]: 158→140 lines. Uses second constructor; inner classes + custom query retained
- `WorkflowStore.java` [MODIFIED]: 171→155 lines. Same approach as AgentStore
- `AbstractMongoResourceStoreTest.java` [NEW]: 7 tests verifying CRUD delegation

**Design decision:**
Two-constructor base class: standard constructor for 7 stores, second constructor accepts pre-built `HistorizedResourceStore` for `AgentStore`/`WorkflowStore` which have custom inner classes extending `MongoResourceStorage`. `@ConfigurationUpdate` annotation stays on base class `update()`/`delete()` — CDI interceptors bind by annotation regardless of concrete class. Stores with validation (e.g. `BehaviorStore`) override `create()`/`update()` and call `super` after validation.

**Testing:**

- [x] Builds cleanly
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

**Commit:** `refactor(configs): consolidate configuration stores into AbstractMongoResourceStore` (`201c5f99`)

### 2026-03-05 — Extract ConversationService from RestAgentEngine

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #1

**What changed:**

- `IConversationService.java` [NEW]: Domain interface with `ConversationResponseHandler` callback, records (`ConversationResult`, `ConversationLogResult`), and domain exceptions (`AgentNotReadyException`, `ConversationNotFoundException`, `AgentMismatchException`, `ConversationEndedException`)
- `ConversationService.java` [NEW]: All business logic extracted from RestAgentEngine — conversation lifecycle, metrics, caching, memory management (~565 lines)
- `RestAgentEngine.java` [MODIFIED]: Refactored from 668 to ~230 lines — now a thin JAX-RS adapter that delegates to `IConversationService` and maps domain exceptions to HTTP responses
- `ConversationServiceTest.java` [NEW]: 16 unit tests covering start, end, state, read, say, undo/redo, and properties handler

**Design decision:**
Kept metrics and caching inside `ConversationService` rather than extracting separate `ConversationMetricsService` and `ConversationStateCache` classes. The metrics code is ~20 lines and caching is 3 trivial methods — separate classes would be premature decomposition. Can be split later if needed.

**Testing:**

- [x] Builds cleanly
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

**Commit:** `refactor(engine): extract ConversationService from RestAgentEngine` (`7dd1488e`)

### 2026-03-06 — Decompose LangchainTask into Focused Classes

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #6

**What changed:**

- `ChatModelRegistry.java` [NEW]: Model creation, caching, and lookup. Owns ConcurrentHashMap model cache, filterParams logic, ModelCacheKey record, UnsupportedLangchainTaskException
- `ConversationHistoryBuilder.java` [NEW]: Memory → ChatMessage list conversion. Handles system message prepending, prompt replacement, log size limits, multi-modal content types
- `LegacyChatExecutor.java` [NEW]: Simple chat mode (no tools). Executes chat with retry, returns response + metadata
- `AgentOrchestrator.java` [NEW]: Tool-calling agent loop. Owns all built-in tool references, collectEnabledTools logic, tool spec/executor building, budget checks, execution trace
- `LangchainTask.java` [MODIFIED]: 592→252 lines. Now a thin orchestrator delegating to the 4 helper classes. Constructor signature unchanged (CDI injection preserved)
- `AgentExecutionHelper.java` [MODIFIED]: 223→140 lines. Trimmed to just retry logic (executeWithRetry, executeChatWithRetry, isRetryableError). collectEnabledTools moved to AgentOrchestrator
- `AgentExecutionHelperTest.java` [MODIFIED]: Trimmed to 7 retry-focused tests. Tool collection tests moved to new test
- `AgentOrchestratorTest.java` [NEW]: 16 tests for tool collection, isAgentMode, getSystemMessage

**Design decision:**
All 4 new classes are package-private helpers (not CDI beans). They are instantiated by LangchainTask's constructor and receive dependencies through constructor parameters. This keeps the public API surface unchanged — LangchainTask remains the sole @ApplicationScoped entry point. AgentExecutionHelper was kept as a lean static utility for retry logic rather than deleted entirely.

**Testing:**

- [x] Builds cleanly
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

### 2026-03-06 — Phase 2: Testing Infrastructure + Weather Agent Fix

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 2 — Integration Testing

**What changed:**

- Migrated all integration tests from `EDDI-integration-tests` into main repo
- Created `BaseIntegrationIT` [NEW]: shared helpers for resource creation, agent deployment, cleanup
- Created `IntegrationTestProfile` [NEW]: DevServices MongoDB via Testcontainers, fixed test port 8081
- Created 8 integration test classes [NEW]: `AgentEngineIT` (11), `AgentDeploymentComponentIT` (4), `ConversationServiceComponentIT` (7), `ApiContractIT` (10), `AgentUseCaseIT` (2), `BehaviorCrudIT` (4), `DictionaryCrudIT` (5), `OutputCrudIT` (5)
- Filled unit test gaps [NEW]: `BehaviorRulesEvaluationTaskTest` (11), `RestAgentEngineTest` (14), `ConversationHistoryBuilderTest`, `LegacyChatExecutorTest`
- `RestInterfaceFactory.java` [MODIFIED]: inject `quarkus.http.port` via `@ConfigProperty` instead of hardcoded 7070

**Design decisions:**

- Fixed test port 8081 instead of random — `RestInterfaceFactory` needs agenth `quarkus.http.port` and `quarkus.http.test-port` set to same value since `@QuarkusTest` reads config property as configured value, not runtime value
- Weather agent analysis confirmed v6.0 compatibility — no migration needed. `fromObjectPath` expressions work with PathNavigator
- `EDDI-integration-tests` repo is now fully superseded

**Testing:**

- [x] Builds cleanly
- [x] All 48 integration tests pass (0 failures, 0 skipped)
- [x] All 620 unit tests pass
- [x] Total: 668 tests, no regressions

**Commit:** `feat: Phase 2 testing infrastructure + weather agent fix` (`0956cefd`)

### 2026-03-07 — Phase 3.16: Workflow Editor with Drag-and-Drop Pipeline Builder

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #16

**What changed:**

- `extensions.ts` [NEW]: API module for `GET /extensionstore/extensions` + `EXTENSION_TYPE_INFO` registry mapping 7 extension types to labels, icons, and pipeline order
- `use-extensions-store.ts` [NEW]: TanStack Query hook for fetching available extension types (5-minute cache)
- `pipeline-builder.tsx` [NEW]: `@dnd-kit` sortable list component — drag handles with pointer + keyboard sensors, step numbers, type icons, `eddi://` URI parsing to resource detail links, remove buttons
- `add-extension-dialog.tsx` [NEW]: Modal listing available extension types from `/extensionstore/extensions` with search filter and pipeline-order sorting
- `package-detail.tsx` [REWRITE]: Full editor with drag-and-drop pipeline builder, version picker, add/remove extensions, save/discard with dirty-state tracking, delete, collapsible raw JSON viewer
- `packages.ts` [MODIFIED]: Added `getWorkflowVersions()` for version picker
- `use-packages.ts` [MODIFIED]: Added `useWorkflowVersions()` hook
- `handlers.ts` [MODIFIED]: MSW handlers for `GET /extensionstore/extensions` (7 types) and `PUT /packagestore/packages/:id`
- `en.json` + 10 locales [MODIFIED]: Added `packageEditor.*` i18n keys
- `package-detail.test.tsx` [NEW]: 6 component tests (heading, pipeline items, add button, save/discard, delete)

**Design decision:**
Side-sheet extension inspector deferred to Phase 3.17–3.18. For now, clicking an extension shows its config URI as a link to the existing resource detail page. This keeps the Phase 3.16 scope focused on pipeline reordering and the add/remove workflow.

**Testing:**

- [x] Builds cleanly (`npx tsc -b` clean, `npm run build` succeeds)
- [x] All 105 tests pass (14 test files)
- [x] Verified in browser — packages page, create dialog, error states render correctly
- [x] No regressions

### 2026-03-07 — Phase 3.17: Behavior Rules & HTTP Calls Editors

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #17

**What changed:**

- `behavior-editor.tsx` [NEW]: Form editor for `BehaviorConfiguration` — groups accordion → rule cards with action tag input → recursive condition editors (6 types: inputmatcher, actionmatcher, negation, connector, occurrence, dynamicValueMatcher) with key-value config pairs and nested conditions
- `httpcalls-editor.tsx` [NEW]: Form editor for `HttpCallsConfiguration` — server URL, collapsible call cards with color-coded method badge, request builder (method, path, content type, headers/queryParams KV, body textarea), LLM agent parameters, options toggles, pre/post JSON preview
- `config-editor-layout.tsx` [MODIFIED]: `renderFormEditor` render prop for two-way Form↔JSON binding; default tab = "Form" when editor exists
- `resource-detail.tsx` [MODIFIED]: Wires agenth editors via `renderFormEditor` prop keyed by resource type
- `handlers.ts` [MODIFIED]: Realistic MSW mock data for behavior (1 group, 2 rules, 3 conditions) and httpcalls (1 call with full request)
- `en.json` + 10 locales [MODIFIED]: 60+ new i18n keys (`behaviorEditor.*`, `httpcallsEditor.*`, `editor.invalidJson`)
- `resource-detail-behavior.test.tsx` [NEW]: 7 tests
- `resource-detail-httpcalls.test.tsx` [NEW]: 7 tests

**Design decision:**
Render prop pattern on `ConfigEditorLayout` — form editors receive parsed data and push changes back as objects, which the layout serializes to JSON. This keeps Form↔JSON in perfect sync without extra state management. Pre/post instructions rendered as JSON preview for now; full sub-editors deferred to Phase 3.19.

**Testing:**

- [x] Builds cleanly (`npx tsc -b` clean, `npm run build` succeeds)
- [x] All 119 tests pass (16 test files)
- [x] No regressions

### 2026-03-08 — Phase 3.18: LangChain, Output, Property Setter, Dictionary Editors

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #18

**What changed:**

- `langchain-editor.tsx` [NEW]: Form editor for LangChain configuration — tasks management, model parameters, tool references, built-in tools whitelist
- `output-editor.tsx` [NEW]: Form editor for Output Sets — action-grouped outputs with text alternatives, quick replies, and delay settings
- `propertysetter-editor.tsx` [NEW]: Form editor for Property Setter — action-triggered property assignments with scope selection
- `dictionary-editor.tsx` [NEW]: Form editor for Regular Dictionaries — words, phrases, and regex patterns with expression mapping
- `handlers.ts` [MODIFIED]: Added realistic MSW mock data for langchain, output, propertysetter, and dictionary resources
- 10 locale files [MODIFIED]: 80+ new i18n keys for all 4 editors

**Testing:**

- [x] Builds cleanly
- [x] All 160 tests pass (22 test files)
- [x] No regressions

### 2026-03-08 — Phase 3.19: Polish, Tests & Documentation

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #19

**What changed:**

- `resources.test.ts` [NEW]: 10 API-layer tests for generic resource CRUD
- `dashboard.test.tsx` [NEW]: Dashboard component test with hook mocking
- `renderPage` test helper [NEW]: DRY utility wrapping providers for page tests
- `README.md` [REWRITTEN]: Architecture overview, quickstart, and technology stack
- `HANDOFF.md` [UPDATED]: Phase 3.19 completion documented

**Testing:**

- [x] Builds cleanly
- [x] All 160 tests pass (22 test files)
- [x] No regressions

### 2026-03-08 — Phase 3.20: UI/UX Enterprise Polish

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #20

**What changed:**

- **Foundation UI Components** (6 new): `button.tsx` (cva variants), `card.tsx` (compound), `badge.tsx`, `skeleton.tsx`, `input.tsx`, `alert-dialog.tsx` (replaces `window.confirm()`)
- **Shared Components** (3 new): `back-link.tsx`, `empty-state.tsx`, `error-state.tsx`
- `index.css` [MODIFIED]: Deep charcoal dark mode (`#09090b`), premium aesthetic
- `main.tsx` [MODIFIED]: Sonner `<Toaster />` at app root for global toast notifications
- `sidebar.tsx` [MODIFIED]: Section groupings (Management/Development/Operations), active accent states
- `top-bar.tsx` [MODIFIED]: Dynamic breadcrumb navigation from URL path
- `utils.ts` [MODIFIED]: Centralized `formatRelativeTime` and `statusConfig`
- **5 pages refactored**: `agents.tsx`, `packages.tsx`, `conversations.tsx`, `resource-list.tsx`, `resource-detail.tsx` — all now use Button, Skeleton, AlertDialog, shared Empty/Error states, and toast notifications
- `dashboard.tsx` [REWRITTEN]: Real API data with stat cards, quick action buttons, recent agents grid
- `dashboard.ts` [NEW]: Dashboard API aggregation layer
- `use-dashboard.ts` [NEW]: TanStack Query hooks for dashboard stats/recent agents
- 11 locale files [MODIFIED]: New sidebar and dashboard i18n keys

**Design decisions:**

- `cva` (class-variance-authority) + Radix UI primitives for consistent design system
- `sonner` for toast notifications over native alerts
- Skeleton shimmer loaders prioritized over spinners
- `AlertDialog` replaces `window.confirm()` for accessible confirmation dialogs

**Testing:**

- [x] TypeScript: zero errors
- [x] All 160 tests pass (22 test files)
- [x] Verified in browser — all pages render correctly
- [x] No regressions

### 2026-03-09 — Phase 3.21: MSW Browser Mode, Backend Integration & JSON Schema

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #21

**What changed:**

- `browser.ts` [NEW]: MSW browser worker using `setupWorker()` from `msw/browser`, reuses existing test handlers
- `main.tsx` [MODIFIED]: `startApp()` async boot — probes backend with 1.5s timeout; if unreachable, dynamically imports and starts MSW browser worker so dev UI works without a running backend
- `vite.config.ts` [MODIFIED]: Added missing `/extensionstore` proxy to `localhost:7070`
- `schemas.ts` [NEW]: API functions to fetch JSON Schema from backend's `/{store}/{plural}/jsonSchema` endpoints, with in-memory cache
- `use-json-schema.ts` [NEW]: TanStack Query hook with `staleTime: Infinity` (schemas are static)
- `json-editor.tsx` [MODIFIED]: `jsonSchema` prop + `beforeMount` callback configures Monaco `setDiagnosticsOptions` for validation and autocomplete
- `config-editor-layout.tsx` [MODIFIED]: `jsonSchema` prop passthrough to `JsonEditor`
- `resource-detail.tsx` [MODIFIED]: Calls `useJsonSchema(type)` and passes schema to `ConfigEditorLayout`
- `handlers.ts` [MODIFIED]: Mock schema handlers for all 8 resource types + agents + packages
- `public/mockServiceWorker.js` [NEW]: MSW service worker script (auto-generated by `npx msw init`)

**Design decisions:**

- MSW auto-detection over manual flag: the app probes the backend to decide whether to mock — no env vars needed
- JSON Schema fetched once, cached forever (schemas don't change at runtime)
- Monaco `setDiagnosticsOptions` provides agenth validation (red squiggles) and autocomplete (Ctrl+Space) from a single schema

**Testing:**

- [x] TypeScript: zero errors
- [x] All 160 tests pass (22 test files)
- [x] Verified in browser — all pages show MSW mock data when backend is unavailable
- [x] No regressions

### 2026-03-09 — Phase 4 Roadmap Finalized

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 4 — Hardening & Production Readiness

**What changed:**

Phase 3 (Manager UI Rewrite) is complete (3.1–3.21). Phase 4 roadmap confirmed:

| Phase | Description                                                                                                       | Status |
| ----- | ----------------------------------------------------------------------------------------------------------------- | ------ |
| 4.1   | **Keycloak Auth Adapter** — wire `keycloak-js` 26+, login/logout flow, token refresh, route guards, role-based UI | ⬜     |
| 4.2   | **E2E Test Suite (Playwright)** — full coverage of agents, packages, editors, chat                                | ⬜     |
| 4.3   | **Real-Backend Integration Testing** — CRUD round-trips against live EDDI backend                                 | ⬜     |
| 4.4   | **JSON Schema Enrichment** — real field definitions for dev-mode autocomplete                                     | ⬜     |
| 4.5   | **Production Build Optimization** — bundle analysis, code splitting, lazy loading                                 | ⬜     |

**Phase 5+**: Chat-UI Rewrite (`eddi-chat-ui`), Website → Astro (`eddi-website`), further backend work.

**Files updated:** `HANDOFF.md`, `AGENTS.md`, `changelog.md`

### 2026-03-09 — Phase 4.1: Keycloak Auth Adapter

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 4 — Item #1

**What changed:**

- `keycloak-js@26` [NEW DEP]: Official Keycloak JavaScript adapter
- `docker-compose.keycloak.yml` [NEW]: Local Keycloak 26 dev environment on port 8180 with auto-imported realm
- `keycloak/eddi-realm.json` [NEW]: Pre-configured realm `eddi` — client `eddi-manager` (public SPA), roles (admin/editor/viewer), test users (eddi/eddi, viewer/viewer)
- `docker-compose.keycloakenabled.yml` [DELETED]: Referenced dead `sso.labs.ai` SSO
- `docker-compose.keycloakenabled.local.yml` [DELETED]: Same — obsolete
- `auth-config.ts` [NEW]: Three-tier config reader: Vite env → `window.__EDDI_AUTH__` global → default `'none'`
- `auth-context.ts` [NEW]: React context + types (separated from component for Fast Refresh)
- `auth-provider.tsx` [NEW]: Two-branch provider — `'none'` renders children immediately, `'keycloak'` handles init lifecycle with loading screen, PKCE S256, token refresh, profile loading
- `use-auth.ts` [NEW]: `useAuth()` + `useHasRole()` hooks
- `main.tsx` [MODIFIED]: Wraps app tree in `<AuthProvider>` (above QueryClientProvider)
- `sidebar.tsx` [MODIFIED]: User profile section (avatar initials, name, email, logout button) — hidden when auth disabled
- `top-bar.tsx` [MODIFIED]: User avatar + dropdown menu with logout — hidden when auth disabled
- `en.json` + 10 locales [MODIFIED]: `auth.*` i18n keys (login, logout, loading, sessionExpired, profile, guest)
- `setup.ts` [MODIFIED]: Global `vi.mock('keycloak-js')` so all tests pass without Keycloak
- `auth-provider.test.tsx` [NEW]: 4 tests (disabled auth rendering, authenticated state, loading state, no user section)

**Design decisions:**

- **Auth is optional** — default is `'none'` (no login required). Enabled via `VITE_AUTH_METHOD=keycloak` or `window.__EDDI_AUTH__`
- **Local Keycloak via docker-compose** — one command (`docker compose -f docker-compose.keycloak.yml up`) gives a fully configured SSO with realm, client, roles, and test users
- **PKCE S256** for all auth flows — modern security best practice, no client secret needed
- **Context split** — `AuthContext` in separate `auth-context.ts` file to satisfy React Fast Refresh (contexts can't be in the same file as components)

**Testing:**

- [x] TypeScript: zero errors
- [x] All 164 tests pass (23 test files)
- [x] Build succeeds
- [x] No regressions

### 2026-03-09 — Phase 4.1b: Full-Stack Keycloak Docker-Compose

**Repo:** EDDI + EDDI-Manager  
**Branch:** `feature/version-6.0.0`

**What changed:**

- `application.properties` [MODIFIED]: `quarkus.oidc.enabled=true` (build-time), `quarkus.oidc.tenant-enabled=false` (runtime toggle)
- `RestAgentManagement.java` [MODIFIED]: Reads `quarkus.oidc.tenant-enabled` instead of `quarkus.oidc.enabled`
- `IntegrationTestProfile.java` [MODIFIED]: Updated to override `tenant-enabled`
- `docker-compose.keycloak.yml` [MODIFIED]: Full stack — Keycloak 26 + EDDI backend (`labsai/eddi:6`, OIDC enabled) + MongoDB
- `eddi-realm.json` [MODIFIED]: Added `eddi-backend` bearer-only client, `localhost:3000` to redirect/webOrigins

**Design decisions:**

- `quarkus.oidc.enabled` is build-time — switched to `quarkus.oidc.tenant-enabled` (runtime) for the actual auth toggle
- Backend uses bearer-only client (validates tokens, never initiates login)
- One `docker compose -f docker-compose.keycloak.yml up` brings up everything with auth

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

| Date       | Decision                                                              | Context                                | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | -------------------------------------- | ----------------------------------------------------------- |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages            | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work     | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder  | Could migrate to Astro Content Collections later            |
| 2026-03-10 | Move NATS, Postgres, MCP, multi-agent, persistent memory into v6.0    | All are core platform capabilities     | Only Redis cache and Helm/OTel deferred post-6.0            |
| 2026-03-10 | Website migration is last phase (Phase 11) before v6.0 GA             | Website is standalone, lowest priority | Could interleave with backend work                          |
|            |                                                                       |                                        |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date       | Regression                                  | Cause                                                       | Fix                                      | Commit     |
| ---------- | ------------------------------------------- | ----------------------------------------------------------- | ---------------------------------------- | ---------- |
| 2026-03-13 | `AgentUseCaseIT.useAgentManagement` 500/NPE | Stale agent trigger from previous test run with old agentId | DELETE trigger before POST in test setup | `e77b6f23` |
|            |                                             |                                                             |                                          |            |
