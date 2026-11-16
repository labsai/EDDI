## ✨ feat(groups): a paused cadence run no longer blocks its standing team, and a stopped run keeps its finished tasks (2026-11-15)

**Repo:** EDDI (`feat/cadence-nonblocking-approvals`, branched from `feat/cadence-approved-counts-as-done` — PR #1374 — with `origin/main` merged in)

### Why

A standing team whose cadence run pauses for a person was blocked: `TeamCadenceService.reconcile`
treats `AWAITING_APPROVAL` / `AWAITING_HUMAN_INPUT` as still running, so every later fire was
skipped until the claim TTL (`PT24H`). When the pause ended without completing — an `AUTO_REJECT`
timeout makes the discussion `REJECTED`, an `ABORT` timeout or the TTL reclaim makes it
`CANCELLED` — `writebackFailure` returned every pulled task to `PENDING`, including the ones that
were already verified or approved, so the next run did and paid for them again. A team plan had to
drop human approval from cadence runs because of this.

### What changed

Two group switches on `hitlConfig`, both off by default; stored configs behave exactly as before.

- **`maxPausedCadenceRuns`** (integer `0`–`10`, absent = `0`). A fire that finds the claim-holding
  discussion paused *parks* it: [`GroupWorkspace`](../../src/main/java/ai/labs/eddi/configs/groups/model/GroupWorkspace.java)
  gains `parkedRuns: [ParkedRun(discussionId, claimedAt, pulledTaskIds)]`. A parked run keeps its
  tasks `IN_PROGRESS` (so `findExecutableTasks` never hands them to the new run), keeps its original
  claim time (the TTL still reclaims it), and is reconciled on its own at every fire and workspace
  read. The new run pulls only free tasks. A working (not paused) run is never parked; at the limit
  the fire is skipped and the reason says so. Counter `eddi_team_cadence_runs_parked_total`.
- **`stoppedRunKeepsFinishedTasks`** (boolean). A run that ends `FAILED`, `CANCELLED`, reclaimed, or
  `REJECTED` at a `TASK` pause writes its finished tasks back by the completed-run rule (`VERIFIED`
  stays verified; a person-approved task stays `COMPLETED` when #1374's
  `approvalCompletesBacklogTask` is on too); only the rest — the undecided tasks — return to
  `PENDING`. A reject at a `PHASE` pause and a vanished discussion keep the old rule.
- [`TeamCadenceService`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/TeamCadenceService.java):
  the writeback now works on a `Run` (claim holder or parked) so one code path settles both;
  `claim` persists the park in the same revision-checked write as the new claim and re-parks on a
  fresh copy when it loses to an unrelated write; the verified/approved writeback is shared by
  `writebackCompleted` and `writebackFailure`; the group's `hitlConfig` is read by one helper.
- Stale-claim reclaim (review fix, also present before this branch): the writeback now reads the
  discussion after the cancel instead of the snapshot taken before it. If an approval resumed the
  run in between, the cancel only signals the live leg, so the claim stays held and the run is
  settled from its final task list once the leg ends; before, the tasks it finished went back to
  `PENDING`. `eddi_team_cadence_claims_reclaimed_total` now counts only claims actually released.
- `HitlConfigValidation` range-checks `maxPausedCadenceRuns`.
  `TeamHandoffIntake.deleteBacklogTask` refuses a task a parked run holds (`GroupWorkspace.isHeldByRun`).
- Platform Operator prompt learns both switches (operator revision bumped).
- Docs: [group-conversations.md](../group-conversations.md#standing-teams-i13), [hitl.md](../hitl.md),
  [team-budgets.md](../team-budgets.md).

### Design decisions

- **Park, not per-task claims.** The single claim (`runningDiscussionId`) still means "the one run
  that is working"; paused runs move aside. That keeps the existing CAS protocol intact (two pods
  still start one run) instead of replacing it with N claims.
- **A cap, not a boolean.** Every parked run can be resumed and spend; a fast cadence with a slow
  approver would otherwise start a run per fire. The cap bounds concurrent runs to `n + 1`.
- **No unattended continuation after a timeout.** `stoppedRunKeepsFinishedTasks` changes only the
  writeback; the HITL coordinator is untouched, so an `AUTO_REJECT` still ends the run where it stood.
- Both switches on `hitlConfig`, next to #1374's, for the same reason: versioned with the group,
  and the workspace does not record which cadence started a run.

### Verification

`TeamCadenceNonBlockingApprovalsTest` (28, new) plus the existing cadence, handoff, workspace-store
and HITL-validation suites. Mutation checks on the parking guard, retry re-park, PHASE exclusion,
lost-settle handling, reclaim-as-stop, parked removal, limit bound, held-task check and both switch
reads (11 mutants, all killed). The workspace is a Jackson document on both stores, so
`parkedRuns` needs no store change; a `parkedRuns_roundTrip` case in the MongoDB and PostgreSQL
`GroupWorkspaceStore` container tests confirms it (13 tests, run locally with Docker).

### Next

- The Manager's workspace page does not show `parkedRuns` yet (it is in the API).
