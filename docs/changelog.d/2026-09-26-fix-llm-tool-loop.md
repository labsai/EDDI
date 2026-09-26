## 🐛 fix(llm): tool loop, cascade and summarizer correctness — no tool replay, summary in agent mode, bounded telemetry (2026-09-26)

**Repo:** EDDI (`fix/llm-tool-loop`)

Findings H11a, H11b, H12, M-L1 to M-L6, the tool-cache side-effect finding, M-T3, L1 to L4 and
the optional RAG provenance item from the 2026-09-25 code review. Each one below: what was wrong,
what changed, where.

### H11a — a retry re-executed every tool the turn had already run

`ToolLoopRunner.runToolCallLoop` ran the whole multi-iteration loop inside
`AgentExecutionHelper.executeWithRetry`, which restarts the lambda from `initialMessages`. A 429
or 5xx on iteration 4 re-ran every tool from iterations 0-3: a second POST, a second created agent,
a second memory write, each charged and traced twice. The retry now wraps the single
`chatModel.chat(chatRequest)` call. A retry resends that one request, with every tool result
gathered so far in its transcript, so the model continues where it stopped. Exhausted and
non-retryable model failures keep their messages ("Agent execution failed after N attempts" /
"Agent execution failed: …"). Unchecked exceptions outside the model call are wrapped the same way,
and an abandoned-thread interrupt still becomes a plain `LifecycleException` so the turn settles to
ERROR (`wrapLoopFailure`). The one shape change: a plain `LifecycleException` raised inside the loop
("Agent execution cancelled (interrupted)…", or one thrown by a tool executor) now propagates as
itself instead of under an extra "Agent execution failed:" prefix. Nothing matches on those messages
and the terminal state is the same. The 60-second backoff ceiling still covers the whole turn:
every model request of one loop run draws from one shared budget
(`RetryConfiguration.executeWithRetry(…, long[] sharedBackoffMs)`), so per-request retries cannot
sleep a minute each. `AgentOrchestratorToolCostTest`: the two
tests that described the old replay semantics are rewritten, and
`retryAfterToolRunDoesNotReplayTheTool` / `exhaustedRetriesFailWithoutReplay` are added.

### H11b — cascade escalation re-ran the tool loop; `maxCostPerRun` ignored tools and the judge

- **Carry-forward.** An agent-mode step now returns its tool exchange (the assistant messages that
  requested tools, plus the tool results) on `ExecutionResult.toolExchange`. When the cascade
  escalates, the next step starts from the conversation plus that exchange, so it sees the order
  that was already placed and does not place it again. The new cascade field
  `carryToolResultsOnEscalation` defaults to `true`; set it to `false` to go back to starting each
  step from the conversation alone, for example when a cross-provider escalation rejects the earlier
  provider's transcript. The returned tool trace now covers every step's calls, not only the
  winning step's.
- **Cost ceiling.** `maxCostPerRun` is checked against step token cost + judge token cost + tool
  cost (`RunTotals.runCostUsd()`). `JudgeModelConfig` gains `inputPricePer1M` / `outputPricePer1M`,
  and negative values fail deployment in `CascadeConfigValidator`, the same rule as the other new
  price fields. `ConfidenceEvaluator.EvaluationResult` carries the judge's token usage, including
  when the judge's reply was unusable and scoring fell back to the heuristic. Judge spend is
  included in `runCostUsd` / `cascadeCostUsd`, and each step's trace entry gets `judgeTokenUsage`
  and `judgeCostUsd`.
