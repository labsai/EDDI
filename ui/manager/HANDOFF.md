# EDDI Manager — Handoff Document

> **Last updated**: 2026-03-09  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: Phase 4.3 — Real-Backend Integration Testing (all 44 tests pass)

---

## Current Status

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.21 complete.  
**Phase 4 (Hardening)**: Phase 4.1 + 4.2 + 4.3 complete.

### What's Done

| Phase | Description                                                                                                                                                                                                                        | Commit    |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| 3.1   | Study existing Manager + implementation plan                                                                                                                                                                                       | —         |
| 3.2   | Vite + React 19 + Tailwind v4 + ESLint + Vitest + Playwright scaffold                                                                                                                                                              | `020007e` |
| 3.3   | Layout shell: sidebar, top bar, dark/light/system theme, i18n w/ RTL                                                                                                                                                               | `020007e` |
| 3.4   | Bots page: bot cards, deployment status, search, create dialog                                                                                                                                                                     | `e47b0fb` |
| 3.5   | Bot Detail page: package management, deployment, raw config viewer                                                                                                                                                                 | `dadc669` |
| 3.6   | Package Detail page: extensions list, raw JSON viewer                                                                                                                                                                              | `938aa6e` |
| 3.7   | Packages list page: cards grid, search, create dialog                                                                                                                                                                              | `938aa6e` |
| 3.8   | Conversations page: list w/ filters, conversation detail viewer                                                                                                                                                                    | `938aa6e` |
| 3.9   | Chat Panel: bot selector, SSE streaming toggle, history, markdown                                                                                                                                                                  | —         |
| 3.10  | Resources Pages: generic CRUD for 6 extension types (hub + list + detail)                                                                                                                                                          | —         |
| 3.11  | Import/Export + Bot Wizard: file upload/download, 4-step guided creation                                                                                                                                                           | —         |
| 3.12  | i18n finalized: 11 languages (en, de, fr, es, ar, zh, th, ja, ko, pt, hi)                                                                                                                                                          | —         |
| 3.13  | EDDI branding: black/gold theme, Noto Sans font, original logo, system theme fix, wide-screen constraint                                                                                                                           | —         |
| 3.14  | JSON Editor, Version Picker, Cascade Save: Monaco, Form↔JSON toggle, config→package→bot cascade                                                                                                                                    | —         |
| 3.15  | Bot Editor: version picker, env badges (unrestricted/restricted/test), deploy per-env, duplicate, export                                                                                                                           | —         |
| 3.16  | Package Editor: drag-and-drop pipeline builder (`@dnd-kit`), add/remove extensions, save/discard, version picker                                                                                                                   | —         |
| 3.17  | Behavior Rules & HTTP Calls Editors: form-based editors with render prop, Form↔JSON sync, 14 new tests                                                                                                                             | —         |
| 3.18  | LangChain, Output, Property Setter, Dictionary Editors: 4 form editors, MSW mocks, 29 new tests                                                                                                                                    | —         |
| 3.19  | Polish, Tests & Documentation: API-layer tests, dashboard test, renderPage helper, README rewrite                                                                                                                                  | —         |
| 3.20  | UI/UX Enterprise Polish: unified component library (Button/Card/Badge/Skeleton/AlertDialog), Sonner toasts, Charcoal dark mode, sidebar sections, breadcrumbs, shared EmptyState/ErrorState/BackLink, dashboard with real API data | —         |
| 3.21  | MSW Browser Mode + JSON Schema: auto-detect backend, mock data in dev, Vite proxy fix, Monaco JSON schema validation/autocomplete from backend `/jsonSchema` endpoints                                                             | —         |

### Test Status

- **TypeScript**: Zero errors (`npx tsc --noEmit`)
- **Unit/Component**: 164/164 pass (`npm run test`) — 23 files
- **E2E (Playwright)**: 75/75 pass (`npm run test:e2e`) — 11 spec files across 3 browsers
- **Integration**: 44/44 pass (`npm run test:integration`) — 6 spec files, 10 parallel workers, 28.8s. Requires live EDDI backend
- **Build**: Succeeds

### Files Created (summary)

