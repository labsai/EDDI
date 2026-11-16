## 🐛 fix(groups): approved-result actions — stale cancel fenced, unreadable config refuses the approval, FAIL_TASK at discussion end (2026-11-15)

**Repo:** EDDI (`feat/group-approved-result-actions`)

Review follow-ups on PR #1375.

### What changed and why

- **A stale cancel can no longer overwrite a claim.** A cancel on another pod reads the running discussion, the
  leg then writes its `RUNNING` claim, and the cancel's compare-and-set (state `IN_PROGRESS`, no pause) still
  matched — its stale `PENDING` record was settled `NOT_SENT` while the leg sent the request, whose outcome write
  then failed against `CANCELLED`. `SharedTaskList` now carries `approvedActionsRevision`, which
  `GroupApprovedResultActions` changes with every claim and outcome (and restores when that write fails). A
  conditional write in `GroupConversationStore` (`updateIfState`, `updateIfStateAndPause`) that *settles* owed
  records also requires `taskList.approvedActionsRevision` to be what the writer read, through the existing
  `storeIfFieldsEqual` dotted-path CAS on MongoDB and PostgreSQL. The stale cancel loses (`409`); a cancel that read
  the claim lands and reads it `OUTCOME_UNKNOWN`. Writes that settle nothing keep the plain state CAS.
- **An approval is not consumed when the group configuration cannot be read.** `GroupHitlCoordinator` refused the
  approval only through strict plan binding; a decision with the matching `planHash` completed the task and
  persisted the decision without the action record it owed. A TASK-pause decision that would approve a task now
  throws `ResourceStoreException` before anything is mutated — the pause and its awaiting tasks stay; a timeout's
  `AUTO_APPROVE` retries through `RetryLaterException`. Rejections still apply (they owe no action).
- **Nor when the paused phase drifted.** With the configuration read but the bookmarked phase renamed, moved or
  removed, `recordApprovedResultActions` skipped the record while the task completion persisted with the resume
  CAS; the resumed leg's drift guard then restored the pause but not the task, so a retry after the fix found
  nothing awaiting and the action never ran. The same refusal now fires first, through one
  `bookmarkedPhaseDrift` check that recording also uses.
- **Dashboard panel id.** The *Approved-result actions by outcome* panel moved from id `447` (taken on `main` by
  the *Input Parser* row) to `449`; the k8s split copy was regenerated with `scripts/sync-monitoring-assets.py`.
- **`FAIL_TASK` at discussion end.** `SharedTaskList.settleOwedApprovedActions` turned an orphaned `RUNNING` claim
  into `OUTCOME_UNKNOWN` but left its task `COMPLETED`. It now applies the same `onFailure` rule as the live leg,
  through one shared `applyFailurePolicy`, stamping the discussion's end time so repeated reads agree.
- **Docs** (`docs/group-approved-result-actions.md`): a store outage leaves an `IN_PROGRESS` discussion with a
  `PENDING` record (not an ended one), and the update endpoint is `PUT /groupstore/groups/{id}?version=`.
  Operator prompt: the refused approval, the cancel `409` and `OUTCOME_UNKNOWN` failing the task (fingerprint only).

### Tests

`GroupApprovedResultActionsStaleCancelTest` replays cancel read → claim write → cancel write → send against an
in-memory CAS storage; `GroupConversationStoreApprovedActionsTest` covers the fence and terminal `FAIL_TASK` /
`KEEP_COMPLETED`; the Mongo and PostgreSQL container tests run the stale and current cancel on real backends;
`GroupHitlCoordinatorApprovedActionsTest` covers the refused approval (unreadable configuration, renamed and
removed paused phase).
