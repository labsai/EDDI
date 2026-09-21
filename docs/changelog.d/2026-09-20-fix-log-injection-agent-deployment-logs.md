## 🔒 fix(security): sanitize the agent-deployment log lines CodeQL flagged for CWE-117 (2026-09-20)

**Repo:** EDDI (`fix/log-injection-agent-deployment-logs`)

GitHub code scanning had 14 open `java/log-injection` alerts against `refs/heads/main` in two files —
9 in `RestAgentAdministration` (#90–#92, #97–#102) and 5 in `AgentFactory` (#116–#120). They are not
new: they surfaced while triaging community PRs #558 and #561, which only rename logger fields and
neither introduce nor fix any of them. `agentId` is a path parameter on every endpoint involved, so a
CR/LF in one closes the real log record and lets the remainder read as a second line the server wrote
itself — a forged `[SCHEDULE] Auto-enabled` line for an agent nobody deployed, for instance.

### What changed

- **`AgentFactory`** — `waitForDeploymentCompletion` quoted the raw `AgentId` on five lines (two
  "did not complete successfully" ERRORs, the still-deploying DEBUG, the timeout WARN and the
  interrupted WARN). It now derives one `var safeAgentId = sanitize(agentIdObj.toString())` after the
  listener lookup and every line quotes that — one call site instead of five, and the rendering is
  byte-identical to the old `%s` on the object for a benign id. `logAgentDeployment`'s two INFO lines
  (alerts #119/#120, the only two still current at `798c6e84`) now `sanitize(agentId)`. The file
  already sanitized its three `debugf` calls; these were simply missed.
- **`RestAgentAdministration`** — all 9 flagged sinks: the deploy-wait timeout WARN, the deploy-failed
  WARN (both the id *and* the cause's message), the successful-undeploy INFO, `throwError` and
  `throwErrorForbidden`, and the four `[SCHEDULE]` auto-enable/auto-disable lines, which also quote
  `schedule.getName()` and `schedule.getId()` verbatim. Log levels and message wording are unchanged
  throughout.
- **`RestAgentAdministration` now static-imports `sanitize`** rather than calling
  `LogSanitizer.sanitize(...)` on two pre-existing sites, matching `RestAgentStore` and
  `RestWorkflowStore` and AGENTS.md's ban on inline qualification. The class import is gone, so
  Checkstyle's `UnusedImports` stays green.

### Tests

Two new classes in the established shape — `captureLogsOf(<Class>.class, …)` plus
`assertNoForgedRecordBoundary(...)` against `LogCaptureSupport.FORGED_RECORD`, attaching by logger
category rather than by field name (so PR #558's renames cannot break them):

- `AgentFactoryLogInjectionTest` — 6 tests. Reaching `waitForDeploymentCompletion` needs a published
  `IN_PROGRESS` placeholder, so each test parks a deployment inside its store lookup on a virtual
  thread (the idiom `AgentFactoryUndeployVersionTest` already uses) and calls `getAgent` from the test
  thread.
- `RestAgentAdministrationLogInjectionTest` — 9 tests, one per alert.

Every one of the 17 arguments newly wrapped in `sanitize(...)` was mutation-checked one at a time:
revert exactly that call, run the class, require the named test to fail. 17 mutations, 17 killed,
0 survived. Driver lived in the session scratchpad and is not committed.

### Decisions

- **Which alerts are real.** Three of the five `AgentFactory` alerts (#116–#118) were last seen at
  `d5294a60` and point at lines that carry `sanitize(agentId)` on today's `main` — stale instances
  that should close on the next scan of the branch. The genuinely unsanitized sinks in that file were
  the two `logAgentDeployment` INFOs plus the five `waitForDeploymentCompletion` calls, which CodeQL's
  stale line refs no longer name. Fixing by *call site* rather than by *reported line* is the only way
  to end up with the file actually clean.
- **`throwErrorForbidden`'s sanitize also reaches the client.** Its `message` is both logged and put
  into the `WebApplicationException` body, so sanitizing the id narrows a response-splitting surface
  as well. That is a strict improvement and the wording is untouched, so it stays on one call.
- **The auto-enable/auto-disable failure WARNs pin the id only.** Both pass the cause as the record's
  *throwable*, not as a format parameter, so `LogCaptureSupport` never sees its message — those tests
  deliberately use a benign exception message rather than implying coverage they do not have.
- **The `CompletableFuture` in the timeout/interrupt tests is hand-written, not mocked.** The default
  mock maker does not intercept `CompletableFuture.get(long, TimeUnit)`: a stubbed mock silently ran
  the real 60-second wait, and Mockito reported it as `UnfinishedStubbing` from inside the JDK. A
  four-line anonymous subclass whose `get` throws is deterministic and needs no mock maker at all.
- **Each capture window holds exactly one line under test.** The parked deployment's own
  `logAgentDeployment` INFO fires *before* the store lookup and the endpoint calls run outside the
  window, with only the captured Callable inside it. Without that ordering a test could be satisfied
  by whichever line leaked first, and the per-site mutation check would not localise.

### Still open

`java/log-injection` has ~40 further open alerts on `main` in `GroupHitlCoordinator`,
`GroupConversationService`, `MemberTurnExecutor`, `ConversationHitlService`, `PhaseExecutionEngine`,
`AuditLedgerService` and others. Out of scope here; same one-line fix and same test shape apply.
