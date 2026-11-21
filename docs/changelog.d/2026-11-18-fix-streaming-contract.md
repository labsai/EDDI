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
  the turn fails — for the providers whose langchain4j model always reports one (OpenAI, Azure OpenAI, Anthropic,
  Bedrock, Gemini, Vertex Gemini, Mistral, jlama) without a custom `baseUrl`. Every other provider, and any task with
  its own `baseUrl`, accepts a completion without one by default, warns once per endpoint and counts it
  (`eddi.llm.stream.no_finish_reason_accepted`). `streaming.requireFinishReason` or
  `eddi.llm.streaming.require-finish-reason` overrides that in either direction.
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
- **Cancel stops the provider.** A cancel wakes the waiting call at once (a listener on the turn's cancel flag; a 1 s
  poll remains for the cluster lease, which nothing signals), and the provider's stream is cancelled through
  langchain4j's `StreamingHandle` (1.23 hands it out with the first partial, in `PartialResponseContext`) — also when
  the call was given up on before its first partial: the handle is cancelled the moment it arrives. A cancel is typed
  (`CallCancelledException`), so no retry, no JSON-format fallback request and no cascade escalation follows it, and
  the retry loop checks the flag before every attempt and while it waits out a backoff or a `Retry-After`; `ObservableStreamingChatModel` now forwards that context — and tool-call and thinking
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
cancel no longer runs on the provider's callback thread under the stream lock: it is handed to a bounded executor
(`eddi.streaming.disconnect-concurrency`, 16) — the turn's in-memory cancel flag is raised first, on the thread
that noticed the disconnect, so the turn stops spending at once while the store work waits its turn — and the agent's
policy arrives with the turn, so a disconnect reads
nothing from a store. A client that leaves after the turn finished generating no longer cancels it — the answer is
stored.

**Not persisted on a cancelling disconnect:** the turn is discarded as before — the user's input, the partial answer
and the step are not stored. Keeping the input needs a persisted turn journal, a separate planned branch.

**Not charged either:** a streamed call that was cancelled, timed out or abandoned never delivered its usage (that
arrives with the completion), so the tokens the provider billed for it are in neither the tenant ledger nor
`maxCostPerRun`. Both undercount by those calls; see "Left open".

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
  refused `503` + `Retry-After` (3 to 8 s, spread so refused clients do not return together) +
  `{"code":"streams_at_capacity","retryable":true}` before its turn starts and before its access check reads the
  store. `/v1/chat/completions` with `stream: true` and A2A `message/stream` take a slot of the same cap
  (`kind` `openai` / `a2a`); the user-event and admin streams do not.
- **A node-wide buffer budget**, `eddi.streaming.max-pending-bytes-total` (268,435,456 characters): when it is spent,
  the stream holding the most unsent data is closed (`backpressure`) without cancelling its turn, and the write that
  hit the limit goes through — soft by the writes in flight. Keep-alive frames are reserved in the budget like every
  other write (they were released without being reserved, so the counter drifted below zero). "Bytes" in the
  streaming settings are characters of event data, not UTF-8 bytes. `/v1` and A2A streams hold a thread each while
  they run and are outside the buffer bounds; they count against the stream cap.
- **Certain cleanup.** Every terminal path releases the registry slot, the tick and the sink exactly once.
- **Meters:** `eddi.streaming.active{kind}`, `eddi.streaming.closed{kind,reason}`, `eddi.streaming.refused{kind}`,
  `eddi.streaming.first_token{kind}`.
- **Errors carry `code` and `retryable`** on every `error` event (opaque failures: `internal_error`, not retryable).
- **The group stream** gets keep-alive and disconnect detection (a leaving client releases the stream; the
  discussion continues and persists). No silence backstop: a member turn may legitimately run far longer than the
  agent timeout (a nested group, a human member), so the discussion ends its own stream.
