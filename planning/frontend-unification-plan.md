# Frontend Unification and End-User UX

**Status:** Plan, revision 3 (2026-10-29): the questions of revision 2 are decided (§10). Nothing implemented.
**Scope:** `ui/manager`, `ui/chat`, a new `ui/workforce`, shared packages under `ui/packages/`, and the backend endpoints an end-user app needs. Coordinates with [`white-label-ui-plan.md`](white-label-ui-plan.md) (branding) and [`monorepo-migration-plan.md`](monorepo-migration-plan.md) (the build wiring this extends).

**Goals, as asked:**

1. Split Workforce out of the Manager into its own app, with shared components.
2. One set of components for the Chat UI, the Manager and Workforce.
3. An end-user experience (outside the Manager) that holds its own against Open WebUI, LibreChat, ChatGPT, Claude.ai and LobeChat.

---

## 0. Revision 2: what the review changed

Revision 1 was reviewed against the code and a real build, and then from eight perspectives (§2). These findings changed the plan:

| # | Finding | Effect on the plan |
|---|---|---|
| R1 | **There are not two chat implementations but at least five**: the Manager's `ChatPanel` (`use-chat.ts`), the Operator (`use-operator-chat.ts`, 1.8k lines), the Workforce member thread (`workforce-thread.tsx`, 1.6k lines, its own stream loop and markdown), the group discussion (three transcript renderers), and the Chat UI widget. There are **14 separate `ReactMarkdown` call sites** (§1.3) | The shared layer is designed around *kinds of conversation* (§3.2), not around one chat panel. The Operator and group discussions are in scope from the start |
| R2 | **Measured:** `/workforce` loads exactly the same files as `/manage`, **714 KB gzipped (2.3 MB raw)** before the first page. 388 KB of the 606 KB main stylesheet is 476 `@font-face` rules for all eight Noto script families. The widget loads **217 KB gzipped**, and the raw-HTML path (`rehype-raw` → `parse5`) is about as big as the widget's own code | The split now has a measured payoff and a budget. Two quick wins (fonts per locale, the widget's HTML parser) come first, independent of the refactor (§7, phase 0) |
| R3 | **The "which agents may I use" logic already exists**: `AgentModelResolver.listModels()` filters deployed agents through `ResourceAccessGuard.requireAgentUseAccess` for `GET /v1/models` | The catalog endpoint (B1) is a thin, OIDC-authenticated wrapper over existing code, not new access control, so it moves to the front of the schedule |
| R4 | **Self-service memory is already safe**: every `RestUserMemoryStore` method calls `ownershipValidator.validateUserAccess(identity, userId)` | B5 shrinks from a security item to a test that pins it |
| R5 | **Workforce does not degrade for `eddi-user`, it fails**: its dashboard, board and settings read `/groupstore/...` (admin/editor only), and nothing in it checks roles | The role-aware Workforce is a prerequisite of the end-user story, not a later refinement |
| R6 | **The catalog could list the Platform Operator, and every internal agent, to every `eddi-user`** when workspaces are off | Listing becomes an explicit, config-driven opt-in per agent, default off (B1, D4) |
| R7 | **Sequencing**: revision 1 put four large refactor phases before any user-visible change | Re-sequenced into vertical slices that each ship something (§7). Packages are extracted when a second consumer needs them, not ahead of time |
| R8 | **Six packages is too many** for three apps and one team | Three packages: `@eddi/core`, `@eddi/ui`, `@eddi/conversation` (§3.1) |
| R9 | **Strategy**: EDDI already ships an OpenAI-compatible `/v1` API so that Open WebUI can be its front end (`docs/open-webui-integration.md`). Competing feature for feature on generic chat throws that away | EDDI's own end-user app matches the basics and leads where an OpenAI-protocol client cannot follow: approvals, teams, structured input, memory transparency, branding (§5) |
| R10 | **The widget forgets the conversation on reload** on the direct route: nothing keeps `conversationId` (only the managed `intent/userId` route resumes) | Added to Tier 1 (T1.15) |
| R11 | **HTTP/1.1 caps a browser at six connections per origin** (noted in `ui/manager/src/main.tsx` and `session-log-store.ts`). An end-user app with a chat stream, a team stream and notifications in two tabs reaches it | One multiplexed per-user event stream, and HTTP/2 guidance for deployments (B12, §2.6) |

---

## 1. Status quo (sweep of 2026-10-29)

### 1.1 What ships today

