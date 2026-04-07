# EDDI Manager — Handoff Document

> **Last updated**: 2026-04-07  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: `6ee2922` feat(v6): Phase 13 Polish - Studio nav, slug fixes, keyboard nav, tool metrics MSW, mobile tabs

---

## Current Status
- **Phase 4.5 (Production Build + Dashboard)** completed.
- **Agent/Workflow Creation Fix:** Hooked up `patchDescriptor` in the `EDDI-Manager` API to properly save `name` and `description` when creating new resources.
- **Terminology Update:** Renamed "Packages" to "Workflows", "LangChain" to "LLM", and "Behavior" to "Rules" globally across the UI. Fixed initialization crashes in the Dictionary and newly-renamed Rules editors.

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.21 complete.  
**Phase 4 (Hardening)**: Phase 4.1 + 4.2 + 4.3 + 4.4 + 4.5 complete.  
**Phase 5 (Backend features with Manager integration)**: Item 5.30 complete (Coordinator Dashboard).  
**Phase 7 (Secrets Vault)**: Chat UI secret input + Manager Secrets Admin page complete.
**Phase 7.34 (Audit Ledger)**: Manager Audit Trail UI complete.
**Phase 7.34b (Tenant Quotas)**: Backend engine + REST API + Manager Quotas admin page complete.
**Agent Setup Wizard**: Two-mode wizard (Standard + API Agent) with backend setup endpoints. LangChain editor updated with enableHttpCallTools + MCP servers.
**Group Discussion UI**: Groups page (card/list views), Group Detail (3-panel), Group Wizard (4-step), 7 components, API layer, hooks, 5 templates.
**A2A Protocol UI**: Agent-detail A2A section (enable/disable, description, skills, endpoints, Agent Card preview). LangChain editor A2A Agents section (remote agents as tools).
**LLM Editor UX**: System Prompt Qute syntax highlighting (Monarch tokenizer), Pre/Post Instructions as Section+ContentEditor, model params cleanup, Add Task dialog label fix, wizard model autocomplete + base URL hints + step labels.
**Phase 14 (UX Hardening)**: Logs session seeding + dropdown filters, Audit auto-load + dropdowns, Create+Wizard unified button, Orphan detection rich UX + selective deletion, Sidebar 4-section reorg + version display, **SecretKeyPicker** vault-aware apiKey component, **Coordinator enrichment** (hero card, success rate bar, SSE event history, error categories, throughput badge), **Dashboard enrichment** (platform health strip, recent conversations, expanded quick actions, gradient stat cards).

### What's Done

