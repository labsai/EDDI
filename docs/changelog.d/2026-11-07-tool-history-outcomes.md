## 🐛 fix(llm): tool history reports what the trace records (2026-11-07)

**Repo:** EDDI (`fix/tool-history-outcomes`)

### What changed and why

`GET /llm/tools/history/{conversationId}` reported every tool call as a success.
`RestToolHistory` set `success=true` on each `tool_call` entry of the stored
trace and only ever copied a `tool_result` onto the last call when the tool
names matched. It ignored every `tool_error` (budget, tenant quota, argument
schema, approval-pause cap), the repeat-guard, call-cap and LAZY-activation
refusals and a blocked tool result, so a call refused before it ran did not
appear at all, and an HTTP 500 result read as a success. The totals were
computed from `executionTimeMs`, `error` and `cost` fields that nothing set, so
`totalExecutionTimeMs`, `hasErrors` and `totalCost` were always `0`, `false`
and `0`. The `callId` the trace has recorded since the tool loop gained call
identity was ignored, so two calls of the same tool could not be told apart.

The endpoint now builds its answer from the trace as written:

- A `tool_call` opens a call; its `tool_result` or `tool_error` closes it,
  found by `callId`, or, in an older trace without call ids, as the earliest
  open call of the same tool.
- A result's outcome comes from `ToolOutcomeClassifier`, the definition the
  repeat guard, the `tool_result` event status and the `toolQuotas` give-back
  already use. The class, its `Outcome` enum, `classify` and `isFailure` are now
  public for that.
- A `tool_error` with no open call, the `tool_repeat_refused`,
  `tool_not_activated` and `tool_call_capped` refusals, and the gated calls the
  HITL resume settles without running them (`hitl_rejected`,
  `hitl_not_executed`, `hitl_request_changed`, `hitl_self_conversation`,
  `hitl_self_ungating`, `hitl_outcome_unknown`) become failed calls of their
  own. A `tool_result_guardrail` with `action: block` fails the call it
  follows. A call the trace opened but never closed is reported as
  `success: false, error: "no_result"`, not as a success.
- Still not listed, because the trace cannot identify them: a call a
  `toolCallGuardrails` entry refused (one entry per finding, no call id) and an
  approved call replayed from the HITL journal (no result in the trace).

### Response shape (a REST client notices)

The body is now `{"toolCalls": [...], "hasErrors": bool}`, each call
`{toolName, callId, arguments, result, success, error}`. Removed, because the
trace never records them and they were always zero or empty: `totalExecutionTimeMs`,
`totalCost`, `cacheHits`, `cacheMisses`, `toolMetrics`, and per call
`executionTimeMs`, `cost`, `fromCache` and `timestamp`. `callId` is new. A
conversation's tool spend is still served by
`GET /llm/tools/costs/conversation/{conversationId}`. Neither the Manager nor
the Platform Operator calls the history endpoint.

`ToolExecutionTrace` (and its unused accumulator, `ToolMetrics` and
`ToolExecutionTraceTest`) is replaced by the `ToolHistory` record.

### Design decisions

- **The classifier, not a second rule.** Reading `success` from
  `ToolOutcomeClassifier` keeps one definition of a failed call; a local rule
  would drift from the repeat guard's.
- **Refusals always get their own entry.** `tool_repeat_refused`,
  `tool_not_activated`, `tool_call_capped` and the `hitl_*` refusals are only
  ever written for calls that never got a `tool_call`, so pairing them by tool
  name with an open call could only attach them to the wrong one.
- **No invented numbers.** Timing and cost would have to be added to the trace
  first; until then the history reports neither.

**Files:** [`RestToolHistory.java`](../../src/main/java/ai/labs/eddi/modules/llm/rest/RestToolHistory.java),
[`ToolHistory.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/ToolHistory.java),
[`ToolOutcomeClassifier.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolOutcomeClassifier.java),
[`RestToolHistoryTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/rest/RestToolHistoryTest.java),
[`metrics.md`](../metrics.md#rest-api-endpoints)
