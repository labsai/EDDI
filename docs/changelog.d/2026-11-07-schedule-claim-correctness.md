## 🐛 fix(schedules): the poller's claim re-checks due-ness; leases judged by the database clock (2026-11-07)

**Repo:** EDDI (`fix/schedule-claim-correctness`)

### What changed and why

Two ways the cluster-wide schedule claim let the same slot fire twice.

**A re-armed schedule could be claimed again.** A poll reads its batch once and
then claims the rows one by one. The claim was a compare-and-set on state alone
(`enabled` and `fireStatus`), with no `nextFire` term. If another instance
claimed, fired and re-armed a row before this instance's loop reached it, the
row was PENDING again with a future `nextFire`, the claim matched, and the slot
fired a second time. The second fire carried a new `fireId`, because the id is
keyed on the claim time, so nothing downstream could tell the two apart. A fast
rule-only turn and a batch of up to `eddi.schedule.poll-batch-size` (100) claim
round trips are enough for this to happen.

`IScheduleStore.tryClaim` now also requires `nextFire <= now`, the condition
`findDueSchedules` already reads. A manual fire (`POST /schedules/{id}/fire`,
`fire_schedule_now`) may fire before the due time, so it claims through the new
`tryClaimAnyTime`, which has the same conditions without the due check. The
existing method became the safe one, so any future batch caller gets the check
by default.

**A fast clock could steal a live lease.** `claimedAt` was written from the
claiming instance's clock and compared with `now - lease` from the reading
instance's clock. With the default 10-minute lease against a 5-minute fire
timeout plus 30 s grace, an instance whose clock ran more than 4.5 minutes ahead
took over claims whose fires were still running. Both stores now write
`claimedAt` with the database's clock and measure a lease's age against that
same clock: MongoDB through a pipeline update with `{$toLong: "$$NOW"}` and an
`$expr` comparison, PostgreSQL through
`(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint`. `tryClaim` and
`findDueSchedules` take the lease as a `Duration` instead of a precomputed
expiry instant. The instance's own `now` still decides due-ness and retry
readiness and keys the `fireId`; skew there only shifts when a schedule fires
on that instance, it does not duplicate a fire.

**Files:**
[`IScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/IScheduleStore.java),
[`MongoScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/mongo/MongoScheduleStore.java),
[`PostgresScheduleStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresScheduleStore.java),
[`SchedulePollerService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/SchedulePollerService.java),
[`ScheduleConfiguration.java`](../../src/main/java/ai/labs/eddi/engine/schedule/model/ScheduleConfiguration.java)
(the `fireId` comment said "scheduleId + fireTime"; it is the claim time),
[`scheduling.md`](../scheduling.md#cluster-awareness).

### Design decisions

- **Change `tryClaim`, add `tryClaimAnyTime`**, rather than adding a poller-only
  due-checked claim next to the old one: the unsafe variant is the one that has
  to be asked for by name.
- **The same tests run on both backends.** The container-backed
  `PostgresScheduleStoreTest` and `datastore/mongo/MongoScheduleStoreTest` each
  gained "a re-armed row is not claimed again (but Fire now still claims it)"
  and "a fast clock does not steal a live lease"; both failed on `main`.
- **`CURRENT_TIMESTAMP` rather than `clock_timestamp()`** in PostgreSQL: the
  value is fixed for the statement, so the write and the comparison in one
  claim use the same instant, and it matches the existing
  `PostgresConnectionNameClaimStore`.

### Behaviour changes

- A schedule that another instance fired and re-armed between a poll's read and
  its claim is no longer fired a second time.
- Instances whose clocks disagree no longer take over each other's running
  fires. An existing CLAIMED row written by an older version carries an
  application-clock `claimedAt`; after the upgrade its lease is judged against
  the database clock, which differs from it only by the skew this fix exists for.

```decision-log
| 2026-11-07 | Schedule leases are written and judged on the database's clock; the poller's claim re-checks nextFire <= now, manual fires use tryClaimAnyTime | Two paths to a duplicate fire: a row re-armed between a poll's read and its claim, and claimedAt compared across instances' clocks | A poller-only due-checked claim beside the old one (leaves the unsafe call as the default); clock_timestamp() (changes during the statement) |
```
