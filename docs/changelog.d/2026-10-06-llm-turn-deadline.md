## feat(llm): turn deadline end to end, cancellation hygiene, deploy-time warnings (2026-10-06)

**Repo:** EDDI (`feat/llm-turn-deadline`, based on `feat/llm-error-classification`)

### What changed and why

Retries, cascade steps and HTTP calls each carried their own timeouts, and they multiply: a caller with a
60-second timeout could give up while the engine was still working, and the late answer was stored but never
seen. This is R1, R10 and R15 of the LLM turn-resilience plan.

- **One deadline per turn (R1).** New agent-level `turnDeadlineMs` (default unset = today's behaviour) and
  `turnDeadlineReserveMs` (default 1500), and a per-request header `X-EDDI-Turn-Deadline-Ms` on `say`, the
  managed-conversation endpoint, the streaming `say` and the OpenAI-compatible `/v1` adapter. The deadline
  is `arrival + min(config, header)`. **Decision:** a header alone enables a deadline when the agent configures
  none (the caller knows how long it will wait), capped at 10 minutes; when the agent configures one the header
  can only shorten it. A malformed, negative or zero header is ignored with a debug line, never an error.
  The value is carried on `IConversationMemory#getTurnDeadline()` as a **transient** field (never persisted),
  set by `ConversationService` before every `say`/`rerun` turn and always overwritten, so a reused memory never
  carries a previous turn's deadline. `TurnDeadline` ([`TurnDeadline.java`](../../src/main/java/ai/labs/eddi/configs/shared/TurnDeadline.java))
  takes an injectable `Clock`.
- **Consumers.**
  - `RetryConfiguration.executeWithRetry(…, TurnDeadline)`: the first attempt always runs while time remains; a
    retry starts only if at least 3000 ms plus the reserve remain; each attempt runs on a virtual thread and is
    abandoned and cancelled when it overruns what is left after the reserve (a cached model carries a fixed provider
    timeout, so this is the only per-attempt bound available); a backoff or a provider `Retry-After` that would
    leave no room for the next attempt is not slept - the loop stops and rethrows the classified failure.
  - `CascadingModelExecutor`: `maxTotalDurationMs = min(config, remaining - reserve)`, step timeouts clamped to the
    remaining budget, no new step under 3 s plus the reserve (best answer so far is returned).
  - `ApiCallExecutor`: the per-call timeout is clamped to the remaining budget; `retryApiCallInstruction` retries
    that cannot fit are skipped; the first call is never skipped unless the deadline has already passed, when it
    fails fast with a clear message.
  - `LlmTask` only passes `memory.getTurnDeadline()` into the three non-cascade `LegacyChatExecutor` calls; the
    tool loop does the same from the memory it already holds.
- **Cancellation hygiene (R10).** The model built for a cascade step has its request `timeout` clamped to the
  step's `timeoutMs` (rounded up into coarse buckets - whole seconds to 10 s, 5 s above - so a shrinking deadline
  does not mint a cached model per millisecond; the registry cache key already includes `timeout` and stays
  bounded). A streamed step keeps its own bound. New counter `eddi.llm.cancelled{scope=attempt|cascade_step}`.
- **Deploy-time warnings (R15), never blocking.** Logged once per agent deployment: a model `timeout` longer than
  its cascade step's `timeoutMs`; `convertToObject` with no fallback path; for agents with `turnDeadlineMs`, a static
  worst case (LLM retries x timeout, cascade budget, slowest httpcall of each httpcalls step with its retries) above
  the deadline minus the reserve. The `onError: fallback` half of the fallback check is read reflectively because
  that field ships in a parallel change.
- **Metrics.** `eddi.llm.turn.deadline.exceeded{stage}` and `eddi.llm.cancelled{scope}`, with two dashboard panels
  and entries in `docs/metrics.md`.
- **Docs.** `docs/langchain.md` (Turn Deadline), `docs/model-cascade.md`, `docs/httpcalls.md`,
  `docs/conversations.md`, `docs/metrics.md`.

### Follow-ups

- The SSE streaming model path (`StreamingLegacyChatExecutor`) keeps its own timeout and is not yet bounded by the
  deadline; a HITL resume starts a turn without one.
- Warning "onInvalidJson retry with maxRetries 0" lands with the format-retry change (R5).
- The Platform Operator prompt does not document agent-level resilience settings today, so it is unchanged.
