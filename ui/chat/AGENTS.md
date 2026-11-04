# EDDI Chat UI — AI Agent Guidelines

> **This file is loaded by AI coding assistants. Follow ALL rules below.** The [root `AGENTS.md`](../../AGENTS.md) applies here too — branching, push approval, commit attribution and the changelog rule are defined there.

## 1. Project Context

> **This directory is part of [labsai/EDDI](https://github.com/labsai/EDDI).** It was the separate `labsai/EDDI-Chat-UI` repository until 2026-09-15; its full history was imported here (`git log -- ui/chat`). Issues and pull requests go to `labsai/EDDI`. The UI is built into the EDDI jar by Maven from the repository root — see the root `AGENTS.md` (Build & Test Commands).

**eddi-chat-ui** is a standalone React 19 chat widget for [EDDI](https://github.com/labsai/EDDI) agents. Built with Vite + TypeScript 5.9, vanilla CSS with CSS custom properties, and `react-markdown` for rich message rendering.

### Tech Stack

| Technology     | Version | Purpose                        |
| -------------- | ------- | ------------------------------ |
| React          | 19      | UI framework                   |
| TypeScript     | 5.9     | Type safety (7 waits on typescript-eslint support) |
| Vite           | 8       | Build tool + dev server        |
| Vitest         | 5.x     | Unit testing (jsdom)           |
| react-markdown | 10.x    | Markdown rendering in messages |

### Ecosystem

- **EDDI backend** (repository root) — Quarkus REST API at `/agents/{env}/{agentId}`; the Maven build copies this widget into the jar, served at `/chat`
- **EDDI Manager** (`ui/manager`) — Admin UI with embedded chat panel, shares API patterns

---

## 2. Project Structure

```
src/
├── api/            # The widget's own API layer — the backend contract itself is @eddi/core
│   ├── embed-auth.ts       # postMessage token hand-off with the host page (docs/chat-ui-embedding.md)
│   ├── chat-config.ts      # Operator settings chat.html loads before the widget; the URL token
│   ├── brand.ts            # The deployment brand and per-agent appearance layers
│   ├── hitl-api.ts         # pauseHeadline() — the widget's words for a pause
│   ├── rating-api.ts       # RATING_CHANNEL, httpRatingClient, ratingErrorMessage() (the widget's copy)
│   └── demo-api.ts         # Mock API for demo mode (incl. an in-memory rating backend)
├── components/     # UI components
│   ├── ChatApp.tsx         # Picks the screen for the URL; unknown paths → /chat/production/default
│   ├── ChatLocationProvider.tsx # The address bar (or, with initialPath, an in-memory one for tests)
│   ├── ChatRoute.tsx       # Direct routes; the demo is off under a brand
│   ├── ChatWidget.tsx      # Main orchestrator (lifecycle, SSE, query params)
│   ├── ChatHeader.tsx      # Logo/title, undo/redo, theme toggle, new conversation
│   ├── MessageBubble.tsx   # User/agent messages with markdown
│   ├── markdown-plugins.ts # On-demand KaTeX / highlight.js / raw-HTML loading (rich-math.ts, rich-highlight.ts, rich-raw.ts)
│   ├── SecretInput.tsx     # The input field an agent requested (password masked, text/email plain)
│   ├── ChatInput.tsx       # Auto-grow textarea, attachment chips, secret mode
│   ├── PausedCard.tsx      # Awaiting-approval state (read-only, no approve/reject)
│   ├── RatingCard.tsx      # "How did we do?" — stars/thumbs, tags, comment, thanks state
│   ├── StepRating.tsx      # Thumbs under an agent reply, optional reason/comment form
│   ├── QuickReplies.tsx    # Pill buttons for suggested replies
│   ├── Indicators.tsx      # Typing (dots), Thinking (brain), Escalating (cascade), Using {tool}
│   └── ScrollToBottom.tsx  # Floating scroll button
├── hooks/
│   ├── useTheme.ts         # Dark/light/system theme with localStorage
│   ├── useHitlPolling.ts   # Polls approval-status while a turn is paused
│   └── useRatings.ts       # Restore, optimistic rate/withdraw, when the rating card offers itself
├── store/
│   └── chat-store.tsx  # Context + useReducer state management
├── styles/
│   ├── variables.css   # CSS custom properties (dark/light tokens)
│   └── chat.css        # All component styles (BEM naming)
├── test-utils/
│   └── sse.ts          # SSE stream + status-aware fetch harnesses
├── i18n.ts             # String tables (en/de/fr/es), t(), initLocale() — ALL widget-owned text goes through t()
├── ime.ts              # isImeComposing(): Enter that confirms an IME candidate is not "send"
├── location.ts         # The three URL shapes (parseChatPath), useChatRoute() — there is no router
├── turn-notices.ts     # skippedTurnMessage() — the widget's words for a dropped turn
├── main.tsx            # Entry point
└── types.ts            # Shared TypeScript types
```

### Where the API and SSE code lives: `@eddi/core`

HTTP (`ApiError`, `errorPayload`, the auth token and the 401 hook), the SSE parser and
reader, the conversation API (start, read, send, stream, undo, redo, rerun, managed,
profile), HITL status and cancel, attachments, ratings, rating steps, snapshot → messages
and the contract types are in [`ui/packages/core`](../packages/core/README.md), shared with
the Manager. Import them from `@eddi/core`; `@/types` re-exports the contract types next
to the widget's own `ChatMessage` and `ChatConfig`. The widget drives core's default
client: `setBaseUrl`, `setAuthToken` and `setUnauthorizedHandler` (installed by
`embed-auth.ts`).

**The backend contract list — the rules verified against EDDI's source (six states, ten SSE
events, `inputField` on `done`, the 409 that restores the draft, the attachment context
keys, the rating envelope, …) — is now [`ui/packages/core/README.md`](../packages/core/README.md),
each rule with the test that pins it.** Read it before touching the turn loop. A contract
change goes into `@eddi/core`, with a test there; what the widget *says* about it (copy
through `t()`) stays here.

---

## 3. Key Conventions

1. **CSS** — BEM naming: `.chat-header__logo`, `.message--user`, `.quick-replies__btn`. No Tailwind.
2. **State** — `ChatProvider` → `useChatState()` / `useChatDispatch()`. No prop drilling.
3. **API** — `@eddi/core` (see above). SSE streaming uses its `AsyncGenerator` (`sendMessageStreaming`). Tests that need the network replace `globalThis.fetch` (`src/test-utils/sse.ts`); a test that mocks an API function mocks `@eddi/core` with `importActual` and overrides only that function (see `ChatInput.test.tsx`).
4. **Testing** — Wrap components in `<ChatProvider>`, and anything that reads the route in `<ChatLocationProvider initialPath="/chat/production/agent-1?…">` (the in-memory location that replaced `MemoryRouter`). Mock `window.matchMedia` in `test-setup.ts`.
4a. **No router** — react-router was 92 KB of first load for three path shapes. `src/location.ts` parses `/chat/managed/:intent/:userId`, `/chat/:environment/:agentId/:userId` and `/chat/:environment/:agentId` with react-router's matching rules (case-insensitive literals, `managed` beats `:environment`, trailing slashes ignored, params decoded); `location.test.tsx` pins them — these URLs are a public contract for embedders. Do not add a router back for a fourth shape; extend `parseChatPath`.
4b. **Keep heavy renderers lazy** — KaTeX, highlight.js and the raw-HTML parser (`rehype-raw` → parse5) load only when a message needs them (`markdown-plugins.ts`); `npm run budget` fails CI when the first load grows past `ui/bundle-budget.json`. Raw HTML is still rendered (sanitised) for every agent until the per-agent `rawHtml` setting exists (frontend unification plan, D9).
5. **Demo mode** — `/chat/demo/showcase` uses `demo-api.ts`. Check with `isDemoMode()`.
6. **Query params** — parsed in `ChatWidget.tsx` (`parseConfigFromQuery`, `COLOR_PARAM_MAP`); read those rather than this list. Behaviour: `hideUndo`, `hideRedo`, `hideNewConversation`, `hideQuickReplies`, `hideStreaming`, `hideLogo`, `hideAgentName`, `theme`, `title`, `apiServer`, `token` (read once, then removed from the address bar), `tokenOrigin` (exact host origins for the postMessage token hand-off, `embed-auth.ts`), `userId`. `theme` must be `dark`/`light`/`system`; every colour must pass `isSafeCssColor` and `fontFamily` `isSafeFontFamily`, or it is ignored. Colours (URL-encode `#` as `%23`): `accentColor`, `accentHover`, `bgColor`, `surfaceColor`, `textColor`, `textMuted`, `agentBg`, `agentBorder`, `agentText`, `userBg`, `userText`, `inputBg`, `inputBorder`, `borderColor`, `headerBg`, `fontFamily`.
7. **HITL is read-only here** — the widget surfaces a paused turn and offers cancel, but never Approve/Reject. Deciding belongs to a reviewer in the Manager's approvals page (`/manage/approvals`), which reads the backend's pending-approvals endpoint.
8. **Lint** — `npm run lint` (ESLint, zero warnings) runs in CI next to `npm run typecheck` and `npm test`.

---

## 4. Build & Deploy

Production build goes to `dist/` (`chat.html`, `scripts/js/chat-ui.<hash>.js`,
`scripts/css/chat-ui.<hash>.css`, `fonts/`, `img/`). The EDDI Maven build copies it into the jar
(`pom.xml`, execution `copy-ui-bundles`), so nothing is ever copied into the backend source tree:

```bash
npm run build    # Outputs to dist/
```

Dependencies install at `ui/`, not here: `ui/` is an npm workspaces root and the Chat UI
shares one `ui/package-lock.json` with the Manager and `ui/packages/*` (`cd ui && npm ci`).
Change a dependency from `ui/` with `npm install <pkg> -w chat`, keep a version the Manager also
uses identical in both `package.json` files, and put any `overrides` in
`ui/package.json`. On Windows, run `npm run lockfile:check` in `ui/` afterwards — the
`oxide-wasm32-wasi` trap described in `ui/manager/AGENTS.md` now applies to this lock too.

### Quality gates

CI's `UI Chat` job runs `npm ci` (at `ui/`, the npm workspaces root), `npm run typecheck`, `npm run lint` and `npm test` on every
PR into `main` that touches `ui/` (`ci.yml` runs on no other base branch, so a stacked PR gets
no CI at all), then builds and runs `npm run budget`. Run them all before pushing.

---

## 5. Mandatory Workflow

1. **Before work**: `git status`, read this file + the top of the root `docs/changelog.md` and anything pending in the root `docs/changelog.d/`
2. **During work**: Commit with `feat(chat-ui):` / `fix(chat-ui):` (`(ui)` for a change that spans both UIs). Each commit must pass the gates below.
3. **After work**: add your entry as a **new file** `docs/changelog.d/YYYY-MM-DD-<slug>.md` — never edit `docs/changelog.md`, which every open PR would conflict over. See [`docs/changelog.d/README.md`](../../docs/changelog.d/README.md).

### DO NOT

- Add external state libraries (zustand, redux) — use Context + useReducer
- Add CSS frameworks — use CSS custom properties
- Duplicate logic from `demo-api.ts` into components
