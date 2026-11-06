## 🐛 fix(llm-tools): per-turn tool budgets live on the conversation step, forgetFact included (2026-11-06)

**Repo:** EDDI (`fix/turn-scoped-tool-budgets`)

### What changed and why

Two LLM-tool budgets were counted in a field of the tool object: `maxWritesPerTurn` in `UserMemoryTool`
and `maxDelegationsPerTask` in `ConverseWithAgentTool`. A turn builds its tools more than once — each
step of an agent-mode model cascade runs its own tool assembly, and so does the continuation of a turn
resumed after a HITL tool approval — and every assembly starts a new tool object at zero. A three-step
escalating cascade could therefore write three times `maxWritesPerTurn`, and a resumed turn got a fresh
budget, while [`user-memory.md`](../user-memory.md) promised a limit "per conversation turn".

The new `TurnCounter` keeps the count on the conversation's current step instead, where
`hitl:tool_pause_count` already lives for the same reason. `ContextualToolsProvider` hands the memory
tool `tools:memory_writes`, and `DynamicAgentToolsProvider` hands the delegation tool
`tools:delegations:<taskId>` (`default` for a task without an id), so the delegation cap stays per LLM
task as documented, now across every assembly of that task in the turn. The next turn's step starts at
zero again. Two consequences follow from counting on the step: the LLM tasks of one turn share
`maxWritesPerTurn` (it was per task execution), and a rerun of the turn, which re-executes the same step,
starts from what its first run spent — the writes and delegations that run made are not undone either.
Neither key has a public prefix, so the counts stay out of the client snapshot. The key is read by exact
match: the step's `getLatestData` is a prefix lookup and would let `tools:delegations:a`
read the count of `tools:delegations:ab`. A tool built without a conversation (direct construction)
still counts on its own instance.

`forgetFact` was not covered by any budget, so one injected "forget everything about the user" could
delete every memory the agent owns in a single turn. A successful deletion now counts against
`maxWritesPerTurn` like a stored fact, and is refused with the same "Maximum writes per turn" message
once the budget is spent. A key that matches nothing and a refused deletion do not count. The refusal
text now says "saved or forgotten", since it can answer either tool.

**Files:** [`TurnCounter.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/TurnCounter.java),
[`UserMemoryTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java),
[`ConverseWithAgentTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ConverseWithAgentTool.java),
[`ContextualToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ContextualToolsProvider.java),
[`DynamicAgentToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/DynamicAgentToolsProvider.java),
[`user-memory.md`](../user-memory.md)

```decision-log
| 2026-11-06 | Per-turn LLM tool budgets are counted on the conversation step (`TurnCounter`), keyed by budget and, for delegations, by task id | A tool object is rebuilt per tool assembly, and a turn assembles once per cascade step and again on a HITL resume, so a field counter multiplied the budget | A shared counter in a CDI bean keyed by conversation id (state outside memory, lost on a resume on another node); counting in `AgentOrchestrator` and passing the count into each tool (spreads one budget over two classes) |
```
