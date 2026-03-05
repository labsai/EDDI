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

_Entries will be added here as implementation progresses._

### 2026-03-05 — Typed Memory Accessors

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #2

**What changed:**

- `MemoryKey.java` [NEW]: Generic type-safe key class — binds a string name to a Java type and carries an `isPublic` flag
- `MemoryKeys.java` [NEW]: Central registry of well-known keys (`ACTIONS`, `INPUT`, `EXPRESSIONS_PARSED`, `INTENTS`, etc.) with prefix strings for dynamic keys (`OUTPUT_PREFIX`, `QUICK_REPLIES_PREFIX`)
- `IConversationMemory.java` [MODIFIED]: Added typed accessor methods `get(MemoryKey)`, `getData(MemoryKey)`, `getLatestData(MemoryKey)`, `set(MemoryKey, T)` to step interfaces
- `ConversationStep.java`, `ConversationMemory.java` [MODIFIED]: Implemented typed accessors
- 10 production files migrated: `Conversation`, `LifecycleManager`, `ConversationMemoryUtilities`, `InputParserTask`, `BehaviorRulesEvaluationTask`, `ActionMatcher`, `InputMatcher`, `HttpCallsTask`, `LangchainTask`, `OutputGenerationTask`
- `MemoryKeyTest.java` [NEW]: 15 tests for MemoryKey and typed accessors
- 4 existing test files updated: `ActionMatcherTest`, `InputMatcherTest`, `OutputItemContainerGenerationTaskTest`, `LangchainTaskTest`

**Design decision:**
Additive approach — typed methods sit alongside existing string-based methods for backward compatibility. Dynamic keys (output:text:action, quickReplies:action) remain as `String` prefix constants since they're used for key construction. `occurredInAnyStep()` continues to use string-based `getLatestData(String)` via `getAllLatestData(String)`.

**Testing:**

- [x] Builds cleanly
- [x] All 533 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

### 2026-03-05 — Extract ConversationService from RestBotEngine

**Repo:** EDDI  
**Branch:** `feature/version-6.0.0`  
**Phase:** 1 — Item #1

**What changed:**

- `IConversationService.java` [NEW]: Domain interface with `ConversationResponseHandler` callback, records (`ConversationResult`, `ConversationLogResult`), and domain exceptions (`BotNotReadyException`, `ConversationNotFoundException`, `BotMismatchException`, `ConversationEndedException`)
- `ConversationService.java` [NEW]: All business logic extracted from RestBotEngine — conversation lifecycle, metrics, caching, memory management (~565 lines)
- `RestBotEngine.java` [MODIFIED]: Refactored from 668 to ~230 lines — now a thin JAX-RS adapter that delegates to `IConversationService` and maps domain exceptions to HTTP responses
- `ConversationServiceTest.java` [NEW]: 16 unit tests covering start, end, state, read, say, undo/redo, and properties handler

**Design decision:**
Kept metrics and caching inside `ConversationService` rather than extracting separate `ConversationMetricsService` and `ConversationStateCache` classes. The metrics code is ~20 lines and caching is 3 trivial methods — separate classes would be premature decomposition. Can be split later if needed.

**Testing:**

- [x] Builds cleanly
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)
- [x] No regressions

**Commit:** `refactor(engine): extract ConversationService from RestBotEngine` (`7dd1488e`)

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
