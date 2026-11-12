# EDDI Workforce — AI Agent Instructions

> **The repository root [`AGENTS.md`](../../AGENTS.md) applies in full** — branching,
> push discipline, commits, the changelog fragment. This file adds what is Workforce's own.
> Workforce is built like the Manager: read [`ui/manager/AGENTS.md`](../manager/AGENTS.md)
> for the conventions both apps share (i18n mandate, `data-testid`, tokens, TanStack Query,
> the MSW tier) — they are not repeated here.

## 1. What Workforce is

The end-user app of EDDI (frontend unification plan, D1): the place a signed-in person uses
AI — **chats** with agents and **teams** of agents, in one sidebar — served by EDDI at
`/workforce`. Since phase 5 of the plan it is **its own app**: its own shell
(`workforce.html`), entry (`src/main.tsx`), router (`src/app.tsx`), bundle, MSW handlers
and E2E suite, beside `ui/manager` and `ui/chat`. It loads only what it uses, within its own
first-load budget (`ui/bundle-budget.json`, `npm run budget`).

It is **role-aware**, as it was inside the Manager:

| Role | Sees |
| --- | --- |
| `eddi-user` | Home (a box to type in, starters, recent items), the catalog of agents and teams they may use (`GET /catalog/*`), their chats and team sessions |
| `eddi-approver` | the same, plus the approvals the Manager's queue offers them (linked) |
| `eddi-editor`, `eddi-admin` | plus "Build a team" (the wizard), team settings, analytics, links into the Manager; an admin a link to the Platform Operator (`/manage/operator`) |

**The Platform Operator is not in Workforce** (D5): an admin gets a link to it, never its
drawer. Its allow-list, prompt and revision stay in `ui/manager/src/lib/operator`.

## 2. Commands

Run `npm ci` once in `ui/` (the npm workspaces root), then here:

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server on port **3002** (`PORT` overrides), proxying the API to :7070 (`EDDI_BACKEND_URL` overrides). With no backend, the shared MSW mock API answers |
| `npm run build` | `tsc -b` + `vite build` → `dist/workforce.html` and `dist/assets/` |
| `npm run budget` | First-load gzip size of `workforce.html` against `ui/bundle-budget.json` (after a build) |
| `npm run typecheck` · `npm run lint` | TypeScript; ESLint, including the app boundary |
| `npm test` | Vitest (jsdom + MSW) |
| `npm run i18n:check` | The i18n drift gate (`ui/scripts/check-i18n.mjs`) over this app's `src/`, the packages and the locale files |
| `npm run test:e2e` | Playwright, the `ui` tier: the browser against this dev server with the mock API, no backend |

