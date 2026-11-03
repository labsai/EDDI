## feat(streaming): resume stream, tool_result events, stored call identity, toolDisplay (2026-11-03)

**Repo:** EDDI (`feat/resume-stream-tool-events`)

### What changed and why

Streaming clients (a live chat with plain-language activity lines and an approval card) could stream a `say` but not the resume of a human-approval pause, saw a tool start but never finish, and lost call identity in the stored trace, so a receipt could not be rebuilt after a reload.

- **`POST /agents/{conversationId}/resume/stream`** (SSE, same roles, body and query flags as `/resume`). Admission is the plain `/resume`'s own method (`RestAgentEngine.resumeStreaming`), so every refusal (400/403/404/409/503, pause mismatch, secret-context error) is the same plain HTTP error and nothing is written to the stream first; the keep-alive starts only after the decision was accepted. `ConversationHitlService.resumeConversationStreaming` installs the same event sink a streamed `say` uses on the resumed memory; failure, lease loss and thrown errors go to `onError`, a dropped resume ends with a typed `error` (`conversation_ended`, `awaiting_approval`, `conversation_busy`, `resume_dropped`), and a re-pause is still delivered only after it is persisted. `hitl_resume {pauseId, verdict}` is sent once, from the resume callable, before any event of the continued turn. `eddi.streaming.cancel-on-client-disconnect` applies through the shared `SseStream`.
- **Events (additive):** `tool_call {tool, callId, displayName?, display?}`; new `tool_result {tool, callId, status: ok|error|not_executed|rejected, durationMs}`, emitted once per call from a single `try/finally` in `ToolLoopRunner.executeToolCall` and for declined/skipped gated calls from `ToolLoopResumer`; new `approval_required {pauseId, pauseType, callCount}` right before `done` on `/stream` and `/resume/stream` when the turn ends `AWAITING_HUMAN`. `ConversationEventSink` and `StreamingResponseHandler` gained default overloads, so existing implementors (group member observers, `toolCallObserver`) are untouched.
- **Stored trace identity:** `tool_call`, `tool_result` and `tool_error` entries carry `callId`; approved gated calls add `hitl_approved {tool, callId}`; `hitl_rejected` adds the per-call reviewer note and the call's arguments, redacted with `SecretRedactionFilter`.
- **`toolDisplay`** (new optional field on the LLM task): `{"<tool>": {"label": "...", "fields": ["..."]}}`. `label` static, at most 120 characters; `fields` an allow-list of at most 3 top-level argument names; only string/number/boolean values are shown, redacted and cut to 80 characters. Validated at save time (`ToolDisplayConfigValidator`, wired in `LlmStore`), keyed like `toolQuotas`.

### Design decisions

- `task_complete.toolTrace` already carries redacted arguments and results. The new events do not, but a client that forwards events to end users must still drop `toolTrace`; this is now stated in `rest-api-reference.md`, `hitl.md` and `langchain.md`.
- `hitl_resume` is emitted when the resume callable starts rather than at submit, so a resume the coordinator drops (pause restored) never announces itself.
- A streamed `say` that is skipped keeps its old `done` answer; only the resume stream turns a skipped resume into a typed `error` event.
- `onToolCall(String)` keeps working: the new three-argument form defaults to it.

### Files

[`RestAgentEngineStreaming.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java), [`ConversationHitlService.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`ToolLoopResumer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopResumer.java), [`ToolDisplayConfigValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolDisplayConfigValidator.java), [`hitl.md`](../hitl.md)
