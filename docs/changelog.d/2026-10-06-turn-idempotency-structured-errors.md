## feat(conversations): structured turn errors and idempotent turns (2026-10-06)

**Repo:** EDDI (`feat/turn-idempotency-structured-errors`, based on `feat/llm-turn-deadline`)

### What changed and why

R9 of the LLM turn-resilience plan: the caller contract. A production 5.x deployment's backend timed out at
60 s, retried, met `409 IN_PROGRESS`, and never saw the answer the engine finished a few seconds later; and a
failed turn gave the caller no machine-readable reason.

- **Structured errors.** A failed task now records `code` / `retryable` / `retryAfterMs` / `message` in its
  `taskErrors` entry (classified by `LlmFailureClassifier`; unrecognised failures are `TURN_FAILED`), and the
  `say` response gains a top-level `error` object for a turn in `ERROR`, plus a `Retry-After` header when the
  provider gave a hint. Streaming `done` carries it too. An unexpected server fault is a `500` with
  `{"error":{"code":"INTERNAL_ERROR",...}}` and no exception text. (`TurnError`, `LifecycleManager`,
  `ConversationService`, `RestAgentEngine`.)
- **Idempotent turns.** `Idempotency-Key` / `X-EDDI-Request-Id` on `say`, managed and streaming `say`
  (`TurnIdempotencyService`, `IdempotencyKeyHeaderReader`): a duplicate during the turn waits for it
  (bounded by the R1 header, capped at the agent timeout) and returns its result; a duplicate after completion
  within `eddi.turns.idempotency.ttl-seconds` (default 600, `0` = off) returns the stored answer. The key is
  stored on the step (`idempotency:key`, persisted); an in-memory map (Caffeine, bounded and expiring) holds
  in-flight turns plus a 30 s copy that bridges the moment before the persist. Keys are per conversation,
  <= 128 printable ASCII characters, else `400`.

### Design decisions

- **The status is unchanged.** A failed turn was already `200` with `conversationState: "ERROR"` (not a bare
  500), so `error` is added to that body rather than replacing it; clients that parse the snapshot keep
  working. The `/v1` adapter and the existing `409/429/503` bodies are untouched. The endpoints produce JSON
  only, so there is no text/plain variant to negotiate.
- **A failed turn is never replayed**, or the retry the error invites would return the same failure.
- **Multi-node:** waiting for a running turn only works on the node running it; the persisted lookup works
  everywhere.

### Follow-ups

- A duplicate of a running turn on another node (NATS) still meets `409`; a cross-node wait would need the
  event bus.
