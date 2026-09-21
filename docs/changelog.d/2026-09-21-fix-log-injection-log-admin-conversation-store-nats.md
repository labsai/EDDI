## 🔒 fix(security): sanitize the CWE-117 log sinks PR #799 left uncovered (2026-09-21)

**Repo:** EDDI (`fix/log-injection-log-admin-conversation-store-nats`)

Six open `java/log-injection` alerts on `main` — #105, #106, #112, #113, #114 and #121, across five log
statements in three files — that [PR #799](https://github.com/labsai/EDDI/pull/799) did not touch: it closed the 14 alerts in
`RestAgentAdministration` and `AgentFactory` only. Like those, these surfaced while triaging the
community logger-rename PRs #558 and #561, were correctly judged pre-existing and out of scope there,
and were then tracked by nothing at all. Same one-line fix, same test shape.

### What changed

- **[`RestLogAdmin`](../../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java)** (#105, #106)
  — the `streamLogs` "SSE log stream started" DEBUG quoted `agentId` and `level` raw. Both are
  `@QueryParam`s on `GET /logs/stream`, so both arrive unvalidated. The irony is specific to this
  endpoint: it *is* the log viewer, so the forged line is served straight back to whoever is tailing
  the stream. The file gained the static `sanitize` import it did not have. `listenerId` on the same
  line is left alone deliberately — `BoundedLogStore` generates it, no caller supplies it, and CodeQL
  did not flag it.
- **[`RestConversationStore`](../../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java)**
  (#112, #113, #114) — the descriptor loop's "Skipping descriptor due to error" DEBUG, and both
  `deleteAttachmentsForConversation` lines ("Deleted %d attachments" and "Failed to delete
  attachments"). `conversationId` is the path parameter on `DELETE
  /conversationstore/conversations/{id}`; the exception message is not the developer's text either,
  since a store routinely quotes back the value it was handed. The file already static-imports
  `sanitize` and applies it on the neighbouring permanent-delete, soft-delete and not-found lines, so
  these three were missed rather than deliberately left.
- **[`NatsConversationCoordinator`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java)**
  (#121) — `publishAndExecute`'s "Published to NATS subject" DEBUG. This one logs the *subject*, not
  the conversation id, which is why the pass that sanitized the rest of the file went past it.

### Tests

Three new classes in the shape `RestAgentAdministrationLogInjectionTest` established —
`captureLogsOf(<Class>.class, …)` plus `assertNoForgedRecordBoundary(…)` against
`LogCaptureSupport.FORGED_RECORD`, attaching by logger category rather than by field name, so PR
#558's renames cannot break them. Seven tests: 2 + 3 + 2.

Each also asserts the benign half of the value still reaches the log, so a "fix" that dropped the
argument instead of sanitizing it would fail. All seven of the newly wrapped arguments were
mutation-checked one at a time — revert exactly that call, run the class, require the named test to
fail. **7 mutations, 7 killed, 0 survived.** The driver lived in the session scratchpad and is not
committed.

### Decisions

- **The NATS fix goes at the log call, not in `sanitizeSubject`.** `subject` is `SUBJECT_PREFIX +
  sanitizeSubject(conversationId)`, and `sanitizeSubject` is not a log sanitizer despite the name: it
  replaces `.` and space because a NATS subject token may not contain them, and leaves CR and LF —
  which a subject token may not contain either — untouched. Widening it would change the subject
  namespace every deployment already publishes and consumes under, for a log bug. It is also what the
  file already does one method down: `routeToDeadLetter` logs `sanitize(deadLetterSubject)`.
  `NatsConversationCoordinatorLogInjectionTest` pins that premise with a second test asserting
  `sanitizeSubject` still lets a CR/LF through — if anyone ever widens it, that test fails and the
  redundancy is a decision rather than a drift.
- **Behavioural tests rather than entries in `SanitizedLogSinksTest`.** That source guard exists for
  the ~38 group-conversation sinks that need a whole discussion to reach, where building one to
  observe a single WARN tests the harness more than the fix. All four sinks here are reachable from a
  public method with mocks, so they get real tests; the guard's hard count of 38 stays honest.
- **A frozen clock in the `RestLogAdmin` test, not a mocked one.** `streamLogs` spawns a cleanup
  virtual thread that logs its own "listener removed" line. A frozen clock parks it in its 2-second
  sleep — it can neither reach the heartbeat branch nor fall out of the max-lifetime loop — so the
  only line inside the capture window is the one under test. Without that the cleanup line could race
  in and satisfy the assertion in place of the real subject, and the per-site mutation check would
  stop localising. `@AfterEach` closes the sink so the thread finishes rather than outliving the test.
- **A 24-character hex conversation id in the `RestConversationStore` test.** `extractResourceId`
  resolves an id only out of a hex path segment; a semantic name yields a null id and the descriptor
  is skipped at the top of the loop, well before the line under test — a test that would have passed
  without ever reaching it.

### Still open

`main` carries **85** open `java/log-injection` alerts across 31 files, the largest clusters being
`RestScheduleStore` (9), `RestUserMemoryStore` (8) and `VaultSecretProvider` (6). Whether to keep
closing them file by file or take one broader pass is a maintainer call and was deliberately not made
here. Two adjacent sinks in `RestConversationStore` were also noticed and left, because CodeQL has not
flagged them and this branch is scoped to what it did flag: the HITL-cleanup WARN at the top of
`deleteConversationLog` sanitizes its `conversationId` but not its `e.getMessage()`, and
`populateDataToDescriptor`'s "Memory snapshot not found" WARN quotes `resourceId.getId()` raw.

```decision-log
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call rather than by widening `sanitizeSubject` | `sanitizeSubject` builds a valid NATS subject token (`.` and space only); it is not a log sanitizer, and CR/LF reach the log through it | Widen `sanitizeSubject` to strip CR/LF — changes the subject namespace every deployment already publishes and consumes under, to fix a log bug |
```
