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
  `SUCCEEDED` / `FAILED` / `OUTCOME_UNKNOWN` / `SKIPPED`, target, `onFailure`, approver, edit flag, idempotency
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
- **Audit/metrics:** one ledger entry per settled action (`group.approved_action`, approver, target, status, result
  SHA-256 and length, HTTP code, URL); counter `eddi_group_approved_action_total{outcome}`.
- **Docs:** new `docs/group-approved-result-actions.md` (in `SUMMARY.md`) with a Content Studio example posting an
  approved GitHub comment; pointers from `group-conversations.md` (per-phase controls), `hitl.md` (group config table,
  Group members) and `hitl-review-and-audit.md` (edited results). Platform Operator prompt gained the field
  (revision bump). Manager: the `DiscussionPhase` type declares `onApproved`; the phase editor already spreads
  unknown fields through, so a save keeps it — editing it stays JSON-only. MCP: `update_group` takes the full
  config JSON already; `create_group` builds phases from a style and needs nothing.

### Design decisions

- **At most once, honestly named.** The record is created in the approval's own state+pauseId CAS write (a retried
  approval loses it, `putIfAbsent` per task), claimed as RUNNING and persisted *before* the send, and a RUNNING
  record found later becomes OUTCOME_UNKNOWN and is never resent — the tool-call execution journal's contract.
  `{action.idempotencyKey}` lets a deduplicating target make it effectively once across a crash. The claim is a CAS
  on the discussion still being IN_PROGRESS, so a cancel stored first makes it fail; a claim that cannot be written
  sends nothing and settles the record SKIPPED (cancel seen on this pod) or FAILED, never leaving it PENDING for a
  leg that may not come.
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

- A pod crash after the approval but before the claim leaves the discussion IN_PROGRESS with a PENDING record;
  groups have no crash recovery of a running discussion, so it is sent only if a later leg of that discussion runs.
- Retries configured on the referenced call (`postResponse.retryApiCallInstruction`) still apply and can repeat a
  request the target processed.

```decision-log
| 2026-11-09 | Approved-result actions run through the existing httpcalls executor (`IApiCallExecutor`), HTTPCALL only in v1 | Reuses vault resolution, the reference guard, redaction and caps instead of a second outbound path; one executor done fully | MCP-tool actions in the same change; a bespoke OutboundHttp call |
| 2026-11-09 | At-most-once: record in the approval CAS, persist a RUNNING claim before the send, never resend a RUNNING record | An external side effect cannot be exactly-once without the target's help; a double publish is worse than a visible OUTCOME_UNKNOWN | Retrying RUNNING records; a separate journal store |
| 2026-11-09 | A `system:*` (AUTO_APPROVE) approval records SKIPPED instead of running the action | The feature exists to publish only human-approved text | A `runOnAutoApprove` switch |
```
