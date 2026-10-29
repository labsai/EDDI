# Frontend Unification and End-User UX

**Status:** Plan, revision 1 (2026-10-29). Nothing implemented.
**Scope:** `ui/manager`, `ui/chat`, a new `ui/workforce`, shared packages under `ui/packages/`, and the backend endpoints an end-user app needs. Coordinates with [`white-label-ui-plan.md`](white-label-ui-plan.md) (branding) and [`monorepo-migration-plan.md`](monorepo-migration-plan.md) (the build wiring this extends).

**Goals, as asked:**

1. Split Workforce out of the Manager into its own app, with shared components.
2. One set of components for the Chat UI, the Manager and Workforce.
3. An end-user experience (outside the Manager) that holds its own against Open WebUI, LibreChat, ChatGPT, Claude.ai and LobeChat.

---

## 1. Status quo (sweep of 2026-10-29)

### 1.1 What ships today

| Surface | URL | Source | Stack | Audience |
|---|---|---|---|---|
| Manager | `/manage/**` | `ui/manager` (`manage.html`) | React 19, Tailwind v4, Radix, TanStack Query, Zustand, i18next (11 locales), React Router data router | admins, editors |
| Welcome chooser | `/welcome` (`/` redirects there) | `ui/manager` (`welcome.html`) | same bundle | everyone |
| Workforce | `/workforce/**` | `ui/manager` (`workforce.html`) | **same bundle, same `main.tsx`, same router** as the Manager | meant for business users; in practice editors (see 1.4) |
| Chat UI | `/chat/**` | `ui/chat` (`chat.html`) | React 19, hand-written BEM CSS, Context + `useReducer`, its own `t()` (4 locales), BrowserRouter | anyone with a link or an embed (iframe + postMessage token) |

The Workforce is already a separate HTML shell served by its own resource (`RestWorkforceResource`, branded through `BrandedShellRenderer`). It is still one Vite build with three inputs (`ui/manager/vite.config.ts`, `rolldownOptions.input`), a single `<Routes>` table in `src/app.tsx`, and one provider stack. "Splitting" it is therefore mostly about **code ownership, bundle and dependency boundaries**, not about URLs. The URLs, shells and backend resources can stay exactly as they are.

### 1.2 Size of what would be shared or split

| Area | Files | Lines (no tests) | Tests |
|---|---|---|---|
| Workforce pages + components (`pages/workforce`, `components/workforce`) | 50 | ~17.2k | 43 test files |
| Manager chat (`components/chat`, `hooks/use-chat.ts`, `lib/api/chat.ts`) | 13 | ~5.9k | 29 test files |
| Chat UI (`ui/chat/src`, including CSS) | 35 | ~10.7k (`ChatWidget.tsx` alone is 2.3k, `chat.css` 1.9k) | 35 test files |

Shared code Workforce already pulls in from the Manager: `lib/api/groups`, `hooks/use-groups`, `hooks/use-chat`, `components/ui/*`, `components/groups/*` (task board, decision cards, structured entries, discussion panel), `lib/markdown-safe`, `lib/ime`, the agent picker, the theme provider, the operator drawer. About 25 Manager files outside `workforce/` mention Workforce (mode switcher, auth provider, route registry, query keys, MSW handlers, operator system prompt).

### 1.3 Two chat implementations, each with fixes the other lacks

The Manager's `ChatPanel` (used by `/manage/chat`, Agent Studio and `/workforce/chat`) and the Chat UI's `ChatWidget` are independent implementations of the same backend contract: start, say, stream, undo/redo, rerun, HITL pause, attachments, secret input, ratings. Both carry hard-won contract fixes (see the "Backend contract" list in `ui/chat/AGENTS.md`: the 9 SSE events, the `{"message","code"}` error envelope, 409 restores the draft, `inputField` on `done`, the masked `input`, and so on).

