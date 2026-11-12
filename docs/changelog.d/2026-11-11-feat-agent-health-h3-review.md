## 🐛 fix(health): the outcome evaluation never judges on what it could not read, and the feed always moves on (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Review findings on PR #1389 (H3, session outcomes), documented in [`docs/agent-health.md`](../agent-health.md#the-evaluation).

- **Unreadable inputs fail the evaluation instead of deciding it.** `HealthConfigPolicy.require` throws
  `HealthStoreException` when the agent store fails (an absent version still gives the defaults; `of` stays lenient
  for the self-report tool and the drill-down), and `LazyRatings` throws when the ratings cannot be read. Before, a
  failed read gave the defaults or "no ratings", and the session was stored as final — `ASSUMED_RESOLVED` from
  silence with no `dueAt` — so a later recovery did not judge it again. Now nothing is written and the feed's
  retry, or the session's unchanged due time, judges it again.
- **The retry limit runs from the first failure.** A failed conversation held the watermark only while its *capture*
  was less than an hour old, so a backfilled turn captured days ago was released on its first failure and, with no
  due session, never judged. The evaluator now remembers, per node and bounded (Caffeine), when each conversation
  first failed; a failure never pulls the watermark back past where the pass started.
- **The overlap cannot stall the feed.** Every pass restarts at `watermark - outcome-feed-overlap`; when more than a
  pass's worth of conversations fell in those 15 minutes, `reached` stayed behind the watermark and every pass
  re-read the same prefix. The overlap re-read now gets at most half of the pass's time and conversations, then the
  feed continues from the watermark.
- **Gapless feed pages on equal timestamps.** `IHealthTurnStore.conversationsUpdatedBetween` takes the last
  conversation id of the previous page and continues after the pair (`lastUpdatedAt`, `conversationId`) — a
  `HAVING` in PostgreSQL, a post-group `$match` in MongoDB. Before, more than a page of conversations sharing one
  millisecond made the feed step past it and skip the rest. Contract test added.
- **A rating mark on an already-overdue session survives the running evaluation.** `markDue` refused a mark later
  than a stored due time, and the evaluation's upsert then cleared that older due time, so a rating saved while an
  overdue session was being judged was lost. A mark now always sets the named sessions due at its own time (they
  stay due; callers pass `now`), which the upsert keeps.
- **A changed conversation rating marks every session**, as a withdrawal does: it counts at its new `updatedAt`, and
  the event does not say which session it was in before.
- **First output carried across retention.** Re-judging a session whose first rows the retention removed now takes
  that session's stored `firstOutputStep` when the step it names is no longer read, so the session is not judged
  unengaged.
- **The pending-evaluation hint is appended** to a measure's own hint (low coverage, no data) instead of replacing
  it.
- **Of more than 32 actions a turn keeps the last 32** (was: the first 32), where the ending and mapped actions are.
- **PostgreSQL**: the `self_reported_outcome` migration check looks at the `health_turns` the search path resolves
  (`to_regclass`), not a same-named table in another schema.

### Design decisions

- Left as is: a single session longer than `MAX_RANGE_TURNS` (10,000 rows) is still not judged; resumable partial
  aggregation would need new persisted state in both stores for a conversation of 10,000 turns without a 24-hour
  pause.
- The action cap stays config-free: the factory does not read `healthConfig` on the turn's thread. A mapped action
  followed by 32 or more other actions in the same turn is still dropped.

## 🐛 fix(health): the outcome feed persists a cursor, so no part of the overlap or a shared timestamp is skipped (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

Three further review findings on PR #1389, all about the outcome feed persisting only an `Instant` watermark.
Documented in [`docs/agent-health.md`](../agent-health.md#the-evaluation).

- **The feed's state is a cursor**, `HealthFeedCursor(watermark, afterId, replayAt, replayAfterId)`:
  `IHealthOutcomeStore.feedCursor()` / `setFeedCursor(...)` replace `setWatermark`; `watermark()` is now a default
  method over the cursor (the lag gauge and the report hint read it). MongoDB keeps the one `healthrollupstate`
  document `outcomes` and adds `afterId`, `replayAt`, `replayAfterId` (an older document with `at` alone reads as a
  bare watermark). PostgreSQL adds a nullable `ref VARCHAR(255)` to `health_rollup_state`
  (`ADD COLUMN IF NOT EXISTS`, run after `CREATE_STATE`; the dirty-day store's rows never set it) and keeps the
  forward position in row `outcomes` and the replay in row `outcomes.replay`, deleted when none is pending — both in
  one transaction.
- **A pass that stops inside one capture time resumes after the last conversation read.** Before, only the time was
  stored, so more than a pass of conversations sharing one `lastUpdatedAt` restarted at the same prefix every pass.
- **The overlap re-read resumes where it stopped** instead of jumping to the watermark and skipping the rest. While
  a replay is pending, the stored watermark is capped at `replayAt + overlap`, so the replay never trails the
  forward feed by more than the overlap; the replay position itself only advances, so the feed still always moves.
- **A failure is retried from the replay**, which is set back to the failed capture: every following pass reaches it
  first, wherever the forward feed has got and whatever the overlap's share, until `MAX_RETRY` (1 h from the first
  failure). A conversation given up at the limit stays marked as given up, so a later re-read does not start the
  hour again.
- Tests: `HealthOutcomeEvaluatorTest` (conversations sharing one capture across passes, an exhausted overlap that
  resumes and still judges a late row after the feed moved on by more than the overlap, an overlap failure retried
  every pass until the limit), the store contract round-trips the cursor including nulls.

### Design decisions

- The replay never compares conversation ids in Java: it stops at the forward cursor's own conversation id, so the
  order is always the database's (PostgreSQL's collation may differ from `String.compareTo`).
