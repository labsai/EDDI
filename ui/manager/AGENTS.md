# EDDI Manager — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI Manager** is the admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform. It is a **React/TypeScript SPA** served from the EDDI backend.

### Ecosystem (5 repos, all under `c:\dev\git\`)

| Repo                       | Tech                      | Purpose                                              |
| -------------------------- | ------------------------- | ---------------------------------------------------- |
| **EDDI**                   | Java 25, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline         |
| **EDDI-Manager** (this)    | React 19, Vite, Tailwind  | Admin dashboard — agents, packages, extensions, chat |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                               |
| **eddi-website**           | HTML → migrating to Astro | Marketing site at eddi.labs.ai                       |
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
│   │   ├── editor-registry.tsx        # Shared EDITOR_MAP (single source of truth)
│   │   ├── rules-editor.tsx           # Behavior rules editor
│   │   ├── apicalls-editor.tsx        # HTTP API calls editor
│   │   ├── llm-editor.tsx             # LLM/langchain task editor
│   │   ├── output-editor.tsx          # Output sets editor
│   │   ├── propertysetter-editor.tsx  # Property setter editor
│   │   ├── dictionary-editor.tsx      # Dictionary/parser editor
│   │   ├── mcpcalls-editor.tsx        # MCP calls editor
│   │   ├── rag-editor.tsx             # RAG config editor
│   │   ├── snippet-editor.tsx         # Prompt snippet editor
│   │   └── agent-config-sections.tsx  # Security, capabilities, memory sections
│   ├── studio/                # Agent Studio workspace
│   │   ├── pipeline-railroad.tsx      # Visual pipeline step list
│   │   └── studio-editor-panel.tsx    # In-place editor for selected stage
│   ├── layout/                # Sidebar, top-bar, theme-provider
│   └── ui/                    # Reusable UI primitives
├── hooks/                     # TanStack Query hooks
├── lib/
│   ├── api/                   # API modules (agents.ts, resources.ts, etc.)
│   └── api-client.ts          # Base fetch wrapper
├── i18n/locales/              # 11 locale JSON files
├── pages/
│   ├── __tests__/             # Vitest component tests
│   ├── resource-detail.tsx    # Wires editors via EDITOR_MAP
│   ├── agent-studio.tsx       # 3-panel studio (railroad + editor + chat)
│   └── gdpr.tsx               # GDPR Privacy Admin page
└── test/mocks/
    ├── handlers.ts            # MSW request handlers
    └── server.ts              # MSW server setup
```

### Key Patterns

#### 1. Editor Registry (Single Source of Truth)

All extension editors are registered in `src/components/editors/editor-registry.tsx`:

```tsx
// editor-registry.tsx — shared by both ResourceDetailPage and StudioEditorPanel
export const EDITOR_MAP: Record<string, EditorRenderFn> = {
  rules:    (p, o, r) => <RulesEditor data={p} onChange={o} readOnly={r} />,
  llm:      (p, o, r) => <LlmEditor data={p} onChange={o} readOnly={r} />,
  apicalls: (p, o, r) => <ApiCallsEditor data={p} onChange={o} readOnly={r} />,
  // ... output, dictionary, propertysetter, mcpcalls, rag, snippets
};
```

To add a new editor: create the component, add to `EDITOR_MAP` in `editor-registry.tsx`, add MSW handler, add i18n keys, add test file.

#### 2. Resource Type Config

All 9 resource types are in `src/lib/api/resources.ts` as `RESOURCE_TYPES`:

| Slug               | Store                    | Plural                |
| ------------------ | ------------------------ | --------------------- |
| `rules`            | `rulestore`              | `rulesets`            |
| `apicalls`         | `apicallstore`           | `apicalls`            |
| `output`           | `outputstore`            | `outputsets`          |
| `dictionary`       | `dictionarystore`        | `dictionaries`        |
| `llm`              | `llmstore`               | `llms`                |
| `propertysetter`   | `propertysetterstore`    | `propertysetters`     |
| `mcpcalls`         | `mcpcallsstore`          | `mcpcalls`            |
| `rag`              | `ragstore`               | `rags`                |
| `snippets`         | `snippetstore`           | `snippets`            |

> **⚠️ Parser vs Dictionary — these are separate stores!**
>
> The backend has **two distinct stores** for the parser subsystem:
>
> | Store | Path | Extension | Config Model | Purpose |
> |-------|------|-----------|--------------|--------|
> | **DictionaryStore** | `dictionarystore/dictionaries` | `ai.labs.dictionary` | `RegularDictionaryConfiguration` | Word→expression mappings, phrases, regex patterns |
> | **ParserStore** | `parserstore/parsers` | `ai.labs.parser` | `ParserConfiguration` | Parser pipeline config that *references* dictionaries |
>
> - A **workflow** references a **parser** (`eddi://ai.labs.parser/parserstore/parsers/{id}`)
> - A **parser config** references one or more **dictionaries** (`eddi://ai.labs.dictionary/dictionarystore/dictionaries/{id}`)
> - The `dictionary` resource type in the Manager maps to `dictionarystore/dictionaries` — this is what users create and edit
> - The parser config is not yet directly editable in the Manager (future enhancement)
> - The `pipeline-builder.tsx` storeMap maps **both** `dictionarystore` and `parserstore` → `dictionary` slug for URI resolution
> - **Do NOT confuse these stores.** `parserstore` ≠ `dictionarystore`.

