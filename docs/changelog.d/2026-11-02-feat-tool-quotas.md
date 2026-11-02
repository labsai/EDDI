## ✨ feat(llm): `toolQuotas` — per-subject, per-window caps on tool calls (2026-11-02)

**Repo:** EDDI (`feat/tool-quotas`)

### What changed and why

An agent designer can now cap how often one **subject** — normally the end user — may make a tool run within a
time window, counted **across conversations and across nodes**: "at most 100 content improvements per user per
day". Until now EDDI had a per-tool-per-minute rate limit per conversation (`toolRateLimits`), a per-conversation
budget that a new conversation resets (`maxBudgetPerConversation`) and per-tenant quotas — none of them is per
user per day.

```json
"toolQuotas": {
  "subject": "{context.personId}",
  "tools": {
    "updateAction":        { "limit": 100, "window": "P1D", "pool": "improvements" },
    "updateSession":       { "limit": 100, "window": "P1D", "pool": "improvements" },
    "generateActionImage": { "limit": 30,  "window": "P1D" }
  }
}
```

- **Config** — `LlmConfiguration.Task.toolQuotas` ([`ToolQuotaConfig`](../../src/main/java/ai/labs/eddi/modules/llm/model/ToolQuotaConfig.java)).
  Keys are tool names with the same matching as `toolRateLimits` (dispatch name wins over slug) and work for every
  tool source, HTTP-call tools included. Tools of one `pool` share one counter. Validated at save time
  ([`ToolQuotaConfigValidator`](../../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaConfigValidator.java),
  wired into `LlmStore` like the `toolApprovals` check, so it is a 400): `limit` a positive integer, `window` an
  ISO-8601 duration from `PT1M` to `P31D`, every rule of a pool states the same limit and window. Windows are fixed
  and aligned to the UTC epoch (`P1D` resets at 00:00 UTC).
- **Subject** — an optional template rendered with the turn's template data; default is the conversation's user id.
  Managed conversations use composite user ids (`<user>-<company>-<program>`), so the default counts per program and
  the template is how a deployment counts per person. A blank render falls back to the user id, a caller with no user
  id is capped per conversation, a subject over 256 characters becomes a prefix plus a digest, and the subject is only
  ever logged as a short digest.
- **Counts real executions only.** The reservation is made in one place, `ToolExecutionService.executeToolWrapped`,
  after the rate limit and the cache and immediately before the tool runs (`ToolQuotaGate.reserve`). Cache hits,
  rate-limited calls, calls refused by the conversation or tenant budget, rejected approvals and calls that never ran
  are never counted. A failed call gives its reservation back (`ToolQuotaGate.settle`): an exception, a tool-reported
  error, an HTTP non-2xx or transport failure — judged by the same `ToolOutcomeClassifier` the repeat guard uses. A
  **timed-out** call keeps its count, because the abandoned worker cannot be stopped and may still finish the side
  effect.
- **Atomic, no overshoot, multi-node.** A new counter store, `IToolQuotaStore`, with a MongoDB implementation
  (`findOneAndUpdate` with a `count < limit` filter and upsert; the duplicate-key race on the window's first insert is
  retried as a conditional update; TTL index on `expiresAt`) and a PostgreSQL one (`INSERT … ON CONFLICT DO UPDATE …
  WHERE used < ? RETURNING used`; an hourly sweep removes expired rows). Selected in `DataStoreProducers` like the other
  stores. With three calls left, a batch of five parallel calls runs exactly three.
- **Refusal** is a tool result the model sees, a final `NOT_EXECUTED` envelope with the limit and the reset time
  ("Not run: the daily limit for this tool (100) is reached; it resets at 2026-11-03T00:00:00Z …"), not a thrown error;
  the turn carries on and the model is not invited to retry.
- **Human approval.** Approved calls run on the resume path through the same `ToolLoopRunner.executeToolCall` →
  `executeToolWrapped` pipeline as live calls, so the quota is applied (and counted) at execution time there too. A
  gated call proposed while its quota is already exhausted is refused immediately by a read-only pre-check
  (`ToolLoopRunner.refuseExhaustedGatedCalls`, before the pause is built), so a human is never asked to approve what
  will then be refused; pausing counts nothing.
- **Fails closed.** If the store cannot answer, a stored rule cannot be applied or the subject template fails to
  render, the call is not run and the model is told the limit could not be checked. One WARN a minute with agent,
  conversation and a digest of the user id; every failure is counted.
- **Metrics** — `eddi.tool.quota.decisions{tool, outcome}` (`allowed`, `refused`, `precheck_refused`, `unavailable`,
  `released`) and `eddi.tool.quota.store.failures{operation}`; documented in `docs/metrics.md` and charted on the Full
  Metrics Reference dashboard.
- **GDPR** — counters are keyed by a user identifier, so
  [`ToolQuotaGdprParticipant`](../../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaGdprParticipant.java)
  joins the erasure cascade (delete by subject) and the export (the user's counters). If a deployment's subject
  template derives an identifier other than the conversation's user id, erasure deletes the counters whose subject
  equals the erased user id; counters hold counts only and expire an hour after their window ends.

### Design decisions

- The reservation sits inside `executeToolWrapped`, not in `ToolLoopRunner` around the supplier: only there is it after
  the cache check, and a refusal returned from inside the supplier would have been cached and charged as a result.
- A rule keyed by a slug is one counter for all of that tool's operations (unlike the per-dispatch-name rate-limit
  bucket), because "30 web searches a day" is naturally per tool; `pool` is the explicit way to share across tools.
- Pre-check only refuses an *already exhausted* quota; it does not reserve slots for pending approvals.
- A call with no quota rule takes exactly the old 10-argument `executeToolWrapped` path.

### What's next / not done

- The Manager has no form for `toolQuotas` yet; its LLM task editor spreads the task object on save, so a configured `toolQuotas` is preserved.

**Files:** [`ToolExecutionService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolExecutionService.java),
[`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`ToolQuotaService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaService.java),
[`MongoToolQuotaStore.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/quota/MongoToolQuotaStore.java),
[`PostgresToolQuotaStore.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/quota/PostgresToolQuotaStore.java),
[`docs/langchain.md`](../langchain.md)

```decision-log
| 2026-11-02 | `toolQuotas` reserve inside `ToolExecutionService.executeToolWrapped` (after cache, before execution), release on failure, keep on timeout | A quota that counts cache hits or refused calls, or that cannot give a failed call back, charges users for nothing; a timed-out call may still have completed its side effect | Reserving in `ToolLoopRunner` around the supplier (cannot see the cache; the refusal would be cached and charged); counting at result time (overshoots under concurrency) |
```