| Phase | Description                                                                                                                                                                                                                        | Commit    |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| 3.1   | Study existing Manager + implementation plan                                                                                                                                                                                       | —         |
| 3.2   | Vite + React 19 + Tailwind v4 + ESLint + Vitest + Playwright scaffold                                                                                                                                                              | `020007e` |
| 3.3   | Layout shell: sidebar, top bar, dark/light/system theme, i18n w/ RTL                                                                                                                                                               | `020007e` |
| 3.4   | Agents page: agent cards, deployment status, search, create dialog                                                                                                                                                                 | `e47b0fb` |
| 3.5   | Agent Detail page: package management, deployment, raw config viewer                                                                                                                                                               | `dadc669` |
| 3.6   | Workflow Detail page: extensions list, raw JSON viewer                                                                                                                                                                             | `938aa6e` |
| 3.7   | Workflows list page: cards grid, search, create dialog                                                                                                                                                                             | `938aa6e` |
| 3.8   | Conversations page: list w/ filters, conversation detail viewer                                                                                                                                                                    | `938aa6e` |
| 3.9   | Chat Panel: agent selector, SSE streaming toggle, history, markdown                                                                                                                                                                | —         |
| 3.10  | Resources Pages: generic CRUD for 6 extension types (hub + list + detail)                                                                                                                                                          | —         |
| 3.11  | Import/Export + Agent Wizard: file upload/download, 4-step guided creation                                                                                                                                                         | —         |
| 3.12  | i18n finalized: 11 languages (en, de, fr, es, ar, zh, th, ja, ko, pt, hi)                                                                                                                                                          | —         |
| 3.13  | EDDI branding: black/gold theme, Noto Sans font, original logo, system theme fix, wide-screen constraint                                                                                                                           | —         |
| 3.14  | JSON Editor, Version Picker, Cascade Save: Monaco, Form↔JSON toggle, config→package→agent cascade                                                                                                                                  | —         |
| 3.15  | Agent Editor: version picker, env badges (production/production/test), deploy per-env, duplicate, export                                                                                                                           | —         |
| 3.16  | Workflow Editor: drag-and-drop pipeline builder (`@dnd-kit`), add/remove extensions, save/discard, version picker                                                                                                                  | —         |
| 3.17  | Behavior Rules & HTTP Calls Editors: form-based editors with render prop, Form↔JSON sync, 14 new tests                                                                                                                             | —         |
| 3.18  | LangChain, Output, Property Setter, Dictionary Editors: 4 form editors, MSW mocks, 29 new tests                                                                                                                                    | —         |
| 3.19  | Polish, Tests & Documentation: API-layer tests, dashboard test, renderPage helper, README rewrite                                                                                                                                  | —         |
| 3.20  | UI/UX Enterprise Polish: unified component library (Button/Card/Badge/Skeleton/AlertDialog), Sonner toasts, Charcoal dark mode, sidebar sections, breadcrumbs, shared EmptyState/ErrorState/BackLink, dashboard with real API data | —         |
| 3.21  | MSW Browser Mode + JSON Schema: auto-detect backend, mock data in dev, Vite proxy fix, Monaco JSON schema validation/autocomplete from backend `/jsonSchema` endpoints                                                             | —         |
| 4.4a  | Backend: migrate from kjetland/jackson-jsonSchema to victools/jsonschema-generator v4.38.0 (Draft 2020-12). `JsonSchemaCreatorTest` (11 tests). both victools deps at 4.38.0 (Jackson 2.x)                                       | EDDI repo |
| 4.4b  | Manager: enrich all 8 MSW mock schemas (agent, package, behavior, httpcalls, output, dictionary, langchain, propertysetter) with real field definitions matching backend Java models                                               | `3013459` |
| 4.5   | Reverted to single bundle (no code splitting) — admin dashboard prioritizes simplicity over micro-optimization. Single JS (1.2 MB, mainly Monaco) + single CSS                                                                     | latest    |
| —     | **Dashboard replacement**: old Bootstrap/jQuery `index.html` → redirect to `/manage`. OpenAPI + Docs external links in sidebar. Branded loading indicator. Removed bootstrap, jQuery, moment, KaTeX, Slick, old webpack bundles    | latest    |
| —     | **Agent Setup Wizard**: Two-mode wizard (Standard Agent + API Agent) with `/administration/agents/setup` and `/setup-api` endpoints. OpenAPI spec input (URL/file/paste), LLM provider picker, feature toggles. 90+ i18n keys | `9a568ac` |
| —     | **LangChain Editor Tooling**: `enableHttpCallTools` toggle, `mcpServers` editor (URL/name/transport/apiKey), updated agent mode detection | `0b527fe` |
| —     | **Group Discussion UI**: Groups page, Group Detail, Group Wizard (4-step, batch-create, per-slot validation), Group Config Panel (style-aware, bulk delete), Discussion Transcript (5 style themes), 7 components, API layer, TanStack Query hooks, 5 templates. `deleteGroupWithMembers` soft-delete. 10 wizard tests. | pending |
| —     | **A2A Protocol UI**: Agent-detail A2A section (CTA enable/disable, description, skills tags, endpoint URLs, Agent Card preview), LangChain editor A2A Agents section (URL, name, apiKey vault warning, timeout, skills filter), MSW mocks, 10 new tests | pending |
| —     | **MCP Tool Auto-Discovery**: Discover button in MCP editor, DiscoveredToolsPanel, whitelist/blacklist add | pending |
| —     | **OpenAPI Endpoint Discovery**: Backend `discover-endpoints` via `McpApiToolBuilder`, editor-level import (Append/Replace with confirmation), workflow-level import dialog (parallel multi-config creation), URL validation, Enter key, collision-safe keys | `184c550` |
| —     | **LangChain Editor Feature Parity**: Model Cascade (reorder, confidence, strategy), Budget & Costs, Execution (parallel, rate limits, tool iterations), Retry (exponential backoff), RAG (knowledge retrieval). 5 bug fixes, 5 UX improvements, deploy.ps1 script | `1a2be77` |
| —     | **LLM Editor UX + Wizard Polish**: Custom `prompt` Monaco language (Qute-first: `{expression}`, `{#if}`, `{! comment !}`, `{| raw |}`; legacy `[[${...}]]`). Pre/Post Instructions redesigned with Section+ContentEditor. Model Params `HIDDEN_PARAM_KEYS`. Add Task dialog curated labels over backend displayName. Wizard: model datalist autocomplete per provider, base URL contextual hints, progress step labels | pending |
| 13a–c | **Agent Studio + Debugger Suite**: Debug Drawer (Pipeline Trace, Cost Dashboard, Memory Inspector, Live Log Viewer, Prompt Viewer). Agent Studio 3-panel workspace (Pipeline Railroad, Editor, Chat+Debug). Zustand debug store, SSE event dispatch, tool metrics API. 5 test files (28 tests), 30+ i18n keys, full ARIA tab pattern, RTL-clean. | pending |
| 8c-M | **RAG Knowledge Base Manager**: `RagEditor` (6-section form editor: General, Embedding Model with 7 providers + context-sensitive param hints, Vector Store tile selector with 5 stores + param cache, Chunking with visual preview, Retrieval Defaults with color-coded sliders, Document Ingestion with drag-drop + text/plain API + polling). IngestionPanel fixed: text/plain body + query params (version, documentName). ConfigEditorLayout `meta` extended with `version`. Embedding param cache on provider switch. KeyValueEditor duplicate-key guard. `ragstore` vite proxy. 11 locales cleaned (removed `isolation`). 19 RAG tests. | pending |
| Audit | **Production Readiness Audit** — 17 fixes: LLM provider sync (7→11), Caffeine cache for EmbeddingModel+StoreFactory, N+1 deployment fix (Promise.allSettled), rag-editor unmount leak, ErrorBoundary (4 tests), RTL compliance (3 files), ApiClient per-request headers, type extraction (langchain/types.ts), AGENTS.md docs sync, permanent delete API, DEV-only console.log guards | pending |
| 13d | **Onboarding Flow**: Multi-chapter interactive tour (Dashboard, Agents, Workflows, Chat, Resources). Welcome Modal (3-panel carousel, glassmorphism). Spotlight overlay (box-shadow cutout). Tour tooltip (smart placement, progress bar, keyboard nav). Per-chapter localStorage persistence. Sidebar Help menu (chapter list, completion badges, restart). Focus trap, body scroll lock, WCAG keyboard support. Zustand store, 18 tests, 60+ i18n keys across 11 locales. | pending |
| Hard | **Platform Hardening**: Secrets vault agent selector dropdown. Audit page: auto-refresh, recent entries, JSON export. Logs page: level stats bar, inline text search, export, copy-to-clipboard. Coordinator: auto-refresh interval, throughput rate, error category breakdown, expandable dead-letter payloads. Debug drawer in side-panel chat. Context-aware back-navigation (resource→workflow→agent). MSW handler dedup. Vite proxy additions. 10 new tests (logs.test.tsx, audit/coordinator/secrets test extensions). | pending |
| PlatS | **Global Platform Status**: Always-visible top-bar health indicator polling `/administration/logs/instance` every 15s. Click/tap popover with instance ID, latency (color-coded), last-checked time. Reusable `StreamBadge` component for SSE connections. Logs page: replaced big SSE badge with StreamBadge. Coordinator: replaced ● Live span with StreamBadge. Secrets: removed vault health badge (covered by global indicator). `usePlatformStatus` hook (TanStack Query). 16 i18n keys across 11 locales. | pending |
| **P14** | **Phase 14 — UX Hardening (partial)**: Logs session SSE collector (`session-log-store.ts`), agent/conversation dropdown filters on Logs + Audit. Audit auto-loads `recent` on mount. Agents + Groups: unified "New" button with CreateOrWizardDialog. Orphans: pre-scan empty state, type-specific icons, version extraction, copy URI, selective deletion checkboxes. Sidebar: regrouped into Core/Build/Monitor/Admin, icons updated (MessagesSquare, CalendarClock, Link2Off), `__APP_VERSION__` display. 31 i18n keys propagated to all 10 locales. | `890fa53` |
| **13P** | **Phase 13 Polish**: Fix stale resource slugs (behavior→rules, httpcalls→apicalls, langchain→llm). Sidebar + Agent Detail Studio entry points. StudioLandingPage with agent picker grid. Pipeline Railroad dictionary/rag icons. Debug drawer ARIA keyboard nav (Arrow/Home/End + roving tabIndex). MSW tool metrics handlers (costs, rate limits, cache, history). Vite proxy `/llm/tools`. Memory inspector API centralized to conversations.ts. Mobile bottom tab navigation. 7 new i18n keys propagated to all 11 locales. | `6ee2922` |

