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
  `rearmSchedule` now first asks the schedule store what exists under the name (`countPendingSchedulesByName`,
  `readScheduleByName`) and creates a schedule only when none that can fire exists for the pause. An armed row —
  pending, claimed by a fire in progress, or `FAILED` awaiting its retry — is never deleted, moved or re-created.
  A missing schedule (the real crash case) is re-created once at the original due time; an overdue one is due at
  once under the cluster sweep (no grace — the sweep runs on a live leader) and 2 minutes after a single node
  starts, as before. A single live row bound to an earlier pause is replaced; a row from an older build without a
  pause id is kept. A spent row for the current pause (the timeout fired and completed) is not re-armed, so a
  decision the handler completed as unappliable is not retried every sweep. A pause the startup pass restores from
  `IN_PROGRESS` after a lost resume still replaces whatever row is there, since the fire that started that resume is
  spent. File: `engine/hitl/HitlCrashRecoveryObserver.java`.
- **A timeout that could not be scheduled at pause time is visible (review C7, that part only).**
  `ConversationHitlService.scheduleHitlTimeout` logged a failure at `WARN` and swallowed it. It now logs at `ERROR`
  with the conversation id and counts `eddi_hitl_timeout_arm_failures_total{surface="regular"}` (in
  `docs/metrics.md` and on the "Approval timeouts by surface" panel of the Full Metrics Reference dashboard). The
  cluster sweep re-creates the missing schedule within one interval; a single node does so at its next start.
- **Docs.** `docs/hitl.md` → Crash Recovery now describes what the sweep keeps and what it re-creates, the single-node
  limit, and the earlier cluster behaviour.

### Design decisions

- No new store method: `countPendingSchedulesByName` already counts the rows that can fire (enabled, or disabled
  only by an undeploy) on both databases, and `readScheduleByName` gives the pause binding. With several rows under
  one name the lookup cannot say which row it returned, so any live row counts as armed; a stale one fires, is
  refused as a pause mismatch and completes, and the next sweep then re-creates the schedule.
- Not the shared timeout-schedule component `04-recommendations.md` proposes: the pause-time schedulers in
  `ConversationHitlService` and `GroupHitlCoordinator` were not part of the defect, and three open PRs edit
  `GroupHitlCoordinator`.
- Review C11 (approved-result actions left `PENDING` by a crash-recovery cancel) was not changed: on `main` the
  group conversation store settles owed records as `NOT_SENT` on every write of a document in a terminal state
  (`GroupConversationStore.settleOwedApprovedActionsIfEnded`), and `cancelStored` writes `CANCELLED` through
  `updateIfStateAndPause`, so the cancel path settles them.

### Behaviour an upgrader notices

- In a cluster, finite HITL timeouts fire at their deadline. Schedules the old sweep left at `now + 2 min` are kept
  and fire within two minutes of the upgrade; pauses whose deadline passed long ago are decided by their policy then.
- The cluster sweep's `re-armed N schedule(s)` WARN line now appears only when a schedule was actually missing.

### Tests

- New `HitlTimeoutRearmSweepTest`: five consecutive cluster sweeps over one schedule store for a regular pause, a
  group approval and a HUMAN turn — the armed row is the same row with the same deadline and fires there; a lost
  schedule is created once; an overdue lost one is due at once; a `FAILED` retry row is kept; a spent row is not
  re-armed; a stale row of an earlier pause is replaced; a legacy row is kept; a restored pause replaces its spent
  row.
- `ConversationServiceHitlCoverageTest.createScheduleThrows_swallowed` asserts the new counter.

### Left open

- On a single node a timeout that could not be scheduled stays missing until the next restart (no periodic repair
  without a cluster). The group pause-time schedulers (`GroupHitlCoordinator`) still only `WARN` on failure.
