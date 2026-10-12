## LLM tool execution: cache, failures, caps, retries and dead code (2026-10-02)

**Repo:** EDDI · **Branch:** `fix/llm-tool-execution` · Review 2026-10-02 §2 #14, #20, §4.3.

**What changed**

- **Conversation-bound tools are never cached** (`ToolCacheService.isConversationBound`). Derived from the
  tool class — a tool object that is not a shared CDI bean is built per turn with one conversation's state —
  replacing the hand-kept `STATEFUL_TOOL_NAMES`. `ReadAttachmentTool` was missing from that list, so
  `listAttachments` in conversation B of a user returned conversation A's files for five minutes.
- **Failures are typed and never cached.** New `ToolFailureException`; the web search, web scraper, weather
  and PDF tools throw it for upstream failures instead of returning `"Error: …"` strings that were cached
  (up to 1 h for the scraper). Reflected built-ins now propagate exceptions
  (`ToolObjectReflector.executorFor`), and an unexpected exception reaches the model only as
  `Error: tool '<name>' failed with an internal error (reference <id>)` — the detail is logged.
- **jsoup ReDoS:** `extractWithSelector` refuses regex pseudo-selectors and `~=`, caps selectors at 256
  characters, and parses on a bounded platform pool with a hard deadline
  (`eddi.tools.web-scraper.parse-threads`, `…parse-timeout-ms`). `ToolExecutionService`'s javadoc no longer
  claims an abandoned worker is free — a CPU-bound one holds a virtual-thread carrier.
- **Tool-call caps:** `maxToolCallsPerIteration` (20) and `maxToolCallsPerTurn` (100) on the LLM task;
  refused calls get a `NOT_EXECUTED` result. `maxToolIterations` is clamped to 100.
- **Token estimator:** an OpenAI-family model unknown to jtokkit (Azure deployment names, compatible
  endpoints) falls back to the approximate estimator instead of failing every turn with
  `maxContextTokens`; the estimator cache is bounded.
- **One retry layer:** provider SDK retries are off (`maxRetries = 0`) on every non-streaming builder, so a
  provider 500 costs `retry.maxAttempts` requests (3) instead of 9.
- **Non-streaming errors:** an ERROR turn's REST response carries a top-level `error`
  (`taskId`, `taskType`, `errorType`, `message`) matching the streaming `task_failed` event.
- **MCP client:** the client cache key includes `timeoutMs`.
- **Telemetry / LAZY:** cascade steps carry the OpenTelemetry context; `discover_tools` is parsed before
  truncation.
- **Logs:** model-controlled values in built-in tool logs are sanitized and at DEBUG.
- **Schemas:** every built-in parameter has a real `@P` description; optional parameters are
  `required = false`. To allow this, `quarkus.mcp.server.support-langchain4j-annotations=false` — the
  extension read a langchain4j `@P` value as the MCP argument *name*, and these tools were never MCP tools.
- **Dead code removed:** `EddiToolBridge` (and its ThreadLocal hand-off), the uncalled / reflection-only
  `AgentOrchestrator` delegators (tests now target `ToolLoopRunner`, `ToolLoopResumer`,
  `ToolApprovalGateSupport`, `HttpCallToolsProvider`), the `executeSingleToolCall` seams, and the
  `ToolCostTracker.trackToolCall(String,String)` / `ToolRateLimiter.tryAcquire(String)` overloads. The two
  live self-conversation guards in `ToolLoopRunner` share one helper.

Docs: [langchain.md](../langchain.md) (caps, retry/error semantics, tool cache, web scraper, built-in
schemas), [configuration-reference.md](../configuration-reference.md),
[mcp-server.md](../mcp-server.md).

**Not changed:** the MCP discovered-tool cache stays keyed per config, not per caller — discovery never
carries a caller-bound or `PER_USER` credential (`authorizationHeader` sends it unauthenticated), so the
tool list does not vary by caller. The resume-path self-conversation check in `ToolLoopResumer` keeps its
own copy (it audits a human approval); P4 owns those lines.

```decision-log
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
```