- The overlap trade-off under sustained overload is unchanged in kind: a conversation the replay already passed in
  the current sweep is not re-read for a row that lands after the sweep passed it.

## 🐛 fix(health): rating marks, failed due sessions and later feed failures are never silently dropped (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h3`)

### What changed and why

A third round of review findings on PR #1389, documented in [`docs/agent-health.md`](../agent-health.md#the-evaluation)
and [`docs/metrics.md`](../metrics.md#agent-health-metrics).

- **A rating saved during a session's first evaluation is not lost.** The mark goes to the sessions that exist, and
  a row the evaluation inserts did not exist yet. When an evaluation inserts a final row (no due time) from ratings
  it read, it reads them once more after the upsert, and a change (or a failed read) makes those new rows due. Rows
  that existed already keep a mark through the upsert, as before; a provisional row reads the ratings again at its
  window's close.
- **A rating mark that cannot be stored is deferred, not dropped.** The hand-off executor no longer uses
  `DiscardPolicy`: a rejected mark, or one whose `markDue` fails, puts the conversation in a bounded per-node set
  (10,000) that the next pass on that node stores first, marking every session of it. Past the bound the mark is
  lost with a WARN. Both are counted in `eddi.health.outcomes.rating.marks{result=deferred|lost}`, charted on the
  Full Metrics Reference's "Agent Health rollup work" panel (Kubernetes copy re-synced with
  `scripts/sync-monitoring-assets.py`).
- **A failing due session backs off.** Its `dueAt` is pushed to `now + age` (time since its last successful
  evaluation, between 15 minutes and 6 hours), so sessions that keep failing no longer fill every pass's page of
  1,000 and starve the rest. `IHealthOutcomeStore.markDue` is unchanged; only its Javadoc names the later `at`.
- **Several feed failures are all retried.** The replay restarts at the *earliest* failure, so when the
  conversations after it used up the replay's share, a later failure was not reached until the earlier one was
  given up, and could then be given up on its first retry. The evaluator now also retries every failure it holds
  (`feedFailures`, per node) directly at the start of the replay, oldest capture first, within the replay's share.
  The persisted replay reset stays as the cross-node path.
- **The orphan sweep deletes an outcome-only orphan's turn rows first.** A conversation found only by its recently
  evaluated sessions (its turn rows older than the window, left by a failed purge) had its sessions deleted and its
  turn rows skipped. Agentless candidates now go through `deleteConversationIfDaysKnown`: the rows are deleted and
  their days marked when the days can be read; otherwise rows and sessions are both kept for the next sweep. A
  candidate whose turn delete fails keeps its sessions too.
- Tests: `HealthOutcomeEvaluatorTest` (rating during a first evaluation, unchanged ratings, deferred and lost rating
  marks, due back-off, a later failure behind an earlier one), `HealthOutcomeGdprParticipantTest` (outcome-only
  orphan with readable and unreadable days). Store interfaces are unchanged, so the store contract tests are too;
  the ITs (Docker) were not run.

### Design decisions

- **A purge that keeps the turn rows keeps the sessions too.** When `ConversationPurgeService` can read neither the
  agent nor the days, it leaves both for the orphan sweep. The sessions are the sweep's way back to turn rows
  captured outside its window, which is the case the sweep fix above handles.
- **A row that lands behind a pending replay is not chased.** A sweep that spans passes does not re-read what it
  already passed. Covering such a row would mean restarting every sweep where the last one began, which never moves
  on under the load that makes a sweep pending. The row is still read by its session's due evaluation at window
  close (at least 1 hour), or with the conversation's next turn. Only a row that lands in a session already judged
  final, while a sweep is pending, waits for that.
- The deferred rating marks and the direct feed retries are per node. A claim that moves to another node re-reads
  the feed from the persisted replay reset. Rating marks deferred on a node that never runs a pass again wait for
  their session's next change or due time.
