## 🐛 fix(streaming): one streaming contract for every LLM path, and a transport that scales (2026-11-18)

**Repo:** EDDI (`fix/streaming-contract`)

Findings B1-B9, B11 and B12 of the November 2026 code review, and the live streaming reproductions S-1 to S-5: a
broken provider stream was stored and shown as a complete answer, the no-tools streaming path retried differently from
every other path, tokens reached the client after an error, `length` / `content_filter` never reached the native
client, `/cancel` and a client disconnect did not stop the provider, and `task_failed` carried an uninformative class
and a message meant for administrators.

### The contract

Every streaming path — a task without tools, the tool loop, and a cascade's live final step — now judges a streamed
model call by the same rules ([`langchain.md` → The streaming contract](../langchain.md#the-streaming-contract)):

- **A stream succeeds only on `onCompleteResponse` with a finish reason and no error.** A stream that just ends (no
  `finish_reason`, no `[DONE]`) is an `IncompleteStreamException`: retried while nothing reached the client, otherwise
  the turn fails. Servers that never send a finish reason opt out with `streaming.requireFinishReason: false` or
  `eddi.llm.streaming.require-finish-reason=false`.
- **The first terminal callback wins** and ends the call: nothing is forwarded after an error or a completion
  ([`AbandonableStreamCall`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AbandonableStreamCall.java)). A `null`
  partial no longer appends the text "null".
- **Retry parity.** The no-tools streaming path runs through
  [`ToolLoopStreamingChatModel`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopStreamingChatModel.java)
  inside [`LegacyChatExecutor`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LegacyChatExecutor.java), so it
  inherits `RetryConfiguration.executeWithRetry`: three attempts by default (it made one), only classes
  `LlmFailureClassifier` calls retryable (it retried a 401), `Retry-After`, jitter and the turn deadline
  (`X-EDDI-Turn-Deadline-Ms` was ignored on this path). Retries happen only before the first forwarded token.
  [`StreamingLegacyChatExecutor`](../../src/main/java/ai/labs/eddi/modules/llm/impl/StreamingLegacyChatExecutor.java)
  lost its own retry loop.
- **A failure after text was sent fails the turn.** The partial text is kept as error detail under
  `llm:partial_output:<taskId>`, never stored as an answer later turns replay. The old salvage stays available per
  task: `streaming.onFailureAfterText: "keepPartial"`. A timed-out partial still goes to an active
  `responseValidation.onStreamingTimeout` policy.
- **`length` / `content_filter` reach the client**: `done` carries `finishReason`, and the turn's conversation output
  keeps it under `finishReason` (the non-detailed snapshot passes it). The tool loop's streamed final round records it
  too. An empty answer is no longer silent: `finishReason: "empty"`.
- **Cancel stops the provider.** The cancel flag is polled while a streamed call waits (every 200 ms) and the
  provider's stream is cancelled through langchain4j's `StreamingHandle` (1.23 hands it out with the first partial, in
  `PartialResponseContext`); `ObservableStreamingChatModel` now forwards that context — and tool-call and thinking
  deltas — when response logging is on. The agent stream ends a cancelled turn with a `cancelled` event, and its
  `done` no longer carries the discarded outputs.
- **Idle-gap timeout**: `streaming.idleTimeoutSeconds` / `eddi.llm.streaming.idle-timeout-seconds` (default 30 s,
  `0` = off), counted from the first stream event, besides the wall-clock backstop.
- **`task_failed`** carries the `LlmFailureClassifier` class (`RATE_LIMITED`, `TRANSIENT`, …) as `errorType` and the
  redacted digest the REST `error.message` carries — not the audit summary (URLs, class names). It is not sent for a
  cancelled turn. The `eddi.pipeline.task.errors` metric keeps its old `error.type` values.
- `RetryConfiguration.backoff` no longer swallows an interrupt (it returns `-1`, "stop").

### Disconnect policy (B8)

A new agent field `streamingClientDisconnect` (`cancel` | `finish`) overrides `eddi.streaming.cancel-on-client-disconnect`
for the agent's `/stream` turns; the global default is unchanged (`true`). The resume stream keeps its own switch. The
cancel no longer runs on the provider's callback thread under the stream lock: it is handed to a virtual thread.

**Not persisted on a cancelling disconnect:** the turn is discarded as before — the user's input, the partial answer
and the step are not stored (the tenant ledger and the metrics are still charged for what the provider billed). Keeping
the input needs a persisted turn journal, a separate planned branch.

### A transport that scales (B7, B11)

The agent turn stream, its resume and the group discussion streams now write through one transport,
[`SseChannel`](../../src/main/java/ai/labs/eddi/engine/internal/SseChannel.java) on the registry
[`SseStreams`](../../src/main/java/ai/labs/eddi/engine/internal/SseStreams.java):

- **No thread per stream.** Writes never wait for the client; keep-alive, the silence backstop and disconnect
  detection are one fixed-rate entry per stream on one process-wide daemon scheduler thread; disconnect handling (a
  store read and compare-and-set) runs on a virtual thread started only when a client actually leaves.
- **Bounded buffering.** Each write is counted until its `CompletionStage` completes; a client more than
  `eddi.streaming.max-pending-events` (512) events or `eddi.streaming.max-pending-bytes` (1 MiB) behind is
  disconnected (reason `backpressure`, the stream's disconnect policy applies) instead of being buffered for.
- **No head-of-line coupling.** The write lock is held only for the non-blocking hand-over; the shared tick only
  `tryLock`s it, so a stalled stream never delays another stream's heartbeat or end.
- **Capacity.** `eddi.streaming.max-concurrent-streams` (5000, `0` = unlimited) open streams per node; the next is
  refused `503` + `Retry-After: 5` + `{"code":"streams_at_capacity","retryable":true}` before its turn starts.
- **Certain cleanup.** Every terminal path releases the registry slot, the tick and the sink exactly once.
- **Meters:** `eddi.streaming.active{kind}`, `eddi.streaming.closed{kind,reason}`, `eddi.streaming.refused{kind}`,
  `eddi.streaming.first_token{kind}`.
- **Errors carry `code` and `retryable`** on every `error` event (opaque failures: `internal_error`, not retryable).
- **The group stream** gets keep-alive, disconnect detection (a leaving client releases the stream; the discussion
  continues and persists) and the silence backstop (`group_error` after the agent timeout plus one interval).
- **Proxy headers (B7):** [`SseResponseHeadersFilter`](../../src/main/java/ai/labs/eddi/engine/internal/SseResponseHeadersFilter.java)
  adds `Cache-Control: no-cache` and `X-Accel-Buffering: no` to every `text/event-stream` response that did not set
  them.

### Upgrade notes

- A provider or OpenAI-compatible server that never sends `finish_reason` now fails every streamed turn until the task
  sets `streaming.requireFinishReason: false` (or the deployment sets `eddi.llm.streaming.require-finish-reason=false`).
  langchain4j maps OpenAI's `finish_reason` and Ollama's `done_reason` (an Ollama server old enough not to send it
  needs the opt-out); jlama reports one too.
- A no-tools streaming task without a `retry` block now makes up to three attempts on a 429/5xx/timeout before the first
  token (it made one).
- A mid-stream error, a malformed frame or a timeout after the first token now fails the turn (`ERROR`, `task_failed`,
  `done` with `error`) instead of storing the fragment. Set `streaming.onFailureAfterText: "keepPartial"` for the old
  behaviour.
- A JSON-formatted (`convertToObject`) no-tools streaming turn is emitted as one token when it completes, like the tool
  loop's JSON rounds, rather than as streamed raw JSON.
- `task_failed.errorType` values change from `timeout`/`transport`/`rate_limit`/`content_filter`/`unknown` to the
  `FailureClass` names; `task_failed.error` is the client-facing digest.
- New `done` field `finishReason`; new `cancelled` event; new conversation-output key `finishReason`. Clients ignore
  unknown events and fields.

### Decisions

- **Route the no-tools path through the bridge inside `LegacyChatExecutor`** (the review's suggestion) rather than
  teaching the old loop the classifier: one retry loop, one bridge, one set of rules. The cascade's live step keeps one
  attempt and never salvages a partial.
- **`OTHER` counts as a finish reason**; only a missing one is incomplete.
- **Empty answers are a flagged `done`, not an error**: thinking-only and tool-only turns are legitimate, and
  `responseValidation.onEmpty` already lets a designer make it an error.
- **The idle limit starts with the first stream event** so a reasoning model's long silence before its first token is
  not cut off.

**Files:** `modules/llm/impl/{AbandonableStreamCall, ToolLoopStreamingChatModel, StreamingLegacyChatExecutor,
StreamPolicy, StreamingSettings, ObservableStreamingChatModel, LlmTask}.java`, `modules/llm/model/{StreamingConfig,
LlmConfiguration}.java`, `engine/lifecycle/internal/LifecycleManager.java`, `engine/internal/RestAgentEngineStreaming.java`,
`engine/internal/{SseChannel, SseStreams, SseResponseHeadersFilter, RestGroupConversation}.java`, `engine/memory/{MemoryKeys, ConversationMemoryUtilities}.java`, `configs/agents/model/AgentConfiguration.java`,
`configs/shared/RetryConfiguration.java`; docs: `langchain.md`, `llm-resilience.md`, `rest-api-reference.md`,
`conversations.md`, `configuration-reference.md`, `metrics.md`.

**Tests:** `StreamingContractParityTest` (the same scripted provider behaviours through the non-streaming, plain
streaming and tool-loop streaming paths), `AbandonableStreamCallTest`, `ToolLoopStreamingChatModelTest`,
`StreamingLegacyChatExecutorRetryTest` (rewritten: it pinned the salvage and the single attempt),
`LifecycleManagerTaskFailedEventTest`, `ConversationMemoryUtilitiesFinishReasonTest`, `SseChannelLoadTest` (4,000 simulated streams: healthy, stalled, leaving, never reading — bounded buffers, no cross-stream delay, full cleanup), `RestAgentEngineStreamingContractTest`, `RestGroupConversationStreamTransportTest`, `SseResponseHeadersFilterTest`; adjusted where tests pinned the
old outcome.