Maven builds it into the jar (`pom.xml`, execution `workforce-build`; copied by
`copy-ui-bundles` beside the Manager's files), and `-DskipUi=true` skips it like the others.

## 3. Architecture

```
src/
├── main.tsx              # startApp (@eddi/app-kit): mock probe, locale, router
├── app-router.tsx        # the data router + AppProviders (@eddi/app-kit)
├── app.tsx               # the route table — everything under /workforce
├── index.css             # tailwindcss + @eddi/app-kit/styles/app.css
├── components/workforce/ # layout, sidebar, Home, board, wizard, analytics, …
├── pages/workforce/      # the routed pages (lazy)
├── hooks/  lib/          # what only Workforce uses
├── i18n/                 # config.ts + locales/ (Workforce's own strings, 11 locales)
└── test/setup.ts         # its i18n; the shared Vitest setup runs first
```

### What it shares, and how

Workforce **never imports Manager code**, and the Manager never imports Workforce's.
ESLint (`appBoundary` in `ui/eslint.config.base.js`) and `src/__tests__/app-boundary.test.ts`
fail on either. What both apps need lives in a package:

- **[`@eddi/app-kit`](../packages/app-kit/README.md)** — the signed-in apps' shared layer:
  the API client and its hooks, the Keycloak session (`AuthProvider`, `useAuth`), the UI
  primitives, the group, chat and editor components both render, the base stylesheet, the
  boot (`startApp`) and providers (`AppProviders`), and the mock API with the test helpers.
  Imported by path: `@eddi/app-kit/components/chat/chat-panel`.
- **`@eddi/core`, `@eddi/ui`, `@eddi/conversation`** — as in the Manager. A team's
  discussion is `@eddi/conversation/team`: the `GroupDiscussion` controller (which the
  board drives through `@eddi/app-kit`'s `useGroupDiscussionStream`) and the team
  components — task board, decision card, structured turns, the human-turn banner, the
  overview. The board and the history viewer render a discussion through ONE transcript,
  `components/workforce/board-transcript.tsx`: the viewer passes the stored conversation
  (read-only, opened at the top), the board the live stream.

Code only Workforce uses stays here. When the Manager comes to need it too, move it to
`@eddi/app-kit` (with `git mv`), not into a copy — or to `@eddi/conversation/team` if it
is part of rendering a discussion and needs no router.

### Moving between the apps

The Manager (`/manage`, `/welcome`) is another app with another router. A `<Link>` or
`navigate()` to its paths matches nothing here — use **`AppLink`**, **`AppNavigate`** or
**`useAppNavigate`** (`@eddi/app-kit`), which use the router within Workforce and a page load
to the Manager. `src/__tests__/route-integrity.test.ts` fails on a router link into the
other app. Theme (`eddi-theme`), language (`i18nextLng`), pins (`eddi-workforce-pins`) and the
Keycloak session carry over through same-origin storage; both shells load
`/manage/__auth_config__.js`, so one Keycloak client serves both.

### Serving and deep links

EDDI serves `workforce.html`, branded, for **every** `/workforce/**` path
(`RestWorkforceResource` → `BrandedShellRenderer`), so a reload or a deep link —
`/workforce/chat?agentId=…&conversationId=…` (T1.15) included — lands in this app and the
router takes it from there. The hashed chunks sit under `/assets/` beside the Manager's.

### The sidebar, Home and the chat URL (phase 4)

- **The sidebar's conversations** (`components/workforce/session-list.tsx`, data in
  `hooks/use-workforce-sessions.ts`, shaping in `lib/workforce-sessions.ts`) list the person's
  chats (`GET /conversationstore/conversations`, owner-scoped by the backend; for an admin,
  who sees everyone's, narrowed to their own here) and team sessions
  (`GET /groups/{id}/conversations` for each team they can see), grouped Pinned / Today /
  Yesterday / Previous 7 / Previous 30 days / by month. Member conversations of a team
  session are left out of the chats (`memberConversationIds`). Rename and pin are
  `PATCH /conversationstore/conversations/{id}` (B2), delete is the owner `DELETE`; all three
  update the TanStack cache first and restore it on error (`workforceKeys`). Team sessions
  have no title or pin on the backend: their question is the title, and they can only be
  deleted. Every endpoint here is open to `eddi-user` — keep it that way
  (`e2e/workforce-end-user.spec.ts` fails on any store read).
- **Home** (`components/workforce/workforce-home.tsx`): `HomeComposer` (agent picker
  defaulting to the agent of the most recent chat, else the first listed; the picked agent's
  `appearance.starters` as chips; its review notice), `RecentSessions` (the top five of the
  sidebar's data), then the catalog with each agent's B4 appearance from
  `GET /catalog/agents`. Sending hands the text to the chat in **router state**
  (`@eddi/app-kit/lib/workforce-chat-start`), never in the URL, so a link still only
  preselects an agent.
- **The chat** is the shared `ChatPanel` with `urlSync`: `?agentId=…&conversationId=…` stays
  in the address bar so a reload or a second tab returns to the conversation.

### Tier 1 (phase 7)

- **Keyboard.** `components/workforce/quick-switcher.tsx` (mounted by the layout) owns ⌘K /
  Ctrl+K — the quick switcher over the sidebar's sessions, the agents and "New chat", a lazy
  `quick-switcher-dialog.tsx` — and ⌘⇧O / Ctrl+Shift+O, which goes Home with
  `NEW_CHAT_STATE` (`lib/workforce-keyboard.ts`) so `HomeComposer` focuses its box. Esc
  (stop), ↑ (edit the last message) and Enter live in the shared `ChatPanel`. The shortcuts
  dialog (`?`) lists all of them; a new shortcut goes there too.
- **Installable, no service worker.** The shells link `/workforce/manifest.webmanifest`,
  which EDDI renders in the brand (`RestWorkforceResource#manifest`, `WorkforceManifest`;
  the dev server answers with the default brand's, `vite.config.ts`). There is deliberately
  no service worker: browsers install without one, and nothing signed-in may be served from
  a cache. Do not add one without that review.
- **Mobile.** The shells set `viewport-fit=cover, interactive-widget=resizes-content`; the
  layout pads by the safe-area insets (topbar, bottom tabs, sides) and takes its height from
  `--wf-viewport-height` (`hooks/use-visual-viewport-height.ts`, set only while an iOS
  keyboard shrinks the visual viewport), else `100dvh`. Use those, not `h-screen`.

### i18n

Workforce's own strings (`Workforce.*`, `analyticsPage.*`, …) are in `src/i18n/locales/`,
in all eleven locales; what the shared components render is in `@eddi/app-kit`'s, and
`setupAppI18n` merges the two (and `@eddi/conversation`'s) into each locale's bundle. A new
key goes where the component that renders it lives — here for a Workforce component, in the
package for a shared one. `npm run i18n:check` fails on a key the code uses that no locale
file it loads defines.

### Tests

- Vitest runs `ui/packages/app-kit/src/test/setup-common.ts` first (mocked Keycloak and
  Monaco, the MSW server over the shared mock API), then `src/test/setup.ts`. Render through
  `renderWithProviders` / `renderPage` / `renderAs` from `@eddi/app-kit/test/test-utils`.
- The mock API is the Manager's too (`@eddi/app-kit/test/mocks/handlers`): a handler added
  for a Workforce screen serves both apps' tests and dev servers.
- `e2e/` is the `ui` tier only; `e2e/fixtures.ts` re-exports the shared fixture that fails a
  test on an API call no handler answered. The backend tiers (integration, full stack,
  auth) run from `ui/manager` against the jar, which carries Workforce too.
