# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during implementation. Updated as work progresses for easy reference and review.

---

## How to Read This Document

Each entry follows this format:

- **Date** — What changed and why
- **Repo** — Which repository was modified
- **Decision** — Key design decisions and their reasoning
- **Files** — Links to modified files

---

## Planning Phase (2026-03-05)

### Audit Completed — Implementation Plan Finalized

**Repos involved:** All 5 (EDDI, EDDI-Manager, eddi-chat-ui, eddi-website, EDDI-integration-tests)

**Key decisions made:**

| #   | Decision                                                   | Reasoning                                                                                | Appendix |
| --- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------- | -------- |
| 1   | UI framework: **React + Vite + shadcn/ui + Tailwind CSS**  | AI-friendly (components are plain files), no dependency rot, accessible (Radix), fast DX | J.1a     |
| 2   | Keep Chat UI **standalone** + extract **shared component** | EDDI has a dedicated single-bot chat endpoint; standalone deployment is needed           | M.1      |
| 3   | Website: **Astro** on GitHub Pages                         | Static output, built-in i18n routing, zero JS by default, Tailwind integration           | L        |
| 4   | **Skip API versioning**                                    | Only clients are Manager + Chat UI, both first-party controlled                          | M.7      |
| 5   | **Remove internal snapshot tests**                         | Never production-ready; integration tests provide sufficient coverage                    | K.1      |
| 6   | **Trunk-based branching**                                  | Short-lived feature branches, squash merge, clean main history                           | N.1      |
| 7   | **Mobile-first responsive** is Phase 1                     | Core requirement, not afterthought; Tailwind breakpoints make this natural               | J.4      |

**Biggest gap discovered:** No CI/CD anywhere — all builds, tests, and deployments are manual.

**Strongest existing areas:** Security (SSRF, rate limiting, cost tracking), Monitoring (30+ Prometheus metrics, Grafana dashboard), Documentation (40 markdown files published via docs.labs.ai).

---

## Implementation Log

### 2026-03-06 — Manager UI: Packages + Conversations Pages

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #6-8

**What changed:**

1. **Packages List Page** — Full rewrite of placeholder: cards grid, search/filter, create dialog, context menu (duplicate/delete)
2. **Package Detail Page** — Extensions list with type labels, config URI, add/remove, expandable raw JSON, delete
3. **Conversations List Page** — Table layout with state filter pills (All/Active/In Progress/Ended/Error), search, delete, links to detail view
4. **Conversation Detail Page** — Step-by-step memory viewer showing user input, actions, bot output per turn, expandable raw JSON per step, conversation properties section
5. **API modules** — `conversations.ts` (GET descriptors, simple log, raw log, DELETE)
6. **TanStack Query hooks** — `useConversationDescriptors`, `useSimpleConversation`, `useRawConversation`, `useDeleteConversation`, `useCreatePackage`, `useUpdatePackage`, `useDeletePackage`
7. **MSW handlers** — Package descriptors, package detail, package CRUD, conversation descriptors, conversation logs
8. **i18n** — Added all `packages.*`, `packageDetail.*`, `conversations.*`, `conversationDetail.*` keys to en.json
9. **Routes** — `/manage/packageview/:id` → PackageDetailPage, `/manage/conversationview/:id` → ConversationDetailPage
10. **Vite proxy** — Added `/managedbots` proxy for future Chat Panel

**Tests:** 31/31 passing (7 files), TypeScript zero errors, build succeeds (421KB JS, 29KB CSS)

**Key decisions:**

- Conversations page uses low-level `/conversationstore/conversations` API for browsing/inspecting
- Chat Panel (future Phase 3.9) will use `/managedbots/{intent}/{userId}` (managed) or `/bots/{env}/{botId}` (direct)

---

### 2026-03-06 — Manager UI: Greenfield Scaffold + Layout Shell

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #2-3

**What changed:**

- Replaced entire Webpack + MUI v4 + Redux + TSLint codebase with Vite 6 + React 19 + Tailwind v4 + TanStack Query + Zustand + ESLint
- 28 new files: config, layout components, i18n (en/de/ar with auto RTL), 5 placeholder pages
- Testing pyramid: Vitest + RTL + MSW (unit/component) + Playwright (E2E config)

**Testing:** ✅ `npx tsc -b` zero errors, `npm run build` succeeds, 14/14 tests pass  
**Commit:** `020007e`

---

### 2026-03-06 — Manager UI: Bots Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #4

**What changed:**

- Bot card component with deployment status badges (auto-polled via TanStack Query)
- Deploy/undeploy actions, context menu (duplicate, delete), create bot dialog
- Search/filter, version deduplication via `groupBotsByName`

**Testing:** ✅ 23/23 tests pass (9 new)  
**Commit:** `e47b0fb`

---

### 2026-03-06 — Manager UI: Bot Detail Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #5

**What changed:**

- Bot Detail page: deployment status + deploy/undeploy, package list with add/remove
- Searchable package selector, raw JSON config viewer, delete with navigation
- Packages API, descriptors API, TanStack Query hooks for packages

**Testing:** ✅ 23/23 tests pass, zero TypeScript errors  
**Commit:** `dadc669`

---

### 2026-03-06 — Handoff Prep

**Repo:** EDDI-Manager  
**What changed:** Updated AGENTS.md, created HANDOFF.md  
**Commit:** `6fc510e`

### Template for Each Entry

```markdown
### [DATE] — [SHORT TITLE]

**Repo:** [repo name]  
**Branch:** `feat/...` or `fix/...`  
**Phase:** [1/2/3] — Item #[number]

**What changed:**

- [file 1]: [what and why]
- [file 2]: [what and why]

**Design decision (if any):**
[Why this approach was chosen over alternatives]

**Testing:**

- [ ] Builds cleanly
- [ ] Verified in browser
- [ ] No regressions

**Commit:** `feat(scope): message`
```

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
|            |                                                                       |                                       |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
|      |            |       |     |        |
