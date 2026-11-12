## 🐛 fix(llm): a model cascade keeps the agent's workflow and MCP tools (2026-11-06)

**Repo:** EDDI (`fix/cascade-workflow-tools`)

### What changed and why

A task whose tools come only from its workflow — an `httpcalls` or `mcpcalls` step, with the task's own
`tools`, `enableBuiltInTools` and `a2aAgents` unset (for example an agent built by `setup_agent` with
`mcpServerUrls` and built-in tooling off) — gets those tools on the single-model path, because the tool
loop offers everything its assembly finds. With a `modelCascade` it got none: the cascade decided tool
mode from `Task.isAgentMode()`, which sees only the task's own configuration, so every step ran as a
plain chat and the model answered without the tools it was written for. The same predicate made
`enableInAgentMode: false` not skip the cascade for such a task, sized the opt-in cost reservation at
one round instead of `1 + maxToolIterations`, and decided whether a spend refusal's fallback is shown.

- **One answer per turn.** `IAgentOrchestrator.offersTools(task, memory)` (new; `AgentOrchestrator`
  answers from `buildToolSetup`, the assembly the loop itself runs on) says whether the task has a tool
  other than the `answerMode: "tool"` reply tool. `LlmTask` computes `taskHasTools` once — the config check
  first, the orchestrator only when a cascade is configured or the reservation is on, so a plain chat
  agent pays nothing — and uses it for the cascade's tool decision (new `taskHasTools` argument on the
  `CascadingModelExecutor.execute` overload `LlmTask` calls), for `skipCascade`, for
  `ModelCallBound.phaseMaxUsd` (now takes `toolLoop`) and for a spend refusal's `usedToolMode` (which
  asks the orchestrator itself when the plan did not need to).
- **Config-time copy folded.** `CompatibilityRules` and `EmailAgentToolsInspector` carried the same
  "own tools or workflow httpcalls/mcpcalls" expression; it is now `Task.usesTools(hasHttpCalls,
  hasMcpCalls)`. `Task.isAgentMode()`'s Javadoc says what it is (the task's own tools) and points at
  both.

**Behaviour change.** An existing cascade on a task with workflow-only tools now runs the tool loop in
each step: its answer streams as one chunk, and `structured_output` confidence is downgraded to
`judge_model` / `heuristic`, as for any agent-mode cascade. With `enableInAgentMode: false` such a task
now takes the single-model path. A cost reservation for a workflow-tool task, or an `answerMode:
"tool"` task, now holds `1 + maxToolIterations` rounds.

**Files:** [`IAgentOrchestrator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/IAgentOrchestrator.java),
[`AgentOrchestrator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`ModelCallBound.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelCallBound.java),
[`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java),
[`CompatibilityRules.java`](../../src/main/java/ai/labs/eddi/engine/compat/CompatibilityRules.java),
[`EmailAgentToolsInspector.java`](../../src/main/java/ai/labs/eddi/integrations/email/EmailAgentToolsInspector.java),
[`model-cascade.md`](../model-cascade.md#agent-mode).

### Design decisions

- **Ask the assembly, do not re-derive it.** Widening `isAgentMode()` (or a new config predicate) would
  have been a second copy of the discovery rules that cannot see attachments, LAZY placeholders or
  runtime MCP discovery, and would drift. `offersTools` is the loop's own answer.
- **Asked once, only when it matters.** The answer is computed in `LlmTask` and passed down, not asked
  again inside the cascade; a task with no cascade and no reservation never triggers the extra assembly.
- **The reply tool does not count** as a tool of the task: a cascade already runs the loop for
  `answerMode: "tool"` regardless of `enableInAgentMode`, and that rule stays.

### Tests

`LlmTaskCascadeWorkflowToolsTest` (real cascade inside `LlmTask`, mocked orchestrator: the loop runs
for workflow tools, `enableInAgentMode: false` skips the cascade, no tools keeps the legacy cascade, a
plain task never asks, the reservation holds four rounds, a spend refusal shows its fallback),
`AgentOrchestratorOffersToolsTest` (real assembly plus a stubbed workflow spec), `ModelCallBoundTest`
(round count follows `toolLoop`).
