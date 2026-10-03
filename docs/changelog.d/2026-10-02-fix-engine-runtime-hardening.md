## 🐛 fix(engine): watchdog really stops a timed-out turn; client errors get 400s with a message; readable, fail-fast startup (2026-10-02)

**Repo:** EDDI (`fix/engine-runtime-hardening`) — review findings of 2026-10-02 (§4.1 engine core, top issue #13 part 2, harness defects 2 and 3).

### What changed and why

**Agent-timeout watchdog** ([`ConversationStepRunner`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java))
- On `systemRuntime.agentTimeoutInSeconds` the watchdog only cancelled the future: an interrupt, which any layer below can swallow, plus the token that suppresses the final persist. A pipeline that swallowed the interrupt kept running every remaining lifecycle task — model calls, tool calls, long-term property writes — and, because the turn left `inFlightConversations` the moment the watchdog returned, neither `/cancel` nor the GDPR `stopInFlightWork` sweep could reach it.
- The watchdog now raises the memory's cooperative-cancel flag before the interrupt, through one small method, `ConversationStepRunner.abandonTurn(memory)` (meant to be the single entry point for every "this turn is no longer wanted" signal that does not come from a caller — a lost cluster lease can call it too). `LifecycleManager` checks the flag before every task, and `Conversation.isTurnDiscarded` before the long-term write-back.
- An abandoned turn stays registered until its body actually returns (`InFlightRegistration`): the body unregisters itself; a body that never started is unregistered at once. Removal stays value-conditional. The HITL resume path's watchdog sets the flag too.
- A dispatcher that dies in the watchdog's own abandon branch (a datastore outage is the likeliest cause of a timeout) no longer unregisters a body that is still running, and an abandoned turn's failure repairs the conversation-state cache, which the pipeline's own error handling had left at `ERROR` while the store said `EXECUTION_INTERRUPTED`.
- A turn already flagged cancelled no longer writes `ERROR` from its failure callback. Live, the interrupt aborts the model call and the next store access on the interrupted thread throws; on 6.5.0 that `ERROR` write raced the watchdog's `EXECUTION_INTERRUPTED` and sometimes won.
- Test against the real `BaseRuntime` and a real `LifecycleManager`: [`ConversationWatchdogAbandonTest`](../../src/test/java/ai/labs/eddi/engine/internal/ConversationWatchdogAbandonTest.java) — a task that swallows the interrupt, a task after it that must never run, the GDPR sweep reaching the zombie while it runs and not after.

**Client errors** ([`engine/exception`](../../src/main/java/ai/labs/eddi/engine/exception/))
- `POST /agents/{malformed-id}` answered 500 with an ERROR stack trace (the say path is resumed through an `AsyncResponse`, so `IllegalArgumentExceptionMapper` never saw it); it is now 400, like `GET` on the same id. `ConversationService` no longer logs it at ERROR either.
- A query or path parameter of the wrong type (`?conversationState=BOGUS`, `?version=abc`, `?limit=x`) answered the JAX-RS-mandated `404 Not Found`. `ParameterConversionExceptionMapper` recognises exactly those 404s (raised by the framework's parameter handler with the conversion failure as cause) and answers 400 naming the value and, for an enum, the legal ones. Every other 404 is unchanged.
- Bodies the JSON reader cannot read — malformed JSON, a value of the wrong shape (an invalid `context`), nesting past the parser's depth limit — answered 400 with an empty body. `UnreadableBodyExceptionMapper` and `MismatchedJsonInputExceptionMapper` now give the line/column or JSON path and what belongs there, never a Java type.
- All of these, and `IllegalArgumentExceptionMapper`, use one JSON body, `{"error": "bad_request", "message": "…"}` ([`ErrorResponses`](../../src/main/java/ai/labs/eddi/engine/exception/ErrorResponses.java)) — the keys the quota and capacity mappers already use. The IAE mapper used to send the bare message under the endpoint's `application/json` type, which did not parse.
- Under `/v1` (the OpenAI-compatible API) the same mappers answer in the OpenAI envelope, `{"error": {"message", "type", "code"}}`, so SDKs keep reading `error.message`; `ErrorResponses.badRequest(message, uriInfo)` chooses by path.
- `ConversationService.readConversationLog` lowercased `outputType` before its null check. Over REST the `@DefaultValue` covers an empty parameter, so this was only reachable from code; it is null-safe now, and a blank type means text like an empty one.

**Startup**
- The vault and compliance banners reached container logs as one line full of literal `\n`: the console filter escapes line breaks inside a record (CWE-117). [`LogBanner`](../../src/main/java/ai/labs/eddi/utils/LogBanner.java) logs them one record per line.
- `AuthStartupGuard` and `HighValueSurfaceGuard` now observe `StartupEvent` at `PLATFORM_BEFORE`. At the default priority the vault/index observers ran first, so a bare start with no reachable database spent 30 s per operation on MongoDB and exited with a MongoDB error — the guard's own message never appeared.
- `HV000271` (×3 per boot): `@Valid` moved from `DiscussRequest.attachments`' list to its type argument.
- `OpenTelemetry API usage issue detected` at every boot came from Quarkus' Prometheus exemplar sampler, which hands `Span.fromContextOrNull` a null context on any thread without one. [`NullSafeExemplarSamplerProducer`](../../src/main/java/ai/labs/eddi/engine/runtime/NullSafeExemplarSamplerProducer.java) replaces it (`@Alternative`) with the same sampler minus the null.

**Cleanups**
- `ConversationService.say` and `sayStreaming` were two copies of ~170 lines. One admission path, `admitTurn`, now serves both; what differs (where the outcome goes, the progress-event sink, the streaming error callback) is a small `TurnListener`.
- The environment-qualified `IConversationService` overloads with no caller outside the service (`readConversation`, `getConversationState`, `sayStreaming`, undo/redo and their availability checks) left the interface; `say(Environment, …)` stays (schedules and A2A use it).
- `undo`/`redo`/`isUndoAvailable`/`isRedoAvailable` by conversation id loaded the snapshot twice; once now.
- Removed `DeepCopyUtil` (used only by its own test). Moved `ACCEPTED_END_REASONS` out from between `endActiveConversations` and its Javadoc.

### Verified, not changed
- **Secret input** (`secretInput` context flag): verified live against 6.5.0 and this branch — the stored conversation (all collections), REST snapshots, the conversation log, the audit trail, the next turn's LLM history and the server log never contain it. Residual, by design: the model receives the plaintext in the current turn (documented: tasks see it while the turn runs), so a model that echoes it echoes it into that turn's live SSE `token` frames, to the caller who sent it; nothing of it is persisted.

- **Live, the watchdog's interrupt is honoured** by EDDI's own model path (the body ends at once with "Chat request interrupted"), so on a default deployment the swallowed-interrupt case is the exception the flag guards against, proven by the unit test. What does keep running is the langchain4j request on its `eddi-chat-timeout` thread: it times out at 60 s and retries, so an abandoned turn still costs a second model call (seen identically on 6.5.0 and this branch). That belongs to the LLM module and is left to its PR.

### Not done here (and why)
- Postgres: a malformed conversation id still answers 500 on the Postgres backend (id parsing differs; owned by the datastore PR).
- `ThreadContext` and the unused `threadBindings` parameter of `IRuntime.submitCallable`: the removal touches ~40 files, including `RestAgentAdministration`'s deploy path (fix/hitl-scheduling) and the NATS coordinator (the cluster PR). Left for after those merge.
- `IAgentFactory`'s never-thrown checked exceptions: owned by fix/hitl-scheduling.
- Other REST catch-alls that turn an `IllegalArgumentException` into 500 sit in schedules, groups and import (other PRs' areas); see the evidence file for the probe.

```decision-log
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
```