- **Shutdown** sends every open stream a last `error` `server_shutdown` before closing it: `retryable: false` on a turn
  stream (the runtime lets running and queued turns finish, so the turn may still be stored; read the conversation
  first), `retryable: true` on a group discussion stream.
- **Exactly one terminal event per stream**: the turn, the silence backstop and the shutdown claim it by
  compare-and-set (`SseChannel.tryMarkTerminal`); a loser only closes.
- **A lost cluster lease** ends the stream with `turn_timeout`, `retryable: true` (the turn was discarded and is never
  stored), logged at WARN — not an opaque `internal_error`.
- **A silent stream whose client stopped reading** is closed by the tick once its keep-alive frames reach
  `eddi.streaming.max-pending-events`, instead of holding its slot indefinitely.
- **The tick cannot be killed by one stream**: a sink that throws ends its own stream (`failed`), and an `Error` while
  starting a turn still frees the stream's slot.
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
- A JSON-formatted (`convertToObject`) no-tools streaming turn — and a cascade's live final step — still streams token by
  token, as before. Only the tool loop's JSON rounds are buffered: such a round may turn out to be a tool call.
- `task_failed.errorType` values change from `timeout`/`transport`/`rate_limit`/`content_filter`/`unknown` to the
  `FailureClass` names; `task_failed.error` is the client-facing digest.
- New `done` field `finishReason`; new `cancelled` event; new conversation-output key `finishReason`. Clients ignore
  unknown events and fields.
- `cancelled` is sent only for a turn somebody stopped (`/cancel`, a cancelling disconnect, a GDPR stop) — recorded on
  the memory before the cancel flag goes up and read again at completion, so a user's stop never reads as a timeout. A turn the
  agent-timeout watchdog or a lost cluster lease interrupted ends with `error` `{"code":"turn_timeout","retryable":true}`
  and no `done`; it was not stored. The stream's own silence backstop also sends `turn_timeout`, but with
  `retryable: false` — that layer cannot know whether the turn was stored, so the client reads the conversation first.
- `finishReason: "empty"` is reported only when the whole turn shows nothing; a turn whose output another task filled
  carries none.

### Decisions