#### 3. MSW Mock Handlers

- All handlers are in `src/test/mocks/handlers.ts`
- Specific GET handlers go **before** the generic `createResourceHandlers` block
- Include realistic mock data matching the backend Java model

#### 4. i18n

- Each editor has its own namespace: `llmEditor.*`, `apiCallsEditor.*`, `rulesEditor.*`, `outputEditor.*`, etc.
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

| Phase    | Description                                                                                           | Status |
| -------- | ----------------------------------------------------------------------------------------------------- | ------ |
| 3.1–3.13 | Read-only dashboard (layout, agents, packages, chat, resources)                                       | ✅     |
| 3.14     | JSON Editor, Version Picker, Cascade Save                                                             | ✅     |
| 3.15     | Agent Editor (deploy, duplicate, version picker)                                                      | ✅     |
| 3.16     | Workflow Editor (drag-and-drop pipeline)                                                              | ✅     |
| 3.17     | Behavior Rules & HTTP Calls Editors                                                                   | ✅     |
| 3.18     | LangChain, Output, Property Setter, Dictionary Editors                                                | ✅     |
| 3.19     | Polish, remaining tests, documentation                                                                | ✅     |
| 3.20     | UI/UX Enterprise Polish (component library, toasts, dark mode)                                        | ✅     |
| 3.21     | MSW Browser Mode, Backend Integration & JSON Schema                                                   | ✅     |
| **4.1**  | **Keycloak Auth Adapter** — login/logout, token refresh, route guards, role-based UI                  | ✅     |
| **4.2**  | **E2E Test Suite (Playwright)** — full coverage of agents, packages, editors, chat                    | ✅     |
| **4.3**  | **Real-Backend Integration Testing** — validate full CRUD with live EDDI                              | ✅     |
| **4.4**  | **JSON Schema Enrichment** — victools migration + mock schema enrichment                              | ✅     |
| **4.5**  | **Production Build + Dashboard** — single bundle, loading indicator, old dashboard → Manager redirect | ✅     |

**Phase 5+**: Phases 5–6 (NATS JetStream, PostgreSQL/DB-agnostic) ✅ complete. Phase 6E (langchain4j core + ObservableChatModel) ✅ complete. Phase 6F (Contextual Logging) ✅ complete. Phase 6D (Lombok Removal) ✅ complete. Phase 7 (Secrets+Audit+Tenancy) ✅ complete. Phase 8a (MCP Servers, 33 tools) ✅ complete. Phase 8b (MCP Client) ✅ complete. A2A Protocol (server + client) ✅ complete. Phase 8c (RAG Foundation) ✅ complete. Phase 10 (Group Conversations) ✅ complete. Multi-Model Cascading ✅ complete. LLM Provider Expansion (7→12) ✅ complete. Quarkus 3.34.1 ✅ complete. Then: DAG+OTel (9), HITL (9b), Persistent Memory (11), CI/CD (12), **Advanced Manager UI — Debugger, Visual Pipeline, Taint Tracking (13)**, Website Astro (14). See EDDI [`AGENTS.md`](../EDDI/AGENTS.md) and [`project-philosophy.md`](../EDDI/docs/project-philosophy.md) for full roadmap and principles.

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
