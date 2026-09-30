## 🔒 fix(schedules): a configuration PUT no longer re-enables a schedule someone else disabled (2026-09-30)

**Repo:** EDDI (`fix/schedule-put-preserves-enabled`)

### What changed and why

`PUT /schedulestore/schedules/{id}` wrote every field, including `enabled`, from the
request body. It had no version or expected-state check. The Manager's schedule editor
sent `enabled` from the copy it had read. If another operator disabled the schedule after
that read, the editor's Save switched it back on, and the schedule fired again. Leaving
`enabled` out of the body would not have helped: the Java model defaults it to `true`, so
an omitted value read as "enable". CodeRabbit raised this on labsai/EDDI#854 (review
5357550681) as a server-contract gap outside that UI PR.

The fix makes `enabled` a switch that only `/enable` and `/disable` flip. That is option 1
of the two considered.

- **Both stores' `updateSchedule`** leave `enabled` out of the write: the Mongo `$set`
  list and the Postgres `UPDATE … SET` list. The stored value is kept inside the same
  atomic write, so no read-then-write window exists. This is the same treatment
  `fireStatus` and `failCount` already get. The Postgres parameter indexes after
  `max_cost_per_fire` shift down by one.
- **`RestScheduleStore.updateSchedule`** copies the stored `enabled` onto the body in
  `carryOverNonEditableFields`. This keeps the in-memory body consistent with the row;
  the store is what guarantees the behaviour. A PUT with `"enabled": false` does not
  disable either. The OpenAPI description of the PUT says so.
- **Manager editor** (`ui/manager/src/pages/schedules.tsx`): an edit no longer sends
  `enabled`. A create still sends `true`. The row toggle already used `/enable` and
  `/disable`.
- **Docs:** [`scheduling.md`](../scheduling.md#enabling-and-disabling) has a new "Enabling
  and disabling" section, plus notes on the field and PUT rows.
  [`import-export-an-agent.md`](../import-export-an-agent.md#what-happens-to-schedules-on-import)
  notes the effect on imports.

### Behaviour changes to know

- Editing a one-shot that has already fired, and so disabled itself, no longer re-arms
  it. Save the new time, then call `/enable`.
- An import with `strategy=merge` that updates an existing schedule now keeps the
  target's `enabled` state instead of taking the archive's. This matches how a merge
  already keeps the schedule's owner. Rollback of a failed import needs no change,
  because the import never alters `enabled` on an existing schedule.

### Tests

- `RestScheduleStoreTest`: a stale `"enabled": true` body, or one that omits `enabled`,
  keeps a stored `enabled=false` and never calls `setScheduleEnabled`. A body with
  `"enabled": false` does not disable.
- `MongoScheduleStoreTest` (unit) and `PostgresScheduleStoreUnitTest`: the update never
  names `enabled`. The shifted Postgres parameter indexes are re-asserted.
- `datastore/mongo/MongoScheduleStoreTest` and `PostgresScheduleStoreTest`
  (Testcontainers; CI runs them) cover the race: read, then `setScheduleEnabled(false)`,
  then `updateSchedule(staleCopy)`. The edit lands and the schedule stays disabled. The
  Postgres IT that expected a PUT to disable now asserts the opposite.
- Manager `schedules-regressions.test.tsx`: an edit's PUT body has no `enabled`, even
  when the snapshot the dialog opened on is stale, and Save never calls `/enable`.

```decision-log
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
```