- **Route the no-tools path through the bridge inside `LegacyChatExecutor`** (the review's suggestion) rather than
  teaching the old loop the classifier: one retry loop, one bridge, one set of rules. The cascade's live step keeps one
  attempt and never salvages a partial.
- **`OTHER` counts as a finish reason**; only a missing one is incomplete. langchain4j maps Gemini's
  `MALFORMED_FUNCTION_CALL` and `RECITATION` to `OTHER` too, indistinguishable from a provider's benign unknown
  reason; failing on `OTHER` would fail healthy turns of every provider that adds a reason langchain4j does not know.
- **Empty answers are a flagged `done`, not an error**: thinking-only and tool-only turns are legitimate, and
  `responseValidation.onEmpty` already lets a designer make it an error.
- **The idle limit starts with the first stream event** so a reasoning model's long silence before its first token is
  not cut off.

**Files:** `modules/llm/impl/{AbandonableStreamCall, ToolLoopStreamingChatModel, StreamingLegacyChatExecutor,
StreamPolicy, StreamingSettings, ObservableStreamingChatModel, LlmTask}.java`, `modules/llm/model/{StreamingConfig,
LlmConfiguration}.java`, `engine/lifecycle/internal/LifecycleManager.java`, `engine/internal/RestAgentEngineStreaming.java`,
`engine/internal/{SseChannel, SseStreams, SseResponseHeadersFilter, RestGroupConversation, ConversationService,
ConversationStepRunner}.java`, `engine/memory/{ConversationMemory, IConversationMemory, TurnFinishReason}.java`,
`engine/runtime/{IAgent, internal/Agent, internal/Conversation}.java`, `integrations/openai/RestOpenAiAdapter.java`,
`engine/a2a/RestA2AEndpoint.java`, `configs/shared/CallCancelledException.java`, `modules/llm/impl/{CascadingModelExecutor,
LegacyChatExecutor, AgentExecutionHelper, ToolLoopRunner}.java`, `engine/memory/{MemoryKeys, ConversationMemoryUtilities}.java`, `configs/agents/model/AgentConfiguration.java`,
`configs/shared/RetryConfiguration.java`; docs: `langchain.md`, `llm-resilience.md`, `rest-api-reference.md`,
`conversations.md`, `configuration-reference.md`, `metrics.md`.

**Tests:** `StreamingContractParityTest` (the same scripted provider behaviours through the non-streaming, plain
streaming and tool-loop streaming paths), `AbandonableStreamCallTest`, `ToolLoopStreamingChatModelTest`,
`StreamingLegacyChatExecutorRetryTest` (rewritten: it pinned the salvage and the single attempt),
`LifecycleManagerTaskFailedEventTest`, `ConversationMemoryUtilitiesFinishReasonTest`, `SseChannelLoadTest` (4,000 simulated streams: healthy, stalled, leaving, never reading — bounded buffers, no cross-stream delay, full cleanup), `RestAgentEngineStreamingContractTest`, `RestGroupConversationStreamTransportTest`, `SseResponseHeadersFilterTest`; after
review: `StreamPolicyTest` (provider defaults, overrides both ways, the lenient meter, JSON forwarding, no credentials in
the warning), `CascadingModelExecutorCancelTest`, `LegacyChatExecutorCancelTest`, the late-handle and event-driven-wake
cases in `AbandonableStreamCallTest`, cancel before an attempt / during a backoff / during a `Retry-After` in
`RetryConfigurationTest` (whose `canBeSwitchedOff` no longer races a 200 ms hint against a 150 ms bound),
`ConversationMemoryTest` (cancel versus abandon), and in `SseChannelLoadTest` a throwing sink, the shutdown event,
`/v1` and A2A slots and concurrent senders whose writes complete on other threads, some exceptionally; a mass disconnect
through the bounded pool in `RestAgentEngineStreamingContractTest`. Adjusted where tests pinned the old outcome.

### Left open

- **Abandoned calls are not charged** (see above). Charging them needs the usage of a stream that never completed —
  an estimate from the forwarded text, or a provider usage API — and a decision on whether an estimate may count
  against a tenant budget.
- **Input loss on a cancelling disconnect** stays until the turn journal lands.
- **Merging with `fix/turn-not-stored-reported-done`.** Both branches touch `RestAgentEngineStreaming`. When the second
  one merges:
  - keep this branch's opaque error shape (`{"message":"Internal server error","code":"internal_error","retryable":false,"correlationId":…}`)
    and its `typedError(message, code, retryable)`;
  - keep that branch's per-reason `retryable` for `turn_not_stored`, passed explicitly through
    `typedError(…, retryable)` — do not add `turn_not_stored` to `RETRYABLE_ERROR_CODES`, which is for codes whose
    answer never depends on the turn;
  - close such a stream as `SseChannel.CloseReason.FAILED` (or add a `NOT_STORED` reason, with its `reason` tag in
    `docs/metrics.md`), never `COMPLETED`;
  - a deferred `done` or `turn_not_stored` must claim the terminal through `SseChannel.tryMarkTerminal` like every
    other terminal event;
  - this branch decides `turn_timeout` `retryable: true` from the turn's cancel flag read at snapshot conversion, before
    the persist decision; if that branch moves conversion or delivery after the write, `retryable` must come from the
    write outcome instead (its per-reason value). `server_shutdown` (may be stored) and lease loss (certainly not
    stored) are the two cases its per-reason `retryable` should own;
  - this branch already guarantees the two things that branch relies on: a disconnect after `onGenerationFinished`
    neither cancels nor discards the turn, and the silence backstop never announces `turn_timeout` as retryable.