| Capability | Manager `ChatPanel` | Chat UI `ChatWidget` |
|---|---|---|
| Markdown | GFM; images rendered as links (`markdown-safe.tsx`) | GFM, raw HTML sanitized, KaTeX and highlight.js loaded on demand, output images |
| Quick replies | partial | yes |
| Agent switcher, conversation history | yes | no (one agent per URL, no history list) |
| Debug drawer, pipeline trace, streaming toggle | yes | no |
| Guardrail notices, review notice | yes | review notice only |
| Embedding (postMessage token, theme and colour params, `hide*` flags) | no | yes |
| Managed agents (`/chat/managed/:intent/:userId`) | no | yes |
| Demo mode | MSW | `demo-api.ts` |
| HITL | pause notice; approvals live in `/manage/approvals` | read-only paused card with cancel |
| i18n | i18next, 11 locales, RTL tested | own `t()`, en/de/fr/es |

Duplicated modules, one for one: `ime.ts`, rating steps (`rating-steps.ts` / `chat-rating-steps.ts`), secret input, paused card / pause notice, the rating card and step rating, attachment staging and context building, SSE parsing (`sse-events.ts` / `sse-utils.ts` + `lib/api/chat.ts`), snapshot-to-messages. `lucide-react` is at 0.577 in one app and 1.59 in the other.

### 1.4 The end-user gap is mostly a backend gap

Somebody with only `eddi-user` cannot build a "my AI assistant" experience today, whatever the frontend does:

| Need | Today | Where |
|---|---|---|
| "Which agents may I talk to?" | **No endpoint at `eddi-user`.** `agentstore`, `deploymentstore`, `capabilities` and `descriptorstore` are `eddi-admin`/`eddi-editor` only | `IRestAgentStore`, `IRestDeploymentStore`, `IRestCapabilityRegistry`, `IRestDocumentDescriptorStore` |
| "Which teams (groups) may I use?" | **No endpoint at `eddi-user`.** `groupstore` is admin/editor; only *running* a group discussion (`/groups/...`) admits `eddi-user` | `IRestAgentGroupStore`, `IRestGroupConversation` |
| Agent card (name, description, rating config, review notice) | Yes, at `USE` level | `GET /agents/{agentId}/profile` |
| "My conversations" | Yes: `GET /conversationstore/conversations` is open to `eddi-user` and owner-scoped for non-admins | `IRestConversationStore` |
| Conversation title, rename, pin | **No.** `ConversationDescriptor` has `agentName`, `viewState`, step count, state; no title | `ConversationDescriptor` |
| Search across my conversations | **No** (only `filter` on descriptors) | — |
| "What do you remember about me?" | `usermemorystore` admits `eddi-user`; caller-equals-path-user enforcement **must be verified** before a self-service UI uses it | `IRestUserMemoryStore` |
| Agent avatar, conversation starters, accent | **No.** Parked as the "per-agent appearance" seam | `white-label-ui-plan.md` §4.6 |
| Tool progress | `tool_call` (name only) at start; arguments only later, redacted, in `task_complete.toolTrace`; no "tool finished" event | `RestAgentEngineStreaming` |
| Model reasoning ("thinking") | **No SSE event** | — |
| RAG sources / citations | **Unverified** whether retrieved sources reach the client in any form | `docs/rag.md` |
| Editing an earlier message, branching | Undo/redo/rerun of the last step only; forking is on the roadmap ("Session Forking") | `IRestAgentEngine` |
| Sharing a conversation | **No** | — |

This means the Workforce today is in practice an editor's tool: its wizard calls `agents/setup`, its settings page writes group configurations, and its dashboard lists groups through admin/editor endpoints.

### 1.5 What EDDI already has that the competition mostly does not

These should drive the design, not be squeezed in afterwards:

- **Multi-agent teams** with 7 discussion styles, votes, a facilitator, humans as members, shared artifacts, task lists (`docs/group-conversations.md`).
- **Human-in-the-loop** turn and tool-call approval gates, notifications by email and Slack (`docs/hitl.md`).
- **Persistent user memory** with visibility scoping and Dream consolidation (`docs/user-memory.md`).
- **Deterministic behaviour rules**, quick replies, agent-requested secret input, structured `inputField`.
- **Undo/redo**, ratings, review notices, model cascades with visible escalation, guardrails.
- **White-labelling** at runtime, and workspaces with `USE`/`VIEW`/`EDIT`/`OWN` levels.

