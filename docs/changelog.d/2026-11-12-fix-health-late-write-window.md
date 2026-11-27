## 🐛 fix(health): a late row carries the time of its write, so no dirty mark has to survive for it (2026-11-12)

**Repo:** EDDI (`fix/health-late-write-window`, on top of `feat/agent-health-h2b`)

### What changed and why

Closes the two windows that the entry "a late row's day is marked before the row is written" (2026-11-11) left
open (CodeRabbit on #1388). A
turn row that reaches the store more than 30 seconds after its capture is "late": the rollup's self-heal finds rows
by capture time (`updatedAt`), and its watermark may already be past such a row, so the row depended on a dirty mark
to be rolled up. That mark could be lost in two ways, both ending in the node stopping: a rollup cleared the pre-mark
just before the write and the node stopped just after it, before the re-mark; or the dirty store refused all four
attempts (two before the write, two after) and the mark was still in memory. Either left the day's daily rows without
the row until something else marked that day.

- **The row is its own mark.** A late write now stores the time of the write with the row, in the same write:
  MongoDB `writtenAt` (set in the guarded upsert pipeline, `$$REMOVE`d by every on-time write), PostgreSQL
  `written_at_ms` (bound in the same `INSERT … ON CONFLICT`, replaced by `EXCLUDED` on every applied write).
  `IHealthTurnStore.forEachAgentDayUpdatedSince` — the self-heal scan — matches it as well as `updatedAt` and
  `discardedAt`, so a late row is found again by a time the watermark cannot have passed. An undo in a late write is
  timed by the write too (`markDiscarded(…, lateWrittenAt)`), so the discarded execution's day is re-rolled the same
  way. `updatedAt` is untouched: it still decides "newest capture wins".
- **Window 1 and window 2 are closed**, not just bounded: whatever happens to the marks after the row is stored, the
  next rollup whose self-heal window reaches the write's time re-marks the day. Both rest on the assumption every
  on-time row already rests on: the write commits within the one-minute `SELF_HEAL_GRACE` of the time the self-heal
  goes by (here, the start of the write), less the clock skew between nodes. A write slower than that, on a node that
  then stops before its flush, can still be missed — for late and on-time rows alike; [`docs/agent-health.md`](../agent-health.md)
  says so.
- **Simpler, and cheaper per late row.** The pre-mark, the stored-days read before the write and the write-through
  re-mark are gone (`HealthDirtyDays.markLateWrite` removed): a late row's days are noted for the five-second flush
  like any row's. An on-time row is written exactly as before (the unstamped `upsert` / `insertIfAbsent` /
  `markDiscarded`), carries no stamp and adds no index entry; the stamp's index is sparse on MongoDB
  (`idx_health_turns_written_late`) and partial on PostgreSQL (`WHERE written_at_ms IS NOT NULL`), so it holds only
  the rare late rows, which age out with the turn retention.
- **Schema.** PostgreSQL gets the column in `CREATE TABLE` and, for tables created by an earlier build of the branch,
  `ALTER TABLE health_turns ADD COLUMN IF NOT EXISTS written_at_ms BIGINT` before the indexes (the pattern of
  `PostgresAlertEventStore`). MongoDB needs no migration.

### Design decisions

- Rejected: a **lease** on the pre-mark (the rollup may not clear it until it is older than the lease or confirmed).
  It closes window 1 only as far as the write fits in the lease, does nothing for window 2, and adds a field and a
  condition to every clear. Rejected: a **generation** on the mark — a rollup that started before the write cannot
  clear a later mark, but here the pre-mark is *earlier* than the rollup, so it does not help without the lease
  again. Rejected: **one transaction** for the row and the mark — PostgreSQL could, MongoDB would need multi-document
  transactions (a replica set) for one rare case. Chosen: the stamp, which is the "write and mark in one durable
  operation" option done inside the row's own single-document / single-row write, scanned by the existing self-heal.
- Stamping every row would have made the scan simpler but put an index entry on every turn write; stamping late rows
  only keeps the hot path as it was.
- No new meter: nothing is lost any more that a counter would need to report.
- Not changed: the orphan sweep still finds rows by `updatedAt`, so a late row written after its conversation was
  deleted is left to the recorder's own post-write check (as before). The stamp could serve that sweep too; a
  follow-up if wanted.

### Tests

- `HealthLateWriteWindowTest` (new): the real `HealthTurnRecorder`, `HealthDirtyDays` and `HealthRollupService` over
  in-memory stores that follow the store contracts, with a moving clock. `rollupDuringTheWriteThenTheNodeStops`
  (window 1: a rollup runs inside the write, clears the day and moves its watermark past the capture; the node stops
  the moment the row is stored), `everyMarkFailsThenTheNodeStops` (window 2: the dirty store refuses every mark, the
  mark is left in memory only, the node stops), `lateDiscardThenTheNodeStops` (window 1 for an undo of yesterday's
  execution) — in each, a later rollup counts the row; `onTimeRowsCarryNoStamp`.
- `HealthTurnRecorderTest`: `lateRowCarriesItsWriteTime` replaces the pre-mark tests; `onTimeRowIsNotStamped` pins
  the 30-second boundary. `HealthDirtyDaysTest`: `markLateWriteWritesThrough` removed with the method.
- `PostgresHealthTurnStoreTest`: `lateWriteStamp`, `lateWriteBinding`. `MongoHealthTurnStoreTest`: `lateWriteStamp`,
  `insertIfAbsentStamp`, `lateDiscardTime`, `selfHealMatchesTheStamp`. `HealthTurnStoreContract.lateWriteStamp` runs
  against both databases in the `*HealthTurnStoreIT`s (CI).