| Surface | URL | Source | Stack | Audience |
|---|---|---|---|---|
| Manager | `/manage/**` | `ui/manager` (`manage.html`) | React 19, Tailwind v4, Radix, TanStack Query, Zustand, i18next (11 locales), React Router data router | admins, editors |
| Welcome chooser | `/welcome` (`/` redirects there) | `ui/manager` (`welcome.html`) | same bundle | everyone |
| Workforce | `/workforce/**` | `ui/manager` (`workforce.html`) | **same bundle, same `main.tsx`, same router** as the Manager | meant for business users; in practice editors (R5) |
| Chat UI | `/chat/**` | `ui/chat` (`chat.html`) | React 19, hand-written BEM CSS, Context + `useReducer`, its own `t()` (4 locales), BrowserRouter | anyone with a link or an embed (iframe + postMessage token) |

Workforce already has its own HTML shell and backend resource (`RestWorkforceResource`, branded through `BrandedShellRenderer`). It is still one Vite build with three inputs, one `<Routes>` table in `src/app.tsx` and one provider stack. The URLs, shells and backend resources can stay as they are; what changes is code ownership, bundles and dependencies.

### 1.2 Measured bundles (2026-10-29)

Built from `main` with `npm ci` and `vite build` (Node 22). The numbers are gzip -9 sizes of every script and stylesheet the HTML shell references before any lazy chunk.

| Page | First load (gz) | Raw | Notes |
|---|---|---|---|
| `/manage` | 714 KB | 2.3 MB | `main` JS 1.0 MB raw / 310 KB gz; main CSS 606 KB raw / 156 KB gz |
| `/workforce` | **714 KB** | 2.3 MB | the **identical** file set as `/manage` |
| `/chat` | 217 KB | 0.75 MB | one 717 KB entry; KaTeX (267 KB) and highlight.js (164 KB) are already lazy |

Composition, from module sizes before minification, so read the proportions rather than the absolute numbers:

- **Manager CSS:** 388 KB of the 606 KB is `@font-face` rules. `main.tsx` imports all eight Noto families (Arabic, Thai, Devanagari, JP, KR, SC, TC) for every user, whatever their language. The font *files* load lazily, by unicode-range; the declarations do not.
- **Widget JS:** react-dom 524 KB, the widget's own code 193 KB, `parse5` 190 KB (pulled in by `rehype-raw` to allow raw HTML in agent replies), react-router 92 KB (for three path patterns), then the markdown pipeline.

The full Manager build is 23 MB of JavaScript in 364 files. Most of it is Monaco and its workers, which load lazily.

### 1.3 Every conversation surface

| # | Surface | Where | State / stream | Renderer | Composer |
|---|---|---|---|---|---|
| 1 | Chat page | `/manage/chat` | `use-chat.ts` (1.9k lines, Zustand) | `chat-message.tsx` | in `chat-panel.tsx` |
| 2 | Agent Studio | `/manage/studio/:id` | same `ChatPanel` | same | same |
| 3 | Test drawer (save, deploy, chat) | everywhere in `AppLayout` | `use-chat.ts` | `chat-message.tsx` | its own, in `chat-drawer.tsx` |
| 4 | **Platform Operator**: page and drawer | `/manage/operator`, every layout | `use-operator-chat.ts` (1.8k lines, own Zustand store, own stream loop) | its own markdown in `operator-chat.tsx` | its own, with **inline approve / reject**, request preview, cascade summary |
| 5 | **Group discussion** | `/manage/groups/:id/workspace` | `lib/api/groups.ts` (own SSE reader) | `discussion-transcript.tsx`, `agent-response-card.tsx`, `structured-turn-card.tsx`, artifacts, task board, overview views | `discussion-input.tsx`, `human-turn-banner.tsx` |
| 6 | Conversation detail (read-only) | `/manage/conversationview/:id` | conversation store | own list, reuses `MessageAttachments` | — |
| 7 | Workforce chat | `/workforce/chat` | `ChatPanel` | `chat-message.tsx` | `chat-panel.tsx` |
| 8 | **Workforce member thread** | `/workforce/:board/thread/:member` | **its own** stream loop in `workforce-thread.tsx` (1.6k lines) | its own markdown | its own |
| 9 | Workforce board | `/workforce/:board` | `lib/api/groups.ts` | `board-transcript.tsx`, `advisor-response-card.tsx` | `board-input.tsx` |
| 10 | Workforce history | `/workforce/:board/history` | groups API | `conversation-viewer.tsx` | — |
| 11 | Chat UI widget | `/chat/**` | `ChatWidget.tsx` (2.3k lines) + `chat-store.tsx` | `MessageBubble.tsx` | `ChatInput.tsx`, `SecretInput.tsx` |

That is **five separate 1:1 turn loops** (1–3 share one) over the same `/agents/{id}/stream` contract, **three** group transcript renderers, and **14** `ReactMarkdown` call sites. They do not share one rendering policy. The Manager renders markdown images as links (`markdown-safe.tsx`, against tracking beacons). The widget renders sanitized raw HTML and output images. Some sites use neither.

What each 1:1 implementation has that the others lack:

| Capability | `ChatPanel` | Operator | Workforce thread | Widget |
|---|---|---|---|---|
| KaTeX, highlight.js, output images | — | — | — | yes |
| Quick replies, `inputField`, secret input | partial | — | — | yes |
| History, agent switcher, debug drawer, streaming toggle | yes | history | — | — |
| Inline HITL decision (approve / reject per tool call) | — (notice only) | **yes** | — (notice only) | — (read-only card, cancel) |
| Embedding, URL theming, managed intents | — | — | — | yes |
| i18n | 11 locales | 11 | 11 | 4 |

Modules that exist twice or more: IME handling, rating steps, secret input, the paused card, the rating card, attachment staging and context building, SSE frame parsing (`sse-events.ts`, `sse-utils.ts`, the group reader), snapshot-to-messages, and the "turn failed" notice. `lucide-react` is at 0.577 in one app and 1.59 in the other.

### 1.4 The backend, from an end user's side

| Need | Today | Where |
|---|---|---|
| "Which agents may I talk to?" | Not at `eddi-user`: `agentstore`, `deploymentstore`, `capabilities` and `descriptorstore` are admin/editor. **But** `GET /v1/models` already computes it (R3) | `AgentModelResolver`, `ResourceAccessGuard` |
| "Which teams may I use?" | Not at `eddi-user`: `groupstore` is admin/editor; *running* a discussion (`/groups/...`) admits `eddi-user` | `IRestAgentGroupStore`, `IRestGroupConversation` |
| Agent card | `GET /agents/{id}/profile` at `USE`: name, description, review notice, rating config | `IRestAgentProfile` |
| My conversations | `GET /conversationstore/conversations`, owner-scoped in the query for non-admins (state and orphan checks are in memory, bounded at 500 descriptors per page) | `RestConversationStore` |
| Title, rename, pin, search | None. `viewState` is only `UNSEEN`/`SEEN` | `ConversationDescriptor` |
| My memory | Safe: ownership checked on every method (R4) | `RestUserMemoryStore` |
| Stop a turn | `POST /agents/{id}/cancel` stops a paused turn, or a running one on the same pod | `IRestAgentEngine`, `docs/hitl.md` |
| Tool progress | `tool_call` (name) before each tool; arguments later, redacted, in `task_complete.toolTrace`; no "finished" event | `RestAgentEngineStreaming` |
| Reasoning, citations | No reasoning event. Whether retrieved RAG sources reach the client at all is **unverified** | — |
| Edit an earlier message | Only the last step: undo, redo, rerun. Forking is on the roadmap | `IRestAgentEngine` |
| Undo and side effects | Undo rewinds memory, but **an API call, an email or a spend already made stays made** ("undo no longer refunds spend", 2026-10 changelog) | — |

### 1.5 What EDDI has that the competition mostly does not

Multi-agent teams (7 discussion styles, votes, a facilitator, humans as members, shared artifacts, task lists); human-in-the-loop approval gates with email and Slack notifications; persistent user memory with visibility scoping; deterministic behaviour rules, quick replies, agent-requested secret input and structured `inputField`; undo/redo; ratings and review notices; model cascades with visible escalation; guardrails; runtime white-labelling; workspaces with `USE`/`VIEW`/`EDIT`/`OWN`.

---

## 2. Review from eight perspectives

### 2.1 The end user

- They do not care that there are three apps. They care that sign-in carries over, the sidebar remembers them, and nothing loads for three seconds. 714 KB before the first paint on a phone is the most visible problem today, and the fonts are more than half of the CSS.
- "Workforce" is a builder's name for a team-of-agents product. An end user opening it to *ask a question* needs a home with a box to type in, not a dashboard of boards. The name stays open until phase 4 (D8); white-labelling (`productName`) already lets a customer rename it.
- Undo looks like it reverses everything. When a turn has called tools, the UI must say what undo cannot take back (§5, T1.6).
- An approval wait is a dead end today: the user sees "paused" and nothing tells them when it continues. This is EDDI's most distinctive flow, and it needs the most care (T2.3).

### 2.2 The agent designer

- **Config-driven, as the root AGENTS.md demands.** Which controls a conversation offers (undo/redo, attachments, ratings, quick replies, streaming) is today decided by URL flags on the widget (`hideUndo`…) and by nothing elsewhere. The agent should declare it, served through `/profile` (B4); URL flags may only *narrow* it, never widen it.
- **Preview what the user sees.** With one component family, the Manager's chat can offer "view as end user", which hides the admin slots. That is the concrete payoff of goal 2 for the people building agents.
- Appearance (avatar, display name, starters, accent) and catalog listing are agent settings. They must survive ZIP export and import, agent sync, the MCP tools and the operator.

### 2.3 The admin and operator