---

## 2. Target architecture

### 2.1 Layout

Keep the two existing app directories where they are. Moving `ui/manager` to `ui/apps/manager` would touch `pom.xml`, `ci.yml`, the Stryker scope, `BuildQualityGatesTest`, the operator-revision copy, every AGENTS.md and dozens of docs, and conflict with every open Manager PR, for no user-visible gain.

```
ui/
├── package.json            # NEW: npm workspaces root, one package-lock.json
├── tsconfig.base.json      # NEW: shared strict compiler options, path aliases
├── eslint.config.base.js   # NEW
├── packages/
│   ├── core/               # @eddi/core: framework-free TypeScript
│   ├── tokens/             # @eddi/tokens: design tokens, Tailwind v4 theme, brand scale
│   ├── ui/                 # @eddi/ui: primitives (button, dialog, menu, tooltip, toast…)
│   ├── i18n/               # @eddi/i18n: i18next setup, shared namespaces, locale loader
│   ├── conversation/       # @eddi/conversation: the 1:1 chat experience
│   └── teams/              # @eddi/teams: group transcript, board, task list, decision cards
├── manager/                # app: /manage, /welcome
├── workforce/              # NEW app: /workforce
└── chat/                   # app: /chat (embed and link surface)
```

### 2.2 Packages

| Package | Contents | Comes from |
|---|---|---|
| `@eddi/core` | `http` (status-aware fetch, `ApiError`, `{error, code}` envelope, auth-header provider, 401 retry hook), SSE frame parser and event interpreter, conversation API (start, say, stream, undo, redo, rerun, cancel, resume, state), snapshot to messages, HITL status and poll cadence, attachments (upload, `attachment_N` context), ratings, agent profile, the backend contract types. **No React.** | Union of `ui/chat/src/api/*` and the Manager's `lib/api/{chat,sse-utils,hitl,attachments,ratings}.ts`. Where they disagree, the stricter one wins, with a test from each side |
| `@eddi/conversation` | A **conversation controller** (a plain store with `subscribe`/`getSnapshot`, read through `useSyncExternalStore`) that owns the turn state machine now spread over `ChatWidget.tsx` and `use-chat.ts`; components: `Conversation`, `MessageList`, `Message` (markdown with sanitize, KaTeX, highlight, Mermaid, safe images), `Composer` (auto-grow, IME, attachments with drop and paste, secret input, `inputField`), `QuickReplies`, `ActivityIndicator` (typing, tool, cascade), `PausedCard`, `RatingCard`, `StepRating`, `ScrollToBottom`. **Slots** for host extras: `headerActions`, `messageActions`, `beforeComposer`, `sidePanel` | Both chats |
| `@eddi/teams` | `BoardTranscript`, `ConversationViewer`, `AdvisorResponseCard`, `DecisionRecordCard`, `StructuredEntryBody`, `TaskBoard`, `entryTypeInfo`, the group SSE stream (moved onto the shared frame parser, closing the `readGroupSSE` debt noted in `HANDOFF.md`) | Manager `components/groups`, `components/workforce` |
| `@eddi/ui` | Radix-based primitives and app chrome pieces shared by more than one app: button, badge, card, dialog, alert dialog, dropdown, tooltip, skeleton, error boundary, empty and error states, theme provider, toaster, command palette shell | Manager `components/ui`, parts of `components/shared` and `components/layout` |
| `@eddi/tokens` | Semantic CSS variables (`primary`, `warning`, surfaces, text, bubble colours), the `brand-50…950` `color-mix` scale from white-label R8, dark/light, a Tailwind v4 `@theme` export; maps the Chat UI's 16 URL colour params and `/branding/brand.css` onto the same tokens | Manager `index.css`, Chat `variables.css` |
| `@eddi/i18n` | i18next config, lazy locale loader, `productName` default variables (white-label §4.4), RTL helper; shared namespaces `conversation.*` and `teams.*` live here, in all 11 locales; each app keeps its own namespace | Manager `i18n/config.ts`, Chat `i18n.ts` |

