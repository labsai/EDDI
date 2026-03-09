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

_Entries will be added here as implementation progresses._

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
- `BotStore.java` [MODIFIED]: 158→140 lines. Uses second constructor; inner classes + custom query retained
- `PackageStore.java` [MODIFIED]: 171→155 lines. Same approach as BotStore
- `AbstractMongoResourceStoreTest.java` [NEW]: 7 tests verifying CRUD delegation

**Design decision:**
Two-constructor base class: standard constructor for 7 stores, second constructor accepts pre-built `HistorizedResourceStore` for `BotStore`/`PackageStore` which have custom inner classes extending `MongoResourceStorage`. `@ConfigurationUpdate` annotation stays on base class `update()`/`delete()` — CDI interceptors bind by annotation regardless of concrete class. Stores with validation (e.g. `BehaviorStore`) override `create()`/`update()` and call `super` after validation.

**Testing:**

- [x] Builds cleanly
- [x] All 540 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

**Commit:** `refactor(configs): consolidate configuration stores into AbstractMongoResourceStore` (`201c5f99`)

### 2026-03-05 — Extract ConversationService from RestBotEngine

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #1

**What changed:**

- `IConversationService.java` [NEW]: Domain interface with `ConversationResponseHandler` callback, records (`ConversationResult`, `ConversationLogResult`), and domain exceptions (`BotNotReadyException`, `ConversationNotFoundException`, `BotMismatchException`, `ConversationEndedException`)
- `ConversationService.java` [NEW]: All business logic extracted from RestBotEngine — conversation lifecycle, metrics, caching, memory management (~565 lines)
- `RestBotEngine.java` [MODIFIED]: Refactored from 668 to ~230 lines — now a thin JAX-RS adapter that delegates to `IConversationService` and maps domain exceptions to HTTP responses
- `ConversationServiceTest.java` [NEW]: 16 unit tests covering start, end, state, read, say, undo/redo, and properties handler

**Design decision:**
Kept metrics and caching inside `ConversationService` rather than extracting separate `ConversationMetricsService` and `ConversationStateCache` classes. The metrics code is ~20 lines and caching is 3 trivial methods — separate classes would be premature decomposition. Can be split later if needed.

**Testing:**

- [x] Builds cleanly
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

**Commit:** `refactor(engine): extract ConversationService from RestBotEngine` (`7dd1488e`)

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

### 2026-03-06 — Phase 2: Testing Infrastructure + Weather Bot Fix

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 2 — Integration Testing

**What changed:**

- Migrated all integration tests from `EDDI-integration-tests` into main repo
- Created `BaseIntegrationIT` [NEW]: shared helpers for resource creation, bot deployment, cleanup
- Created `IntegrationTestProfile` [NEW]: DevServices MongoDB via Testcontainers, fixed test port 8081
- Created 8 integration test classes [NEW]: `BotEngineIT` (11), `BotDeploymentComponentIT` (4), `ConversationServiceComponentIT` (7), `ApiContractIT` (10), `BotUseCaseIT` (2), `BehaviorCrudIT` (4), `DictionaryCrudIT` (5), `OutputCrudIT` (5)
- Filled unit test gaps [NEW]: `BehaviorRulesEvaluationTaskTest` (11), `RestBotEngineTest` (14), `ConversationHistoryBuilderTest`, `LegacyChatExecutorTest`
- `RestInterfaceFactory.java` [MODIFIED]: inject `quarkus.http.port` via `@ConfigProperty` instead of hardcoded 7070

**Design decisions:**

- Fixed test port 8081 instead of random — `RestInterfaceFactory` needs both `quarkus.http.port` and `quarkus.http.test-port` set to same value since `@QuarkusTest` reads config property as configured value, not runtime value
- Weather bot analysis confirmed v6.0 compatibility — no migration needed. `fromObjectPath` expressions work with PathNavigator
- `EDDI-integration-tests` repo is now fully superseded

**Testing:**

- [x] Builds cleanly
- [x] All 48 integration tests pass (0 failures, 0 skipped)
- [x] All 620 unit tests pass
- [x] Total: 668 tests, no regressions

**Commit:** `feat: Phase 2 testing infrastructure + weather bot fix` (`0956cefd`)

### 2026-03-07 — Phase 3.16: Package Editor with Drag-and-Drop Pipeline Builder

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #16

**What changed:**

- `extensions.ts` [NEW]: API module for `GET /extensionstore/extensions` + `EXTENSION_TYPE_INFO` registry mapping 7 extension types to labels, icons, and pipeline order
- `use-extensions-store.ts` [NEW]: TanStack Query hook for fetching available extension types (5-minute cache)
- `pipeline-builder.tsx` [NEW]: `@dnd-kit` sortable list component — drag handles with pointer + keyboard sensors, step numbers, type icons, `eddi://` URI parsing to resource detail links, remove buttons
- `add-extension-dialog.tsx` [NEW]: Modal listing available extension types from `/extensionstore/extensions` with search filter and pipeline-order sorting
- `package-detail.tsx` [REWRITE]: Full editor with drag-and-drop pipeline builder, version picker, add/remove extensions, save/discard with dirty-state tracking, delete, collapsible raw JSON viewer
- `packages.ts` [MODIFIED]: Added `getPackageVersions()` for version picker
- `use-packages.ts` [MODIFIED]: Added `usePackageVersions()` hook
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
- `resource-detail.tsx` [MODIFIED]: Wires both editors via `renderFormEditor` prop keyed by resource type
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
- **5 pages refactored**: `bots.tsx`, `packages.tsx`, `conversations.tsx`, `resource-list.tsx`, `resource-detail.tsx` — all now use Button, Skeleton, AlertDialog, shared Empty/Error states, and toast notifications
- `dashboard.tsx` [REWRITTEN]: Real API data with stat cards, quick action buttons, recent bots grid
- `dashboard.ts` [NEW]: Dashboard API aggregation layer
- `use-dashboard.ts` [NEW]: TanStack Query hooks for dashboard stats/recent bots
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
- `handlers.ts` [MODIFIED]: Mock schema handlers for all 8 resource types + bots + packages
- `public/mockServiceWorker.js` [NEW]: MSW service worker script (auto-generated by `npx msw init`)

**Design decisions:**

- MSW auto-detection over manual flag: the app probes the backend to decide whether to mock — no env vars needed
- JSON Schema fetched once, cached forever (schemas don't change at runtime)
- Monaco `setDiagnosticsOptions` provides both validation (red squiggles) and autocomplete (Ctrl+Space) from a single schema

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
| 4.2   | **E2E Test Suite (Playwright)** — full coverage of bots, packages, editors, chat                                  | ⬜     |
| 4.3   | **Real-Backend Integration Testing** — CRUD round-trips against live EDDI backend                                 | ⬜     |
| 4.4   | **JSON Schema Enrichment** — real field definitions for dev-mode autocomplete                                     | ⬜     |
| 4.5   | **Production Build Optimization** — bundle analysis, code splitting, lazy loading                                 | ⬜     |

**Phase 5+**: Chat-UI Rewrite (`eddi-chat-ui`), Website → Astro (`eddi-website`), further backend work.

**Files updated:** `HANDOFF.md`, `AGENTS.md`, `changelog.md`

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
