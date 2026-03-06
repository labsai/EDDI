# EDDI Manager — Handoff Document

> **Last updated**: 2026-03-06  
> **Branch**: `feature/version-6.0.0`  
> **Last commit**: `938aa6e` — Packages + Conversations pages (phases 3.6-3.8)

---

## Current Status

**Phase 3 (Manager UI Rewrite)**: Phases 3.1–3.8 complete.

### What's Done

| Phase | Description                                                           | Commit    |
| ----- | --------------------------------------------------------------------- | --------- |
| 3.1   | Study existing Manager + implementation plan                          | —         |
| 3.2   | Vite + React 19 + Tailwind v4 + ESLint + Vitest + Playwright scaffold | `020007e` |
| 3.3   | Layout shell: sidebar, top bar, dark/light/system theme, i18n w/ RTL  | `020007e` |
| 3.4   | Bots page: bot cards, deployment status, search, create dialog        | `e47b0fb` |
| 3.5   | Bot Detail page: package management, deployment, raw config viewer    | `dadc669` |
| 3.6   | Package Detail page: extensions list, raw JSON viewer                 | `938aa6e` |
| 3.7   | Packages list page: cards grid, search, create dialog                 | `938aa6e` |
| 3.8   | Conversations page: list w/ filters, conversation detail viewer       | `938aa6e` |

### Test Status

- **TypeScript**: Zero errors (`npx tsc -b`)
- **Unit/Component**: 31/31 pass (`npm run test`) — 7 files
- **Build**: Succeeds (421KB JS, 29KB CSS)

### Files Created (summary)

- **Config**: `package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- **Layout**: `sidebar.tsx`, `top-bar.tsx`, `app-layout.tsx`, `theme-provider.tsx`
- **Bots**: `bot-card.tsx`, `create-bot-dialog.tsx`, `bots.tsx`, `bot-detail.tsx`
- **Packages**: `package-card.tsx`, `create-package-dialog.tsx`, `packages.tsx`, `package-detail.tsx`
- **Conversations**: `conversations.tsx`, `conversation-detail.tsx`
- **API**: `api-client.ts`, `api/bots.ts`, `api/packages.ts`, `api/descriptors.ts`, `api/conversations.ts`
- **Hooks**: `use-bots.ts`, `use-packages.ts`, `use-conversations.ts`
- **i18n**: `config.ts`, `en.json`, `de.json`, `ar.json`
- **Tests**: `sidebar.test.tsx`, `top-bar.test.tsx`, `config.test.ts`, `bots.test.tsx`, `bots.test.ts`, `packages.test.tsx`, `conversations.test.tsx`
- **E2E**: `navigation.spec.ts`, `theme.spec.ts`, `rtl.spec.ts`
- **MSW**: `handlers.ts` (bots, packages, conversations mocks), `server.ts`

---

## Next Steps (Phase 3.9+)

### Phase 3.9: Chat Panel

- SSE streaming support (EDDI backend streams via `/bots/{env}/{botId}`)
- Managed bot API via `/managedbots/{intent}/{userId}` (vite proxy already configured)
- Markdown rendering in chat bubbles
- Embeddable chat component (shared with eddi-chat-ui)

### Phase 3.10: Resources Pages (CRUD)

Build generic resource CRUD pages for each extension type:

- Behavior rules (`/behaviorstore/behaviorsets`)
- HTTP calls (`/httpcallsstore/httpcalls`)
- Output sets (`/outputstore/outputsets`)
- Regular dictionaries (`/regulardictionarystore/regulardictionaries`)
- LangChain configs (`/langchainstore/langchains`)
- Property setter (`/propertysetterstore/propertysetters`)

Consider building a **generic ResourcePage** component since the CRUD pattern is the same across all types.

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
