# EDDI Manager — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI Manager** is the admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform. It is a **React/TypeScript SPA** served from the EDDI backend.

### Ecosystem

All repos live under `c:\dev\git\`:

| Repo                       | Tech                      | Purpose                                              |
| -------------------------- | ------------------------- | ---------------------------------------------------- |
| **EDDI**                   | Java 25, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline         |
| **EDDI-Manager** (this)    | React 19, Vite, Tailwind  | Admin dashboard — agents, workflows, extensions, chat |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                               |
| **eddi-website**           | Astro                     | Marketing site at eddi.labs.ai                       |
| **EDDI-integration-tests** | Java                      | End-to-end API tests                                 |

### Tech Stack

| Layer              | Technology                                                             |
| ------------------ | ---------------------------------------------------------------------- |
| **Build**          | Vite 6                                                                 |
| **UI**             | React 19 + TypeScript 5 (strict)                                       |
| **Styling**        | Tailwind CSS v4 + CSS variables (black/gold)                           |
| **State (server)** | TanStack Query v5                                                      |
| **State (UI)**     | Zustand (chat/debug), `useState` / `useCallback` elsewhere             |
| **Routing**        | React Router v7 (`react-router-dom` 7.x, declarative mode — no data router) |
| **i18n**           | react-i18next (11 locales: en, de, fr, es, ar, zh, th, ja, ko, pt, hi) |
| **Test (unit)**    | Vitest + React Testing Library + MSW                                   |
| **Test (e2e)**     | Playwright                                                             |
| **Editor**         | Monaco (@monaco-editor/react)                                          |
| **DnD**            | @dnd-kit (workflow pipeline builder)                                   |

---

## 2. Workflow

### Before Starting Any Work

1. **Read [`HANDOFF.md`](HANDOFF.md)** — current status, completed phases, test counts
2. **Check git logs**: `git log -5 --oneline`
3. **Check for uncommitted work**: `git status`
4. **Cross-repo context**: [`../EDDI/AGENTS.md`](../EDDI/AGENTS.md) when touching API contracts

### During Work

- **Branch**: **NEVER commit directly to `main`.** Always create a feature branch (e.g. `feat/…`, `fix/…`) before making changes. If you find yourself on `main`, create and switch to a new branch first.
- **Commit often** with conventional commits: `feat: description`

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

Every commit is validated by the pre-commit hook (`husky` + `lint-staged`):

1. **ESLint** — `eslint --max-warnings 0` on staged `.ts/.tsx` files
2. **TypeScript** — `npm run typecheck` (`tsc -b`, full project type-check)

> ⚠️ **`npx tsc --noEmit` checks nothing in this repo.** `tsconfig.json` is a
> solution file — `"files": []` plus references to `tsconfig.app.json` and
> `tsconfig.node.json` — so `--noEmit` resolves zero input files and exits 0.
> Always use `npm run typecheck` (what CI runs). The pre-commit hook ran the
> no-op form until it let a syntax error through to CI.

Before pushing or completing a phase, also verify:

```bash
npm run test         # All Vitest tests pass
npm run build        # Production build succeeds (includes tsc -b)
```

### i18n — MANDATORY

> **⚠️ Every time you add or modify keys in `en.json`, you MUST propagate those changes to ALL 10 other locale files before committing.**

The project has **11 locales**: `en`, `de`, `fr`, `es`, `ar`, `zh`, `th`, `ja`, `ko`, `pt`, `hi`.

1. Add new keys to `src/i18n/locales/en.json` first
2. **Immediately** propagate translated versions to all other 10 locale files
3. Verify with: `Get-ChildItem src/i18n/locales/*.json | Where-Object { Select-String -Path $_ -Pattern '"YOUR_KEY"' -Quiet } | Measure-Object` → must be **11**
4. Do NOT leave this as a follow-up step — it must be done in the **same commit**

### After Completing Work

1. **Update [`HANDOFF.md`](HANDOFF.md)**: new phase row, test counts, last commit
2. **Suggest a new conversation** if a phase is complete or context is long

---

## 3. Architecture & Patterns

### File Structure

```
src/
├── components/
│   ├── agents/               # Agent-specific components (import dialog, sync, etc.)
│   ├── editors/              # Extension editors + shared editor chrome
│   │   ├── config-editor-layout.tsx   # Tabs (Form|JSON), version picker, save
│   │   ├── editor-registry.tsx        # Shared EDITOR_MAP (single source of truth)
│   │   └── *.tsx                      # rules, apicalls, llm, output, dictionary, etc.
│   ├── groups/               # Group conversation components
│   ├── studio/               # Agent Studio workspace
│   │   ├── pipeline-railroad.tsx      # Visual pipeline step list
│   │   └── studio-editor-panel.tsx    # In-place editor for selected stage
│   ├── layout/               # Sidebar, top-bar, theme-provider
│   ├── operator/             # Platform Operator (activation, chat, status)
│   ├── shared/               # Reusable shared components (command palette, view toggle, etc.)
│   └── ui/                   # Low-level UI primitives (button, badge, dialog, etc.)
├── hooks/                    # TanStack Query hooks
├── lib/
│   ├── api/                  # API modules (agents.ts, resources.ts, backup.ts, etc.)
│   ├── operator/             # Operator tool allow-list + system prompt
│   ├── api-client.ts         # Base fetch wrapper with auth header injection
│   └── constants.ts          # Shared constants (ENVIRONMENTS, etc.)
├── i18n/locales/             # 11 locale JSON files
├── pages/
│   ├── __tests__/            # Vitest component tests
│   └── *.tsx                 # Route pages
└── test/mocks/
    ├── handlers.ts           # MSW request handlers
    └── server.ts             # MSW server setup
```

### Key Patterns

#### 1. Editor Registry (Single Source of Truth)

All extension editors are registered in `src/components/editors/editor-registry.tsx`:

```tsx
export const EDITOR_MAP: Record<string, EditorRenderFn> = {
  rules:    (p, o, r) => <RulesEditor data={p} onChange={o} readOnly={r} />,
  llm:      (p, o, r) => <LlmEditor data={p} onChange={o} readOnly={r} />,
  apicalls: (p, o, r) => <ApiCallsEditor data={p} onChange={o} readOnly={r} />,
  // ... output, dictionary, propertysetter, mcpcalls, rag, snippets
};
```

**To add a new editor**: create the component → add to `EDITOR_MAP` → add MSW handler → add i18n keys → add test file.

#### 2. Resource Type Config

All 9 resource types are defined in `src/lib/api/resources.ts` as `RESOURCE_TYPES`:

| Slug             | Store                  | Plural           |
| ---------------- | ---------------------- | ---------------- |
| `rules`          | `rulestore`            | `rulesets`        |
| `apicalls`       | `apicallstore`         | `apicalls`        |
| `output`         | `outputstore`          | `outputsets`      |
| `dictionary`     | `dictionarystore`      | `dictionaries`    |
| `llm`            | `llmstore`             | `llms`            |
| `propertysetter` | `propertysetterstore`  | `propertysetters` |
| `mcpcalls`       | `mcpcallsstore`        | `mcpcalls`        |
| `rag`            | `ragstore`             | `rags`            |
| `snippets`       | `snippetstore`         | `snippets`        |

> **⚠️ Parser vs Dictionary — separate stores!**
>
> | Store               | Path                           | Extension            | Purpose                                        |
> | ------------------- | ------------------------------ | -------------------- | ---------------------------------------------- |
> | **DictionaryStore** | `dictionarystore/dictionaries` | `ai.labs.dictionary` | Word→expression mappings, phrases, regex       |
> | **ParserStore**     | `parserstore/parsers`          | `ai.labs.parser`     | Parser pipeline config that *references* dicts |
>
> - Workflows reference a **parser** → parsers reference **dictionaries**
> - The Manager's `dictionary` slug maps to `dictionarystore` (what users edit)
> - `parserstore` has **no mapping** in `pipeline-builder.tsx` — parser extensions show "Editor not available" because parsers don't have a standalone editor (they are infrastructure binding dictionaries to the pipeline)

#### 3. MSW Mock Handlers

- All handlers are in `src/test/mocks/handlers.ts`
- Specific GET handlers go **before** the generic `createResourceHandlers` block
- Include realistic mock data matching the backend Java model

#### 4. i18n

- Each editor has its own namespace: `llmEditor.*`, `apiCallsEditor.*`, `rulesEditor.*`, etc.
- **Always add to `en.json` first**, then propagate to all 10 other locale files
- Use inline fallbacks: `t("key", "Fallback")`

#### 5. Platform Operator

An opt-in, admin-activated agent that inspects this EDDI deployment and explains
what it finds. Off by default. Worth knowing before touching it:

- **It is a real EDDI agent**, provisioned through `setup-api` from EDDI's own
  OpenAPI spec. It shows up in the Agents list with an "Operator" badge; editing
  or deleting it there breaks the operator screen.
- **Its capability boundary is the allow-list** in `src/lib/operator/tool-scopes.ts`
  — an allow-list, never a deny-list, because a deny-list silently grants any
  endpoint the backend adds later. `WRITE_ENDPOINTS` holds 22 entries — read the
  constant rather than this line, and read its doc comment before adding to it:
  what is excluded (every `DELETE`, the full agent and group document PUTs, and
  every `llmstore` write — each because that document carries an approval gate
  of its own) is as deliberate as what is included.
  Offering any of them is additionally gated by `isWriteScopeAvailable`
  (backend HITL support, an already-verified gate, caller-identity auth, and a
  mounted approval surface all have to hold — see `operator-activation.tsx`).
  Granting `read_write` runs a write canary (`write-canary.ts`) that provokes
  a real gated write and rolls the whole activation back on anything but a
  clean pause.
- **Config is one atomic JSON blob** in the `platform.operator` global variable.
  Activation writes several values that must land together and the variable
  store has no transaction.
- **`authMode: "caller-identity"`** makes tool calls run as the signed-in user
  via the backend's `${caller:token}` resolver (EDDI 6.2.0+). `"none"` is
  blocked at activation when OIDC is on, because every tool call would 401.
- **Activation runs a canary** — one probe read counting tool calls — because a
  READY deployment badge says nothing about whether the tools can authenticate.

#### 6. Tests

- Unit tests in `src/pages/__tests__/` — naming: `resource-detail-{type}.test.tsx`
- Use `renderPage(type)` helper with `MemoryRouter` + `QueryClient` + `ThemeProvider`
- Assert on `data-testid` attributes
- E2E tests via Playwright in `e2e/`

### API Communication

- Base URL: `window.location.origin` (never hardcode)
- Vite proxy forwards all store paths to EDDI backend in dev mode
- **Default to `src/lib/api-client.ts`** (`ApiClient` class) for API calls — it injects the Keycloak auth token automatically
- Some call sites use raw `fetch` because they need something `ApiClient` does not do: SSE streams (`sse-utils.ts`, `bearer-event-source.ts`), binary bodies and blob downloads (`backup.ts`, `attachments.ts`), and `text/plain` payloads (`rag-editor.tsx`). **Every raw `fetch` must spread `api.getAuthHeader()` itself** — forgetting it is a 401 that only appears once OIDC is switched on
- `updates.ts` is the one raw `fetch` that must **not** carry the auth header: it calls `api.github.com` for the latest EDDI release, and attaching this deployment's Keycloak token would hand it to a third party. It is also the only call that is not same-origin, so `ApiClient` could not express it anyway
- `secrets.ts` is the exception that is *not* justified: its eight call sites are ordinary JSON CRUD on raw `fetch` for historical reasons. They do pass `api.getAuthHeader()`, and they check `!res.ok` (a past bug swallowed vault failures into an empty state). Treat it as debt to migrate onto `ApiClient`, not as the pattern to copy
- For a new ordinary JSON call, use `ApiClient`
- Server state via TanStack Query hooks in `src/hooks/`

### RTL Support

- Use **logical properties**: `ps-*` / `pe-*` / `ms-*` / `me-*` / `start-*` / `end-*`
- **Never** use `pl-*` / `pr-*` / `ml-*` / `mr-*` / `left-*` / `right-*`

---

## 4. Handoff Protocol

**Picking up from a previous session:**

1. Read `HANDOFF.md`
2. `git log -5 --oneline`
3. `git status` for uncommitted changes

**Ending a session:**

1. Commit all working code (`wip:` prefix if incomplete)
2. Update `HANDOFF.md` with completed work + test counts
3. Suggest new conversation if context is long

---

## 5. Constraints

- Do NOT use MUI, Redux, recompose, or legacy patterns
- Do NOT use `moment.js` — use native `Intl` or `date-fns`
- Do NOT hardcode the API URL — always go through `ApiClient`
- Do NOT use `left`/`right` CSS — use logical properties for RTL
- Do NOT mix component exports with utility function exports in the same file (`react-refresh/only-export-components`)
