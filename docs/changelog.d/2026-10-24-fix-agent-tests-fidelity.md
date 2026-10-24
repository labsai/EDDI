## 🐛 fix(agent-tests): mocked httpcall tools keep their postResponse, runs can be cancelled, replicas stop aborting each other's runs (2026-10-24)

**Repo:** EDDI (`fix/agent-tests-fidelity`)

Review follow-ups to the agent regression harness (PR #1065).

### What changed and why

- **An httpcall mocked on the LLM tool path now runs its response handling.**
  `ToolLoopRunner` answered every mocked LLM tool call itself, before any executor,
  so for an httpcall tool `saveResponse`, retries and `postResponse` property
  instructions never ran: an agent that sets a property from a tool's response
  behaved differently mocked than live, and its `properties` expectations failed.
  The call is still decided and recorded once in `ToolLoopRunner`
  (`ToolMockRegistry.decideToolCall`); a `MOCKED` decision for an executor that
  implements the new `MockAwareToolExecutor` (the httpcall tools of
  `HttpCallToolsProvider`) is handed to it, and `ApiCallExecutor` answers the call
  with it — no request built — exactly as it already did for a rule-triggered call.
  Refusals (unmocked, fail-closed) and every other tool source short-circuit as
  before. A mocked call is uncached and unpriced.
- **A retried mocked call is recorded once.** `ApiCallExecutor` intercepted inside
  the retry loop, so each retry was recorded again and counted against
  `maxToolCalls` (and a retried unmocked call produced several `unmockedTool`
  failures). The decision is made once per call, as a retried live call is one call.
- **Cancel.** `POST /agenttests/runs/{runId}/cancel` and the MCP tool
  `cancel_agent_test_run` (author tier, EDIT on the agent — what starting one
  takes) stop a run cooperatively: no further turn starts, a waiting turn is
  abandoned, its conversations are ended and deleted, and the run ends with the new
  status `CANCELLED`. Before, a run of up to 2,000 turns × 900 s held one of two run
  slots until the node restarted. Manager: a **Cancel run** button on a running
  run, the `CANCELLED` badge and notice (11 locales).
- **Orphan recovery is owner- and heartbeat-aware.** At startup every `RUNNING` run
  was marked `ABORTED` and its conversations deleted — wrong when several replicas
  share one database without cluster mode. A run now records `ownerNode`
  (`<nodeId>/<bootId>`) and refreshes `heartbeatAt` every 30 s; a node aborts only a
  run of an earlier boot of itself or one whose heartbeat is older than three
  minutes, at startup and every three minutes.
- **Capacity before the deploy.** `AgentTestService` checked the run slots after
  deploying the version to `test`, so a request refused with 429 had already
  changed what the `test` environment serves.
- **Reserved test user ids.** A conversation started from outside as
  `agent-test:<liveRunId>:c:r` joined that run's mock session and wrote into its
  results. `OwnershipValidator.validateAndResolveUserId` now refuses any requested
  user id in that namespace (403), for an admin and with authorization off too.
- **A permanent delete removes memory checkpoints.** `ConversationPurgeService.purge`
  (every permanent conversation delete, not only test runs) left the checkpoints the
  tool loop writes before every tool call; only GDPR erasure removed them.
- **PostgreSQL IT and a two-turn case.** `PostgresAgentTestSuiteIT` runs the harness
  IT against PostgreSQL; `AgentTestSuiteIT` gained a two-turn case.
- **Docs.** `agent-testing.md`: the Manager page is shipped (not a follow-up), the
  "a mocked httpcall is still built" limitation was wrong and is replaced, cancel and
  the liveness rules are documented, and test conversations are noted to appear in
  listings and metrics while a run is in progress.

### Design decisions

- The mock rides into `ApiCallExecutor` in the template data
  (`ToolMockRegistry.TOOL_PATH_MOCK`), next to the existing `TOOL_PATH_MARKER`,
  rather than through a `ThreadLocal`: the executor runs on the tool-timeout thread.
- Cancel is node-local. A cancel arriving at another replica answers 409 naming the
  owner; a cross-node flag would race the heartbeat's whole-document writes.
- A run recorded before 6.6 (no owner, no heartbeat) counts from its `startedAt`.

**Files:** [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`MockAwareToolExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/MockAwareToolExecutor.java), [`HttpCallToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/HttpCallToolsProvider.java), [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`ToolMockRegistry.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/ToolMockRegistry.java), [`AgentTestRunner.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/AgentTestRunner.java), [`AgentTestService.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/AgentTestService.java), [`AgentTestRunRecovery.java`](../../src/main/java/ai/labs/eddi/engine/agenttests/AgentTestRunRecovery.java), [`ConversationPurgeService.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationPurgeService.java), [`OwnershipValidator.java`](../../src/main/java/ai/labs/eddi/engine/security/OwnershipValidator.java), [`agent-testing.md`](../agent-testing.md)