- **Carried calls always have ids.** `ToolLoopRunner.toolExchange` gives a null-id call (Ollama and
  Gemini bindings pass the provider's id through, often null) a synthetic `gen-` id and gives the same
  id to the result that answers it, because OpenAI and Anthropic reject a tool call without one.
- **Cross-provider rejection.** A step carrying an exchange whose **first** model request is
  rejected as a bad request (HTTP 400/422, `InvalidRequestException`) is retried **once from the
  conversation alone**, the pre-carry behaviour. The first-request condition is carried by a marker
  exception from `ToolLoopRunner` (`FailedBeforeToolsException`): a failure after that request was
  answered may follow executed tools, and a retry could repeat their side effects. Authentication,
  unknown-model and content-filter failures, timeouts, transient 429/5xx and HITL pauses never
  trigger it. It is logged, recorded on the step trace as
  `carriedToolExchangeRejected`, and counted as
  `eddi.llm.cascade.step.errors{type=carried_exchange_rejected}`. This was chosen over carrying only
  between identical provider types: same-provider escalations (the common case) keep the no-replay
  guarantee, and cross-provider ones still escalate instead of silently stopping at the cheap step's
  answer.
- **Timed-out and failed steps' tool cost** now counts toward the ceiling. It is measured as the
  conversation's tracked tool-cost delta around the step (`IAgentOrchestrator.conversationToolCost`),
  so a step whose result never arrives still charges its tools.
- **A pause inside an escalated step** keeps the earlier steps' tool calls in the batch trace, ahead
  of its own.
- **Not carried (follow-up):** the partial tool *exchange* (transcript) of a step that timed out or
  threw. Its future is cancelled and its result lost, so the next step starts without it.
  `docs/model-cascade.md` says so.

### H12 — the rolling summary and the gap marker were dropped in agent mode

`ConversationHistoryBuilder` folds the summary into the system message, and `LlmTask` stripped
**every** `SystemMessage` for the agent path, then handed the tool loop the *pre-summary* prompt.
The summary was lost, along with every turn it covered (skipSteps had already removed them from the
history), the windowing gap marker and any system-role log parts. New helpers
`ConversationHistoryBuilder.composeSystemMessage` and `withoutLeadingSystemMessage` fix this: the
agent path (standard, skipCascade and cascade agent steps) now gets the composed system message and
the history minus only that one leading message.

### M-L1 — a HITL resume of a cascade-step pause continued on the base model

`CascadingModelExecutor` records the pausing step on the batch (`PendingToolCallBatch.cascadeStepIndex`,
a new nullable field; older batches read null). `LlmTask.executeResume` rebuilds that step's model
through the shared `CascadingModelExecutor.resolveStepModel`. If the index no longer exists (the
config was redeployed during the pause), it falls back to the base model and logs a WARN. When the
continuation pauses *again* (a second gated call), `executeResume` copies the index onto the new
batch, so the second resume also stays on the step's model.

### M-L2 — the summarizer model override wrote only `modelName`

New `ModelParameterKeys.withModel(params, provider, model)`. The override is written to every model
key the inherited parameters already carry, and always to the provider's own key (`model` for
Ollama, `modelId` for Bedrock / HuggingFace / Vertex, `deploymentName` for Azure, `modelName` for
the rest) — parameters inherited from a task on another provider can carry only that provider's
key. No other key is added, since it would trip the unrecognised-parameter WARN. Both `SummarizationService` and `ToolResponseTruncator` use it.

### M-L3 — unbounded summarizer batch

`ConversationSummaryConfig` gains `maxTurnsPerUpdate` (20) and `maxCharsPerUpdate` (60 000). A
backlog is caught up in bounded batches, each batch is shortened turn by turn to fit, and a single
turn larger than the budget is cut (the cut notice counts toward the budget). The stored `summary_through_step` records what the summary
actually covers. A window with no renderable text is stepped over without an LLM call, so a bounded
batch cannot stall on it.

### M-L4 — `convertToObject` failed the turn on `[` or on malformed JSON

`LlmTask.convertResponseToObject` (used by both the live and resume paths) turns an object into a
Map and an array into a List. Plain text, or JSON the model truncated, stays the raw string with a
WARN. It is tested against the real `JsonSerialization`, not a mock.

### M-L5 — the JSON-mode fallback fired on any `LifecycleException`

`LegacyChatExecutor` now rethrows interrupts and retryable failures (timeout, 429, 5xx, already
retried) instead of paying for a second plain request. Only a failure that could mean "JSON mode is
unsupported" falls back.

### M-L6 — a transient store error cached an empty snippet map for five minutes

`PromptSnippetService` no longer caches a failed load. It serves the last successfully loaded map
(kept across invalidation and expiry). After a failure the store is left alone for 10 seconds
(`FAILURE_BACKOFF_MS`), so an outage does not put a driver timeout on every turn; an explicit
`invalidateCache()` retries at once. A reload is single-flight (`ReentrantLock`): a caller that
finds one in flight gets the last good map instead of starting its own store read, so an outage
cannot tie up a burst of request threads before the first failure sets the back-off. Unchecked store exceptions are covered too. Conflict note: `fix/template-injection` (C4c) also touches this class.
This change is confined to `getAll` / `loadAllSnippets`.

