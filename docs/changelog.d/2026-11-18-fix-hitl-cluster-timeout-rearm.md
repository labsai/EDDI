## 🐛 fix(hitl): approval timeouts fire in cluster mode — the recovery sweep no longer re-arms armed schedules (2026-11-18)

**Repo:** EDDI (`fix/hitl-cluster-timeout-rearm`)

### What changed and why

- **Cluster-mode timeout livelock (review C1, live CL1).** With `eddi.messaging.type=nats` the leader-elected
  `ClusterHitlRecovery` sweep runs every `eddi.cluster.hitl-recovery.interval` (60 s) and called
  `HitlCrashRecoveryObserver.rearmSchedule` for every finite pause. That method deleted the timeout schedule and
  created a new one at `max(pausedAt + timeout, now + 2 min)`, so any deadline within two minutes of a sweep was
  pushed out again on every pass: `AUTO_APPROVE`, `AUTO_REJECT` and `ABORT` never fired in a cluster — for regular
  pauses, group approvals and HUMAN member turns — and a `FAILED` timeout waiting for its retry was reset each
  minute. On a two-node cluster three pauses with PT30S, PT150S and PT5M were still waiting after 13 minutes.
  `rearmSchedule` now reads every row stored under the timeout's name (new `IScheduleStore.readSchedulesByName`, on
  MongoDB and PostgreSQL) and judges them together:
  - a row of this pause that can still fire — pending, claimed by a fire in progress on any node, or `FAILED` with
    retries left — is never deleted, moved or re-created; a row from an older build without a pause id counts as
    this pause's while it can fire;
  - a row of this pause a person disabled is left alone;
  - when the pause's timeout fired and completed while the pause still waits (also a legacy row that fired after
    the pause began), it is re-armed one hour after that fire — a safety net, not a retry loop;
  - otherwise — no row, rows of earlier pauses only, rows that can never fire again (`DEAD_LETTERED`, `FAILED` out of
    `eddi.schedule.max-retries`, switched off by an agent undeploy: older builds did all three to HITL timeouts), or
    rows that cannot be read — every row under the name is deleted and one is created at the original due time. An
    overdue one is due at once under the cluster sweep (no grace — the sweep runs on a live leader) and 2 minutes
    after a single node starts, as before.
  A pause the startup pass restores from `IN_PROGRESS` after a lost resume still replaces whatever row is there.
  Files: `engine/hitl/HitlCrashRecoveryObserver.java`, `engine/schedule/IScheduleStore.java`,
  `engine/schedule/mongo/MongoScheduleStore.java`, `datastore/postgres/PostgresScheduleStore.java`. The semantics of
  `countPendingSchedulesByName` and `readScheduleByName` are unchanged.
- **A timeout decision that can never apply ends the pause.** `HitlTimeoutHandler` used to complete the fire — and
  so leave the pause waiting forever — when the regular resume refused the decision because the plan stored with the
  pause no longer matches the approved one (`PlanMismatchException`, which it mistook for a stale pause) or as
  invalid (`IllegalArgumentException`); a group `GroupPlanMismatchException` likewise. It now cancels the pause the
  timeout was armed for (the paused-only cancel bound to its pause id, audited as `system:timeout`), logs at `ERROR`
  and counts `eddi_hitl_timeout_undecidable_total{surface,reason}`; a cancel that cannot be applied yet is retried.
  A group or human-turn `ABORT` whose paused-only cancel returns `false` while the same pause still waits (a lost
  race) is now retried instead of completed.
- **A timeout that could not be scheduled at pause time is visible (review C7, that part only).**
  `ConversationHitlService.scheduleHitlTimeout` and the group approval and human-turn schedulers in
  `GroupHitlCoordinator` logged a failure at `WARN` and swallowed it. They now log at `ERROR` and count
  `eddi_hitl_timeout_arm_failures_total{surface}` (`regular`, `group`, `group-human`). The cluster sweep re-creates
  the missing schedule within one interval; a single node does so at its next start.
- **Docs.** `docs/hitl.md` → Crash Recovery describes what the sweep keeps, re-creates and re-arms, the undecidable
  outcome, the single-node limit, the startup grace (only for re-created rows: a surviving overdue row fires at the
  first poll, so `AUTO_APPROVE` decides right after an outage longer than the timeout) and the earlier cluster
  behaviour. Both metrics are in `docs/metrics.md` and on the "Approval timeouts by surface" panel of the Full
  Metrics Reference dashboard (Kubernetes copies regenerated with `scripts/sync-monitoring-assets.py`).

### Design decisions

- One additive store method rather than reusing `countPendingSchedulesByName` + `readScheduleByName`: the latter
  returns an arbitrary row of several, so the decision was nondeterministic, and the pending count treats a
  dead-lettered row as pending. The two existing methods keep their semantics (another branch changes the due query).
- A spent row of a still-waiting pause is re-armed after an hour rather than never (it could hide a pause forever) or
  at once (a decision that keeps failing would fire every sweep). The cases that can never succeed end the pause in
  the handler instead, so the hour is a safety net.
- Not the shared timeout-schedule component `04-recommendations.md` proposes: three open PRs edit
  `GroupHitlCoordinator`; its change here is a setter and two catch blocks.
- Review C11 (approved-result actions left `PENDING` by a crash-recovery cancel) was not changed: on `main` the
  group conversation store settles owed records as `NOT_SENT` on every write of a document in a terminal state
  (`GroupConversationStore.settleOwedApprovedActionsIfEnded`), and `cancelStored` writes `CANCELLED` through
  `updateIfStateAndPause`, so the cancel path settles them.

### Behaviour an upgrader notices

- In a cluster, finite HITL timeouts fire at their deadline. Schedules the old sweep left at `now + 2 min` are kept
  and fire within two minutes of the upgrade; pauses whose deadline passed long ago are decided by their policy then.
- A still-waiting pause whose timeout row an older build dead-lettered, retried out or disabled with its agent gets
  a fresh timeout and is decided by its policy.
- A timeout whose decision can never apply now cancels the pause instead of leaving it waiting.
- The cluster sweep's `re-armed N schedule(s)` WARN line now appears only when a schedule was actually created.

### Tests

- `HitlTimeoutRearmSweepTest`: five consecutive cluster sweeps over one schedule store — regular pause, group
  approval and HUMAN turn keep their row and deadline and fire there; lost, dead-lettered, out-of-retries,
  undeploy-disabled, unreadable and stale rows are replaced once; claimed, retrying, hand-disabled and legacy live
  rows are untouched; spent rows (also legacy) are re-armed once after the backoff; a restored pause replaces its
  spent row.
- `HitlTimeoutHandlerTest`: plan mismatch and invalid decision cancel the pause and are counted; a failing cancel is
  retried; group plan mismatch; group and human-turn `ABORT` lost races are retried, a newer pause completes.
- `MongoScheduleStoreTest`, `PostgresScheduleStoreUnitTest`: `readSchedulesByName`.
- `ConversationServiceHitlCoverageTest`, `GroupHitlCoordinatorTest`: the arm-failure counter.

### Left open

- On a single node a timeout that could not be scheduled stays missing until the next restart (no periodic repair
  without a cluster).
