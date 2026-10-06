## ✨ feat(llm): recovery policies — re-ask the same model before escalating (2026-10-06)

**Repo:** EDDI (`feat/llm-recovery-policies`, stacked on `feat/llm-fallback-and-onerror` and `feat/llm-error-classification`)

### What changed and why

Item R5 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md)), plus the cost and injection guards of R16 that belong to it. A one-off format slip (prose around the JSON, an empty reply, a cut-off object) is usually fixed by the model that made it; escalating first pays for a different, possibly slower or pricier model on every hiccup. `responseValidation` gains a `retry` action and the engine now recovers in cost order: **answer → local repair (the R3 parser) → up to `maxRetries` same-model corrective re-asks → escalate to the next cascade step (which repeats this with its own re-asks) → `fallbackAction`.**

New `responseValidation` fields: `onInvalidJson` (default `ignore`), `onSchemaMismatch` (a reply that parses but breaks `responseSchema` / `nonBlankFields`, detected by R4; handled exactly like `onInvalidJson`, the R4 violation being the corrective message's reason), `onContextTooLong` (default `error`), `maxRetries` (default 1, clamped 0..3), `truncationRetryFactor` (default 2, clamped 1..4, never above 32768 tokens), `correctiveMessage`, `maxRetryCostUsd`, `minAttemptMs` (default 3000), `fallbackAction` (default `fallback`). `retry` is also accepted by `onEmpty`, `onTruncation`, `onContentFilter`. A cascade step gets `maxFormatRetries` (null inherits). Every default keeps today's behaviour: a task without a `retry` action is untouched.

- **New `FormatRetryRunner`** owns the same-model recovery for all three call sites: plain chat (`LlmTask`), each cascade step (`CascadingModelExecutor`, escalation reason `invalid_output` in the trace, in `eddi.llm.cascade.escalations` and in the `cascade_escalation` SSE event), and the final answer of a tool loop.
- **Truncation:** one re-ask with `maxOutputTokens` × factor, set on the `ChatRequest` (the cached model is not touched). The base cap comes from `maxTokens` / `maxOutputTokens` (Gemini) or Anthropic's built-in default; unknown base means no re-ask.
- **Context too long (B3):** one re-send with the history window halved (`maxContextTokens` / 2, else `conversationHistoryLimit` / 2, else half the conversation), rebuilt through `ConversationHistoryBuilder`, anchored first steps kept. Not available in tool mode.
- **Tool mode:** only the final model call is re-asked (`IAgentOrchestrator.reaskFinalAnswer`, a single request over system + history + the loop's tool exchange + corrective). `ToolLoopRunner` is never re-entered; a tool request in the re-asked answer is ignored. Proven by tests that count tool executions.
- **Time:** a re-ask starts only if the step's remaining time (its `timeoutMs`) is at least `minAttemptMs`; otherwise the cascade escalates at once. The budget is a `RemainingBudget` (`UNBOUNDED` by default, combined with the step timeout via `min`) — the hook the turn deadline (R1) feeds through the new `CascadingModelExecutor.execute(..., contextShrinker, turnBudget)` overload.
- **Breaker hook:** `FormatRetryRunner.ReaskGate` (`CascadingModelExecutor.setReaskGate`) lets R8 skip the re-ask for a model that has been failing the same way for most recent turns. Nothing sets it yet.
- **Streaming:** a task that may re-ask is buffered, not streamed; a new SSE event `llm_retry` `{reason, attempt}` (via `ConversationEventSink.onLlmRetry`) precedes each re-ask.
- **Observability:** `eddi.llm.recovery{action=retry|escalate, outcome, trigger}` (R6's meter) with outcomes `recovered`, `still_invalid`, `failed`, `skipped_budget|no_time|cost|breaker` and `invalid_output`; a panel on the Full Metrics dashboard; `llm:retry:<taskId>` = `{reasks, unresolved}` on the step. Token usage of every attempt is summed into the turn's usage, so re-asks appear in the audit ledger and in `maxCostPerRun`.

### Design decisions

- **What a re-ask sends.** Invalid JSON: original messages + the model's own bad reply as an **assistant** turn (cut at 16,000 chars) + one corrective **user** message. Echoing the reply is standard corrective prompting and keeps model text in the model's role. The user-role text is the configured template with `{reason}` replaced literally by `ModelOutputParser`'s own constant reason — no Qute, no model output, no user input (R16). Empty / truncated / filtered replies are re-asked with the original request unchanged (a corrective sentence adds nothing there).
- **Escalation vs the confidence ladder.** An unusable step is a candidate of last resort (confidence 0 in the best-so-far ranking); if the last step is unusable and an earlier step produced a usable answer, that answer wins.
- **`retry` after exhaustion.** The final `responseValidation` pass resolves a `retry` that is still failing to `fallbackAction`. `retry` on `onRefusal` / `onStreamingTimeout` is not supported and is treated as `warn`.
- **Bug fix on the way:** `LegacyChatExecutor`'s "provider may not support JSON mode" fallback re-sent the identical request after *any* non-retryable failure; a context-too-long, auth, quota or unknown-model refusal now propagates instead (the retry could not help, and the context recovery needs to see the failure).
- **`maxRetryCostUsd`** is priced from the task's / step's configured per-1M prices (zero when none). It does not consult the per-conversation `ToolCostTracker`.

### Not done / follow-ups

- The breaker (R8); the R1 deadline plumbing (only the `RemainingBudget` hook exists).
- Tool mode: no truncation or context-too-long re-ask (no finish reason from the loop; shrinking after tools ran would replay them).
- The HITL resume path (`executeResume`) keeps R3's local repair but has no re-asks.
- Manager: TypeScript types only (`responseValidation`, `CascadeStep.maxFormatRetries`); no form fields yet.
- Platform Operator prompt unchanged: it does not cover LLM response-validation troubleshooting today.

### Docs

[`docs/langchain.md`](../langchain.md) — "Recovery policies: retry where it helps"; [`docs/model-cascade.md`](../model-cascade.md) — "Format recovery and escalation"; [`docs/metrics.md`](../metrics.md).

**Files:** [`FormatRetryRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java), [`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java), [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LegacyChatExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LegacyChatExecutor.java), [`AgentOrchestrator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java), [`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`ConversationEventSink.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java)

```decision-log
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
```
