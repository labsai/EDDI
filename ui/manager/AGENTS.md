# EDDI Manager — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI Manager** is the admin dashboard for the EDDI conversational AI platform. It is a **greenfield rewrite** (v6.0.0) as a modern React SPA.

### Tech Stack (v6.0.0 — Current)

| Layer              | Technology                                   |
| ------------------ | -------------------------------------------- |
| **Build**          | Vite 6                                       |
| **UI**             | React 19 + TypeScript 5.7 (strict)           |
| **Styling**        | Tailwind CSS v4 + CSS variables for theming  |
| **Components**     | shadcn/ui + Radix UI primitives              |
| **State (server)** | TanStack Query v5                            |
| **State (UI)**     | Zustand                                      |
| **Routing**        | React Router v6                              |
| **i18n**           | react-i18next (en, de, ar with auto LTR/RTL) |
| **Linting**        | ESLint flat config                           |
| **Test (unit)**    | Vitest + React Testing Library + MSW         |
| **Test (E2E)**     | Playwright                                   |
| **Auth**           | keycloak-js 26+ (to be wired)                |

### Ecosystem

| Repo               | Relationship                                                             |
| ------------------ | ------------------------------------------------------------------------ |
| **EDDI** (backend) | Manager calls EDDI's REST API via Vite proxy (dev) or same-origin (prod) |
| **eddi-chat-ui**   | Shares chat rendering logic — extracting shared `@eddi/chat-core`        |
| **eddi-website**   | Separate marketing site at eddi.labs.ai                                  |

All repos are at `c:\dev\git\`. Branch: `feature/version-6.0.0`.

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work — MUST READ

1. **`HANDOFF.md`** (this repo) — **READ FIRST.** Current status, what's done, what's next, design decisions, known issues
2. **`c:\dev\git\EDDI\docs\changelog.md`** — Ecosystem-wide changelog with implementation log entries across all repos
3. **Check git**: `git log -5 --oneline` on `feature/version-6.0.0` to see recent commits
4. **If planning new work**: also read the implementation plan artifact in the Antigravity brain (search for `implementation_plan.md`)

### During Work

- Commit often with `feat(v6):`, `fix(v6):`, `refactor(v6):`. Each commit must build (`npm run build`) and pass tests (`npm run test`).
- Use `--no-verify` for git commit (old pre-commit hook references removed package)

### After Work (or if interrupted/switching sessions)

- **Update `HANDOFF.md`** with: what changed, current status, what's next
- **Update `c:\dev\git\EDDI\docs\changelog.md`** with an implementation log entry
- **Suggest a new conversation** if context is getting long (more than ~15 tool calls deep)

---

## 3. Project Structure

```
src/
├── app.tsx                    # React Router routes
├── main.tsx                   # Entry point (providers)
├── index.css                  # Tailwind v4 + EDDI tokens
├── components/
│   ├── layout/                # Sidebar, TopBar, AppLayout, ThemeProvider
│   └── bots/                  # BotCard, CreateBotDialog
├── hooks/                     # TanStack Query hooks (use-bots, use-packages)
├── lib/
│   ├── api-client.ts          # Typed fetch client (base URL from window.location.origin)
│   ├── api/                   # API modules: bots.ts, packages.ts, descriptors.ts
│   └── utils.ts               # cn() for Tailwind class merging
├── pages/                     # Route pages (dashboard, bots, bot-detail, packages, etc.)
├── i18n/                      # config.ts + locales/ (en, de, ar)
└── test/                      # setup.ts, mocks/, test-utils.tsx
e2e/                           # Playwright tests (navigation, theme, RTL)
```

---

## 4. Code Conventions

### API Communication

- Base URL: `window.location.origin` (no hardcoded URLs)
- Vite proxy forwards `/botstore/*`, `/packagestore/*`, `/administration/*` to EDDI backend
- All API calls through typed modules in `src/lib/api/`
- Server state via TanStack Query hooks in `src/hooks/`

### RTL Support

- Use **logical properties**: `ps-*` / `pe-*` / `ms-*` / `me-*` / `start-*` / `end-*`
- Never use `pl-*` / `pr-*` / `ml-*` / `mr-*` / `left-*` / `right-*`
- `border-e` instead of `border-r` for sidebar borders

### DO NOT

- Do NOT use MUI, Redux, recompose, or any old code patterns
- Do NOT use `moment.js` — use native `Intl` or `date-fns`
- Do NOT hardcode the API URL
- Do NOT use `left`/`right` in CSS — use logical properties for RTL

---

## 5. Handoff Protocol

Read `HANDOFF.md`, check `git log -5`, update `HANDOFF.md` after work. See also EDDI/AGENTS.md Section 5 for the full protocol.