Packages are consumed **as source** through workspace links (Vite compiles them), so there is no publish step and no build order. Each package has its own Vitest suite; the `i18n:check` script learns to scan packages.

### 2.3 Rules that keep it from rotting

- Apps may import packages; packages never import apps; `core` imports nothing but the platform. Enforced by an ESLint `no-restricted-imports` rule per package, plus a test that fails on a cycle.
- Packages never call `useNavigate` or read the URL. Routing is the host's: the controller receives an `agentId`, `conversationId` and callbacks.
- Packages never read `window.__EDDI_AUTH__` or `__EDDI_CHAT__`. Hosts pass an auth provider into `core`.
- A **bundle budget** per app (`size-limit` or a small script over `dist/`), run in CI. Today the Chat UI is the small one, and it must stay small: it is what gets embedded in customers' pages.

### 2.4 Conventions that must be reconciled

| Topic | Manager | Chat UI | Decision |
|---|---|---|---|
| Styling | Tailwind v4 | BEM, vanilla CSS, "no CSS frameworks" rule | **Tailwind v4 + tokens everywhere.** The widget renders in its own iframe, so global utility CSS cannot leak into a host page. The URL colour params keep working by setting the same variables. `ui/chat/AGENTS.md` §3/§5 change with it |
| State | Zustand, TanStack Query | Context + reducer, "no Zustand" rule | The shared controller is framework-free. Apps keep their own choice for app state. TanStack Query for server lists (conversation list, agent catalog) in the two signed-in apps |
| i18n | i18next, 11 locales | own `t()`, 4 locales | **i18next for all.** The widget gains 7 locales and RTL; a lazy loader keeps the cost to one locale |
| Routing | data router | BrowserRouter | Each app its own; packages are router-free |
| Icons | lucide 1.59 | lucide 0.577 | One version at the workspace root |

---

## 3. The three apps after the split

### 3.1 Manager (`/manage`, `/welcome`)

Administration and building: agents, workflows, resources, groups, operator, approvals, monitoring, settings. `/manage/chat` and Agent Studio use `@eddi/conversation` plus admin slots: agent and environment picker, streaming toggle, debug drawer, pipeline trace, raw memory. Workforce routes, the Workforce half of the mode switcher's state, `workforce-pins`, and the Workforce MSW handlers leave.

### 3.2 Workforce (`/workforce`), the end-user app

The place a signed-in person uses AI. One app for both shapes of conversation:

- **Chats**: 1:1 conversations with agents.
- **Teams**: group discussions (the current boards).

Role-aware rather than two apps:

| Role | Sees |
|---|---|
| `eddi-user` | Home, agent and team catalog (what they may `USE`), their chats and team sessions, their memory, approvals addressed to them |
| `eddi-approver` | plus an approvals inbox |
| `eddi-editor`, `eddi-admin` | plus "Build a team" (the wizard), team settings, analytics, and a link into the Manager |

The operator drawer, which `WorkforceLayout` mounts today, is a Manager concern (its allow-list, revision and gate guards live in `lib/operator`). For admins, Workforce shows a link to `/manage/operator` instead of mounting the drawer.

Same Keycloak client and realm as the Manager (both shells already load `/manage/__auth_config__.js`), so there is one sign-in across both apps. Switching apps is a full page navigation.

### 3.3 Chat UI (`/chat`)

Stays the **embeddable, link-shareable, single-agent surface**: anonymous use of published agents, managed intents, postMessage auth, URL theming, `hide*` flags, demo mode. Built from `@eddi/conversation` in a `compact` variant. Optional later: a conversation list when the embedding supplies a signed-in user.

---

## 4. End-user UX: the bar to meet

Benchmarked against Open WebUI, LibreChat, ChatGPT, Claude.ai and LobeChat. "Backend" names what the server must add first (§5).

### Tier 1: table stakes (missing any of these reads as unfinished)

