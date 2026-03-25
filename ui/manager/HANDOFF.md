# EDDI Manager — Handoff Document

> **Last updated**: 2026-03-17  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: Phase 7.34b — Tenant Quota Stub (Manager UI)

---

## Current Status

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.21 complete.  
**Phase 4 (Hardening)**: Phase 4.1 + 4.2 + 4.3 + 4.4 + 4.5 complete.  
**Phase 5 (Backend features with Manager integration)**: Item 5.30 complete (Coordinator Dashboard).  
**Phase 7 (Secrets Vault)**: Chat UI secret input + Manager Secrets Admin page complete.
**Phase 7.34 (Audit Ledger)**: Manager Audit Trail UI complete.
**Phase 7.34b (Tenant Quotas)**: Backend engine + REST API + Manager Quotas admin page complete.

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
| 4.4a  | Backend: migrate from kjetland/jackson-jsonSchema to victools/jsonschema-generator v4.38.0 (Draft 2020-12). `JsonSchemaCreatorTest` (11 tests). Agenth victools deps at 4.38.0 (Jackson 2.x)                                       | EDDI repo |
| 4.4b  | Manager: enrich all 8 MSW mock schemas (agent, package, behavior, httpcalls, output, dictionary, langchain, propertysetter) with real field definitions matching backend Java models                                               | `3013459` |
| 4.5   | Reverted to single bundle (no code splitting) — admin dashboard prioritizes simplicity over micro-optimization. Single JS (1.2 MB, mainly Monaco) + single CSS                                                                     | latest    |
| —     | **Dashboard replacement**: old Bootstrap/jQuery `index.html` → redirect to `/manage`. OpenAPI + Docs external links in sidebar. Branded loading indicator. Removed bootstrap, jQuery, moment, KaTeX, Slick, old webpack bundles    | latest    |

### Test Status

- **TypeScript**: Zero errors (`npx tsc --noEmit`)
- **Unit/Component**: 254 pass (`npm run test`) — 30 files (incl. `quotas.test.tsx` 8 tests)
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
- **Agent Wizard**: `agent-wizard.tsx` (4-step guided creation)
- **API**: `api-client.ts`, `api/agents.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`, `api/chat.ts`, `api/resources.ts`, `api/backup.ts`, `api/extensions.ts`, `api/schemas.ts`
- **Hooks**: `use-agents.ts`, `use-packages.ts`, `use-conversations.ts`, `use-chat.ts`, `use-resources.ts`, `use-backup.ts`, `use-extensions-store.ts`, `use-dashboard.ts`, `use-json-schema.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `fr.json`, `es.json`, `ar.json`, `zh.json`, `th.json`, `ja.json`, `ko.json`, `pt.json`, `hi.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `agents.test.tsx`, `agents.test.ts`, `packages.test.tsx`, `conversations.test.tsx`, `chat.test.tsx`, `resources.test.tsx`, `backup.test.tsx`, `agent-wizard.test.tsx`, `package-detail.test.tsx`, `dashboard.test.tsx`, `import-agent-dialog.test.tsx`
- **E2E**: `e2e-helpers.ts`, `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`, `dashboard.spec.ts`, `agents.spec.ts`, `agent-detail.spec.ts`, `packages.spec.ts`, `conversations.spec.ts`, `chat.spec.ts`, `resources.spec.ts`
- **Integration Tests**: `e2e/integration/integration-helpers.ts`, `agents.integration.spec.ts`, `packages.integration.spec.ts`, `resources.integration.spec.ts`, `conversations.integration.spec.ts`, `deployment.integration.spec.ts`, `schemas.integration.spec.ts`
- **MSW**: `handlers.ts` (agents, packages, conversations, resources, schemas, extension store mocks), `server.ts`, `browser.ts`
- **Editors**: `json-editor.tsx` (with JSON schema support), `content-editor.tsx` (Monaco inline+fullscreen), `version-picker.tsx`, `config-editor-layout.tsx`, `update-usage-dialog.tsx`, `pipeline-builder.tsx`, `add-extension-dialog.tsx`, `behavior-editor.tsx`, `httpcalls-editor.tsx`, `langchain-editor.tsx`, `output-editor.tsx`, `propertysetter-editor.tsx`, `dictionary-editor.tsx`
- **Cascade**: `cascade-save.ts`, `resource-usage.ts`
- **Auth**: `auth-context.ts`, `auth-provider.tsx` (optional Keycloak), `use-auth.ts`, `auth-config.ts`
- **Keycloak**: `docker-compose.keycloak.yml` (local dev), `keycloak/eddi-realm.json` (auto-import realm)
- **Docker**: `docker-compose.integration.yml` (EDDI + MongoDB for integration tests)
- **Coordinator**: `coordinator.tsx` (dashboard page), `api/coordinator.ts` (API module), `use-coordinator.ts` (SSE + TanStack hooks), `coordinator.test.tsx` (12 tests)
- **Secrets Admin**: `secrets.tsx` (admin page at `/manage/secrets`), `api/secrets.ts` (API module), `use-secrets.ts` (TanStack hooks), `secrets.test.tsx` (12 tests)
- **Audit Trail**: `audit.tsx` (timeline-based page at `/manage/audit`), `api/audit.ts` (API module), `use-audit.ts` (TanStack hooks), `audit.test.tsx` (13 tests)
- **Chat Panel Secrets**: `SecretInputField` + `ChatInputWithSecretToggle` inline components in `chat-panel.tsx`, `extractInputField()` in `conversations.ts`
- **Tenant Quotas**: `quotas.tsx` (admin page at `/manage/quotas`), `api/quotas.ts` (API module), `use-quotas.ts` (TanStack hooks with 10s usage polling), `quotas.test.tsx` (8 tests)

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
