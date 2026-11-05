## ✨ feat(groups): team learning loop — bets and outcomes (2026-11-03)

**Repo:** EDDI (`feat/team-learning-loop`), built on the local integration branch `feat/operator-mcp-integration` because
PRs #1222–#1231 are not merged yet

### What changed and why

Approval gates record that a human said yes; nothing recorded what came of it, and a backlog task was free text. Two
opt-in pieces close that:

- **Bets as first-class backlog tasks.** `TaskItem.bet` (`hypothesis`, `metric`, `target`, `reviewDate`, `reviewedAt`)
  on workspace backlog tasks, filed through REST, `add_team_task`, `addGroupTask`, `handoff_to_team` (and kept in the
  REVIEW inbox until accepted) and the Manager. `GET …/workspace/backlog/due-reviews` lists bets whose date has arrived
  and that have no verdict; `POST …/backlog/{taskId}/bet/review` closes or reschedules one; `list_team_backlog` takes
  `dueForReview`. Every task copy site (all status transitions, the cadence writeback, dependency rewrite on delete,
  handoff intake) now carries the bet. No bet fields = an ordinary task; stored tasks read unchanged.
- **Outcome events.** `POST/GET /groupstore/groups/{groupId}/outcomes` records a real-world result against a backlog
  task or an approval decision. Off until the group has an `outcomeConfig` (409 otherwise). Bounded per subject, per
  team and by age, idempotent by `idempotencyKey` (scoped to the team), the principal is the authenticated caller. Stored
  in `group_outcomes` on MongoDB and PostgreSQL ([`IOutcomeEventStore`](../../src/main/java/ai/labs/eddi/engine/groups/outcomes/IOutcomeEventStore.java)),
  cascaded on permanent group deletion, pseudonymized on GDPR erasure. The RETRO phase quotes the newest
  `retroDigestSize` outcomes as labelled data; `GET /hitl/analytics/outcomes` joins approval decisions with them per category.
- **Manager.** Workspace page: *File as a bet*, the bet on each backlog row, a *Bets due for review* section with Mark
  reviewed / Reschedule, per-task *Outcomes*, a *What came of approvals* table and the outcome setting on the group
  config panel. `groupLearning.*` in all 11 locales.
- **Platform Operator.** Read-only grants for the outcomes, due-reviews and outcome-analytics endpoints and a prompt
  section; **revision 56** (above the open #1245's 55). Deliberately not granted: recording an outcome or a bet verdict.

### Design decisions

- Outcomes are team-scoped and never decide anything: no promotion, demotion or re-gating reads them.
- `outcomeConfig == null` is *off*, not a default (unlike `retroConfig`): the endpoint refuses and the retro is unchanged.
- A task id joins an approval decision only through a `TASK` unit, so an unrelated row that carries the same id is not
  credited.
- A bet is due whatever the task's status.

### Not in this PR

Cross-team lesson sharing (explicit grants) and the scheduled, cost-capped consolidation of retro lessons are a second
PR. Not run locally: the `*IT` store tests (Docker) and the Playwright suite; CI is the gate for those.

Docs: [`docs/team-learning-loop.md`](../team-learning-loop.md).
- Final Platform Operator revision for 6.7.0 after merging main, #1259, #1260 and #1265 into this branch: **60** (the revisions 55–59 named in those PRs' fragments and earlier on this branch were intermediate).
- Platform Operator: closes the remaining gaps of the ten 6.7 follow-up notes; final revision 60. The prompt now names the
  oversight deployment properties (sampling switch, retentions, maintenance interval, suspension cache TTL), the served
  `approvalContext` shape and its render outcomes, human task results under TASK-granularity approval, the free human
  tie-break, Slack's edit limits and `hitlShareRuleDrafts`, reason-code and category rules, `hitl.edit.refused`, the
  human-only backlog delete, and the tool-call guardrails' `disclosure` parameters, could-not-decide reasons, trace step
  and budget accounting.