| # | Feature | Today | Backend |
|---|---|---|---|
| T1.1 | Sidebar of my conversations, grouped by date, with rename, pin, delete | history popover in the Manager only | B2 |
| T1.2 | Automatic titles | — | B2 |
| T1.3 | Search across my conversations | — | B3 |
| T1.4 | Home screen: agent and team catalog, recent items, "conversation starters" per agent | — | B1, B4 |
| T1.5 | Streaming that reads well: no layout jump, sticky scroll that yields when the user scrolls up, a Stop button, visible reconnect | partial in both | — |
| T1.6 | Edit the last message and resend; regenerate (rerun) | rerun exists | — (undo + say); B9 for older messages |
| T1.7 | Rich output: GFM tables, code blocks with language label and copy, KaTeX, Mermaid, sanitized images, copy message | split between the two apps | — |
| T1.8 | Attachments: paste, drop, picker, image thumbnails, per-file progress and errors | both, differently | — |
| T1.9 | Tool activity shown as collapsible cards ("Searched the web", "Called CRM: 2 records") | name only, until next token | B6 |
| T1.10 | Keyboard: Enter / Shift+Enter, ⌘K palette, ⌘⇧O new chat, Esc to stop, ↑ to edit last | partial | — |
| T1.11 | Mobile and PWA: installable, safe areas, a bottom-sheet composer, the virtual keyboard not covering input | Workforce has a mobile layout | — |
| T1.12 | Accessibility: live region for streamed text, focus management, contrast in both themes, axe clean | partial | — |
| T1.13 | 11 locales with RTL, light/dark/system | Manager only | — |
| T1.14 | Thumbs up/down and optional reason on replies | both | — |

### Tier 2: where EDDI should be better than anyone

| # | Feature | Backend |
|---|---|---|
| T2.1 | **Teams in the same sidebar as chats.** Start a team session from a template, watch members discuss live, see votes and decision records, join as the human member | B1 |
| T2.2 | **@mention** an agent or a team inside a chat to hand the question over | B1; a design pass on how the transcript records the hand-off |
| T2.3 | **Approvals in the flow.** A paused turn shows who must approve and by when; the user is notified (web notification, existing email) when it continues; an approver decides in place if they hold the role | existing HITL endpoints; B8 for push |
| T2.4 | **"What I remember about you".** A memory panel: list, search, delete one, delete all, and see which agent wrote what. A GDPR asset, not a hidden feature | B5 |
| T2.5 | **Structured input.** `inputField` (secret, email, choice) and quick replies as first-class composer modes, not just pills | — |
| T2.6 | **Shared artifacts** of a team session in a side panel (the canvas pattern), with versions | existing group artifacts |
| T2.7 | **Visible model cascade and guardrails** in plain language ("escalated to a stronger model", "answer revised by a check") | — |
| T2.8 | Long jobs: self-continuation and scheduled runs reported as progress, with a notification when done | B8 |

### Tier 3: worth doing after the above

Voice input (browser speech recognition first, server STT later) and read-aloud; reasoning display (B7); citations for RAG answers (B10); share a read-only link (B11); export to Markdown or PDF; projects or folders; a personal prompt library with `/` commands; side-by-side comparison of two agents; temporary chats that are not kept.

### Explicitly not planned

Picking a raw model in the end-user app. EDDI's unit is the *agent*, whose model the designer chose; a model picker would bypass cascades, cost ceilings and guardrails. (The Manager keeps its model tooling.)

---

## 5. Backend work the UX depends on

Each item is config-driven where an agent designer could reasonably want a say (root AGENTS.md §1).

