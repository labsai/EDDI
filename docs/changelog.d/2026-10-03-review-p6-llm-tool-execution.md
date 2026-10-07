## Review of the LLM tool-execution hardening: HITL resume count, internal retries (2026-10-03)

**Repo:** EDDI · **Branch:** `fix/llm-tool-execution` (review follow-up).

**What changed**

- **The per-turn tool-call cap survives a HITL resume.** `ToolLoopResumer` ran the continuation with a fresh
  trace and merged the pre-pause trace in only afterwards, so `maxToolCallsPerTurn` was counted from the
  resumed part alone: a turn could make `maxToolCallsPerTurn` calls before the pause and again after it. The
  continuation's trace is now seeded with the pre-pause trace, which also means a second pause freezes the
  whole turn (it used to freeze only the resumed part) into its batch.
- **Model calls outside a task's retry policy keep one retry.** Turning provider SDK retries off removed the
  only retry the shared summarizer (rolling and stance summaries, Dream), the tool-response summarizer and the
  cascade judge had. `TransientFailureRetry` gives each one retry after 500 ms, for transient failures only.
- **The web-scraper parse pool no longer answers "busy" with a free worker.** Admission is a semaphore with one
  permit per worker, released by the parse itself when it ends (or by the caller for a parse that never
  started); the `SynchronousQueue` hand-off refused a call in the instant between a worker finishing and
  taking its next task. A runaway that ignores the interrupt still holds its permit until it really ends.
- **Refused tool calls are answered after the executed ones**, so tool results follow the order of the calls
  (the refused ones are always the tail of the response).
- Documented that under a model cascade `maxToolCallsPerTurn` applies per cascade step.

```regression-note
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
```
