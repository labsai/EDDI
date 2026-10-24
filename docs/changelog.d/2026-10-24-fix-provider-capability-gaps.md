## 🐛 fix(llm): per-cascade-step vision, one repeat-guard test, consistent trace-header presence (2026-10-24)

**Repo:** EDDI (`fix/provider-capability-gaps`)

### What changed and why

Follow-ups from the review of #1056, #1057 and #1062.

- **Attachments are decided by the endpoint actually called.** `LlmTask` forwarded attachments once, with the task's own type and parameters, so a cascade step on its own `openai-compatible` endpoint with `vision=true` got no images, and a step without it inherited the task's. The JSON response format already used the step's parameters. `CascadingModelExecutor` now takes an optional per-step prompt provider; `LlmTask` supplies one that re-forwards the attachments with the step's type, model and vision decision, but only when the task or the step is `openai-compatible` and the endpoint differs (type, model or vision decision). Non-cascade tasks and `type: openai` cascades take exactly the old path. The re-forward is non-recording (`AttachmentForwarder.forward(..., record=false)`), so the turn's attachment errors and meters are not counted twice. A rebuilt-prompt retry after a context-too-long error (`onContextTooLong: retry`) still forwards with the task's decision; that rarely-hit path is unchanged.
- **Repeat guard, gated batch.** `ToolLoopRunnerToolPolicyTest` gains a test for the ungated half of a gated (HITL) batch, the second `executeGuarded` call site, which no test drove.
- **MCP trace headers.** `McpToolProviderManager.withTraceHeaders` checked `traceparent` and `tracestate` one at a time, while `TracePropagation.headersMissingFrom` is all-or-nothing. It now reuses the shared rule (`TracePropagation.withoutIfAnyPresent`): an author's own `traceparent` or `tracestate` suppresses both.
- **Refuted:** `create_api_agent` already runs the same `OutboundTargetPolicy` check on `llmBaseUrl` as `setup_agent` does on `baseUrl` (`AgentSetupService.createApiAgent`, covered by `AgentSetupLlmBaseUrlPolicyTest`); no change.

### Design decisions

- The per-step decision compares (type, model name, effective vision) of the step with the task's and re-forwards only on a difference, so the common cascade keeps the one prompt it always had.

### Verification

Mutation-checked: bypassing the per-step provider, the guard at the gated-batch site, and the shared presence rule each fail the new tests.