- **Config**: `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- **Layout**: `sidebar.tsx` (with `logo_eddi.png`), `top-bar.tsx` (breadcrumbs), `app-layout.tsx` (max-width constraint), `theme-provider.tsx` (system theme listener)
- **Assets**: `public/logo_eddi.png` (original EDDI wordmark), `public/mockServiceWorker.js` (MSW browser worker)
- **UI Components**: `button.tsx`, `card.tsx`, `badge.tsx`, `skeleton.tsx`, `input.tsx`, `alert-dialog.tsx`
- **Shared Components**: `back-link.tsx`, `empty-state.tsx`, `error-state.tsx`
- **Bots**: `bot-card.tsx`, `create-bot-dialog.tsx`, `bots.tsx`, `bot-detail.tsx`
- **Packages**: `package-card.tsx`, `create-package-dialog.tsx`, `packages.tsx`, `package-detail.tsx`
- **Conversations**: `conversations.tsx`, `conversation-detail.tsx`
- **Chat**: `chat-panel.tsx`, `chat-message.tsx`, `chat-input.tsx`, `chat-history.tsx`, `streaming-toggle.tsx`, `chat.tsx`
- **Resources**: `resource-card.tsx`, `create-resource-dialog.tsx`, `resources.tsx` (hub), `resource-list.tsx`, `resource-detail.tsx`
- **Dashboard**: `dashboard.tsx` (stat cards, quick actions, recent bots), `api/dashboard.ts`, `use-dashboard.ts`
- **Backup**: `api/backup.ts` (export/download/import), `use-backup.ts` (hooks)
- **Bot Wizard**: `bot-wizard.tsx` (4-step guided creation)
- **API**: `api-client.ts`, `api/bots.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`, `api/chat.ts`, `api/resources.ts`, `api/backup.ts`, `api/extensions.ts`, `api/schemas.ts`
- **Hooks**: `use-bots.ts`, `use-packages.ts`, `use-conversations.ts`, `use-chat.ts`, `use-resources.ts`, `use-backup.ts`, `use-extensions-store.ts`, `use-dashboard.ts`, `use-json-schema.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `fr.json`, `es.json`, `ar.json`, `zh.json`, `th.json`, `ja.json`, `ko.json`, `pt.json`, `hi.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `bots.test.tsx`, `bots.test.ts`, `packages.test.tsx`, `conversations.test.tsx`, `chat.test.tsx`, `resources.test.tsx`, `backup.test.tsx`, `bot-wizard.test.tsx`, `package-detail.test.tsx`, `dashboard.test.tsx`
- **E2E**: `e2e-helpers.ts`, `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`, `dashboard.spec.ts`, `bots.spec.ts`, `bot-detail.spec.ts`, `packages.spec.ts`, `conversations.spec.ts`, `chat.spec.ts`, `resources.spec.ts`
- **Integration Tests**: `e2e/integration/integration-helpers.ts`, `bots.integration.spec.ts`, `packages.integration.spec.ts`, `resources.integration.spec.ts`, `conversations.integration.spec.ts`, `deployment.integration.spec.ts`, `schemas.integration.spec.ts`
- **MSW**: `handlers.ts` (bots, packages, conversations, resources, schemas, extension store mocks), `server.ts`, `browser.ts`
- **Editors**: `json-editor.tsx` (with JSON schema support), `version-picker.tsx`, `config-editor-layout.tsx`, `update-usage-dialog.tsx`, `pipeline-builder.tsx`, `add-extension-dialog.tsx`, `behavior-editor.tsx`, `httpcalls-editor.tsx`, `langchain-editor.tsx`, `output-editor.tsx`, `propertysetter-editor.tsx`, `dictionary-editor.tsx`
- **Cascade**: `cascade-save.ts`, `resource-usage.ts`
- **Auth**: `auth-context.ts`, `auth-provider.tsx` (optional Keycloak), `use-auth.ts`, `auth-config.ts`
- **Keycloak**: `docker-compose.keycloak.yml` (local dev), `keycloak/eddi-realm.json` (auto-import realm)
- **Docker**: `docker-compose.integration.yml` (EDDI + MongoDB for integration tests)

---

### What's Next — Phase 4: Hardening & Production Readiness

Phase 3 (Manager UI Rewrite) is functionally complete through Phase 3.21. Phase 4 focuses on hardening, testing, and production readiness.

| Phase | Description                                                                                                                                                                | Status |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| 4.1   | **Keycloak Auth Adapter** — wire `keycloak-js` 26+, login/logout flow, token refresh, route guards, role-based UI                                                          | ✅     |
| 4.2   | **E2E Test Suite (Playwright)** — 75 tests across 11 spec files covering dashboard, bots, packages, conversations, chat, resources, navigation, theme, RTL                 | ✅     |
| 4.3   | **Real-Backend Integration Testing** — 44 Playwright API tests: CRUD round-trips, conversations (POST /say 200 ✅), deployment, JSON schemas. All pass in parallel (28.8s) | ✅     |
| 4.4   | **JSON Schema Enrichment** — populate mock schemas with real field definitions for better dev-mode autocomplete; validate against backend `/jsonSchema` endpoints          | ⬜     |
| 4.5   | **Production Build Optimization** — bundle analysis, code splitting, lazy loading, tree-shaking audit, lighthouse performance score                                        | ⬜     |

### ⚡ Immediate Next Steps — N.7 Backend API Consistency Fixes (EDDI repo)

**Do these BEFORE Phase 4.4.** These are small, focused fixes in the EDDI Java backend discovered during Phase 4.3 integration testing. Details in [implementation_plan.md §N.7](file:///c:/dev/git/EDDI/docs/v6-planning/implementation_plan.md).

| Fix       | Issue                                            | Key Files                                                   | Notes                                                                                                                                       |
| --------- | ------------------------------------------------ | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **N.7.1** | Duplicate POST returns 200 instead of 201        | `RestBotStore.java`, `RestPackageStore.java`                | ⚠️ `restVersionInfo.create()` already returns `Response.created(201)` — **verify if Vite proxy strips it**. Test against port 7070 directly |
| **N.7.2** | DELETE inconsistent across stores (soft vs hard) | `RestVersionInfo.java`, LangChain MongoDB store             | LangChain returns 404 for older versions, others return 409. Proposal: `?permanent=true` param                                              |
| **N.7.3** | Deployment status returns plain text not JSON    | `IRestBotAdministration.java`, `RestBotAdministration.java` | Change `@Produces(TEXT_PLAIN)` → JSON. **Breaking**: update `TestCaseRuntime.java` too                                                      |
| **N.7.4** | Health endpoint returns HTML on failure          | Quarkus dev-mode issue                                      | **Defer** — `/q/health/live` workaround is sufficient                                                                                       |

**How to start**: Build EDDI from source (`.\mvnw.cmd quarkus:dev -DskipTests "-Dquarkus.http.port=7070"`), verify N.7.1 with direct curl, then fix N.7.2 and N.7.3. Run EDDI integration tests (`.\mvnw.cmd test`) + Manager integration tests (`npm run test:integration`) to verify.

**Phase 5+ (future):**

- Chat-UI Rewrite (`eddi-chat-ui` repo)
- Website migration to Astro (`eddi-website` repo)
- Further EDDI backend work

**📄 Reference docs:**

- [Editing layer plan](docs/editing-layer-plan.md) — Phases 3.14–3.19 (all ✅)
- [UX research analysis](docs/ux-research-analysis.md) — competitive analysis across Voiceflow, n8n, Langflow, Dify, Botpress, LangSmith, Make.com
- [EDDI v6 implementation plan](file:///c:/dev/git/EDDI/docs/v6-planning/implementation_plan.md) — backend architecture & roadmap
- [Business logic analysis](file:///c:/dev/git/EDDI/docs/v6-planning/business-logic-analysis.md) — extension types, pipeline model, Bot Father pattern

---

## Key Design Decisions

1. **Greenfield rewrite** — old codebase deleted, not migrated
2. **TanStack Query** for server state — replaces Redux + sagas (218 actions eliminated)
3. **Logical CSS properties** everywhere — `ps-*`, `pe-*`, `inset-s-*`, `end-*` for RTL
4. **API base URL** from `window.location.origin` — no env.json needed
5. **Vite proxy** at dev time to EDDI backend on `localhost:7070`
6. **i18n** auto-detects RTL from language and sets `dir` attribute on `<html>`
7. **Version deduplication** — `groupBotsByName()` keeps latest version per name
8. **Two conversation APIs** — low-level (`/conversationstore/conversations`) for browsing, managed (`/managedbots`) for Chat Panel
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
- Package duplicate not implemented (no backend endpoint for it yet)
- JSON Schema mock data is minimal (empty `properties`); real schemas come from backend at runtime

### Backend API Issues Found During Integration Testing (2026-03-09)

| Issue                                       | Current Behavior                                                                        | Expected                                     | Proposal                                               |
| ------------------------------------------- | --------------------------------------------------------------------------------------- | -------------------------------------------- | ------------------------------------------------------ |
| **Duplicate POST status code**              | Returns 200                                                                             | Should return **201** (new resource created) | Fix status codes in `RestBotStore`, `RestPackageStore` |
| **DELETE inconsistency across stores**      | Most stores soft-delete (409 if newer version exists), but LangChain hard-deletes (404) | Consistent behavior across all stores        | Add `?permanent=true` query param for hard delete      |
| **Deployment status format**                | Returns plain text (`READY`, `NOT_FOUND`)                                               | JSON for consistency with other endpoints    | Wrap in `{"status": "READY"}`                          |
| **Health endpoint on dev-services failure** | Returns HTML error page                                                                 | JSON error body                              | Quarkus dev mode issue — may need custom error handler |
