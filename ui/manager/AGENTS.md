# EDDI Manager — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI Manager** is the admin dashboard for the EDDI conversational AI platform. It's a **React + TypeScript** SPA served from the EDDI backend at `/chat/unrestricted`.

### Current State → Target

| Aspect           | Current                | Target                   |
| ---------------- | ---------------------- | ------------------------ |
| **Build tool**   | Webpack (custom)       | Vite                     |
| **UI framework** | MUI v4 (deprecated)    | shadcn/ui + Tailwind CSS |
| **State**        | Redux + recompose HOCs | Redux + React hooks      |
| **Linting**      | tslint (deprecated)    | ESLint                   |
| **Styling**      | CSS modules + MUI      | Tailwind CSS             |
| **i18n**         | None                   | react-i18next + RTL      |
| **Theme**        | Light only             | Dark/Light toggle        |
| **Responsive**   | Desktop only           | Mobile-first             |

### Ecosystem

| Repo               | Relationship                                                          |
| ------------------ | --------------------------------------------------------------------- |
| **EDDI** (backend) | Manager calls EDDI's REST API. Bundle is copied into EDDI's resources |
| **eddi-chat-ui**   | Shares chat rendering logic — extracting shared `@eddi/chat-core`     |
| **eddi-website**   | Separate marketing site at eddi.labs.ai                               |

All repos are at `c:\dev\git\`. The full implementation plan (14 appendices) and changelog are in the Antigravity brain directory — search for `implementation_plan.md` and `changelog.md` under `~/.gemini/antigravity/brain/`.

---

## 2. Mandatory Workflow Protocol

1. **Before work**: Find and read `changelog.md` and `implementation_plan.md` (Appendix J covers Manager). Check `git status` + `git log -5`.
2. **During work**: Commit often with `feat(manager):`, `fix(manager):`, `refactor(manager):`. Each commit must build (`npm run build`).
3. **After work**: Update `changelog.md` with what changed, decisions made, and next steps.

---

## 3. Development Order (Manager-specific)

```
Phase 1: Foundation (unblocks everything)
  1. Fix env.json → use relative URL or window.location.origin
  2. Migrate tslint → ESLint
  3. Replace recompose HOCs with React hooks

Phase 2: Core UX (must-do for a great product)
  4. Dark/light mode toggle (Tailwind dark: variant)
  5. i18n (react-i18next) + RTL support
  6. Pagination + sorting on Bots/Conversations lists
  7. Deduplicate bot entries (group by name, show versions)
  8. Replace eddi:// URIs with human-readable names
  9. Conversation export (JSON/CSV)
  10. Bot health indicators (green/yellow/red)

Phase 3: Polish (makes it exceptional)
  11. Config editor: JSON Schema validation + autocomplete
  12. Config editor: Diff view (changed vs saved)
  13. Config editor: Undo/Redo
  14. Keyboard shortcuts (Ctrl+S, Ctrl+Enter, Esc)
  15. Import/export UI (not just Swagger)
  16. First-use onboarding tour
  17. Config version history
```

---

## 4. Code Conventions

### API Communication

- Base URL from `env.json` → `window.eddiApiUrl` (**Phase 1 fix target**: use relative URL)
- All API calls through Redux action dispatchers
- EDDI REST API has no version prefix (e.g., `/botstore/bots`)

### Important: DO NOT

- Do NOT use MUI v4 components in new code — use shadcn/ui
- Do NOT create new recompose HOCs — use React hooks
- Do NOT use tslint annotations — use ESLint equivalents
- Do NOT use moment.js for new date formatting — use `date-fns` or native `Intl`
- Do NOT hardcode the API URL — always read from env.json or window.location.origin

### Key Files

| File                                               | Purpose                                          |
| -------------------------------------------------- | ------------------------------------------------ |
| `src/scripts/resources/env.json`                   | API URL config — **Phase 1 fix target**          |
| `src/scripts/components/`                          | All UI components                                |
| `src/scripts/reducers/`                            | Redux state management                           |
| `src/scripts/actions/`                             | API action dispatchers                           |
| `src/scripts/selectors/AuthenticationSelectors.ts` | ⚠️ Hardcodes `authenticated: true` — auth bypass |
| `tslint.json`                                      | Deprecated linter — **Phase 1 migration target** |

### Known Issues

- `AuthenticationSelectors.ts` hardcodes `authenticated: true` — auth is non-functional
- `recompose` HOCs wrap most container components — dead library since 2019
- `moment.js` used in some date formatting — replace with `date-fns` or native `Intl`
- Duplicate bot entries appear due to multiple deployments/versions of same bot
- `PackagesUsingPlugin.tsx` shows which packages use a resource (already implemented)

---

## 5. Handoff Protocol

Same as EDDI backend — read `changelog.md`, check `git log`, update changelog if interrupted. See EDDI/AGENTS.md Section 5 for full protocol.
