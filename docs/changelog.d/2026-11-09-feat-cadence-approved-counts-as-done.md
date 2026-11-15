## ✨ feat(groups): a human-approved backlog task can count as done at cadence writeback (2026-11-09)

**Repo:** EDDI (`feat/cadence-approved-counts-as-done`)

### Why

`TeamCadenceService.writebackCompleted` kept a pulled backlog task done only when the matching
discussion task was `VERIFIED`; everything else went back to `PENDING` with feedback appended
(the cross-run retry loop). A task approved by a person at a TASK-granularity HITL gate becomes
`COMPLETED`, not `VERIFIED`, so a standing team that uses human approval instead of a VERIFY
phase had every approved task pulled and executed again on its next run — for a team that
publishes, the same post twice. The only workaround was an extra VERIFY phase with a verifier
agent.

### What changed

- **New switch `hitlConfig.approvalCompletesBacklogTask`** (boolean, default `false`) on the
  group configuration ([`AgentGroupConfiguration.HitlConfig`](../../src/main/java/ai/labs/eddi/configs/groups/model/AgentGroupConfiguration.java)).
  With it on, the writeback also keeps a task done when a person approved it: the backlog task
  lands `COMPLETED` (not `VERIFIED`, `verified=false`) with the approved result — the approver's
  edit when `review.allowResultEdits` was used — and no retry feedback is appended.

  ```json
  "hitlConfig": { "granularity": "TASK", "timeoutPolicy": "WAIT_INDEFINITELY", "approvalCompletesBacklogTask": true }
  ```

- **A durable approval marker.** The task list had no record of *how* a task became `COMPLETED`.
  [`SharedTaskList`](../../src/main/java/ai/labs/eddi/configs/groups/model/SharedTaskList.java)
  gains a side map `approvals: taskId → TaskApproval(decidedBy, at, edited)`, written by
  `approveTask(taskId, decidedBy)` and `approveTaskWithEditedResult`, cleared by
  `resetFromAnyToAssigned` (a new attempt supersedes the approval, as it already superseded the
  reviewer edit). `GroupHitlCoordinator` now passes the decision's `decidedBy` on both approval
  paths (explicit `taskApprovals` and the approve-all / timeout path).
- **Metrics.** `WorkspaceMetrics.tasksApproved` and `MemberStats.tasksApproved` count these
  tasks; `tasksVerified` is left meaning verified. Before, an approved task counted as a
  `tasksFailed` for its member.
- **Docs:** [`group-conversations.md`](../group-conversations.md#standing-teams-i13) (Standing
  Teams, new "Human approval as done" paragraph), [`hitl.md`](../hitl.md) (group config table).
- **Manager:** a checkbox with help text in the group HITL editor, shown at TASK granularity;
  `GroupHitlConfig.approvalCompletesBacklogTask` in the API types; two i18n keys in all 11 locales.
- **Platform Operator:** a bullet explaining the writeback rule and the switch; revision bump.

### Design decisions

- **Where the switch lives — `hitlConfig` on the group, not the cadence.** It qualifies the
  approval gate, so it sits next to `granularity` and `onTaskRejection` where that gate is
  configured, and it is versioned with the group. A cadence-level flag would have needed the
  workspace to record which cadence started the running discussion (it does not), and the
  workspace is deliberately unversioned. The writeback reads it from the group's **current**
  version (field-injected `IAgentGroupStore`, like `teamBudgetService`); an unreadable config
  falls back to the default rule, logged.
- **How "approved by a person" is told from "merely COMPLETED".** The outcome must be
  `COMPLETED` (a later VERIFY that failed it leaves it `FAILED`) **and** carry an approval record
  whose `decidedBy` is not a `system:*` actor. A task completed in an ungated phase has no record.
  A side map rather than a 16th `TaskItem` component, following `reviewerEdits`, `awardedBids`
  and `reworkCounts`: no positional-constructor churn, and the map is persisted with the
  discussion by the same Jackson document path on MongoDB and PostgreSQL. A document written
  before it reads back with an empty map (such tasks simply do not count as approved).
- **Timeouts do not count.** `timeoutPolicy: AUTO_APPROVE` approves with
  `decidedBy=system:timeout`; that is a decision nobody made, so its tasks return to `PENDING`
  as before — the same line earned-autonomy evidence draws (`TIMEOUT`/`SYSTEM` refused).
  `system:` is a reserved principal prefix, so no caller can claim it. A `null` decider (auth
  disabled) is a person, consistent with the coordinator's existing fresh-turn-budget rule.
- **A distinct counter**, `tasksApproved`, rather than crediting `tasksVerified`.

### Tests

`TeamCadenceApprovalWritebackTest` (new): default and explicit `false` unchanged; approved task
stays done with its result and counts; edited approval keeps the edit; ungated completion still
returns to PENDING; timeout approval does not count; unnamed approver counts; approved-then-failed
VERIFY retries; VERIFIED untouched; unreadable config and missing store fall back to the default.
`SharedTaskListHitlTest.Approvals` (new): marker recorded/cleared, `byPerson`, old JSON without
`approvals` loads, round-trip. `GroupConversationServiceHitlTest`: the timeout approve-all path
records `system:timeout`. Manager: `group-hitl-editor.test.tsx`.

### Not done

The workspace page does not display `tasksApproved` yet (it is in the workspace JSON).
