# EDDI Manager — Handoff Document

> **Last updated**: 2026-03-06  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: Phase 3.10 — Generic Resources CRUD pages for 6 extension types

---

## Current Status

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.10 complete.

### What's Done

| Phase | Description                                                               | Commit    |
| ----- | ------------------------------------------------------------------------- | --------- |
| 3.1   | Study existing Manager + implementation plan                              | —         |
| 3.2   | Vite + React 19 + Tailwind v4 + ESLint + Vitest + Playwright scaffold     | `020007e` |
| 3.3   | Layout shell: sidebar, top bar, dark/light/system theme, i18n w/ RTL      | `020007e` |
| 3.4   | Bots page: bot cards, deployment status, search, create dialog            | `e47b0fb` |
| 3.5   | Bot Detail page: package management, deployment, raw config viewer        | `dadc669` |
| 3.6   | Package Detail page: extensions list, raw JSON viewer                     | `938aa6e` |
| 3.7   | Packages list page: cards grid, search, create dialog                     | `938aa6e` |
| 3.8   | Conversations page: list w/ filters, conversation detail viewer           | `938aa6e` |
| 3.9   | Chat Panel: bot selector, SSE streaming toggle, history, markdown         | —         |
| 3.10  | Resources Pages: generic CRUD for 6 extension types (hub + list + detail) | —         |

### Test Status

- **TypeScript**: Zero errors (`npx tsc -b`)
- **Unit/Component**: 53/53 pass (`npm run test`) — 9 files
- **Build**: Succeeds

### Files Created (summary)

- **Config**: `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- **Layout**: `sidebar.tsx`, `top-bar.tsx`, `app-layout.tsx`, `theme-provider.tsx`
- **Bots**: `bot-card.tsx`, `create-bot-dialog.tsx`, `bots.tsx`, `bot-detail.tsx`
- **Packages**: `package-card.tsx`, `create-package-dialog.tsx`, `packages.tsx`, `package-detail.tsx`
- **Conversations**: `conversations.tsx`, `conversation-detail.tsx`
- **Chat**: `chat-panel.tsx`, `chat-message.tsx`, `chat-input.tsx`, `chat-history.tsx`, `streaming-toggle.tsx`, `chat.tsx`
- **Resources**: `resource-card.tsx`, `create-resource-dialog.tsx`, `resources.tsx` (hub), `resource-list.tsx`, `resource-detail.tsx`
- **API**: `api-client.ts`, `api/bots.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`, `api/chat.ts`, `api/resources.ts`
- **Hooks**: `use-bots.ts`, `use-packages.ts`, `use-conversations.ts`, `use-chat.ts`, `use-resources.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `ar.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `bots.test.tsx`, `bots.test.ts`, `packages.test.tsx`, `conversations.test.tsx`, `chat.test.tsx`, `resources.test.tsx`
- **E2E**: `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`
- **MSW**: `handlers.ts` (bots, packages, conversations, resources mocks), `server.ts`

---

## Next Steps (Phase 3.11+)

### Phase 3.11: Import/Export + Bot Wizard

- Bot import/export UI
- Guided bot creation wizard

### Phase 3.12: Finalize i18n

- Add German (de.json) and Arabic (ar.json) translations for all new keys

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

---

## Known Issues / TODOs

- Bot/Package detail pages don't have a version picker yet (hardcoded to v1)
- Keycloak auth adapter not yet wired (keycloak-js 26+ dependency is installed)
- German and Arabic translations incomplete (only common keys translated)
- Pre-commit hook references old `pre-commit` npm package — use `--no-verify` for now
- Package duplicate not implemented (no backend endpoint for it yet)
