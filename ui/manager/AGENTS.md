# EDDI Manager — AI Agent Instructions

> **This directory is part of [labsai/EDDI](https://github.com/labsai/EDDI).** It was the separate `labsai/EDDI-Manager` repository until 2026-09-15; its full history was imported here (`git log -- ui/manager`). Issues and pull requests go to `labsai/EDDI`. The UI is built into the EDDI jar by Maven from the repository root — see the root `AGENTS.md` (Build & Test Commands).

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.** The [root `AGENTS.md`](../../AGENTS.md) applies here too — branching, push approval, commit attribution and the changelog rule are defined there once and not repeated below.

## 1. Project Context

**EDDI Manager** is the admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform. It is a **React/TypeScript SPA** served from the EDDI backend at `/manage`.

### Ecosystem

The Manager, the Chat UI and the backend are one repository, `labsai/EDDI`:

| Location | Tech | Purpose |
| --- | --- | --- |
| **repo root** | Java 25, Quarkus, MongoDB or PostgreSQL | Backend engine, REST API, lifecycle pipeline, integration tests (`src/test/java/**/*IT.java`) |
| **`ui/manager`** (this) | React 19, Vite, Tailwind | Admin dashboard — agents, workflows, extensions, chat |
| **`ui/chat`** | React, TypeScript | Standalone chat widget |
| **eddi-website** | Astro | Marketing site at eddi.labs.ai |

### Tech Stack

| Layer | Technology |
| --- | --- |
| **Build** | Vite 6 |
| **UI** | React 19 + TypeScript 5 (strict) |
| **Styling** | Tailwind CSS v4 + CSS variables (black/gold) |
| **State (server)** | TanStack Query v5 |
| **State (UI)** | Zustand (chat/debug), `useState` / `useCallback` elsewhere |
| **Routing** | React Router v7 (`react-router-dom` 7.x, declarative mode — no data router) |
| **i18n** | react-i18next (11 locales: en, de, fr, es, ar, zh, th, ja, ko, pt, hi) |
| **Test (unit)** | Vitest + React Testing Library + MSW |
| **Test (e2e)** | Playwright |
| **Editor** | Monaco (@monaco-editor/react) |
| **DnD** | @dnd-kit (workflow pipeline builder) |

---

## 2. Workflow

### Before Starting Any Work

1. **Check state**: `git status`, `git log -5 --oneline`, `git branch --show-current`
2. **Recent context**: the top entries of the root [`docs/changelog.md`](../../docs/changelog.md) and anything pending in [`docs/changelog.d/`](../../docs/changelog.d/README.md) — that is where Manager work is recorded (root rule 8)
3. **Backend context**: the [root `AGENTS.md`](../../AGENTS.md) when touching API contracts
4. **[`HANDOFF.md`](HANDOFF.md)** is the Manager's running log from before the monorepo move — about 180 KB. **Do not read it end to end.** Search it for the screen or feature you are touching; its deep dives (the operator, secrets grants, the Workforce decisions) are worth finding when you need them

### During Work

- **Branch** per root rule 3 — never commit to `main`, branch from `origin/main`, name it `feat/…`/`fix/…` (never a tool-generated `claude/…` name).
- **Commit often** with conventional commits scoped to the area: `feat(manager): …`, `fix(manager): …`, or a feature scope such as `fix(operator): …`. Use `(ui)` only for a change that spans both UIs.
- **Record the change** in a new `docs/changelog.d/YYYY-MM-DD-<slug>.md` fragment at the repository root (root rule 8), in the same commit as the work. Do not add entries to `HANDOFF.md` — every PR editing the same file is the merge conflict the fragments exist to avoid.

### ⚠️ Dependency changes on Windows break CI's `npm ci`

`@tailwindcss/oxide-wasm32-wasi` is an optional package that Windows skips, so npm
on Windows never resolves its children and **prunes them from
`package-lock.json`** on any `npm install` / `npm uninstall`. The Linux CI runner
then fails before it runs anything:

```
npm error `npm ci` can only install packages when your package.json and
npm error package-lock.json are in sync.
npm error Missing: @emnapi/core@1.11.3 from lock file
```

Neither `npm install --package-lock-only` nor `--os=linux --cpu=x64` re-adds them.
After changing any dependency on Windows, check the lock still carries all four:

```bash
node -e "const l=require('./package-lock.json');Object.keys(l.packages).filter(k=>k.includes('emnapi')||k.includes('wasm-runtime')).forEach(k=>console.log(k,l.packages[k].version))"
```

Expect `@emnapi/core`, `@emnapi/runtime`, `@emnapi/wasi-threads` and
`@napi-rs/wasm-runtime` nested under
`node_modules/@tailwindcss/oxide-wasm32-wasi/node_modules/`. If any are gone,
restore them from the last lockfile CI accepted rather than regenerating.

### Quality Gates

There is no pre-commit hook (the husky + lint-staged hook did not survive the move into the
EDDI monorepo). CI's `UI Manager Checks` job runs these on every PR that touches `ui/`, in
this order — run them yourself before pushing:

```bash
npm run audit:prod   # no known-vulnerable production dependency
npm run lint         # ESLint over src/ and e2e/, --max-warnings 0
npm run i18n:check   # no locale/code drift (see i18n below)
npm run typecheck    # tsc -b — the full project, including tsconfig.e2e.json
npm run test         # Vitest (CI adds --coverage)
```

`npm run build` (which includes `tsc -b`) should also pass. `UI Manager E2E (MSW)` runs the
Playwright suite against the mock backend.

> ⚠️ **`npx tsc --noEmit` checks nothing in this repo.** `tsconfig.json` is a
> solution file — `"files": []` plus project references (`tsconfig.app.json`,
> `tsconfig.node.json`, `tsconfig.e2e.json`, `tsconfig.design-sync.json`) — so `--noEmit` resolves zero input files and exits 0.
> Always use `npm run typecheck` (what CI runs). The old pre-commit hook ran the
> no-op form until it let a syntax error through to CI.

One gate is deliberately **not** in that list: mutation testing (Stryker). It asks the
question the others cannot — whether the suite would have *complained* — but a
full run is 20-odd minutes, and it is **not in CI**: the Stryker workflow did not survive
the move into labsai/EDDI, and porting it into the root `ci.yml` is a follow-up. So if you
change a file in its scope, run it locally and read its survivors — a survivor is a line
that was broken while every test still passed. See CONTRIBUTING.md.

The scope is `src/lib/operator/**`, `src/lib/api/updates.ts` and
`src/lib/hitl-tool-approvals.ts`. Two exclusions are argued in
`stryker.config.json`, each with the measurement behind it: `api-client.ts` (75
importers, so each of its mutants replays most of the suite) and static mutants
— module-level constants, which cost a full runner restart each. A constant you
need guarded needs a unit test asserting its contents; this gate will not do it.
(Tests themselves are excluded too, for the obvious reason.)

### i18n — MANDATORY

> **⚠️ Every time you add or modify keys in `en.json`, you MUST propagate those changes to ALL 10 other locale files in the same commit.**

The project has **11 locales**: `en`, `de`, `fr`, `es`, `ar`, `zh`, `th`, `ja`, `ko`, `pt`, `hi`.

1. Every user-visible string goes through `t("namespace.key", "English fallback")`. Each editor has its own namespace: `llmEditor.*`, `apiCallsEditor.*`, `rulesEditor.*`, etc.
2. Add new keys to `src/i18n/locales/en.json` first
3. **Immediately** propagate translated versions to all other 10 locale files
4. Verify with `npm run i18n:check` — it fails on a key the code uses that `en.json` lacks, a key `en.json` has that a locale lacks, a leftover key in a locale, and a key called with two different English defaults. Plural categories and a translation-debt baseline are checked only by the full Vitest run, so run that too
5. Do NOT leave this as a follow-up step — it must be done in the **same commit**

> An inline fallback (`t("key", "Fallback")`) keeps the UI readable while you
> work, but it is **not** a translation: it renders the same English in all
> eleven locales and looks identical to a real translation when you read the
> code. 349 keys accumulated that way before the gate existed — the whole
> Workforce namespace and the Analytics screen shipped in English everywhere.

---

## 3. Architecture & Patterns

### Where things live

```
src/
├── app.tsx                   # Routes (pages load through lazyPage — Key Patterns 5)
├── components/
│   ├── ui/                   # Low-level primitives (button, badge, dialog, …)
│   ├── shared/               # App-level shared components (empty/error state, pickers, …)
│   ├── layout/               # AppLayout, Sidebar, TopBar, theme provider
│   ├── editors/              # Extension editors + shared editor chrome
│   │   ├── config-editor-layout.tsx   # Tabs (Form|JSON), version picker, save
│   │   └── editor-registry.tsx        # EDITOR_MAP (single source of truth)
│   ├── studio/               # Agent Studio workspace
│   ├── operator/             # Platform Operator (activation, chat, status)
│   ├── workforce/            # Group-conversation workspace (/workforce)
│   └── …                     # one directory per feature area — agents, chat, hitl,
│                             # secrets, connections, groups, workflows, …
├── hooks/                    # TanStack Query hooks, one file per domain
├── lib/
│   ├── api/                  # API modules (agents.ts, resources.ts, backup.ts, …)
│   ├── operator/             # Operator tool allow-list + system prompt
│   ├── api-client.ts         # Base fetch wrapper with auth header injection
│   └── query-keys.ts         # Shared TanStack Query keys
├── i18n/                     # config.ts + locales/ (11 locale JSON files)
├── pages/                    # Route pages; pages/workforce/ for the Workforce app
└── test/
    ├── setup.ts              # Vitest setup (MSW, monaco mock, …)
    ├── test-utils.tsx        # Shared render helpers (renderPage, …)
    └── mocks/                # MSW handlers.ts + server.ts
```

Tests sit next to what they test, in `__tests__/` directories throughout `src/`. List
`src/components/` rather than trusting any enumeration here — it grows with every feature.

### Key Patterns

#### 1. Editor Registry (Single Source of Truth)

All extension editors are registered in `src/components/editors/editor-registry.tsx`:

```tsx
export const EDITOR_MAP: Record<string, EditorRenderFn> = {
  rules:    (p, o, r) => <RulesEditor data={p} onChange={o} readOnly={r} />,
  llm:      (p, o, r) => <LlmEditor data={p} onChange={o} readOnly={r} />,
  apicalls: (p, o, r) => <ApiCallsEditor data={p} onChange={o} readOnly={r} />,
  // ... output, dictionary, propertysetter, mcpcalls, rag, snippets, parser
};
```

**To add a new editor**: create the component → add to `EDITOR_MAP` → add MSW handler → add i18n keys → add test file.

#### 2. Resource Type Config

All 10 resource types are defined in `src/lib/api/resources.ts` as `RESOURCE_TYPES`:

| Slug | Store | Plural |
| --- | --- | --- |
| `rules` | `rulestore` | `rulesets` |
| `apicalls` | `apicallstore` | `apicalls` |
| `output` | `outputstore` | `outputsets` |
| `dictionary` | `dictionarystore` | `dictionaries` |
| `llm` | `llmstore` | `llms` |
| `propertysetter` | `propertysetterstore` | `propertysetters` |
| `mcpcalls` | `mcpcallsstore` | `mcpcalls` |
| `rag` | `ragstore` | `rags` |
| `snippets` | `snippetstore` | `snippets` |
| `parser` | `parserstore` | `parsers` |

> **⚠️ Parser vs Dictionary — separate stores!**
>
> | Store | Path | Extension | Purpose |
> | --- | --- | --- | --- |
> | **DictionaryStore** | `dictionarystore/dictionaries` | `ai.labs.dictionary` | Word→expression mappings, phrases, regex |
> | **ParserStore** | `parserstore/parsers` | `ai.labs.parser` | Parser pipeline config that *references* dicts |
>
> - Workflows reference a **parser** → parsers reference **dictionaries**
> - The Manager's `dictionary` slug maps to `dictionarystore` (what users edit)
> - `parserstore` **does** map to the `parser` slug in `pipeline-builder.tsx`, and `parser` is registered in `EDITOR_MAP`. A parser step with an embedded config is edited inline; one with a `config.uri` is edited as an ordinary resource

#### 3. MSW Mock Handlers

- All handlers are in `src/test/mocks/handlers.ts`
- Specific GET handlers go **before** the generic `createResourceHandlers` block
- Include realistic mock data matching the backend Java model — a fixture written to match the page rather than the server hides the very bugs the test exists for

#### 4. Platform Operator

An opt-in, admin-activated agent that inspects this EDDI deployment and explains
what it finds. Off by default. Worth knowing before touching it:

- **It is a real EDDI agent**, provisioned through `setup-api` from EDDI's own
  OpenAPI spec. It shows up in the Agents list with an "Operator" badge; editing
  or deleting it there breaks the operator screen.
- **Its capability boundary is the allow-list** in `src/lib/operator/tool-scopes.ts`
  — an allow-list, never a deny-list, because a deny-list silently grants any
  endpoint the backend adds later. Read the constant rather than any count
  quoted here, and read its doc comment before adding to it: what is excluded
  (every `DELETE`, and the full agent and group document PUTs, each because that
  document carries an approval gate of its own) is as deliberate as what is
  included. `llmstore` writes ARE granted — that document can carry a gate
  (`Task.toolApprovals` fully replaces the agent's), so the grant is valid only
  alongside `gate-guard.ts`, which hard-refuses any llmstore write carrying that
  field. Do not separate the two.
  `read_write` is the DEFAULT scope and freely selectable on first activation
  (read-only is the explicit opt-down); the safety lives in activation itself:
  the gate is read back from the just-provisioned document
  (`verifyGateInstalled`), and granting `read_write` runs a write canary
  (`write-canary.ts`) that provokes a real gated write and rolls the whole
  activation back on anything but a clean pause.
  **Backend floor: operator activation requires EDDI 6.2.0+** — the allow-list
  includes `GET /administration/docs{,/{name}}` (`@since 6.2.0`), and
  `findMissingEndpoints` refuses activation when the spec lacks an entry.
- **Config is one atomic JSON blob** in the `platform.operator` global variable.
  Activation writes several values that must land together and the variable
  store has no transaction.
- **`apiBaseUrl` is the address EDDI can reach ITSELF at, never this browser's
  origin.** The generated tools call it from inside the server. It is resolved by
  `resolveOperatorApiBaseUrl`: an explicit value on the config first, then EDDI's
  own `GET /administration/operator/self-url` (`eddi.self.base-url`, else loopback
  on `quarkus.http.port`), and only on a backend that 404s that endpoint the
  browser's origin — with a `console.warn`. A backend that *answers*
  `source: "unresolved"` (random port, no override) is not a 404: the Manager
  throws and asks for the base URL explicitly rather than falling back to the
  browser's origin. `provisionOperator` throws on a blank
  one rather than guessing. The two coincide on a single-host deployment with
  nothing in between, which is exactly why `window.location.origin` survived to
  production: on a tunnelled staging instance it provisioned all 22 resources with
  an address meaningless inside the container, and the operator reported itself
  deployed and gate-verified while every tool call failed on connect. Do not
  reintroduce a fallback that cannot be seen.
- **Reconfigure REPLACES the agent.** `setup-api` only creates, so a reconfigure
  gets a new agent id and the predecessor must be retired — with
  `endAllActiveConversations`, because the admin's own operator chat is almost
  always open and the backend answers 409 otherwise. A failed retirement is
  reported through `ActivationOutcome.supersededWarning`, never swallowed: two
  `READY` operators with nothing on screen saying which one the UI addresses once
  sent an engineer to repair the abandoned one.
- **`authMode: "caller-identity"`** makes tool calls run as the signed-in user
  via the backend's `${caller:token}` resolver (EDDI 6.2.0+). `"none"` is
  blocked at activation when OIDC is on, because every tool call would 401.
- **Activation runs a canary** — one probe read counting tool calls — because a
  READY deployment badge says nothing about whether the tools can authenticate.

#### 5. Route-level code splitting

Route pages in `app.tsx` load through `lazyPage()` (`src/lib/lazy-page.ts`) —
with three deliberate exceptions that stay eager: the two layouts, the landing
page (where `/` redirects, so it is on the critical path) and the command palette
(it binds a global hotkey and must exist before the user presses it).
Consequences worth knowing:

- **Add a route → use `lazyPage`.** A static page import puts that page back in
  the entry chunk, which is how it reached 8.5 MB before the split.
- **Monaco is not in the entry chunk.** It is imported by
  `src/lib/monaco-setup.ts`, which the four editor components import for its side
  effect. That file must stay a side-effect import — `@monaco-editor/react` falls
  back to the jsDelivr CDN if `loader.config()` has not run before `<Editor>`
  mounts, and tests mock it out via `vi.mock("@/lib/monaco-setup")` in
  `src/test/setup.ts`.
- **Locales are code-split too.** Only `en.json` is bundled (it is `fallbackLng`,
  so it must resolve synchronously); the other ten load through a tiny i18next
  backend in `src/i18n/config.ts`. Add a locale by adding it to
  `SUPPORTED_LANGUAGES` **and** `LOCALE_LOADERS` — the types make a missed entry
  a compile error. `main.tsx` awaits `i18nReady` before the first render so a
  non-English user never sees a flash of English, and `i18n.changeLanguage()` is
  now genuinely async: await it, and handle rejection (a chunk can 404 across a
  deploy).

Chunks are content-hashed, and the Maven build copies a fresh `dist/` into the jar on every
`./mvnw clean package`, so a stale hashed asset cannot ship; `lazyPage` reloads once if a chunk 404s
(a tab held open across a deploy).

#### 6. Tests

- Vitest + RTL in `__tests__/` directories beside the code — page tests in `src/pages/__tests__/` (naming: `resource-detail-{type}.test.tsx`), component and lib tests beside their sources
- Render pages through `renderPage(path, element, routePattern?, client?)` from `src/test/test-utils.tsx` (`MemoryRouter` + `QueryClient` + `ThemeProvider`). Some older files still define a local `renderPage` of their own — don't copy that pattern into new files
- **Assert on `data-testid` attributes**, never on translated copy — every row, button, input and state a test checks gets one
- E2E tests via Playwright in `e2e/`, including `rtl.spec.ts` and `theme.spec.ts`

### API Communication

- Base URL: `window.location.origin` (never hardcode)
- Vite proxy forwards all store paths to EDDI backend in dev mode
- **Default to `src/lib/api-client.ts`** (`ApiClient` class) for API calls — it injects the Keycloak auth token automatically
- Some call sites use raw `fetch` because they need something `ApiClient` does not do: SSE streams (`sse-utils.ts`, `bearer-event-source.ts`), binary bodies and blob downloads (`backup.ts`, `attachments.ts`), and `text/plain` payloads (`rag-editor.tsx`). **Every raw `fetch` must spread `api.getAuthHeader()` itself** — forgetting it is a 401 that only appears once OIDC is switched on
- `updates.ts` holds the only raw `fetch` that must **not** carry the auth header: it calls `api.github.com` for the latest EDDI release, and attaching this deployment's Keycloak token would hand it to a third party. It is also the only call that is not same-origin, so `ApiClient` could not express it anyway. **`api.github.com` is the only host the Manager contacts off-origin on its own initiative, and it stays that way** — the Docker image shown beside the release is *derived* from the release version, never looked up, because EDDI's CI pushes the image before it cuts the release. Do not add a relay (shields.io or similar) to "verify" the tag: every first-party Docker endpoint is CORS-blocked from a browser, so anything that appears to work is a third party in the path. A test in `update-check-card.test.tsx` fails if a second host appears **for the update card** — note that it guards those two components, not the whole app. The one other off-origin request is `agent-wizard.tsx`'s OpenAPI-spec fetch, which goes to a URL the *user* types; it is hardened the same way (`credentials: "omit"`, `referrerPolicy: "no-referrer"`, an http/https check, a timeout and a size cap) precisely because it leaves the origin
- `secrets.ts` is the exception that is *not* justified: its eight call sites are ordinary JSON CRUD on raw `fetch` for historical reasons. They do pass `api.getAuthHeader()`, and they check `!res.ok` (a past bug swallowed vault failures into an empty state). Error handling now runs through one `throwVaultError` helper raising a `SecretsError` with a translatable `code`, rather than seven copies of an English sentence. Treat the raw `fetch` itself as debt to migrate onto `ApiClient`, not as the pattern to copy
- For a new ordinary JSON call, use `ApiClient`
- Server state via TanStack Query hooks in `src/hooks/`, with keys from `src/lib/query-keys.ts`

---

## 4. Handoff Protocol

**Ending a session:** commit all working code (`wip:` prefix if incomplete), write the
changelog fragment (root rule 8) with what was done, the test count you actually observed
and what is next, and suggest a new conversation if the context is long.

---

## 5. Constraints

- Do NOT use MUI, Redux, recompose, or legacy patterns
- Do NOT use `moment.js` — use native `Intl` or `date-fns`
- Do NOT hardcode the API URL, and do NOT add raw `fetch` for ordinary JSON — use `ApiClient` (the justified exceptions are listed under API Communication)
- Do NOT use physical-direction utilities — `pl-*` / `pr-*` / `ml-*` / `mr-*` / `left-*` / `right-*` / `text-left` / `text-right`. Use the logical ones (`ps-*` / `pe-*` / `ms-*` / `me-*` / `start-*` / `end-*` / `text-start` / `text-end`): the app ships Arabic, and `e2e/rtl.spec.ts` checks it
- Do NOT mix component exports with utility function exports in the same file (`react-refresh/only-export-components`)
