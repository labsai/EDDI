## ⏱️ feat(llm): per-tool execution timeout on the live dispatch path (2026-09-21)

**Repo:** EDDI (`feat/per-tool-execution-timeout`)

### Why

`ToolExecutionService.executeToolWrapped` is the single pipeline every tool call takes — built-in,
http, MCP, A2A, dynamic, memory and recall. Its stages were rate limit → cache → **execute** → cost
tracking, and the execute stage was a bare `toolExecution.get()` with no time bound of any kind.
`LlmConfiguration.Task` had no per-tool timeout either. A tool that never returned held the whole
conversation turn open indefinitely: no error, no metric, no recovery, just a turn that never ends.

The gap was **identified by an outside contributor in PR #574**, which was closed unmerged because it
built on the reflection-based `executeTool(Object, Method, Object[], …)` path that `main` had already
deleted as unreachable. The problem statement was right; the location no longer existed. None of that
PR's code is reused here.

### What changed

- **`LlmConfiguration.Task.defaultToolTimeoutMs`** (new, `Integer`, default `120000`) and
  **`toolTimeoutsMs`** (new, `Map<String, Integer>`) — declared, defaulted and resolved exactly like
  the neighbouring `defaultRateLimit` / `toolRateLimits` pair, including the dispatch-name-before-slug
  key precedence that makes the documented slug form bind at all. `-1` (or `0`) means no bound.
- **`ToolLoopRunner.resolveToolTimeoutMs` / `defaultToolTimeoutMs(task)`** — the resolution table,
  sitting next to `resolveRateLimit`. Read off the `task` parameter rather than threaded down as yet
  another argument, the way `toolPricing` already is two lines above.
- **`ToolExecutionService.executeToolWrapped(ToolInvocation, …)`** takes a tenth argument,
  `int timeoutMs`, and wraps the execution stage in `executeBounded`. With no bound configured the
  supplier still runs **inline on the calling thread** — byte-identical to the previous behaviour on
  the path operators leave alone. With a bound it runs on one application-wide
  `newVirtualThreadPerTaskExecutor`, and the pipeline thread waits on the future.
- **On expiry the MODEL gets a result, not an exception**:
  `Error: Execution timed out after <n>ms for tool: <name>`, in the same shape as the existing
  `Error: Rate limit exceeded for tool: …` branch, so it can apologise, try another tool or answer
  without one. Nothing is cached and nothing is charged — both stages sit after the one that expired.
- **New meter `eddi.tool.execution.timeout`**, tagged `tool`, incremented alongside
  `eddi.tool.execution.failure` (the same pairing `ratelimited` already uses). Documented in
  [`metrics.md`](../metrics.md) and charted as target `E` of the "Execution outcomes" panel in
  [`eddi-full-metrics-dashboard.json`](../monitoring/eddi-full-metrics-dashboard.json). Joined by the
  gauge **`eddi.tool.execution.abandoned`** and its own panel — see "The abandoned worker" below.
- **Context travels with the call.** A bounded call leaves the pipeline thread, so the worker is
  wrapped with `CallerIdentityContext.propagate` (caller **and** resolution principal, the pairing
  that method exists to keep together), with `EddiToolBridge`'s thread-local conversation id, and with
  the OpenTelemetry `Context` so a tool's spans stay children of `eddi.pipeline.task` instead of
  becoming roots. Without the first of those, `${caller:token}` and `PER_USER` connections would have
  started failing closed inside every timed tool call.
- The `String` overload keeps its nine arguments and passes `TIMEOUT_DISABLED`: its one caller is
  `McpCallsTask`, whose tools are bounded by `McpCallsConfiguration.timeoutMs` (30s) and which has no
  `LlmConfiguration.Task` to read a timeout from.

