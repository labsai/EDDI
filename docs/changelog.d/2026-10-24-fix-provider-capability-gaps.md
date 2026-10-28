## 🐛 fix(llm): per-cascade-step vision, one repeat-guard test, consistent trace-header presence (2026-10-24)

**Repo:** EDDI (`fix/provider-capability-gaps`)

### What changed and why

Follow-ups from the review of #1056, #1057 and #1062.

- **Attachments are decided by the endpoint actually called.** `LlmTask` forwarded attachments once, with the task's own type and parameters, so a cascade step on its own `openai-compatible` endpoint with `vision=true` got no images, and a step without it inherited the task's. The JSON response format already used the step's parameters. `CascadingModelExecutor` now takes an optional per-step prompt provider; `LlmTask` supplies one that re-forwards the attachments with the step's type, model and vision decision, but only when the task or the step is `openai-compatible` and the endpoint differs (type, model or vision decision). Non-cascade tasks and `type: openai` cascades take exactly the old path. The re-forward is non-recording (`AttachmentForwarder.forward(..., record=false)`), so the turn's attachment errors and meters are not counted twice. A rebuilt-prompt retry after a context-too-long error (`onContextTooLong: retry`) still forwards with the task's decision; that rarely-hit path is unchanged.
- **Input guardrails no longer undo it.** The step's prompt is screened by carrying the task-level rewrites onto it (`LlmTask.screenedStepPrompt`): each text part the screening changed is rewritten by its text in the step's message, while the step's own image and document parts and engine notes are kept. `GuardrailService.reapply` is not used for a step, because it swaps the whole last user message for the task's screened one, attachments included.
- **Text only a step's prompt carries is screened.** An attachment can turn into different text per endpoint (a PDF extracted to text for a step without native PDF, a file inlined for one step and skipped for the task, a vision-skip note naming the file). That text is current-turn content the task-level screening never saw, so `GuardrailService.screenUnseenText` screens it with **every** input guardrail, including the classifier, which skips earlier messages for cost. It is quiet (no outcome, metric, SSE event or audit entry; the turn's findings were recorded once); its cost is added to the turn. `redact` rewrites the text; a block, or a guardrail that cannot decide (a classifier that is down or unparseable) according to its `failMode` (a `redact` guardrail without `failMode` lets the text through), replaces it with the withheld placeholder. This differs from the task path on purpose: a BLOCK on attachment text there blocks the whole turn, while on a cascade step's step-only text the part is withheld and the turn continues, because step-only parts are never replayed into later history. The cost of a classifier call is counted whether it flags or clears the text. A verdict is kept for the turn by text, so several cascade steps carrying the same part cost one classifier call. When the step's prompt cannot be matched to the task's, the step gets the task's screened prompt.
- **What is recorded.** The attachment errors written to the step data and the `eddi.attachment.*` meters come from the task-level pass (the task's endpoint), not from the cascade step that answered. A step's own forwarding is non-recording.
- **Repeat guard, gated batch.** `ToolLoopRunnerToolPolicyTest` gains a test for the ungated half of a gated (HITL) batch, the second `executeGuarded` call site, which no test drove.
- **MCP trace headers.** `McpToolProviderManager.withTraceHeaders` checked `traceparent` and `tracestate` one at a time, while `TracePropagation.headersMissingFrom` is all-or-nothing. It now reuses the shared rule (`TracePropagation.withoutIfAnyPresent`): an author's own `traceparent` or `tracestate` suppresses both.
- **Refuted:** `create_api_agent` already runs the same `OutboundTargetPolicy` check on `llmBaseUrl` as `setup_agent` does on `baseUrl` (`AgentSetupService.createApiAgent`, covered by `AgentSetupLlmBaseUrlPolicyTest`); no change.

### Design decisions

- The per-step decision compares (type, model name, effective vision) of the step with the task's and re-forwards only on a difference, so the common cascade keeps the one prompt it always had.

### Verification

Mutation-checked: bypassing the per-step provider, the guard at the gated-batch site, and the shared presence rule each fail the new tests.
