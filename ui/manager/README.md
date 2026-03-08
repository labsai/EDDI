# EDDI Manager

> Admin dashboard for the [EDDI](https://github.com/labsai/EDDI) conversational AI platform.

## Tech Stack

| Layer              | Technology                                   |
| ------------------ | -------------------------------------------- |
| **Build**          | Vite 6                                       |
| **UI**             | React 19 + TypeScript 5 (strict mode)        |
| **Styling**        | Tailwind CSS v4 + CSS variables (black/gold) |
| **State (server)** | TanStack Query v5                            |
| **State (UI)**     | React hooks (`useState` / `useCallback`)     |
| **Routing**        | React Router v7                              |
| **i18n**           | react-i18next — 11 locales with RTL support  |
| **Editor**         | Monaco (`@monaco-editor/react`)              |
| **DnD**            | @dnd-kit (package pipeline builder)          |
| **Tests**          | Vitest + React Testing Library + MSW         |

## Prerequisites

- **Node.js** ≥ 20
- **npm** ≥ 10
- EDDI backend running on `localhost:7070` (for dev proxy)

## Quick Start

```bash
npm install
npm run dev          # Vite dev server on http://localhost:5173
```

The Vite proxy forwards all API calls to the EDDI backend at `localhost:7070`.

## Testing & Build

```bash
npx tsc -b           # TypeScript type-check (zero errors expected)
npm run test          # Vitest unit + component tests
npm run build         # Production build
```

## Architecture

```
src/
├── components/
│   ├── editors/              # Form editors + shared editor chrome
│   │   ├── config-editor-layout.tsx   # Tabs (Form|JSON), version picker, save
│   │   ├── behavior-editor.tsx        # Behavior rules form editor
│   │   ├── httpcalls-editor.tsx       # HTTP calls form editor
│   │   ├── langchain-editor.tsx       # LangChain/AI config editor
│   │   ├── output-editor.tsx          # Output sets editor
│   │   ├── propertysetter-editor.tsx  # Property setter editor
│   │   ├── dictionary-editor.tsx      # Dictionary editor
│   │   ├── pipeline-builder.tsx       # Drag-and-drop extension pipeline
│   │   └── version-picker.tsx         # Version dropdown
│   └── layout/                # Sidebar, top-bar, theme-provider
├── hooks/                     # TanStack Query hooks
├── lib/
│   ├── api/                   # Typed API modules
│   └── api-client.ts          # Base fetch wrapper
├── i18n/locales/              # 11 locale JSON files
├── pages/                     # Route pages
│   └── __tests__/             # Component tests
└── test/mocks/
    ├── handlers.ts            # MSW request handlers
    └── server.ts              # MSW server setup
```

### Key Patterns

- **Editor Render Prop**: All editors plug into `ConfigEditorLayout` via `renderFormEditor` in `resource-detail.tsx`
- **Generic CRUD**: `src/lib/api/resources.ts` provides typed CRUD for all 6 extension types
- **API base URL**: `window.location.origin` — no hardcoded URLs
- **Logical CSS**: Uses `ps-*`/`pe-*`/`ms-*`/`me-*` for RTL support
- **i18n**: Auto-detects RTL, sets `dir` on `<html>`, supports 11 languages

### Supported Locales

English, German, French, Spanish, Arabic (RTL), Chinese, Thai, Japanese, Korean, Portuguese, Hindi

## Branch

All v6 development is on `feature/version-6.0.0`.

## Related Repos

- [EDDI](https://github.com/labsai/EDDI) — Backend engine (Java 21, Quarkus, MongoDB)
- [eddi-chat-ui](https://github.com/labsai/eddi-chat-ui) — Standalone chat widget

## License

See [LICENSE](LICENSE) for details.
