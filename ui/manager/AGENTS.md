# EDDI Manager — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI Manager** is the admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform. It is a **React/TypeScript SPA** served from the EDDI backend.

### Ecosystem (5 repos, all under `c:\dev\git\`)

| Repo                       | Tech                      | Purpose                                            |
| -------------------------- | ------------------------- | -------------------------------------------------- |
| **EDDI**                   | Java 21, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline       |
| **EDDI-Manager** (this)    | React 19, Vite, Tailwind  | Admin dashboard — bots, packages, extensions, chat |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                             |
| **eddi-website**           | HTML → migrating to Astro | Marketing site at eddi.labs.ai                     |
| **EDDI-integration-tests** | Java                      | End-to-end API tests                               |

### Tech Stack

| Layer              | Technology                                                             |
| ------------------ | ---------------------------------------------------------------------- |
| **Build**          | Vite 6                                                                 |
| **UI**             | React 19 + TypeScript 5 (strict)                                       |
| **Styling**        | Tailwind CSS v4 + CSS variables (black/gold)                           |
| **State (server)** | TanStack Query v5                                                      |
| **State (UI)**     | `useState` / `useCallback` (no Zustand needed)                         |
| **Routing**        | React Router v7                                                        |
| **i18n**           | react-i18next (11 locales: en, de, fr, es, ar, zh, th, ja, ko, pt, hi) |
| **Test (unit)**    | Vitest + React Testing Library + MSW                                   |
| **Editor**         | Monaco (@monaco-editor/react)                                          |
| **DnD**            | @dnd-kit (package pipeline builder)                                    |

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work — MUST READ

1. **[`HANDOFF.md`](HANDOFF.md)** — **READ FIRST.** Current status, completed phases, test counts, what's next
2. **[`docs/editing-layer-plan.md`](docs/editing-layer-plan.md)** — Phases 3.14–3.19 plan (editors, cascade save)
3. **EDDI backend**: [`AGENTS.md`](../EDDI/AGENTS.md) and [`docs/v6-planning/changelog.md`](../EDDI/docs/v6-planning/changelog.md) for cross-repo context
4. **Check git**: `git log -5 --oneline` on `feature/version-6.0.0`

### During Work

- **Branch**: `feature/version-6.0.0` — do NOT commit to `main`
- **Commit often** with conventional commits: `feat(v6): Phase X.XX - description`
- **Each commit must pass** all three checks:
  ```bash
  npx tsc -b          # Zero TypeScript errors
  npm run test         # All tests pass
  npm run build        # Production build succeeds
  ```
- Use `--no-verify` for git commit (old pre-commit hook references removed package)

### After Completing Work

1. **Update `HANDOFF.md`**: new phase row, test counts, last commit
2. **Update `docs/editing-layer-plan.md`**: add ✅ and completion note to finished phases
3. **Suggest a new conversation** if a phase is complete or context is long

---

## 3. Architecture & Patterns

### File Structure

```
src/
├── components/
│   ├── editors/              # Form editors + shared editor chrome
│   │   ├── config-editor-layout.tsx   # Tabs (Form|JSON), version picker, save
│   │   ├── behavior-editor.tsx        # Phase 3.17
│   │   ├── httpcalls-editor.tsx       # Phase 3.17
│   │   ├── langchain-editor.tsx       # Phase 3.18
│   │   ├── output-editor.tsx          # Phase 3.18
│   │   ├── propertysetter-editor.tsx  # Phase 3.18
│   │   └── dictionary-editor.tsx      # Phase 3.18
│   ├── layout/                # Sidebar, top-bar, theme-provider
│   └── ui/                    # Reusable UI primitives
├── hooks/                     # TanStack Query hooks
├── lib/
│   ├── api/                   # API modules (bots.ts, resources.ts, etc.)
│   └── api-client.ts          # Base fetch wrapper
├── i18n/locales/              # 11 locale JSON files
├── pages/
│   ├── __tests__/             # Vitest component tests
│   └── resource-detail.tsx    # Wires editors via renderFormEditor
└── test/mocks/
    ├── handlers.ts            # MSW request handlers
    └── server.ts              # MSW server setup
```

### Key Patterns

#### 1. Editor Render Prop

All extension editors plug into `ConfigEditorLayout` via `renderFormEditor` in `resource-detail.tsx`:

```tsx
<ConfigEditorLayout
  renderFormEditor={
    type === 'behavior'
      ? (parsed, onFormChange, ro) => (
          <BehaviorEditor data={parsed} onChange={onFormChange} readOnly={ro} />
        )
      : type === 'langchain'
        ? (parsed, onFormChange, ro) => (
            <LangchainEditor
              data={parsed}
              onChange={onFormChange}
              readOnly={ro}
            />
          )
        : undefined // Falls back to JSON-only
  }
/>
```

To add a new editor: create the component, add the type check in the ternary, add MSW handler, add i18n keys, add test file.

#### 2. Resource Type Config