- **Catalog exposure (R6).** With workspaces off, "every deployed agent" includes the Platform Operator, test agents and internal helpers. Listing must be opt-in per agent, or follow workspace visibility. Silently publishing every agent to every signed-in user on upgrade is not acceptable.
- One sign-in: Manager and Workforce share the Keycloak client (both shells load `/manage/__auth_config__.js`). The new app needs no new client and no new redirect URIs; `/workforce/*` is already served.
- The Operator drawer stays a Manager feature (its allow-list, gate guard and revision live in `lib/operator`). Workforce links to it for admins.

### 2.4 The embedder (a site that frames `/chat`)

- The widget's URL parameters, the postMessage token protocol (`docs/chat-ui-embedding.md`), the managed routes and the 16 colour variables are a **public contract**. Phase work must not break them; a contract test pins them.
- Embedders expect a **launcher**: one `<script>` that draws a floating button and opens the iframe. EDDI only documents a bare iframe. That is cheap to add (Tier 2, T2.9).
- Size matters most here. 217 KB gzipped is heavy for a widget on someone else's page; the `parse5` and router weight are avoidable.

### 2.5 Security and privacy

- **One markdown policy for all 14 call sites**: images from LLM text become links (no beacons); images the *agent designer* put in structured output may render; raw HTML only through the sanitizer; KaTeX with `trust: false`; Mermaid with `securityLevel: "strict"`, rendered lazily. A test enumerates every `ReactMarkdown` import and fails on one that bypasses the shared renderer.
- **Derived data inherits the source's rules.** A generated title or a search index entry is conversation content: it must be covered by GDPR export and erasure, must never come from a secret-input turn, and must follow the same review visibility.
- Catalog: an anonymous caller only ever sees published agents (as `/v1/models` already does); the opt-in listing flag (R6) applies on top.
- Web push (later): payloads carry no content, only "something changed"; VAPID keys live in the vault.
- Share links (later) are bearer tokens and need their own design (expiry, revocation, what an anonymous viewer can do).

### 2.6 Performance and infrastructure

- Budgets, measured in CI from the built `dist/`: the widget's first load, Workforce's first load, the Manager's first load. Phase 0 sets them from the numbers in §1.2.
- **Connections (R11).** Over HTTP/1.1, Chrome allows six connections per origin across all tabs. Plan for one per-user event stream (notifications, approvals decided, team progress) and a turn stream only while a turn runs. Document HTTP/2 at the reverse proxy as the recommended production setup.
- Long conversations: the snapshot loads every step. The message list needs windowing (virtualised rendering) before histories grow long; paging the snapshot on the server is a later backend item.
- Build time: a third Vite build adds about a minute to `./mvnw package`, unless the apps build in one workspace run.

### 2.7 Maintainers

- Three AGENTS.md files encode the current conventions ("no Tailwind", "no Zustand", "own `t()`" in the widget). Each phase that changes a convention updates the file in the same PR.
- A single root lockfile changes several things: Dependabot (`/ui/manager` and `/ui/chat` become `/ui`), the CycloneDX SBOM step (`bom-ui-manager.json`, `bom-ui-chat.json`), CodeQL's UI paths, Trivy, and the Windows `oxide-wasm32-wasi` lockfile trap. That is a reason to do it once, early, in its own PR.
- Merging five turn loops is where regressions hide. Each has a test suite encoding real incidents. The merged controller must pass the **union** of those suites.

### 2.8 Product strategy

- Open WebUI already reaches EDDI agents through `/v1`. A deployment that only wants generic chat can use it today, and the docs should say so plainly.
- What `/v1` cannot carry: approval pauses and decisions, quick replies and `inputField`, secret input, ratings, review notices, group discussions, memory transparency, a per-agent appearance, the deployment's brand. **That list is EDDI's end-user product.** Tier 1 parity is the entry ticket; Tier 2 is the reason to choose it.

---

## 3. Target architecture

### 3.1 Layout

Keep the two existing app directories. Moving `ui/manager` to `ui/apps/manager` would touch `pom.xml`, `ci.yml`, the Stryker scope, `BuildQualityGatesTest`, the operator-revision copy, Dependabot and every AGENTS.md, for no gain to users.

```
ui/
├── package.json            # npm workspaces root, one package-lock.json
├── tsconfig.base.json
├── eslint.config.base.js
├── packages/
│   ├── core/               # @eddi/core: framework-free TypeScript
│   ├── ui/                 # @eddi/ui: tokens, primitives, theme, i18n setup
│   └── conversation/       # @eddi/conversation: every kind of conversation
├── manager/                # app: /manage, /welcome
├── workforce/              # NEW app: /workforce
└── chat/                   # app: /chat
```

