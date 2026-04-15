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
| **Routing**        | React Router v7                                                        |
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

- **Branch**: Ensure you are on the correct user-specified feature branch.
- **Commit often** with conventional commits: `feat: description`

### Quality Gates

Every commit is validated by the pre-commit hook (`husky` + `lint-staged`):

1. **ESLint** — `eslint --max-warnings 0` on staged `.ts/.tsx` files
2. **TypeScript** — `npx tsc --noEmit` (full project type-check)

Before pushing or completing a phase, also verify:

```bash
npm run test         # All Vitest tests pass
npm run build        # Production build succeeds (includes tsc -b)
```

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
│   ├── shared/               # Reusable shared components (command palette, view toggle, etc.)
│   └── ui/                   # Low-level UI primitives (button, badge, dialog, etc.)
├── hooks/                    # TanStack Query hooks
├── lib/
│   ├── api/                  # API modules (agents.ts, resources.ts, backup.ts, etc.)
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
> - The `pipeline-builder.tsx` storeMap maps **both** stores → `dictionary` slug

#### 3. MSW Mock Handlers

- All handlers are in `src/test/mocks/handlers.ts`
- Specific GET handlers go **before** the generic `createResourceHandlers` block
- Include realistic mock data matching the backend Java model

#### 4. i18n

- Each editor has its own namespace: `llmEditor.*`, `apiCallsEditor.*`, `rulesEditor.*`, etc.
- **Always add to `en.json` first**, then propagate to all 10 other locale files
- Use inline fallbacks: `t("key", "Fallback")`

#### 5. Tests

- Unit tests in `src/pages/__tests__/` — naming: `resource-detail-{type}.test.tsx`
- Use `renderPage(type)` helper with `MemoryRouter` + `QueryClient` + `ThemeProvider`
- Assert on `data-testid` attributes
- E2E tests via Playwright in `e2e/`

### API Communication

- Base URL: `window.location.origin` (never hardcode)
- Vite proxy forwards all store paths to EDDI backend in dev mode
- **All API calls** go through `src/lib/api-client.ts` (`ApiClient` class) — this ensures Keycloak auth tokens are propagated automatically
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
