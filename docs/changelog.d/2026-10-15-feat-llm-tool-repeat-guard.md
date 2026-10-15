## feat(llm): tool repeat guard refuses identical failing tool calls (2026-10-15)

**Repo:** EDDI (`feat/llm-tool-repeat-guard`)

### What changed and why

Inside the iteration cap a model could re-send the same failing call (a 403, a 404, a schema-rejected body) every round, burning cost and rate-limit budget and repeating side effects on non-idempotent endpoints. The tool loop now keeps a per-run `ToolRepeatGuard`: a call whose last identical call failed, with no other call succeeding since, is answered `NOT_EXECUTED` without running and traced as `tool_repeat_refused` (metric `eddi.tool.repeat.refused`, tags `source`, `reason`). After `maxRefusalsPerTurn` (default 3) refusals the loop stops asking the model and ends the turn with a fixed message.

**Behaviour change: the guard is ON by default.** An agent with no `toolRepeatGuard` now gets it with the defaults (`maxIdenticalFailures` 2, `maxIdenticalCalls` unlimited, `maxRefusalsPerTurn` 3). It only refuses calls that already failed identically with no progress in between. `"toolRepeatGuard": {"enabled": false}` restores the previous behaviour exactly. See [Repeated failing tool calls](../langchain.md#repeated-failing-tool-calls).

- `ToolOutcomeClassifier` classifies the RAW tool result (before the provenance envelope): HTTP `httpCode` 2xx success, 408/425/429/5xx retryable, other statuses terminal; `Error:` strings and `{"error":...}` retryable, except rejected arguments and unknown tools (terminal); loop-level refusals (budget, tenant quota, expired deadline, `NOT_EXECUTED`/`DENIED`) never count as failures. MCP `isError` results reach the loop as an internal-error string and are retryable.
- `ToolLoopRunner.executeToolCall` returns governed and raw results; `executeSingleToolCallResult` still returns the governed string (resume path unchanged).
- Guard state is per run of the loop: a HITL resume and each cascade step start fresh. Failures were already never cached.
- Platform Operator prompt updated (revision bumped).

**Files:** [`ToolRepeatGuard.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolRepeatGuard.java), [`ToolOutcomeClassifier.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolOutcomeClassifier.java), [`ToolRepeatGuardConfig.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/ToolRepeatGuardConfig.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java)

**Follow-up:** the Manager editor control for `toolRepeatGuard` is not part of this change.