| Package | Contents | Rules |
|---|---|---|
| `@eddi/core` | `http` (status-aware fetch, `ApiError`, the `{error, code}` envelope, an injected auth provider, 401 retry); one SSE frame parser for agent and group streams; the agent conversation API (start, say, stream, undo, redo, rerun, cancel, resume, state, profile); the group discussion API; snapshot-to-messages; HITL status; attachments; ratings; contract types | No React, no DOM beyond `fetch`. The backend contract list in `ui/chat/AGENTS.md` becomes its README and test index |
| `@eddi/ui` | Design tokens (semantic CSS variables, white-label brand scale, dark and light, Tailwind v4 `@theme`); Radix-based primitives; theme provider; toaster; i18next setup with lazy locales, `productName` default variables and **per-locale font loading** | No API calls |
| `@eddi/conversation` | Controllers and components for every kind of conversation (§3.2), the one markdown renderer (§2.5), and the shared `conversation.*` and `teams.*` i18n namespaces in all 11 locales | Router-free: hosts pass ids and callbacks. Never reads `window.__EDDI_*__` |

Packages are consumed **as source** through workspace links, so there is no publish step and no build order. Apps may import packages; packages never import apps; `core` imports neither React nor `ui`. ESLint `no-restricted-imports` enforces this, and a test fails on a cycle.

### 3.2 One family, four kinds of conversation

Everything in §1.3 is one of four kinds:

| Kind | Used by | Controller | Specific parts |
|---|---|---|---|
| **Agent** (1:1) | Manager chat, Studio, test drawer, Workforce chat and member thread, widget | `AgentConversation`: the turn state machine of today's five loops (streaming, 409 restores the draft, dropped vs paused turns, `inputField` on `done`, undo/redo, rerun, cancel) | — |
| **Operator** | Operator page and drawer | `AgentConversation` plus an approval policy | Inline approval with the operator's request preview and guards, supplied by the Manager through a slot. The security-reviewed guards stay in `lib/operator` |
| **Team** | Manager group workspace, Workforce board and history | `GroupDiscussion`: phases, member turns, human turns, decisions | Multi-speaker transcript, phase rail, decision cards, task board, artifacts |
| **Transcript** (read-only) | Conversation detail, review, Workforce history, shared links later | none: a snapshot in, messages out | — |

All four share `MessageList` (windowed, sticky scroll), `Message` (the one markdown renderer, attachments, copy, actions slot), `Composer` (IME, attachments with drop and paste, secret input, `inputField`, quick replies), `ActivityIndicator` (typing, tool, cascade, guardrail), `PausedCard` and `RatingCard`.

The controllers are plain stores with `subscribe` and `getSnapshot`, read through `useSyncExternalStore`, because the apps use different state libraries and the turn logic must be testable without React. Hosts add their extras through slots: `headerActions`, `messageActions`, `beforeComposer`, `sidePanel`. Examples are the Manager's debug drawer, environment picker and streaming toggle, and the Operator's approval banner.

### 3.3 Conventions to reconcile