### Test Status

- **TypeScript**: Zero errors (`npx tsc --noEmit`)
- **Unit/Component**: 453 pass (`npm run test`) — 42 files (100% green, fixed parallel flakiness via localStorage isolated clearance and improved timeout threshold)
- **E2E (Playwright)**: 75/75 pass (`npm run test:e2e`) — 11 spec files across 3 browsers
- **Integration**: 44/44 pass (`npm run test:integration`) — 6 spec files, 10 parallel workers, 28.8s. Requires live EDDI backend
- **Build**: Succeeds

### Files Created (summary)

- **Config**: `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- **Layout**: `sidebar.tsx` (with `logo_eddi.png`), `top-bar.tsx` (breadcrumbs), `app-layout.tsx` (max-width constraint), `theme-provider.tsx` (system theme listener)
- **Assets**: `public/logo_eddi.png` (original EDDI wordmark), `public/mockServiceWorker.js` (MSW browser worker)
- **UI Components**: `button.tsx`, `card.tsx`, `badge.tsx`, `skeleton.tsx`, `input.tsx`, `alert-dialog.tsx`
- **Shared Components**: `back-link.tsx`, `empty-state.tsx`, `error-state.tsx`
- **Agents**: `agent-card.tsx`, `create-agent-dialog.tsx`, `import-agent-dialog.tsx`, `agents.tsx`, `agent-detail.tsx`
- **Workflows**: `package-card.tsx`, `create-package-dialog.tsx`, `packages.tsx`, `package-detail.tsx`
- **Conversations**: `conversations.tsx`, `conversation-detail.tsx`
- **Chat**: `chat-panel.tsx`, `chat-message.tsx`, `chat-input.tsx`, `chat-history.tsx`, `streaming-toggle.tsx`, `chat.tsx`
- **Resources**: `resource-card.tsx`, `create-resource-dialog.tsx`, `resources.tsx` (hub), `resource-list.tsx`, `resource-detail.tsx`
- **Dashboard**: `dashboard.tsx` (stat cards, quick actions, recent agents), `api/dashboard.ts`, `use-dashboard.ts`
- **Backup**: `api/backup.ts` (export/download/import), `use-backup.ts` (hooks)
- **Agent Wizard**: `agent-wizard.tsx` (5-6 step two-mode setup wizard)
- **Agent Setup API**: `api/agent-setup.ts` (types + fetch), `use-agent-setup.ts` (mutation hooks)
- **API**: `api-client.ts`, `api/agents.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`, `api/chat.ts`, `api/resources.ts`, `api/backup.ts`, `api/extensions.ts`, `api/schemas.ts`
- **Hooks**: `use-agents.ts`, `use-packages.ts`, `use-conversations.ts`, `use-chat.ts`, `use-resources.ts`, `use-backup.ts`, `use-extensions-store.ts`, `use-dashboard.ts`, `use-json-schema.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `fr.json`, `es.json`, `ar.json`, `zh.json`, `th.json`, `ja.json`, `ko.json`, `pt.json`, `hi.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `agents.test.tsx`, `agents.test.ts`, `packages.test.tsx`, `conversations.test.tsx`, `chat.test.tsx`, `resources.test.tsx`, `backup.test.tsx`, `agent-wizard.test.tsx`, `package-detail.test.tsx`, `dashboard.test.tsx`, `import-agent-dialog.test.tsx`
- **Debugger**: `debug-drawer.tsx` (5-tab drawer), `pipeline-trace.tsx`, `cost-dashboard.tsx`, `memory-inspector.tsx`, `live-log-viewer.tsx`, `prompt-viewer.tsx`
- **Studio**: `pipeline-railroad.tsx`, `agent-studio.tsx` (3-panel workspace)
- **Debugger Hooks**: `use-debug-events.ts` (Zustand), `use-tool-metrics.ts`, `api/tool-metrics.ts`, `api/audit.ts`, `api/logs.ts`
- **Debugger Tests**: `debug-drawer.test.tsx`, `pipeline-trace.test.tsx`, `cost-dashboard.test.tsx`, `prompt-viewer.test.tsx`, `pipeline-railroad.test.tsx`
- **E2E**: `e2e-helpers.ts`, `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`, `dashboard.spec.ts`, `agents.spec.ts`, `agent-detail.spec.ts`, `packages.spec.ts`, `conversations.spec.ts`, `chat.spec.ts`, `resources.spec.ts`
- **Integration Tests**: `e2e/integration/integration-helpers.ts`, `agents.integration.spec.ts`, `packages.integration.spec.ts`, `resources.integration.spec.ts`, `conversations.integration.spec.ts`, `deployment.integration.spec.ts`, `schemas.integration.spec.ts`
- **MSW**: `handlers.ts` (agents, packages, conversations, resources, schemas, extension store mocks), `server.ts`, `browser.ts`
- **Editors**: `json-editor.tsx` (with JSON schema support), `content-editor.tsx` (Monaco inline+fullscreen), `version-picker.tsx`, `config-editor-layout.tsx`, `update-usage-dialog.tsx`, `pipeline-builder.tsx`, `add-extension-dialog.tsx`, `behavior-editor.tsx`, `httpcalls-editor.tsx`, `langchain-editor.tsx`, `output-editor.tsx`, `propertysetter-editor.tsx`, `dictionary-editor.tsx`, `mcpcalls-editor.tsx`, `rag-editor.tsx`
- **Cascade**: `cascade-save.ts`, `resource-usage.ts`
- **Auth**: `auth-context.ts`, `auth-provider.tsx` (optional Keycloak), `use-auth.ts`, `auth-config.ts`
- **Keycloak**: `docker-compose.keycloak.yml` (local dev), `keycloak/eddi-realm.json` (auto-import realm)
- **Docker**: `docker-compose.integration.yml` (EDDI + MongoDB for integration tests)
- **Coordinator**: `coordinator.tsx` (dashboard page), `api/coordinator.ts` (API module), `use-coordinator.ts` (SSE + TanStack hooks), `coordinator.test.tsx` (12 tests)
- **Secrets Admin**: `secrets.tsx` (admin page at `/manage/secrets`), `api/secrets.ts` (API module), `use-secrets.ts` (TanStack hooks), `secrets.test.tsx` (12 tests)
- **Audit Trail**: `audit.tsx` (timeline-based page at `/manage/audit`), `api/audit.ts` (API module), `use-audit.ts` (TanStack hooks), `audit.test.tsx` (13 tests)
- **Chat Panel Secrets**: `SecretInputField` + `ChatInputWithSecretToggle` inline components in `chat-panel.tsx`, `extractInputField()` in `conversations.ts`
- **Tenant Quotas**: `quotas.tsx` (admin page at `/manage/quotas`), `api/quotas.ts` (API module), `use-quotas.ts` (TanStack hooks with 10s usage polling), `quotas.test.tsx` (8 tests)
- **Groups UI**: `groups.tsx`, `group-detail.tsx`, `group-wizard.tsx` (pages); `group-card.tsx`, `agent-response-card.tsx`, `group-config-panel.tsx`, `discussion-transcript.tsx`, `discussion-input.tsx`, `phase-header.tsx`, `create-group-dialog.tsx` (components); `api/groups.ts`, `group-templates.ts`, `use-groups.ts`

---

### What's Next — Phase 4: Hardening & Production Readiness

Phase 3 (Manager UI Rewrite) is functionally complete through Phase 3.21. Phase 4 focuses on hardening, testing, and production readiness.

| Phase | Description                                                                                                                                                                | Status |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| 4.1   | **Keycloak Auth Adapter** — wire `keycloak-js` 26+, login/logout flow, token refresh, route guards, role-based UI                                                          | ✅     |
| 4.2   | **E2E Test Suite (Playwright)** — 75 tests across 11 spec files covering dashboard, agents, packages, conversations, chat, resources, navigation, theme, RTL               | ✅     |
| 4.3   | **Real-Backend Integration Testing** — 44 Playwright API tests: CRUD round-trips, conversations (POST /say 200 ✅), deployment, JSON schemas. All pass in parallel (28.8s) | ✅     |
| 4.4   | **JSON Schema Enrichment** — backend victools migration + mock schema enrichment + `JsonSchemaCreatorTest` (11 tests)                                                      | ✅     |
| 4.5   | **Production Build** — single bundle (no code splitting), loading indicator, old dashboard replaced with Manager redirect                                                  | ✅     |

### ✅ N.7 Backend API Consistency Fixes — DONE (2026-03-09, EDDI + Manager)

| Fix       | Resolution                                                                                             |
| --------- | ------------------------------------------------------------------------------------------------------ |
| **N.7.1** | ✅ Verified: backend returns 201. The 200 was Vite dev proxy stripping status code. No fix needed.     |
| **N.7.2** | ✅ Added `?permanent=true` to all 8 store DELETE endpoints. Default stays soft-delete.                 |
| **N.7.3** | ✅ `getDeploymentStatus` returns JSON `{"status":"READY"}` by default. `?format=text` backward compat. |
| **N.7.4** | ✅ Deferred — Quarkus dev-mode issue, `/q/health/live` workaround sufficient.                          |

Manager tests updated: `integration-helpers.ts` and `deployment.integration.spec.ts` now parse JSON.

### Chat Panel SSE Streaming + Undo/Redo ✅ (2026-03-10)

- [x] `chat.ts`: Added `undoConversation()` and `redoConversation()` API functions
- [x] `use-chat.ts`: SSE streaming integration with real-time token rendering, undo/redo support
- [x] `chat-panel.tsx`: Undo/redo buttons, SSE streaming toggle, improved message rendering
- [x] `en.json`: New i18n keys for undo/redo

**Phase 5+ (future):**

- Phase 6 (PostgreSQL / DB-Agnostic Architecture)
- Phase 7 (MCP Server + Client)
- Phase 8 (Multi-Agent Orchestration)
- Phase 9 (Persistent User Memory)
- Phase 10 (CI/CD)
- Phase 11 (Website Astro migration — LAST)
- Chat-UI Rewrite (`eddi-chat-ui` repo) — ✅ Vite rewrite complete, deployed to EDDI backend
- See EDDI `AGENTS.md` for full roadmap

---

### What's Next — Manager v6 Complete 🚀

All Phase 14 UX hardening items (Secret Vault Key Picker, Coordinator Enrichment, Dashboard Enrichment) and the full UI test suite stabilization have been successfully completed. The EDDI Manager UI rewrite and hardening phases are now fully finished.

The next steps in the overall EDDI project roadmap occur in other repositories:
- **Website Migration**: Rewriting the marketing website into an **Astro** static site (`eddi-website` repository - Phase 14).
- **Backend Deep Tech**: Implementation of DAG + OTel tracing (Phase 9), Human-In-The-Loop (Phase 9b), Persistent User Memory (Phase 11), and CI/CD (Phase 12) in the core `EDDI` repository.

**Implementation plan artifact**: See `implementation_plan.md` in conversation `8bdeea74` for full technical details on each phase.

**📄 Reference docs:**

- [Editing layer plan](docs/editing-layer-plan.md) — Phases 3.14–3.19 (all ✅)
- [UX research analysis](docs/ux-research-analysis.md) — competitive analysis across Voiceflow, n8n, Langflow, Dify, Agentpress, LangSmith, Make.com
- [EDDI v6 implementation plan](file:///c:/dev/git/EDDI/docs/v6-planning/implementation_plan.md) — backend architecture & roadmap
- [Business logic analysis](file:///c:/dev/git/EDDI/docs/v6-planning/business-logic-analysis.md) — extension types, pipeline model, Agent Father pattern

---

## Key Design Decisions

1. **Greenfield rewrite** — old codebase deleted, not migrated
2. **TanStack Query** for server state — replaces Redux + sagas (218 actions eliminated)
3. **Logical CSS properties** everywhere — `ps-*`, `pe-*`, `inset-s-*`, `end-*` for RTL
4. **API base URL** from `window.location.origin` — no env.json needed
5. **Vite proxy** at dev time to EDDI backend on `localhost:7070`
6. **i18n** auto-detects RTL from language and sets `dir` attribute on `<html>`
7. **Version deduplication** — `groupAgentsByName()` keeps latest version per name
8. **Two conversation APIs** — low-level (`/conversationstore/conversations`) for browsing, managed (`/managedagents`) for Chat Panel
9. **Noto Sans** as primary font — Google Fonts with script variants (Arabic, Thai, Devanagari, JP, KR, SC) for universal glyph coverage
10. **System theme mode** uses `matchMedia` listener — reacts to OS dark/light changes in real time
11. **Main content max-width** 1536px (`max-w-screen-2xl`) — prevents infinite stretching on ultrawide monitors
12. **MSW auto-detection** — `main.tsx` probes backend with 1.5s timeout; starts MSW browser worker if unreachable
13. **JSON Schema from backend** — all 8 config stores expose `/jsonSchema`; fetched once per session, fed to Monaco `setDiagnosticsOptions`
14. **Optional Keycloak auth** — default is `'none'` (no login). Enabled via `VITE_AUTH_METHOD=keycloak`. Config priority: Vite env → `window.__EDDI_AUTH__` → `'none'`
15. **Local Keycloak docker-compose** — `docker compose -f docker-compose.keycloak.yml up` gives a fully configured Keycloak 26 on port 8180 with realm, client, roles, test users
16. **PKCE S256** for all auth flows — no client secret needed, modern browser security best practice

---

## Known Issues / TODOs

- Pre-commit hook references old `pre-commit` npm package — use `--no-verify` for now
- Workflow duplicate not implemented (no backend endpoint for it yet)
- `index.html` now redirects to `/manage` — Quarkus must serve `manage.html` at `/manage` route (check `application.properties`)

---

## Phase 14 — Files Modified (this session)

| File | Change |
|------|--------|
| `src/hooks/session-log-store.ts` | **NEW** — Zustand store, SSE auto-connect at boot, 1000-entry circular buffer |
| `src/hooks/use-logs.ts` | Seed live log entries from session store on mount |
| `src/pages/logs.tsx` | Agent/Conversation filter dropdowns (both Live + History tabs) |
| `src/pages/audit.tsx` | Auto-load recent on mount, agent dropdown, conversation datalist |
| `src/pages/agents.tsx` | Unified "New Agent" button with CreateOrWizardDialog |
| `src/pages/groups.tsx` | Unified "New Group" button with CreateOrWizardDialog |
| `src/components/shared/create-or-wizard-dialog.tsx` | **NEW** — Choice dialog: Quick Create vs Guided Setup |
| `src/pages/orphans.tsx` | Complete rewrite: pre-scan state, type icons, selective deletion |
| `src/components/layout/sidebar.tsx` | 4 sections (Core/Build/Monitor/Admin), new icons, version display |
| `vite.config.ts` | `define: { __APP_VERSION__: ... }` from package.json |
| `src/vite-env.d.ts` | `declare const __APP_VERSION__: string` |
| `src/i18n/locales/*.json` | 31 new keys propagated to all 11 locales, fixed `studio.type.output` collision |
| `src/pages/__tests__/audit.test.tsx` | Updated for auto-load + text input (not select) |
| `src/pages/__tests__/backup.test.tsx` | Updated for unified button (no separate wizard button) |