All 6 extension types are in `src/lib/api/resources.ts` as `RESOURCE_TYPES`:

| Slug               | Store                    | Plural                |
| ------------------ | ------------------------ | --------------------- |
| `behavior`         | `behaviorstore`          | `behaviorsets`        |
| `httpcalls`        | `httpcallsstore`         | `httpcalls`           |
| `output`           | `outputstore`            | `outputsets`          |
| **`dictionaries`** | `regulardictionarystore` | `regulardictionaries` |
| `langchain`        | `langchainstore`         | `langchains`          |
| `propertysetter`   | `propertysetterstore`    | `propertysetters`     |

> **⚠️ Important**: The URL slug for dictionaries is `dictionaries`, not `regulardictionary`.

#### 3. MSW Mock Handlers

- All handlers are in `src/test/mocks/handlers.ts`
- Specific GET handlers go **before** the generic `createResourceHandlers` block
- Include realistic mock data matching the backend Java model

#### 4. i18n

- Each editor has its own namespace: `langchainEditor.*`, `outputEditor.*`, etc.
- Add to `en.json` first, then propagate to all 10 other locale files
- Fallback values are inline in the component: `t("key", "Fallback")`

#### 5. Tests

- Located in `src/pages/__tests__/`
- Use `renderPage(type)` helper with `MemoryRouter` + `QueryClient` + `ThemeProvider`
- Assert on `data-testid` attributes
- Naming: `resource-detail-{type}.test.tsx`

### API Communication

- Base URL: `window.location.origin` (no hardcoded URLs)
- Vite proxy forwards all store paths to EDDI backend in dev mode
- All API calls through typed modules in `src/lib/api/`
- Server state via TanStack Query hooks in `src/hooks/`

### RTL Support

- Use **logical properties**: `ps-*` / `pe-*` / `ms-*` / `me-*` / `start-*` / `end-*`
- Never use `pl-*` / `pr-*` / `ml-*` / `mr-*` / `left-*` / `right-*`

---

## 4. Development Phases

All phases tracked in [`HANDOFF.md`](HANDOFF.md):

| Phase    | Description                                                                          | Status |
| -------- | ------------------------------------------------------------------------------------ | ------ |
| 3.1–3.13 | Read-only dashboard (layout, bots, packages, chat, resources)                        | ✅     |
| 3.14     | JSON Editor, Version Picker, Cascade Save                                            | ✅     |
| 3.15     | Bot Editor (deploy, duplicate, version picker)                                       | ✅     |
| 3.16     | Package Editor (drag-and-drop pipeline)                                              | ✅     |
| 3.17     | Behavior Rules & HTTP Calls Editors                                                  | ✅     |
| 3.18     | LangChain, Output, Property Setter, Dictionary Editors                               | ✅     |
| 3.19     | Polish, remaining tests, documentation                                               | ✅     |
| 3.20     | UI/UX Enterprise Polish (component library, toasts, dark mode)                       | ✅     |
| 3.21     | MSW Browser Mode, Backend Integration & JSON Schema                                  | ✅     |
| **4.1**  | **Keycloak Auth Adapter** — login/logout, token refresh, route guards, role-based UI | ✅     |
| **4.2**  | **E2E Test Suite (Playwright)** — full coverage of bots, packages, editors, chat     | ✅     |
| **4.3**  | **Real-Backend Integration Testing** — validate full CRUD with live EDDI             | ✅     |
| **4.4**  | **JSON Schema Enrichment** — victools migration + mock schema enrichment              | ✅     |
| **4.5**  | **Production Build + Dashboard** — single bundle, loading indicator, old dashboard → Manager redirect | ✅     |

**Phase 5+**: Phases 5–6 (NATS JetStream, PostgreSQL/DB-agnostic) ✅ complete. Remaining: Secrets+Audit (7), MCP full suite (8), DAG+HITL (9), Multi-Bot+RAG (10), Persistent Memory+Heartbeat (11), CI/CD (12), **Advanced Manager UI — Debugger, Visual Pipeline, Taint Tracking (13)**, Website Astro (14). See EDDI [`AGENTS.md`](../EDDI/AGENTS.md) and [`project-philosophy.md`](../EDDI/docs/project-philosophy.md) for full roadmap and principles.

---

## 5. Handoff Protocol

**Picking up from a previous session:**

1. Read `HANDOFF.md`
2. `git log -5 --oneline` on `feature/version-6.0.0`
3. `git status` for uncommitted changes
4. Check which phase is next from Section 4

**Ending a session:**

1. Commit all working code (`wip:` prefix if incomplete)
2. Update `HANDOFF.md` with completed work + test counts
3. Suggest new conversation if a phase is done or context is long

### DO NOT

- Do NOT use MUI, Redux, recompose, or old code patterns
- Do NOT use `moment.js` — use native `Intl` or `date-fns`
- Do NOT hardcode the API URL
- Do NOT use `left`/`right` CSS — use logical properties for RTL
