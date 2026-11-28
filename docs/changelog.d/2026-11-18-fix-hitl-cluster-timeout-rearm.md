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
  MongoDB and PostgreSQL; on MongoDB from the primary, so a lagging secondary cannot hide an armed row) and judges
  the rows marked as HITL timeouts together:
  - a row of this pause that can still fire — pending, claimed by a fire in progress on any node, or `FAILED` with
    retries left — is never deleted, moved or re-created; a row from an older build without a pause id counts as
    this pause's while it can fire, and also when it was created after this pause began;
  - a row of this pause a person disabled is left alone (a legacy one too, by that creation-time rule);
  - when the pause's timeout fired and completed while the pause still waits (also a legacy row that fired after
    the pause began), a recovery pass creates the replacement row at once, but its next fire is due no earlier than
    one hour after that fire (or the original deadline, if later) — a safety net, not a retry loop;
  - otherwise — no row, rows of earlier pauses only, rows that can never fire again (`DEAD_LETTERED`, `FAILED` out of
    `eddi.schedule.max-retries`, switched off by an agent undeploy: older builds did all three to HITL timeouts) — a
    row is created due at the later of the original due time and the repair attempt plus a grace: none for the
    cluster sweep's ordinary repair (an overdue row is due at once — the sweep runs on a live leader), 2 minutes from
    the attempt on a single node's startup pass and when a cluster sweep restores a lost resume, as before.
  Before it writes anything the sweep re-checks that the scanned pause is still the current one, and it deletes
  only the timeout rows it read and judged, one by one by id — never every row under the name. A newer pause's row
  therefore survives (it can only be among the rows read once that pause is current, and the re-check then stops
  the sweep), and so does a row under the name that is not a HITL timeout. A row created by a sweep for a pause
  that ended meanwhile is withdrawn by its id; a withdrawal that fails is logged at `WARN` and leaves a row bound to
  the ended pause, whose fire is refused. A read that fails is retried twice within the pass (after 1 s and 4 s,
  `readRetryDelays`); if it still fails nothing more is written for that pause — on the restore path too, which used
  to create a row then and so could put an enabled twin next to a row a person had disabled. The stored rows stay as
  they are, so an armed timeout of the pause still fires; without one, the safe failure for an approval gate is the
  pause waiting for a human: the failure counts in `eddi_hitl_timeout_arm_failures_total{surface}` and logs
  at `WARN` in a cluster (the next sweep reads again) or at `ERROR` on a single node (the pause waits without a
  re-armed timeout until the next start or a person's decision). A row the store cannot decode is skipped by
  `readSchedulesByName` and logged at `WARN` with its id, so it no longer blocks the repair of its readable
  siblings; the store reads past such rows until it has `limit` decodable ones (at most 100 more raw rows), so a
  result shorter than the limit is complete, and fails the read beyond that allowance. When 100 rows or more share the name, the stale ones read are
  removed and the name is read again, up to five rounds in the same pass; once a read returns fewer than 100 rows the
  replacement is created in that pass, and only rows still remaining after five rounds defer it to a later pass. A pause the startup pass restores from
  `IN_PROGRESS` after a lost resume replaces a spent row at once (no backoff) but keeps a row of its own that can
  still fire or that a person disabled. Files: `engine/hitl/HitlCrashRecoveryObserver.java`,
  `engine/schedule/IScheduleStore.java`, `engine/schedule/mongo/MongoScheduleStore.java`,
  `datastore/postgres/PostgresScheduleStore.java`. The semantics of `countPendingSchedulesByName` and
  `readScheduleByName` are unchanged.
- **Reserved timeout names.** `POST`/`PUT /schedulestore/schedules` (and the MCP schedule tools and the agent
  import, which write through it) refuse a schedule whose name starts with `hitl-timeout-` (any case; the group
  prefix `hitl-timeout-group-` included) with `400`, as they already refused the `hitlType=hitl_timeout` marker. The
  server finds a pause's timeout by that name and deletes every row under it on resume and cancel. Files:
  `engine/hitl/HitlSchedules.java`, `engine/schedule/rest/RestScheduleStore.java`.
- **A timeout decision that can never apply ends the pause.** `HitlTimeoutHandler` used to complete the fire — and
  so leave the pause waiting forever — when the regular resume refused the decision because the plan stored with the
  pause no longer hashes to the committed one (`PlanMismatchException`, which it mistook for a stale pause). It now
  cancels the pause the timeout was armed for (the paused-only cancel bound to its pause id, audited as
  `system:timeout`; the conversation ends `EXECUTION_INTERRUPTED`), logs at `ERROR` and counts
  `eddi_hitl_timeout_undecidable_total{surface="regular",reason="plan_changed"}`. A timeout row that names no pause
  (armed by an older build) never ends one — the unbound cancel would skip the pause-identity check: it logs at
  `ERROR`, counts `reason="unbound_plan_changed"` and completes; the pause keeps waiting. Under a finite policy
  (older builds armed rows only for those, so normally the pause the row was armed for) a recovery pass arms a
  timeout bound to it (about an hour later on a cluster, at the next start on a single node), which then cancels
  it. Under `WAIT_INDEFINITELY` (the row is a leftover of an earlier, finite pause; a pause's policy is fixed when
  it pauses) recovery never arms a timeout, so nothing automatic ends it and no approval can apply: a person
  cancels it, or `eddi.hitl.pending.max-age` retention does when configured. A cancel that finds nothing to cancel while a decision on the same pause is in
  flight (`IN_PROGRESS`) or while this very pause is back in `AWAITING_HUMAN` — read from the store, not the state
  cache, and bound to the pause id — or that fails while the pause waits, is retried; a conversation paused again on
  another request completes the fire. The regular `ABORT` follows the same rule (it used to retry only on
  `IN_PROGRESS`, so a resume that failed and restored the pause in between consumed the ABORT). Every other failure of the timeout's resume — an `IllegalArgumentException` included, which
  used to be logged and completed — is now retried while the pause waits, never turned into a cancel: it can come
  from a broken deployment as well as from the decision. The group surface has no such case: `HitlPlanBinding.check`
  accepts a `system:*` decision without a plan hash, so a group timeout never meets a plan mismatch. A group or
  human-turn `ABORT` whose paused-only cancel returns `false` while the same pause still waits (a lost race) is now
  retried instead of completed.
- **A spent timeout re-armed by the sweep is counted**: `eddi_hitl_timeout_rearmed_spent_total{surface}`, so a pause
  re-armed every hour forever is visible.
- **A timeout that could not be scheduled at pause time is visible (review C7, that part only).**
  `ConversationHitlService.scheduleHitlTimeout` and the group approval and human-turn schedulers in
  `GroupHitlCoordinator` logged a failure at `WARN` and swallowed it. They now log at `ERROR` and count
  `eddi_hitl_timeout_arm_failures_total{surface}` (`regular`, `group`, `group-human`). The cluster sweep re-creates
  the missing schedule on a sweep that reaches the pause (best effort: the regular pause scan reads at most 10,000 paused conversations per pass, each group pause scan at most 1,000); a single node does so at its next start.
- **Docs.** `docs/hitl.md` → Crash Recovery describes what the sweep keeps, re-creates and re-arms, the undecidable
  outcome, the single-node limit, the startup grace (only for re-created rows: a surviving overdue row keeps its due
  time and fires once its retry time, claim and batch conditions allow, so `AUTO_APPROVE` decides soon after an
  outage longer than the timeout, with no grace) and the earlier cluster
  behaviour. Both metrics are in `docs/metrics.md` and on the "Approval timeouts by surface" panel of the Full
  Metrics Reference dashboard (Kubernetes copies regenerated with `scripts/sync-monitoring-assets.py`).

### Design decisions

- One additive store method rather than reusing `countPendingSchedulesByName` + `readScheduleByName`: the latter
  returns an arbitrary row of several, so the decision was nondeterministic, and the pending count treats a
  dead-lettered row as pending. The two existing methods keep their semantics (another branch changes the due query).
- Deleting the judged rows by id, after a re-check of the pause, rather than a new conditional delete in the store:
  no further `IScheduleStore` method (another open PR adds methods there), and a pause is committed before its
  timeout row is created, so a newer pause's row that the read returned is always caught by the re-check.
- An undecodable row is skipped by the read rather than returned flagged: the poller cannot fire it either, so it is
  no timeout of the pause, and the interface keeps its method set (only its Javadoc changed). The store reads past
  skipped rows up to a fixed allowance and fails beyond it, so `rows.size() >= limit` stays a truthful "maybe more".
- A failed read writes nothing more for the pause on every path, including the restore after a lost resume (stale
  rows a full-page cleanup removed before it stay removed): the alternative (create
  anyway) can arm an automatic decision next to a row a person disabled. The retry for a single node, which has no
  later sweep, is two short re-reads inside the pass rather than a deferred re-run: no new scheduling machinery,
  and a longer outage would fail a deferred run just the same — it ends loud (ERROR plus the arm-failure counter)
  with the pause waiting for a human.
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
  and normally fire within about two minutes of the upgrade, once the poller's retry, claim and batch conditions
  allow (a `FAILED` row waits for its retry time); pauses whose deadline passed long ago are decided by their policy
  when their row fires. A finite timeout a person disabled stays disabled and does not fire.
- A still-waiting pause whose timeout row an older build dead-lettered, retried out or disabled with its agent gets
  a fresh timeout and is decided by its policy.
- A timeout whose decision can never apply now cancels the pause instead of leaving it waiting — when the row is
  bound to its pause. A row an older build armed without a pause id does not cancel: its fire completes and the pause
  keeps waiting until recovery arms a bound timeout (about an hour later in a cluster, at the next start on a single
  node) or a person decides.
- The cluster sweep's `re-armed N schedule(s)` WARN line now appears only when a schedule was actually created.
- A schedule named `hitl-timeout-…` can no longer be created or updated through the schedule API, the MCP tools or
  an import (`400`). A user schedule that already has such a name keeps working but can only be deleted or renamed
  away from the prefix; an export that contains one no longer imports until the name is changed.
- A schedule row under a timeout name that is not a HITL timeout is no longer deleted by the recovery sweep (the
  resume and cancel paths still delete every row under the name, as before).

### Tests

- `HitlTimeoutRearmSweepTest`: five consecutive cluster sweeps over one schedule store — regular pause, group
  approval and HUMAN turn keep their row and deadline and fire there; lost, dead-lettered, out-of-retries,
  undeploy-disabled and stale rows are replaced once; claimed, retrying, hand-disabled (also legacy) and legacy live
  rows are untouched; spent rows (also legacy) are re-armed once after the backoff, timed from this pause's own
  fire; a restored pause replaces its spent row and keeps its hand-disabled one; a failed read writes nothing; a
  foreign row under the name neither counts nor is deleted; a newer pause's row read by the sweep survives, and the
  withdrawal removes only the sweep's own row (a failed withdrawal is not fatal); a read capped at 100 rows removes
  the stale rows and keeps the protected row beyond them. The store stub enforces the read limit.
- `HitlTimeoutHandlerTest`: a plan mismatch cancels the pause and is counted; an unbound row never cancels; a cancel
  lost to an in-flight decision, to a restore of the same pause (also behind a stale cached state) or a failing
  cancel is retried, a newer pause or one that is gone completes — for the undecidable cancel and the regular
  `ABORT`; a generic `IllegalArgumentException` is retried and never cancels; group and human-turn `ABORT` lost
  races are retried, a newer pause completes.
- `HitlCrashRecoveryObserverTest`, `HitlCrashRecoveryObserverCoverageTest`: no write when the pause ended before the
  sweep writes; withdrawals by id; nothing deleted by name.
- `RestScheduleStoreTest`: a reserved name is refused on create and update.
- `ConversationServiceHitlCoverageTest.TimeoutOnChangedPlan`: the plan-mismatch path end to end through the real
  `ConversationHitlService` resume and cancel (stores mocked) — nothing runs, the pause ends
  `EXECUTION_INTERRUPTED`.
- `MongoScheduleStoreTest`, `PostgresScheduleStoreUnitTest`: `readSchedulesByName` — the exact name filter and limit,
  a zero limit reads nothing, MongoDB reads the primary (two distinct views), an undecodable row is skipped and its
  sibling returned, the read goes past an undecodable row in the first 100 to a protected row at raw #101, and too
  many undecodable rows fail the read. `HitlTimeoutRearmSweepTest` (store fake with the same contract): the same
  undecodable-plus-protected layout creates nothing; on the restore path a read that keeps failing creates nothing,
  leaves the hand-disabled row as it is and counts the failure, and a read that succeeds on the retry is judged
  normally.
- `ConversationServiceHitlCoverageTest`, `GroupHitlCoordinatorTest`: the arm-failure counter (the group one also
  without a registry).

### Review follow-ups (same PR)

- Stale rows are deleted after the replacement is created, not before: a spent row is the only record of when its
  timeout fired, and with it the one-hour re-arm backoff, so it must survive a create that fails. A full page of
  stale rows is cleared and read again in the same pass instead of waiting for the next one (a standalone node has
  none).
- Read retries share one budget per recovery pass (30 s): with the store down, a thousand pauses no longer wait
  their own 1 s and 4 s each. Once it is spent a failed read is not retried and the pause stays unarmed this pass.
- `findDueSchedules` (MongoDB and PostgreSQL) skips and names a row it cannot decode instead of failing the whole
  poll, so a replacement timeout is not starved by an undecodable sibling. It reads past such rows — the poll
  batch plus the same allowance of 100 — until it has a full batch of decodable ones, so undecodable rows at the
  head of the `next_fire` order cannot take every poll's batch; beyond the allowance it logs at `ERROR`.
- A legacy timeout row (no pause id) of another surface no longer passes for the pause being repaired: a group
  approval and a HUMAN member turn share the schedule name `hitl-timeout-group-<id>`, and the handler routes a fire
  by surface, so an armed approval row left behind used to stop the turn's `SKIP_TURN`/`ABORT` timeout from being
  re-created. A row without a surface counts as `regular`, as the handler routes it.

### Left open

- On a single node a timeout that could not be scheduled stays missing until the next restart (no periodic repair
  without a cluster).
- `readSchedulesByName` has no ordering and the sweep reads at most 100 rows per name; with more it removes the
  stale rows it read (keeping this pause's spent row, which carries the backoff) and reads again, up to five
  rounds, before it gives up until a later pass. An undecodable row stays (logged on every pass that reads the name)
  until someone deletes it.
- A single node whose schedule store stays unreadable through the in-pass retries of its startup pass leaves the
  affected pauses without a re-armed timeout until the next start (ERROR log plus the arm-failure counter); there is
  no periodic repair without a cluster.
- A pause whose state cannot be read counts as still current when the sweep creates the replacement (as the
  post-create check always did), but never when it deletes: stale rows are removed only on a confirmed pause
  identity and only after the replacement exists, so an unreadable pause leaves them for a later sweep.
- The delete of a stale row re-reads the row and skips it if its lifecycle moved (an operator requeued it); the
  window between that read and the delete needs a compare-and-delete in the store, which does not exist.
- The resume and cancel paths (`ConversationHitlService.deleteHitlTimeoutSchedule`, the group equivalents) still
  delete by name.