| # | Item | Shape | Notes |
|---|---|---|---|
| **B1** | **Catalog of what the caller may use** | `GET /catalog/agents` and `GET /catalog/groups` at `eddi-user`, deployed only, returning the `AgentProfile` fields plus `callerLevel` and appearance (B4) | Filtered in the query like the workspace-aware listings. Nothing about how an agent is built. Without workspaces enabled, what "everything" means for `eddi-user` needs a decision (§8 Q4) |
| B2 | Conversation title, rename, pin | `title`, `pinned` on `ConversationDescriptor`; `PATCH /conversationstore/conversations/{id}` owner-only; auto-title after the first agent reply, optionally by an LLM the agent names (`titleGeneration` on `AgentConfiguration`, off = first user message, truncated) | Auto-titling with an LLM is a cost, so it goes through the cost tracker and is off by default |
| B3 | Search my conversations | `GET /conversationstore/conversations/search?q=` over titles and outputs, owner-scoped, paged | Mongo text index, Postgres `tsvector`. Size and privacy review before indexing outputs |
| B4 | Agent appearance | `appearance {displayName, avatar, accentColor, starters[]}` on the agent, served by `/profile` and B1 | The white-label §4.6 seam. Needs ZIP export/import and the Manager editor |
| B5 | Self-service memory | Verify `usermemorystore` refuses a `userId` other than the caller's for non-admins; otherwise add `/usermemorystore/memories/me/...` | Security review item, not optional |
| B6 | Tool lifecycle on SSE | `tool_result {tool, ok, summary}` after each call, using the same redaction as `toolTrace` | Additive: an older client ignores the event. The 9-event contract test in `ui/chat` is extended, not broken |
| B7 | Reasoning on SSE | `reasoning` token events when the provider exposes them, opt-in per agent | Provider support varies; off by default |
| B8 | Notifications to the browser | Reuse workspace notifications; add web push (VAPID) for "your approval is decided" and "your long job finished" | Later phase |
| B9 | Edit an earlier message | Depends on Session Forking (roadmap) | Tier 1 ships "edit last" only |
| B10 | Citations | Surface the RAG sources the turn used, with titles and links, in `task_complete` | Verify what `docs/rag.md` stores first |
| B11 | Share link | Read-only snapshot with a signed, revocable token | Its own security design |

---

## 6. Phases

Each phase is shippable and leaves `main` green. Phases 1–4 change no behaviour a user would notice; they move code behind tests.