| Topic | Manager | Chat UI | Decision |
|---|---|---|---|
| Styling | Tailwind v4 | BEM, "no CSS framework" | Tailwind v4 + tokens everywhere. The widget renders in its own iframe, so nothing leaks into a host page. The `--chat-*` variable names and URL colour parameters are kept as **documented aliases** of the shared tokens, pinned by a test |
| State | Zustand, TanStack Query | Context + reducer, "no Zustand" | Framework-free controllers; each app keeps its own choice for app state; TanStack Query for server lists in the two signed-in apps |
| i18n | i18next, 11 locales | own `t()`, 4 locales | i18next everywhere, one lazily loaded locale at a time |
| Routing | data router | BrowserRouter (92 KB for three patterns) | Manager and Workforce keep a router. The widget parses its three path shapes itself |
| Raw HTML in replies | not rendered | rendered through `rehype-raw` + sanitize (`parse5`, about as large as the widget's own code) | Decide per agent (B4). The default is markdown only, with the HTML parser loaded lazily when an agent enables it |
| Icons | lucide 1.59 | lucide 0.577 | one version at the root |

---

## 4. The three apps after the split

### 4.1 Manager (`/manage`, `/welcome`)

Administration and building. It keeps the Operator, approvals, the group workspace (kind *Team*, with editor tools), Studio and the test drawer, all on `@eddi/conversation`. It gains "view as end user" in its chat. It loses the Workforce routes, `workforce-pins` and the Workforce MSW handlers.

### 4.2 Workforce (`/workforce`), the end-user app

The place a signed-in person uses AI: **chats** (agent kind) and **teams** (team kind) in one sidebar. It is role-aware:

| Role | Sees |
|---|---|
| `eddi-user` | Home (a box to type in, starters, recent items), the catalog of agents and teams they may use, their chats and team sessions, their memory |
| `eddi-approver` | plus an approvals inbox |
| `eddi-editor`, `eddi-admin` | plus "Build a team" (the wizard), team settings, analytics, and a link into the Manager and its Operator |

It uses the same Keycloak client as the Manager. Moving between the two apps is a full page navigation, and theme, language and pins carry over through same-origin storage.

### 4.3 Chat UI (`/chat`)

The embeddable, link-shareable, single-agent surface: anonymous use of published agents, managed intents, postMessage auth, URL theming, `hide*` flags, demo mode. It is the agent kind, compact variant, with no router and lazily loaded rich rendering.

---

## 5. End-user UX: the bar to meet

"Backend" names what the server must add first (§6).

### Tier 1: table stakes

| # | Feature | Backend |
|---|---|---|
| T1.1 | Sidebar of my chats and team sessions, grouped by date; rename, pin, delete | B2 |
| T1.2 | Automatic titles | B2 |
| T1.3 | Search my conversations | B3 |
| T1.4 | Home: a composer first, the catalog, recent items, per-agent starters | B1, B4 |
| T1.5 | Streaming that reads well: no layout jump, sticky scroll that yields when the user scrolls up, Stop, visible reconnect | — (Stop aborts the stream and calls `/cancel`, which stops a running turn only on the pod running it, `docs/hitl.md`; in a cluster the UI must say when the turn kept going) |
| T1.6 | Edit the last message (undo + say) and regenerate (rerun), with a **notice when the turn ran tools** whose effects stay | B6 for knowing which tools ran |
| T1.7 | Rich output: GFM tables, code with language label and copy, KaTeX, Mermaid, safe images, copy message; one policy (§2.5) | — |
| T1.8 | Attachments: paste, drop, picker, image thumbnails, per-file progress and errors | — |
| T1.9 | Tool activity as collapsible cards | B6 |
| T1.10 | Keyboard: Enter / Shift+Enter, ⌘K, new chat, Esc to stop, ↑ to edit last | — |
| T1.11 | Mobile and PWA: installable, safe areas, a composer the virtual keyboard does not cover | — |
| T1.12 | Accessibility: a live region for streamed text, focus management, contrast in both themes, axe clean | — |
| T1.13 | 11 locales with RTL; light, dark, system | — |
| T1.14 | Thumbs and an optional reason on replies | — |
| T1.15 | A reload or a second tab returns to the same conversation (the widget's direct route included, R10) | — |
| T1.16 | First load within budget (§7 phase 0) | — |

### Tier 2: where EDDI should lead

| # | Feature | Backend |
|---|---|---|
| T2.1 | **Teams beside chats**: start a team session from a template, watch the discussion live, see votes and decisions, take part as the human member | B1 (groups) |
| T2.2 | **@mention** an agent or a team inside a chat to hand the question over | B1; a design pass on how the transcript records the hand-off |
| T2.3 | **Approvals in the flow**: a paused turn says who must approve and by when; the user is notified when it continues; an approver decides in place. The Operator's inline approval, generalised and still security-reviewed | existing HITL endpoints; B12 |
| T2.4 | **"What I remember about you"**: list, search, delete one, delete all, which agent wrote what | existing (R4) |
| T2.5 | **Structured input** as composer modes: `inputField`, quick replies, secret input | — |
| T2.6 | **Shared artifacts** of a team session in a side panel, with versions | existing group artifacts |
| T2.7 | Cascades and guardrails explained in plain words | — |
| T2.8 | Long jobs (self-continuation, schedules) shown as progress, with a notification when done | B12 |
| T2.9 | **Embed launcher**: `<script src="/chat/embed.js">` draws a floating button and opens the iframe, with the existing token hand-off | — |

### Tier 3: later

Voice input (browser speech recognition first) and read-aloud; reasoning display (B7); citations (B10); share links (B11); export to Markdown or PDF; projects or folders; a prompt library with `/` commands; comparing two agents side by side; temporary chats.

### Not planned

A raw model picker in the end-user app. EDDI's unit is the agent, whose model its designer chose. A model picker would bypass cascades, cost ceilings and guardrails.

---

## 6. Backend work

| # | Item | Shape | Notes |
|---|---|---|---|
| **B1** | **Catalog** | `GET /catalog/agents` and `GET /catalog/groups` at `eddi-user`: deployed and usable (reuse `AgentModelResolver` / `ResourceAccessGuard`), **and listed** (a per-agent and per-group `listed` flag, default `false`; with workspaces on, `published` and `internal` visibility also count as listed). Returns the profile fields, `callerLevel` and appearance | R3, R6. The empty state tells an admin how to list an agent |
| B2 | Title, rename, pin | `title`, `pinned` on `ConversationDescriptor`; owner-only `PATCH`; the title is the first user message (truncated) unless the agent opts into LLM titles (`titleGeneration`, default off), charged to the cost tracker like any LLM call (D7) | Never from a secret turn; covered by GDPR export and erasure (§2.5) |
| B3 | Search my conversations | `GET /conversationstore/conversations/search?q=`, owner-scoped, paged; a Mongo text index or Postgres `tsvector` over titles, then outputs | Privacy review before outputs are indexed; secret turns excluded; erasure removes the entries |
| B4 | Agent presentation | `appearance {displayName, avatar, accentColor, starters[]}` and `conversationControls {undo, redo, attachments, rating, quickReplies, streaming, rawHtml}` on the agent, served by `/profile` and B1 | The white-label §4.6 seam. Export/import, agent sync, MCP tools, the operator prompt, the Manager editor. `rawHtml` defaults to `false` (D9): from the release that ships it, the widget shows HTML in an agent's replies as text unless the agent enables it. The Manager never rendered raw HTML, so only widget users of agents that emit HTML see the change, and the release notes and the upgrade section of `docs/chat-ui-embedding.md` name it |
| B5 | Memory self-service | Already enforced (R4). Add a REST test that `eddi-user` A gets 403 for B's memories | — |
| B6 | Tool lifecycle on SSE | `tool_result {tool, ok, summary}` after each call, with the same redaction as `toolTrace` | Additive; an older client ignores it. The widget's 9-event contract test grows |
| B7 | Reasoning on SSE | `reasoning` tokens when the provider exposes them, opt-in per agent | Off by default |
| B8 | — | merged into B12 | — |
| B9 | Edit an earlier message | Session Forking (roadmap) | Tier 1 ships "edit last" only |
| B10 | Citations | The RAG sources a turn used, in `task_complete` | Verify what RAG keeps first |
| B11 | Share link | A read-only snapshot behind a signed, revocable token | Its own security design |
| **B12** | **Per-user event stream** | `GET /me/events` (SSE): approvals decided, team progress, long jobs finished, workspace notifications. One connection per tab; web push later | R11. Payloads carry ids, not content |

Every item goes through the root AGENTS.md §4.6 checklist: docs, changelog fragment, MCP skills, and whether the Platform Operator needs to know (then bump `operator-revision.json`).

---

## 7. Phases: vertical slices

Each phase ships something a person notices, keeps `main` green, and extracts shared code only once a second consumer needs it.

| Phase | Ships | Work | Exit criteria |
|---|---|---|---|
| **0. Baseline and quick wins** (S) | faster first load everywhere | Commit the §1.2 numbers and a budget check over `dist/` in CI. **Fonts per locale**: load a script family when its locale is active, not all eight up front. **Widget**: drop the router (three path patterns), and load the raw-HTML parser only for an agent that enables raw HTML (D9; until B4 ships the setting, the widget keeps rendering HTML so nothing changes before agents can opt in). **Docs**: say plainly in `docs/open-webui-integration.md` and the README that a deployment wanting only generic chat can use Open WebUI over `/v1` (D10). Record a feature matrix (§1.3) as one Playwright spec per capability, tagged by surface | Manager/Workforce first load clearly below 714 KB gz and widget clearly below 217 KB gz, with the new numbers as budgets; matrix green for what each surface supports today |
| **1. Workspaces and `@eddi/core`** (L) | fewer bugs, one fix fixes all | `ui/package.json` workspaces, one lockfile, Dependabot, SBOM, CodeQL and Maven updated in one PR. Then `@eddi/core`: port the **union** of the five loops' tests, then move the loops onto it one at a time, starting with the widget and `ChatPanel` | `./mvnw package` jar layout unchanged; duplicated API and SSE modules deleted; matrix unchanged |
| **2. Catalog and Workforce for `eddi-user`** (M, backend + frontend) | an `eddi-user` can sign in to Workforce, see what they may use, and chat | B1 with the opt-in `listed` flag (D4), B5 test; Workforce becomes role-aware: editor-only screens hidden, reads move from `/groupstore` to the catalog; the Operator drawer is replaced by a link for admins (D5); `/` and `/welcome` send an `eddi-user` straight to `/workforce`, while editors and admins keep the chooser (D6) | E2E as `eddi-user`: no 403 on any Workforce page; catalog shows only listed and usable agents; the Operator never appears |
| **3. `@eddi/ui` and the agent kind** (L) | the same chat everywhere: KaTeX, quick replies, secret input and history in the Manager and Workforce; 11 locales in the widget | Tokens, primitives and i18n into `@eddi/ui`; `AgentConversation` and the shared components; `ChatPanel`, the test drawer, the Workforce chat and member thread, and the widget move onto them; the one markdown policy | Matrix shows the **union** of capabilities on every agent surface; widget within budget; URL colour parameters and `/branding/brand.css` still apply; RTL e2e on the widget |
| **4. Sidebar, titles, home** (L) | T1.1, T1.2, T1.4, T1.15 | B2, B4; Workforce home and sidebar | Per-item e2e |
| **5. Workforce as its own app** (M) | Workforce loads only what it uses | `ui/workforce` with its own `main.tsx`, router, layout, MSW handlers and e2e; Manager drops the Workforce routes; Maven copies a third `dist/` | Workforce first load within its own, smaller budget; no Manager import of Workforce code (lint) |
| **6. Operator and team kinds** (L) | approvals and teams look and behave the same in the Manager and Workforce | `Operator` (the approval slot, guards untouched) and `GroupDiscussion` + team components; the three transcript renderers become one | Operator Stryker scope still green; the HITL guard tests unchanged and passing |
| **7. Rest of Tier 1** (L) | T1.3, T1.5–T1.14 | B3, B6 | Lighthouse accessibility and PWA ≥ 95 on Workforce |
| **8. Tier 2** (XL) | T2.1–T2.9 | B12 | Per item |
| **9. Tier 3** | as prioritised then | — | — |

Phases 0 and 2 can start at once and in parallel. Phase 5, the split you asked for, comes *after* the shared packages exist, because before that it would copy code rather than share it.

### What every phase checks

i18n in all 11 locales in the same commit (`i18n:check` learns the package directories in phase 1); the Platform Operator's prompt and allow-list (§6); MSW handlers and the OpenAPI contract `EXEMPT` list; the Stryker scope for moved files; the three AGENTS.md files; a changelog fragment; `docs/chat-ui-embedding.md` and a new end-user guide for Workforce.

---

## 8. Success measures

| Measure | Source | Target |
|---|---|---|
| First load (gz) per page | the CI budget check | set in phase 0, never raised without a decision |
| Time from send to first visible token | client timing, reported with ratings (opt-in) | not worse than the backend's own first-token time plus 100 ms |
| Turns that fail visibly (error, dropped, refused) per 1000 | client error codes | down after phase 3 |
| Reply rating (thumbs up share) | existing ratings | tracked per agent; the UI change must not move it down |
| Conversation surfaces with their own turn loop | code (§1.3) | 5 → 1 |
| `ReactMarkdown` call sites outside the shared renderer | the guard test | 14 → 0 |

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Merging five turn loops loses one's incident fixes | The union of their tests first; the merged controller must pass all of them; migrate one surface per PR |
| The Operator's approval UI is security-reviewed for one surface | Its guards and request preview stay in `lib/operator` and plug in through a slot; its tests and Stryker scope are untouched |
| A long refactor conflicts with ongoing Manager work | Small PRs; `git mv` in separate commits; announce a short freeze per directory per phase |
| Listing agents to end users exposes internal agents | Opt-in `listed` flag, default off (B1) |
| Tailwind preflight changes the widget for embedders | Screenshots of the widget's public states from phase 0, compared in phase 3; the colour aliases pinned by a test |
| The single lockfile hits the Windows `oxide-wasm32-wasi` trap harder | Keep the lockfile check and run it in CI on the root lock |
| HTTP/1.1 connection cap in the end-user app | B12, and HTTP/2 guidance (§2.6) |

---

## 10. Decisions (2026-10-29)

All ten recommendations of revision 2 were accepted. Where each one lands:

| # | Decision | Lands in |
|---|---|---|
| D1 | **Workforce is the end-user app** (chats and teams, role-aware); `/chat` stays the embeddable single-agent surface. No third app | §4.2, §4.3; phases 2, 4, 5 |
| D2 | **Keep `ui/manager` and `ui/chat` where they are**; add `ui/workforce` and `ui/packages/*` | §3.1; phase 1 |
| D3 | **The widget moves to Tailwind and i18next.** The `--chat-*` variables and URL colour parameters stay as documented aliases, pinned by a test. `ui/chat/AGENTS.md` §3 and §5 change in the same PR | §3.3; phase 3 |
| D4 | **Catalog listing is opt-in per agent and per group, default off.** An upgrade lists nothing. With workspaces on, `published` and `internal` visibility also count as listed | B1; phase 2 |
| D5 | **Workforce links to the Operator** (`/manage/operator`) for admins instead of mounting its drawer | §2.3, §4.2; phase 2 |
| D6 | **An `eddi-user` lands in Workforce.** `/` and `/welcome` send them to `/workforce`; editors and admins keep the chooser | §4.2; phase 2 |
| D7 | **LLM titles are a per-agent opt-in, charged through the cost tracker.** The default title is the first user message | B2; phase 4 |
| D8 | **The name "Workforce" stays open until phase 4 starts.** Until then the code, routes and i18n keys keep the name; a rename later is a display string (and `productName` already lets a deployment rename it) | §2.1; before phase 4 |
| D9 | **Raw HTML in replies is a per-agent opt-in, off by default** (`conversationControls.rawHtml`). The widget keeps today's behaviour until the setting exists, so agents can opt in before the default bites | §3.3, B4; phases 0, 3 |
| D10 | **The docs recommend Open WebUI over `/v1`** for deployments that only want generic chat | §2.8; phase 0 |
