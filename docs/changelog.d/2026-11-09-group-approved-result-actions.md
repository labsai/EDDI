## ✨ feat(groups): approved-result actions — `phase.onApproved` runs one call with each human-approved task (2026-11-09)

**Repo:** EDDI (`feat/group-approved-result-actions`)

### What changed and why

A member agent's approval-gated tool call is auto-rejected inside a group turn (`inGroupTurns: REJECT`), so a
team that may publish only human-approved text could not gate the posting call. The workaround was a separate
"Publisher" member re-posting the approved text in a later phase: an extra LLM turn per post, a judge to check it
did not reword the text, and phase plumbing to file publish tasks after the approval (a TASK pause resumes in the
same phase). The gate now sits where it belongs — on the draft — and the group itself makes the call:

```json
{ "name": "Draft", "type": "EXECUTE", "requiresApproval": true,
  "onApproved": { "action": { "type": "HTTPCALL",
                              "uri": "eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=1",
                              "call": "postIssueComment" },
                  "onFailure": "FAIL_TASK" } }
```

- **Model** (`AgentGroupConfiguration`): `DiscussionPhase.onApproved` (`OnApprovedConfig` → `ApprovedAction`
  `{type, uri, call}`, `ApprovedActionFailurePolicy` `FAIL_TASK` default | `KEEP_COMPLETED`). A 13-argument
  compatibility constructor keeps every existing call site; the two full-copy sites (facilitator EXTEND_PHASE,
  NEGOTIATION arbitration repair) carry the field. `null` = nothing runs.
- **Record** (`SharedTaskList.approvedActions`, taskId → `ApprovedActionRecord`): status `PENDING` / `RUNNING` /
  `SUCCEEDED` / `FAILED` / `OUTCOME_UNKNOWN` / `SKIPPED` / `NOT_SENT`, target, `onFailure`, approver, edit flag, idempotency
  key, timestamps, HTTP code, `html_url`/`url` of the response, a short redacted summary. Persisted with the
  discussion; old MongoDB/PostgreSQL documents load with an empty map.
- **Runner** (`GroupApprovedResultActions`, wired into `GroupHitlCoordinator` by a setter like the tie-break
  pauses): `recordApprovals` runs inside `resumeDiscussion` after the task approvals and before the resume CAS,
  for the tasks that went AWAITING_APPROVAL → COMPLETED in the paused phase; `runOwed` runs on the resumed leg
  (approval and human-input resumes) after the drift guards and **before** `executeDiscussion`, so a dependent
  never starts before its dependency's publish settled.
- **Executor:** the existing `IApiCallExecutor` (`ApiCallExecutor`), the path httpcall rules and httpcall LLM tools
  use — vault resolution, the reference guard, redaction, response caps, the agent-test mock registry — with a
  throwaway `ConversationMemory` (a group has no conversation of its own). Template data is `task.*`, `group.*`,
  `action.idempotencyKey`, nothing else. MCP-tool actions are not in v1 — one executor done properly, and the
  `type` field leaves room.
- **Validation** (`ApprovedResultActionValidation`, called from `AgentGroupStore.create/update`, `400` with the
  phase index and the fix): EXECUTE phase, `requiresApproval`, `hitlConfig.granularity: TASK`, `uri` an httpcalls
  URI with an explicit version, the call exists and makes one request (no fire-and-forget, batch, forEach), it has
  no `preRequest.propertyInstructions` (the executor would rebuild the template data from memory and drop
  `task.*`/`group.*`/`action.*`), the saver holds USE access to the httpcalls configuration, every `${vault:...}` is
  granted to all agents (`ServiceJudgeVaultGrants`, its message now names approved-result actions too) — checked on
  each field and on the URL as the executor joins it, so a reference split across `targetServerUrl` and the path is
  caught — and `${connection:}`, `${caller:}`, `${context:}` are refused. The reference half is re-checked before
  every send.
- **Audit/metrics:** with the audit ledger enabled, one ledger entry per settled action (`group.approved_action`,
  approver, target, status, result SHA-256 and length, HTTP code, URL); counter
  `eddi_group_approved_action_total{outcome}` on every settled action.
- **Docs:** new `docs/group-approved-result-actions.md` (in `SUMMARY.md`) with a Content Studio example posting an
  approved GitHub comment; pointers from `group-conversations.md` (per-phase controls), `hitl.md` (group config table,
  Group members) and `hitl-review-and-audit.md` (edited results). Platform Operator prompt gained the field
  (revision bump). Manager: the `DiscussionPhase` type declares `onApproved`; the phase editor already spreads
  unknown fields through, so a save keeps it — editing it stays JSON-only. MCP: `update_group` takes the full
  config JSON already; `create_group` builds phases from a style and needs nothing.

### Design decisions

- **At most once, honestly named.** The record is created in the approval's own state+pauseId CAS write (a retried
  approval loses it), claimed as RUNNING and persisted *before* the send, and a RUNNING
  record found later becomes OUTCOME_UNKNOWN and is never resent — the tool-call execution journal's contract.
  `{action.idempotencyKey}` lets a deduplicating target make it effectively once across a crash. The claim is a CAS
  on the discussion still being IN_PROGRESS, so a cancel stored first makes it fail; a claim that cannot be written
  sends nothing and settles the record SKIPPED (a cancel seen on this pod or read back from the store) or FAILED,
  rather than leaving it PENDING for a leg that may not come. The metric always counts that outcome; an audit entry
  carries it only when the audit ledger is enabled and its submission succeeds; the stored record shows it only if
  the follow-up write succeeds — a cancelled discussion refuses it, and then the next rule settles it.