| Phase | What | Exit criteria |
|---|---|---|
| **0. Baseline** (S) | Record bundle sizes per entry, Lighthouse and axe on `/chat` and `/workforce`, screenshots of every chat state; turn §1.3 into an executable feature matrix (one Playwright spec per capability, tagged by app) | Numbers committed under `planning/`; matrix spec green on both apps for what each supports today |
| **1. Workspace scaffolding** (M) | `ui/package.json` workspaces, one lockfile, `tsconfig.base.json`, shared ESLint base, empty `packages/*`; `pom.xml`: one `npm ci` at `ui/`, then `npm run build -w manager -w chat`; `ci.yml` jobs install at the root | `./mvnw package` produces a byte-equivalent jar layout; all UI CI jobs green; the Windows lockfile check in `ui/manager/AGENTS.md` moved to the root |
| **2. `@eddi/core`** (L) | Merge both API layers. Port **both** test suites onto the merged module first, then switch the apps one at a time | Both apps' tests pass against `core`; duplicated modules deleted; no behaviour change in the matrix |
| **3. Tokens, primitives, i18n** (L) | `@eddi/tokens` (with the white-label brand scale; keep shrinking the 360 remaining `amber-*` utilities), `@eddi/ui`, `@eddi/i18n`; Chat UI moves to Tailwind and i18next | Chat bundle within +15% of baseline (set the budget then); URL colour params and `/branding/brand.css` still apply; RTL e2e passes on the widget |
| **4. `@eddi/conversation` and `@eddi/teams`** (XL) | The controller first (pure, heavily unit-tested, both apps' turn edge cases as fixtures), then components; Manager `ChatPanel` becomes `Conversation` plus admin slots; the widget becomes `Conversation compact`; Workforce chat and boards use them | The feature matrix shows the **union** of capabilities on every surface where they make sense (KaTeX in the Manager, history in Workforce…) |
| **5. Workforce app split** (M) | `ui/workforce` with its own `main.tsx`, router, layout, MSW handlers and e2e; Manager loses `/workforce` routes; Maven copies a third `dist/`; `RestWorkforceResource` unchanged | Manager bundle shrinks by the Workforce code; `e2e/workforce.spec.ts` moved and green; no Manager import of `workforce/` (lint-enforced) |
| **6. Backend B1, B2, B4, B5, B6** (L) | Catalog, titles, appearance, memory self-service check, tool results | Unit and IT tests per endpoint; docs pages; MCP/operator checks per root AGENTS.md §4.6 |
| **7. End-user Tier 1** (XL) | T1.1–T1.14 in Workforce, the conversation-level ones also in `/chat` | Each item has an e2e spec; Lighthouse PWA and accessibility ≥ 95 on Workforce |
| **8. Tier 2** (XL) | T2.1–T2.8 | Per item |
| **9. Tier 3** | as prioritised then | — |

**Order:** phase 0 now; phases 1–3 can run while the backend phase 6 starts in parallel, because B1, B2 and B5 do not depend on the frontend refactor. Phase 7 needs 4, 5 and 6.

### Things each phase must not forget

- **i18n**: every new key in `en.json` goes to all 11 locales in the same commit; `i18n:check` must learn the package directories in phase 1.
- **Platform Operator**: every backend item in phase 6, and the removal of the Workforce operator drawer, is checked against `system-prompt.ts` and `tool-scopes.ts`; any change there bumps `operator-revision.json`.
- **MSW**: handlers follow the code into the new app; the OpenAPI contract test's `EXEMPT` list is updated with new endpoints.
- **Stryker scope** in `stryker.config.json` follows any moved file.
- **Changelog fragment** per PR (root AGENTS.md §2 rule 8).
- **Docs**: `docs/chat-ui-embedding.md` (unchanged contract, new internals), a new end-user guide page for Workforce, `docs/agent-manager-gui.md`.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Merging two chats loses one side's contract fixes | Phase 2 and 4 port **both** test suites first; the merged code must pass the union. The backend contract list in `ui/chat/AGENTS.md` becomes the `@eddi/core` README and its test index |
| A long refactor conflicts with ongoing Manager feature work | Phases are small PRs; move files with `git mv` in their own commits; announce the freeze window for `components/chat` and `components/workforce` per phase |
| The widget gets heavier | Bundle budget in CI from phase 1; KaTeX, highlight and Mermaid stay lazy |
| Workforce becomes unusable for `eddi-user` until B1 lands | Ship B1 early (phase 6 runs in parallel); until then Workforce behaves as today for editors |
| One lockfile hits the Windows `oxide-wasm32-wasi` pruning trap harder | Keep the lockfile check; run it in CI on the root lock |
| Tailwind preflight inside the widget changes rendering for embedders | Visual regression screenshots of the widget's public states, taken in phase 0, compared in phase 3 |

---

## 8. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| Q1 | Is Workforce *the* end-user app (chats + teams, role-aware), with `/chat` kept as the embed surface? Or should a third app exist? | Workforce as the end-user app; two end-user surfaces, not three |
| Q2 | Keep `ui/manager` and `ui/chat` paths and add `ui/workforce` + `ui/packages`, or move everything to `ui/apps/*`? | Keep the paths (§2.1) |
| Q3 | Accept Tailwind and i18next in the Chat UI, replacing its "no CSS framework" and own-`t()` rules? | Yes (§2.4) |
| Q4 | Without workspaces enabled, which agents does the catalog (B1) list for `eddi-user`: every deployed agent, or only ones marked public? | Every deployed agent in production, matching what `/agents/{id}/start` already allows; workspaces narrows it |
| Q5 | Should the Workforce drop the operator drawer and link to the Manager instead? | Yes (§3.2) |
| Q6 | Is `/` → `/welcome` still right, or should signed-in `eddi-user`s land directly in Workforce? | Role-aware: `eddi-user` goes to `/workforce`, editors and admins keep the chooser |
| Q7 | Auto-titles with an LLM: allowed at all, and who pays? | Per-agent opt-in, through the cost tracker; default is the first user message |
