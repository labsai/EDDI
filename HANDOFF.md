# EDDI v6.0 — Current Status

> **Last updated:** 2026-03-05 by conversation `a266b030`
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

### Phase 1, Item 2: Typed Memory Accessors ✅

- [x] Created `MemoryKey<T>` — type-safe key class with phantom type parameter and `isPublic` flag
- [x] Created `MemoryKeys` — central registry of well-known keys (`ACTIONS`, `INPUT`, `EXPRESSIONS_PARSED`, etc.)
- [x] Added typed accessor methods (`get`, `getData`, `getLatestData`, `set`) to `IConversationStep`, `IWritableConversationStep`, `IConversationStepStack`
- [x] Migrated 10 production files to use `MemoryKeys.*` constants
- [x] 15 new tests in `MemoryKeyTest`, updated 4 existing test files
- [x] All 533 tests pass (0 failures, 0 errors, 4 skipped)

**Key files:**

- `src/main/java/ai/labs/eddi/engine/memory/MemoryKey.java`
- `src/main/java/ai/labs/eddi/engine/memory/MemoryKeys.java`
- `src/main/java/ai/labs/eddi/engine/memory/IConversationMemory.java`
- `src/test/java/ai/labs/eddi/engine/memory/MemoryKeyTest.java`

## Next Up

### Phase 1, Item 3: Consolidate Configuration Stores (8 SP)

Extract shared CRUD logic from per-resource MongoDB stores into a generic base class. See `docs/v6-planning/implementation_plan.md` Appendix F, Item 3 for details.

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `GEMINI.md` (user rules) for Java coding standards
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