- **An ended discussion owes nothing** (`GroupConversationStore.settleOwedApprovedActionsIfEnded` →
  `SharedTaskList.settleOwedApprovedActions`): on every read and write of a document in a terminal state (COMPLETED,
  FAILED, REJECTED, CANCELLED, CLOSED) a PENDING record becomes `NOT_SENT` with the state it ended in, and an orphaned
  RUNNING claim becomes OUTCOME_UNKNOWN. In the store because it is the one place every ending passes — the leg's own
  terminal writes, cancels by another pod, cadence reclaims and crash recovery (#1326) — on MongoDB and PostgreSQL
  alike. Never sent afterwards: a crash must not turn an approval into a publish nobody watched. No audit entry or
  metric for these (no leg is running); logged at INFO. Rejected: auto-sending on recovery (breaks at most once);
  calling a settle hook at each of the nine terminal-transition sites (one missed site, or #1326's, leaves it stuck).
- **Re-approval owes a new attempt only when nothing was published.** `recordApprovedAction` replaces a record whose
  status `allowsNewAttempt()` — FAILED, SKIPPED, NOT_SENT — and keeps every other: PENDING/RUNNING (still owed or in
  flight), SUCCEEDED (never sent twice), OUTCOME_UNKNOWN (never resent). Only tasks that went AWAITING_APPROVAL →
  COMPLETED in the current decision are recorded, and a settled task is awaiting approval again only after a RETRY or
  VERIFY-rework reset, so the replacement is always a person approving a redone task. Before, `putIfAbsent` dropped
  that approval's action silently while the FAILED summary told the user to "approve the task again"; the summary
  now says to send the task back (retry or rework) and approve it again.
- **Idempotency key from the stable identity.** A cadence discussion's task pulled from the standing team's backlog
  is keyed `sha256("backlog/" + groupId + "/" + backlogTaskId)` — the backlog task found the way writeback matches it
  (by subject among the workspace's `pulledTaskIds`, while `runningDiscussionId` is this discussion) — so a task that
  returns to the backlog after a FAILED or OUTCOME_UNKNOWN publish offers the target the same key in its next
  discussion. Other tasks (and an unreadable workspace) keep `sha256(conversationId + "/" + taskId)`. At most once
  remains per discussion; across discussions only a deduplicating target makes it once (documented).
- **"Approved by a person"** is one private helper, `approvedByPerson`, whose comment names the sibling PR #1374's
  `TaskApproval.byPerson()`: whichever merges second unifies the two.
- **Only human approvals publish.** A `system:*` approval (timeout `AUTO_APPROVE`) records SKIPPED.
- **Identity:** the call runs as the group (`runAs: group:<groupId>` in the audit entry) with author-written,
  all-agents secrets only; the approver is recorded, never impersonated.
- **Cost:** an HTTP call; tool pricing, tool rate limits and the cost ceiling do not apply (documented); bounded by
  one call per approved task.
- **Cadence writeback** (`TeamCadenceService`, not modified): under `FAIL_TASK` a failed publish leaves the task
  FAILED, so the run does not count it done; under `KEEP_COMPLETED` it stays COMPLETED.

### Rolling upgrade

No schema bump: the fields are additive. A pod older than this release ignores `onApproved` (approvals publish
nothing on it) and drops `approvedActions` when it re-saves a discussion, which can lose an owed (PENDING) action but
never sends one twice.

### Open

- A pod crash after the approval but before the claim leaves the record PENDING while the discussion stays
  IN_PROGRESS; it is sent only if a later leg runs, and reads NOT_SENT once the discussion ends (crash recovery
  cancelling it included).
- Retries configured on the referenced call (`postResponse.retryApiCallInstruction`) still apply and can repeat a
  request the target processed.

```decision-log
| 2026-11-09 | Approved-result actions run through the existing httpcalls executor (`IApiCallExecutor`), HTTPCALL only in v1 | Reuses vault resolution, the reference guard, redaction and caps instead of a second outbound path; one executor done fully | MCP-tool actions in the same change; a bespoke OutboundHttp call |
| 2026-11-09 | At-most-once: record in the approval CAS, persist a RUNNING claim before the send, never resend a RUNNING record | An external side effect cannot be exactly-once without the target's help; a double publish is worse than a visible OUTCOME_UNKNOWN | Retrying RUNNING records; a separate journal store |
| 2026-11-09 | A `system:*` (AUTO_APPROVE) approval records SKIPPED instead of running the action | The feature exists to publish only human-approved text | A `runOnAutoApprove` switch |
| 2026-11-11 | An approved action still owed when its discussion ends is settled NOT_SENT by the discussion store, never sent | Every ending (cancel, crash recovery, cadence reclaim) passes the store; at most once forbids sending after a crash | Auto-send on recovery; a settle call at each terminal-transition site |
| 2026-11-11 | A re-approval after retry/rework owes a new attempt only if the earlier one ended FAILED, SKIPPED or NOT_SENT | A failed publish must be recoverable by the user; a SUCCEEDED or OUTCOME_UNKNOWN one must never be sent twice | `putIfAbsent` per task (silently dropped the re-approval) |
| 2026-11-11 | A standing team's backlog task keys its idempotency key on the backlog task id | The task is re-run in new discussions; a per-discussion key let the target receive the same publish twice | Keying on the subject; adding a backlog id to every discussion task |
```
