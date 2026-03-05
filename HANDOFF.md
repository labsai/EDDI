# EDDI v6.0 — Current Status

> **Last updated:** 2026-03-05 by conversation `cad9b9bd`
> **Branch:** `feature/version-6.0.0`

## Completed

### Phase 0: Security Quick Wins ✅ (commit `71448a89`)

- [x] CORS restricted to `localhost:3000,localhost:7070`
- [x] `PathNavigator` replaces all 5 explicit `Ognl.getValue()`/`Ognl.setValue()` calls
- [x] 27 new PathNavigator tests, all 499 tests pass

### Phase 1, Item 1: Extract `ConversationService` from `RestBotEngine` ✅ (commit `7dd1488e`)

- [x] Created `IConversationService` interface with domain exceptions (no JAX-RS deps)
- [x] Created `ConversationService` implementation with all business logic (~565 lines)
- [x] Refactored `RestBotEngine` from 668 to ~230 lines (thin REST adapter)
- [x] 16 new unit tests for `ConversationService`
- [x] All 515 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/IConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java`
- `src/main/java/ai/labs/eddi/engine/internal/RestBotEngine.java`
- `src/test/java/ai/labs/eddi/engine/internal/ConversationServiceTest.java`

## Next Up

### Phase 1, Item 2: Typed Memory Accessors (8 SP)

Replace stringly-typed `memory.getCurrentStep().getLatestData("actions")` with type-safe accessors. See `docs/v6-planning/implementation_plan.md` Appendix F, Item 2 for details.

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `GEMINI.md` (user rules) for Java coding standards
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
