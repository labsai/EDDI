## Review of the LLM tool-execution hardening: HITL resume count, internal retries (2026-10-03)

**Repo:** EDDI · **Branch:** `review/p6` (on `fix/llm-tool-execution`).

**What changed**

- **The per-turn tool-call cap survives a HITL resume.** `ToolLoopResumer` ran the continuation with a fresh
  trace and merged the pre-pause trace in only afterwards, so `maxToolCallsPerTurn` was counted from the
  resumed part alone: a turn could make `maxToolCallsPerTurn` calls before the pause and again after it. The
  continuation's trace is now seeded with the pre-pause trace, which also means a second pause freezes the
  whole turn (it used to freeze only the resumed part) into its batch.
- **Model calls outside a task's retry policy keep one retry.** Turning provider SDK retries off removed the
  only retry the shared summarizer (rolling and stance summaries, Dream), the tool-response summarizer and the
  cascade judge had. `TransientFailureRetry` gives each one retry after 500 ms, for transient failures only.

```regression-note
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `review/p6` |
```
