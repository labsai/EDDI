## ✨ feat(llm): configurable, JSON-aware fallback answer and task-level `onError` (2026-10-06)

**Repo:** EDDI (`feat/llm-fallback-and-onerror`, based on `feat/llm-output-parsing-never-throws`)

### What changed and why

Items R6 and R7 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md)). A production 5.x deployment showed that when a model call fails after its retries, the user gets an HTTP 500 and a conversation in `ERROR`, and that the one fallback the engine had (the `responseValidation` `fallback` action) was a fixed English sentence, not JSON-shaped under `convertToObject`, and was fed back to the model on the next turn.

**R6 — the fallback is configurable.** `responseValidation` gains:

- `fallbackMessage`: a Qute template (author-written config, so it is rendered) with the task's template data — `{#if properties.language == 'de'}…`, `{snippets.x}`. Default is the old sentence; a template that fails to render or renders blank degrades to it.
- `fallbackField`: under `convertToObject` the fallback is stored under `responseObjectName` as `{"<fallbackField>": "<message>"}`, so `{properties.aiOutputObject.htmlResponseText}` in a `postResponse` output template renders it unchanged. The raw `langchain:<type>:<taskId>` slot holds the same JSON.
- `fallbackQuickReplies`: stored the way `OutputGenerationTask` stores quick replies (`quickReplies:<…>` entry plus the `quickReplies` conversation-output list); `value`/`expressions` are templates.

The existing `fallback` action of `responseValidation` goes through the same code (new `LlmFallbackHandler`), so it is wrapped and flagged too.

**The model never sees the fallback.** The step gets `llm:fallback:<taskId>` = `true`, and `ConversationHistoryBuilder` (via `ConversationLogGenerator.generate(…, skipFallbackOutputs)` and the summarized-window loop) leaves out the assistant message of such a turn. The user's input stays; the following user message is merged into it so the history keeps strictly alternating roles (Anthropic rejects consecutive user messages). The transcript returned by the REST log and the UI is unaffected. `OutputItem` gains an optional `fallback` field (omitted from JSON unless set) which the task sets on the output item it adds under `addToOutput`; the step flag is the authoritative signal because a fallback rendered by a `postResponse` never passes through an `OutputItem` the task controls.

**R7 — `onError`.** Task-level `onError: {"action": "fallback" | "error"}`, default `error` (today). With `fallback`, any exception from the model phase (model construction, `preRequest`, cascade, tool loop, plain chat, and a `responseValidation` action of `error`) produces the fallback; the task returns normally, so the turn completes with 200 and the conversation is not `ERROR`. Never absorbed: `ToolApprovalRequiredException` (HITL tool pause), `ConversationPauseException`, `LifecycleInterruptedException`, cancelled group member turns, `CancellationException`/`InterruptedException` (looked for through the whole cause chain), a cancelled conversation, an interrupted thread.

- `llm:error:<taskId>` = `{class, message}`: root-cause simple class name; message redacted with `SecretRedactionFilter`, URLs replaced by `[url]`, cut at 200 characters. Kept in one helper (`LlmFallbackHandler.describe`) so R2's failure classes can replace it.
- Commit Flags: data the failed phase wrote to the step is marked uncommitted (same rule as strict write discipline). The model phase writes little before it succeeds, so in practice this guards partial data from `preRequest` and tool traces.
- Metric `eddi.llm.recovery{action=fallback,outcome=served,trigger=onError|validation}`; charted on the Full Metrics dashboard, listed in `docs/metrics.md`.

### Design decisions

- The guard wraps from model construction to the end of the model-phase branches; a few declarations moved above it (no behaviour change). With `onError` absent the guard rethrows unchanged.
- A served fallback is not run through response validation (an author's apology could match a refusal prefix) and not parsed (it would count as `invalid`/`valid` in `eddi.llm.output`). The audit ledger's `audit:model_response` is not written for it: it records what the model said.
- `fallbackField` only applies under `convertToObject`; otherwise the fallback stays a plain string.
- The audit ledger has no error field, and `LifecycleManager` still writes the task's normal audit entry; the failure is on the step as `llm:error:<taskId>`. Extending `AuditEntry` was out of scope.

### Not done / follow-ups

- The HITL resume continuation (`executeResume`) does not apply `onError` (R12).
- The rolling summary and the recall tool still include fallback turns.
- Manager: only the TypeScript types were extended; no form fields.
- Platform Operator prompt unchanged: it does not cover LLM response-validation troubleshooting today.

### Docs

[`docs/langchain.md`](../langchain.md) — "Fallback answers and `onError`"; [`docs/metrics.md`](../metrics.md).

**Files:** [`LlmFallbackHandler.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmFallbackHandler.java), [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`ConversationLogGenerator.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationLogGenerator.java), [`ConversationHistoryBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java), [`OutputItem.java`](../../src/main/java/ai/labs/eddi/modules/output/model/OutputItem.java)
