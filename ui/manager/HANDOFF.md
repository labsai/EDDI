# EDDI Manager — Handoff Document

> **Last updated**: 2026-03-08  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: Phase 3.19 — Polish, Tests & Documentation

---

## Current Status

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.19 complete.

### What's Done

| Phase | Description                                                                                                      | Commit    |
| ----- | ---------------------------------------------------------------------------------------------------------------- | --------- |
| 3.1   | Study existing Manager + implementation plan                                                                     | —         |
| 3.2   | Vite + React 19 + Tailwind v4 + ESLint + Vitest + Playwright scaffold                                            | `020007e` |
| 3.3   | Layout shell: sidebar, top bar, dark/light/system theme, i18n w/ RTL                                             | `020007e` |
| 3.4   | Bots page: bot cards, deployment status, search, create dialog                                                   | `e47b0fb` |
| 3.5   | Bot Detail page: package management, deployment, raw config viewer                                               | `dadc669` |
| 3.6   | Package Detail page: extensions list, raw JSON viewer                                                            | `938aa6e` |
| 3.7   | Packages list page: cards grid, search, create dialog                                                            | `938aa6e` |
| 3.8   | Conversations page: list w/ filters, conversation detail viewer                                                  | `938aa6e` |
| 3.9   | Chat Panel: bot selector, SSE streaming toggle, history, markdown                                                | —         |
| 3.10  | Resources Pages: generic CRUD for 6 extension types (hub + list + detail)                                        | —         |
| 3.11  | Import/Export + Bot Wizard: file upload/download, 4-step guided creation                                         | —         |
| 3.12  | i18n finalized: 11 languages (en, de, fr, es, ar, zh, th, ja, ko, pt, hi)                                        | —         |
| 3.13  | EDDI branding: black/gold theme, Noto Sans font, original logo, system theme fix, wide-screen constraint         | —         |
| 3.14  | JSON Editor, Version Picker, Cascade Save: Monaco, Form↔JSON toggle, config→package→bot cascade                  | —         |
| 3.15  | Bot Editor: version picker, env badges (unrestricted/restricted/test), deploy per-env, duplicate, export         | —         |
| 3.16  | Package Editor: drag-and-drop pipeline builder (`@dnd-kit`), add/remove extensions, save/discard, version picker | —         |
| 3.17  | Behavior Rules & HTTP Calls Editors: form-based editors with render prop, Form↔JSON sync, 14 new tests           | —         |
| 3.18  | LangChain, Output, Property Setter, Dictionary Editors: 4 form editors, MSW mocks, 29 new tests                  | —         |
| 3.19  | Polish, Tests & Documentation: API-layer tests, dashboard test, renderPage helper, README rewrite                | —         |

### Test Status

- **TypeScript**: Zero errors (`npx tsc -b`)
- **Unit/Component**: 160/160 pass (`npm run test`) — 22 files
- **Build**: Succeeds

### Files Created (summary)

- **Config**: `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- **Layout**: `sidebar.tsx` (with `logo_eddi.png`), `top-bar.tsx`, `app-layout.tsx` (max-width constraint), `theme-provider.tsx` (system theme listener)
- **Assets**: `public/logo_eddi.png` (original EDDI wordmark)
- **Bots**: `bot-card.tsx`, `create-bot-dialog.tsx`, `bots.tsx`, `bot-detail.tsx`
- **Packages**: `package-card.tsx`, `create-package-dialog.tsx`, `packages.tsx`, `package-detail.tsx`
- **Conversations**: `conversations.tsx`, `conversation-detail.tsx`
- **Chat**: `chat-panel.tsx`, `chat-message.tsx`, `chat-input.tsx`, `chat-history.tsx`, `streaming-toggle.tsx`, `chat.tsx`
- **Resources**: `resource-card.tsx`, `create-resource-dialog.tsx`, `resources.tsx` (hub), `resource-list.tsx`, `resource-detail.tsx`
- **Backup**: `api/backup.ts` (export/download/import), `use-backup.ts` (hooks)
- **Bot Wizard**: `bot-wizard.tsx` (4-step guided creation)
- **API**: `api-client.ts`, `api/bots.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`, `api/chat.ts`, `api/resources.ts`, `api/backup.ts`, `api/extensions.ts`
- **Hooks**: `use-bots.ts`, `use-packages.ts`, `use-conversations.ts`, `use-chat.ts`, `use-resources.ts`, `use-backup.ts`, `use-extensions-store.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `fr.json`, `es.json`, `ar.json`, `zh.json`, `th.json`, `ja.json`, `ko.json`, `pt.json`, `hi.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `bots.test.tsx`, `bots.test.ts`, `packages.test.tsx`, `conversations.test.tsx`, `chat.test.tsx`, `resources.test.tsx`, `backup.test.tsx`, `bot-wizard.test.tsx`, `package-detail.test.tsx`
- **E2E**: `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`
- **MSW**: `handlers.ts` (bots, packages, conversations, resources, extension store mocks), `server.ts`
- **Editors**: `json-editor.tsx`, `version-picker.tsx`, `config-editor-layout.tsx`, `update-usage-dialog.tsx`, `pipeline-builder.tsx`, `add-extension-dialog.tsx`, `behavior-editor.tsx`, `httpcalls-editor.tsx`
- **Cascade**: `cascade-save.ts`, `resource-usage.ts`

---

### What's Next: Phase 4 — Chat-UI Rewrite

Phase 3 (Manager UI Rewrite) is **complete** through Phase 3.19.

**Phase 4**: Chat-UI Rewrite — migrate `eddi-chat-ui` from CRA to Vite, add SSE streaming, extract `@eddi/chat-core` shared package.

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

---

## Known Issues / TODOs

- Bot/Package detail pages now have version picker
- Keycloak auth adapter not yet wired (keycloak-js 26+ dependency is installed)
- Pre-commit hook references old `pre-commit` npm package — use `--no-verify` for now
- Package duplicate not implemented (no backend endpoint for it yet)
