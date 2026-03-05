# EDDI v6.0 — Current Status

> **Last updated:** 2026-03-05 by conversation `d109a59c`
> **Branch:** `feature/version-6.0.0`

## Completed

### Phase 0: Security Quick Wins ✅ (commit `71448a89`)

- [x] CORS restricted to `localhost:3000,localhost:7070`
- [x] `PathNavigator` replaces all 5 explicit `Ognl.getValue()`/`Ognl.setValue()` calls
- [x] 27 new PathNavigator tests, all 499 tests pass

## Next Up

### Phase 1, Item 1: Extract `ConversationService` from `RestBotEngine` (5 SP)

`RestBotEngine.java` is a 668-line "god class" mixing REST handling, service logic, metrics, and caching. The goal is to extract the core conversation logic into a clean `ConversationService` class.

**Key file:** `src/main/java/ai/labs/eddi/engine/internal/RestBotEngine.java`

**What to extract:**

- `startConversation()` / `startConversationWithContext()`
- `say()` / `sayWithReturn()`
- `endConversation()`
- `getConversationState()`
- `rerunLastConversationStep()` / `undoLastConversationStep()`

**Leave in `RestBotEngine`:** Only JAX-RS annotations, request parsing, `AsyncResponse` handling.

## Important Rules

- All work on **`feature/version-6.0.0`** branch (never `main`)
- Read `AGENTS.md` for development order and guidelines
- Read `GEMINI.md` (user rules) for Java coding standards
- Read `docs/v6-planning/` for architecture analysis, changelog, and business logic analysis
- Commit often with conventional commits
- Run `.\mvnw test` before committing
- Suggest a new conversation when a phase or major item is completed
