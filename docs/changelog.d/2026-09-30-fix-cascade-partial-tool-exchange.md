## 🐛 fix(llm): a timed-out or failed cascade step hands its completed tools to the next step (2026-09-30)

**Repo:** EDDI (`fix/cascade-partial-tool-exchange`)

Closes the follow-up the 2026-09-26 tool-loop entry left open ("Not carried (follow-up)"): a
cascade step carried its tool exchange into the next step only when it *returned*. A step whose
future timed out or threw lost the exchange with its result, so the next step started from the
conversation alone and could execute the same side-effecting tools again.

### What changed

- **`ToolExchangeRecorder`** (new, `modules/llm/impl`) — a per-run, thread-safe record of the
  tool loop's exchange, appended as it happens. It holds only complete pairs: a call is recorded in
  the same step as its result, and an assistant message that asked for several tools is reported
  with the calls that have a result and nothing else. A run cancelled between two tools of one
  batch therefore never leaves a dangling call. Its output goes through
  `ToolLoopRunner.toolExchange`, so null-id calls get the same synthetic, paired ids a returned
  exchange gets.
- **`ToolLoopRunner`** — `runToolCallLoop` and a new `executeWithTools` overload take the
  recorder; every place the loop appends a tool result (executed, self-conversation refusal,
  pause-cap denial) now goes through one `addResult` helper that appends and records together.
  The old `executeWithTools` signature passes `null` (records nothing); the resume path passes
  `null` too.
- **`IAgentOrchestrator` / `AgentOrchestrator`** — a new `executeIfToolsEnabled` overload with the
  recorder. The interface default ignores it and delegates to the existing method, so another
  implementation keeps the pre-change behaviour (nothing to carry) rather than failing.
- **`CascadingModelExecutor`** — each step attempt gets a recorder (a fresh one for the
  carried-exchange-rejected retry). In the `TimeoutException` and generic `Exception` paths, after
  the future is cancelled, `carryCompletedExchange` applies the success path's rule: the carried
  exchange becomes what the step started from plus what it completed. The step's trace entry
  records `completedToolMessages`. The `ToolApprovalRequiredException` branch is untouched; it
  rethrows before the recorder is read.

### Design decisions

- **A step that completed nothing leaves the carried exchange alone** rather than replacing it
  with an empty one. There is nothing new to hand on, and dropping an exchange the next provider
  might accept would only invite a replay.
- **A tool still executing when the step is cancelled is not carried.** Its outcome is unknown.
  `cancel(true)` does not wait for the virtual thread, and waiting for it would defeat the step
  timeout. This is the one replay the change cannot prevent, and `docs/model-cascade.md` says so.
- **A recorder passed in, not state on the runner.** `ToolLoopRunner` is shared by every
  conversation and stays stateless; the recorder lives exactly as long as one step attempt.

### Tests

- `CascadingModelExecutorPartialExchangeTest` (new) runs the real `ToolLoopRunner` under the
  cascade, with a tool that counts its executions. A step that completes `placeOrder` and then
  hangs (timeout) or throws on the next model request hands the call and its result to step 1, and
  the order is placed once. A step that fails before any tool (`FailedBeforeToolsException`) carries
  nothing. With `carryToolResultsOnEscalation: false` nothing is carried and the tool runs again,
  as documented.
- `ToolExchangeRecorderTest` (new) covers complete batches, a batch cut between two tools, round
  ordering and null-id pairing.
- Existing cascade and `LlmTask` cascade tests stub the new overload, because the cascade now calls
  it.

Docs: [`model-cascade.md`](../model-cascade.md) replaces the known-limitation sentence with the
new behaviour and the one remaining case.