**Files:**
[`ToolExecutionService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolExecutionService.java),
[`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java),
[`EddiToolBridge.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/EddiToolBridge.java),
[`langchain.md`](../langchain.md), [`metrics.md`](../metrics.md).

### A timeout is never retried — and that is the point of returning a string

`RetryConfiguration.isRetryableError` walks the cause chain and calls any `TimeoutException`
retryable. A timeout allowed to escape this method as an exception would therefore put a hanging tool
on a retry loop: several more full waits, and several more chances to re-fire a side effect the tool
had already begun. The internal signal is a private, stackless `RuntimeException` constructed **with
no cause**, so no `TimeoutException` is reachable by the back door either, and it is consumed by its
own catch block before the generic handler. `ToolExecutionTimeoutTest.timedOutToolRunsExactlyOnce`
pins one execution attempt and asserts nothing propagates.

### A call awaiting human approval is not on the clock

Verified in the code and pinned by two tests rather than asserted.

The tool-approval gate runs in `ToolLoopRunner`'s loop body, **outside** `executeSingleToolCall`:
`toolApprovalGate.classify(...)` splits the batch, the ungated calls execute, and then
`ToolApprovalRequiredException` is thrown with the persisted `PendingToolCallBatch`. A gated call
never reaches `executeToolWrapped`, so the clock — which starts when the supplier is invoked, inside
that method — never starts for it. On resume, `ToolLoopResumer` runs each approved call through
`executeSingleToolCallResult` → the same pipeline, with the timeout freshly resolved, timed from the
moment it actually starts.

- `AgentOrchestratorToolPauseTest.gatedCallNeverEntersTheTimedExecutionStep` — a batch of one gated
  and one ungated call under `defaultToolTimeoutMs: 1`; exactly one execution reaches the service and
  it is the ungated one. Two-sided on purpose: a bare `never()` would pass just as happily if the loop
  had executed nothing at all.
- `AgentOrchestratorResumeToolLoopTest.resumeCarriesTheFullConfiguredTimeout` — the approved call is
  handed its whole configured budget, with nothing deducted for the wait.

### The abandoned worker: what a timeout does not do

`future.cancel(true)` interrupts the worker, so a tool blocked in interruptible I/O or a sleep unwinds
at once. One stuck in a native call or a tight loop keeps running — and it still holds the same
`IConversationMemory` the turn does, so **it can still write to conversation memory after the model
was told the call failed**, and still complete an external side effect it had begun. Java offers no
safe kill; the alternative on the table was not "stop it cleanly" but "wait forever". Said out loud in
the class javadoc and in [`langchain.md`](../langchain.md), with the remedy for a tool whose side
effects must never be doubled: the HITL tool-approval gate, or `toolTimeoutsMs: {"thatTool": -1}` —
not a shorter timeout.

Virtual threads rather than a pool for exactly this reason: an abandoned virtual thread parked in I/O
holds no platform thread and no megabyte-sized stack, which is the difference between a hung tool
being an incident and being a log line. One executor per application, created as a field initializer
(unit tests construct the service directly) and `shutdownNow()` in `@PreDestroy`.

**Counted, not assumed away** (Copilot review, #817). Cheap is not free, and an operator who cannot
see workers piling up cannot act on it. The gauge **`eddi.tool.execution.abandoned`** reports how many
are still inside a tool that nobody is waiting for any more — normally zero, because `cancel(true)`
unwinds anything interruptible, so a non-zero reading names the native-call case above. It is derived
from two counters that are each incremented and decremented in a `finally` on the thread that owns
them (`runningWorkers` inside the task body, `awaitedWorkers` around the `future.get`), so a worker
cancelled before it ever started counts in neither and the gauge cannot drift; it clamps at zero
rather than reporting the transient `-1` of a submit observed between the two reads. Deployment-wide
rather than per-tool: which tool leaked is already answered by `eddi.tool.execution.timeout`.

The same review asked for an **admission bound** on the executor, refusing new calls once too many
workers are stuck. Deliberately not done: that converts one tool's hang into a refusal of unrelated,
healthy tool calls — a conversation-wide blast radius in exchange for a leak that is already strictly
smaller than the unbounded wait it replaced. Left as a question for a human reviewer rather than
settled in a follow-up commit.

### Verification

`.\mvnw.cmd compile` clean. New tests: `ToolExecutionTimeoutTest` (12), `ToolTimeoutResolutionTest`
(8), five timeout cases in `AgentOrchestratorCoverageTest`, one each in
`AgentOrchestratorToolPauseTest` and `AgentOrchestratorResumeToolLoopTest`. Final run: 357 tests, 0
failures, one surefire report per named class. Repo-wide guards run: `ImportStyleTest`,
`DocumentationLinksTest`, `DocumentationAccuracyTest`, `ConfigurationReferenceCoverageTest`,
`MetricsDashboardCoverageTest`, `ChangelogFragmentTest`, `BuildQualityGatesTest`,
`StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`.

Eight mutations, each with its kill set predicted before the run and each killed by exactly the
predicted named tests; sources restored byte-for-byte afterwards and re-run green.

| Mutation | Killed by |
| --- | --- |
| Let the timeout escape as a real `TimeoutException` | `timedOutToolIsAnsweredWithAnErrorResult`, `timedOutToolRunsExactlyOnce`, `timeoutIncrementsItsOwnCounterAndTheFailureCounter`, `timedOutCallIsNotCachedAndNotCharged` |
| Drop `future.cancel(true)` on expiry | `expiryInterruptsTheWorker`, `timedOutToolRunsExactlyOnce` — and `fastToolIsUnaffected`, which went from 0.1s to 70s as the un-cancelled 30s workers saturated the carrier threads: the leak, measured |
| Treat `0` as "expire immediately" rather than "off" | `disabledTimeoutRunsInline` |
| Stop unwrapping `ExecutionException` | `toolFailureKeepsItsMessage` |
| Look up the canonical slug before the dispatch name | `dispatchNameWinsOverSlug`, `toolCall_dispatchNameTimeoutWinsOverSlug` |
| Remove the engine-side default for a null `defaultToolTimeoutMs` | `nullTaskDefaultFallsBackToTheEngineDefault`, `toolCall_noTimeoutConfigured_usesTheEngineDefault` |
| Never pass the resolved timeout to the pipeline | `toolCall_perToolTimeoutOverride_usesConfiguredTimeout`, `toolCall_timeoutKeyedOnCanonicalSlug_binds`, `toolCall_dispatchNameTimeoutWinsOverSlug`, `resumeCarriesTheFullConfiguredTimeout` |
| Execute the gated calls before pausing for the human | `gatedCallNeverEntersTheTimedExecutionStep`, and the pre-existing `gatedNotExecuted_ungatedExecutes` |

```decision-log
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
```
