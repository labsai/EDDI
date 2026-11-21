## 🐛 fix(openai): a `/v1` stream nobody reads any more cancels its turn (2026-11-12)

**Repo:** EDDI (`fix/v1-cancel-turn-on-disconnect`)

### What changed and why

A streamed `/v1/chat/completions` turn ran to completion after its client had gone. `OpenAiSseWriter` latched a failed write as `broken` and dropped every later frame, but nothing read the flag: the turn kept generating (and billing) model output and kept starting tool calls, side-effecting ones included, for an Open WebUI user who had pressed stop or closed the tab. The same happened after the request timeout — the client had its `504` error event, and the turn ran on. A turn still queued behind another turn of the chat when the request timed out was also run later, for nobody.

- **Disconnect and timeout cancel the streamed turn**, through the mechanism the native `/agents/{id}/stream` endpoint already uses: `IConversationService.cancelConversation(id, CANCEL_GRACEFUL, actor)`, with actor `system:client-disconnect` (as on `/stream`) or `system:openai-request-timeout`. It is gated by the same switch, `eddi.streaming.cancel-on-client-disconnect` (default `true`). The writer now exposes `isBroken()`; the bridge checks it after each token, requests the cancel and stops waiting for the turn, so the request also releases its thread and its `max-concurrent-requests` permit straight away.
- **A turn still queued when the request times out is dropped unrun.** Both `/v1` paths now stamp `InputData.callerGivesUpAtEpochMs` with the request's deadline, as the native REST `say` does, and the engine skips a queued turn whose caller has given up (`eddi.conversation.skip-turns-of-timed-out-callers`). A non-streamed turn dropped that way answers `504`, not `429` busy.
- **A chunk that cannot be serialized no longer reads as a disconnect.** Jackson's `JsonProcessingException` is an `IOException`, and the writer caught it with the write failures, latching `broken`. Serialization is now caught on its own and only drops that chunk.
- A stream that ended because its client left is counted as `eddi_openai_requests_total{outcome="client_gone"}` instead of `ok`.

### Design decisions

- **Cooperative, never mid-call.** The cancel raises the turn's cancel flag, which the pipeline reads before every lifecycle task, every tool-loop model round and every tool call. So no further model round and no further tool call starts — but a tool call already running is never interrupted (its side effects are not left half-done by us), and the model call in progress runs to its end, its tokens dropped. The cancelled turn is **discarded, not stored**: the conversation settles on `EXECUTION_INTERRUPTED`, a state the next `say` accepts, so the chat continues normally without the stopped exchange.
- **The cancel only targets this request's own turn.** It is requested only once the turn has shown it is running (its first `onTaskStart` or token): while it is queued, the in-flight turn of that conversation is another request's, and the cancel would stop that one. A queued turn that times out is left to the caller-gives-up stamp; if it starts in between, its first task start triggers the cancel. Never after a terminal callback, so a write that fails while reporting a pause cannot cancel the pending approval. At most once per request.
- **Same switch as `/stream`, no new property.** The trade-off is the one documented for `/stream`: on, a dropped connection stops the spend but loses the turn; off, the turn finishes and persists for nobody.
- **Not done:** a non-streamed request whose client disconnects is not detected — the JAX-RS method blocks on the turn and the framework gives it no disconnect signal without a Vert.x close handler, which cannot be verified without a live server. A non-streamed turn that is already running when the request times out is not cancelled either, because the plain handler has no "turn started" callback to tell it from a queued one (as on the native REST `say`). And the disconnect is noticed only at the next token write: `/v1` writes no keep-alive frames, so a silent stretch (a tool chain before the first token) runs until something is written.

### Relation to open PRs

#1355 (`fix/v1-bridge`) ends a stateless conversation whose streaming body never ran; #1364 refactors the LLM streaming skeleton (`AbandonableStreamCall`). Neither cancels a running turn when a `/v1` client leaves. This change touches neither's lines and merges in any order.

**Files:** [`OpenAiConversationBridge.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java), [`OpenAiSseWriter.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiSseWriter.java), [`open-webui-integration.md`](../open-webui-integration.md), [`configuration-reference.md`](../configuration-reference.md), [`metrics.md`](../metrics.md); tests in `OpenAiStreamAbandonmentTest` (new) and `OpenAiSseWriterTest`.