### Tool cache: HTTP / MCP / A2A tools were cached by default (NEW), and the key lacked agent and source (M-T3)

- `ToolCacheService.mayCache`: tools from `http`, `mcp` and `a2a` sources are cached **only when
  the task names the tool in `toolCacheScopes`**. That existing field doubles as the explicit
  opt-in. Built-ins stay cacheable by default, and the stateful built-ins are still never cached.
- `ToolCacheService.namespacedScopeTag` adds `src:<source>` and `agent:<agentId>` to the key. The
  agent is omitted only for a built-in on the `global` scope, whose definition is identical for
  every agent. This means a built-in on the `user` or `conversation` scope (websearch, weather, …)
  is no longer shared between different agents of the same user, which intentionally lowers its
  hit rate. Pick `global` for a built-in whose result depends only on its arguments.
- **Behaviour change:** an agent that relied on caching an HTTP/MCP/A2A tool must now list it in
  `toolCacheScopes`. Existing cache entries become unreachable (new key shape) and expire by TTL.

### JSON-mode fallback and 5xx

A self-hosted OpenAI-compatible gateway that answers an unsupported `response_format` with a 5xx
used to fall back to a plain request. It now fails the turn, and `docs/langchain.md` names the fix:
`jsonResponseFormat: "off"` on the task.

### L1-L4 — LLM telemetry

- **L1** — the `model` meter tag is capped at 100 distinct values per node (the rest share
  `model="other"`), and names are cut to 128 characters. Spans keep the real name.
- **L2** — langchain4j has no `ModelProvider` value for Jlama or HuggingFace (verified against the
  1.20.0 jars: neither overrides `provider()`), so both reported `OTHER`. The decorators now register
  `LlmTelemetryListener.forModelType(listener, modelType)`, which tags `provider` with EDDI's model
  type whenever langchain4j reports `OTHER` or null.
- **L3** — the finding was partly wrong: the provider's own timeout does still reach the listener's
  `onError`, just late (and never for a stream that simply stalls). EDDI now counts its own backstop
  firing as `eddi.llm.stream.timeouts{path=legacy|tool_loop}` (added to the dashboard and to
  `docs/metrics.md`).
- **L4** — span status and exception event carry a secret-redacted, 256-character excerpt and the
  error class. `recordException` (full message + stack trace, every cause included) is no longer
  used.

### RAG provenance (optional item)

Retrieved context (vector RAG and httpCall RAG) is wrapped by `ToolResultProvenance.markRetrieved`
in a `[retrieved context — source '…' …]` … `[end of retrieved context]` envelope before it joins
the system prompt. Delimiter-shaped text **inside** the body (the closing line, or an opening
header, in any letter case) has its bracket rewritten to `[quoted: `, so a retrieved document
cannot close its own envelope and have the rest read as unmarked instructions. The tool-result
envelope (`mark`) gets the same treatment; it had the same gap before this branch. The new task field `markRagProvenance` defaults to marking; `false` restores the
bare append.

### Config surface / compatibility

New, all optional with safe defaults: `modelCascade.carryToolResultsOnEscalation` (true),
`modelCascade.judgeModel.inputPricePer1M` / `outputPricePer1M`,
`conversationSummary.maxTurnsPerUpdate` / `maxCharsPerUpdate`, `markRagProvenance` (true). The
persisted `PendingToolCallBatch` gains nullable `cascadeStepIndex`. Stored configs, ZIP imports and
REST shapes are otherwise unchanged. The Manager's LLM editor does not expose the new fields yet;
they are editable in the JSON view (a follow-up for the Manager editors branch).

`ToolLoopRunner.toolExchange` gives a rebuilt (null-id) call with null or blank arguments `{}`,
since Gemini parses carried arguments as a JSON object. The stream-timeout dashboard panel shows its
legend, now that it plots two series.

**Docs:** `docs/langchain.md`, `docs/model-cascade.md`, `docs/security.md`, `docs/metrics.md`,
`docs/monitoring/eddi-full-metrics-dashboard.json`.

**Files:** [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`ConversationHistoryBuilder.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java),
[`ToolCacheService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolCacheService.java),
[`LlmTelemetryListener.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTelemetryListener.java),
[`ModelParameterKeys.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelParameterKeys.java).

```decision-log
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
```
