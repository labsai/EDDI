## ⏰ fix(rag): an ingestion schedule was stored unarmed and never fired (2026-09-21)

**Repo:** EDDI (`fix/rag-ingestion-schedule-arming`)

CodeRabbit's review of [#790](https://github.com/labsai/EDDI/pull/790) arrived after that
PR had merged. Five findings, all valid; the first is a live defect on `main`.

### What changed and why

**No cron-scheduled ingestion source could ever run.**
[`RagSourceIngestionService.syncSchedules`](../../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java)
writes to `IScheduleStore` directly rather than through `RestScheduleStore`, and the
initial `nextFire` is computed in `RestScheduleStore.computeInitialNextFire` — on the
path this one bypasses. The row was persisted with a null `nextFire`, and *both* backends
select due work with `nextFire <= now`: `WHERE enabled = true AND next_fire <= ?` in
Postgres, where SQL's `NULL <= x` is never true, and `lte(NEXT_FIRE, nowMs)` in Mongo,
where BSON type bracketing excludes null. So the schedule existed, the Manager showed the
source as scheduled, and the poller never once picked it up. `buildSchedule` now sets the
time zone and computes the first fire.

A cron that parses but matches no instant (`0 0 30 2 *` — February 30th) gets **no
schedule at all**, with an ERROR naming the source: `CronParser.validate` accepts it, so
it reaches this far, and storing it would recreate the very state this fix removes.

**The import path never validated crons.** `RagConfiguration.validate()` does not parse
them — only `RestRagStore.requireValidCronExpressions` does, and
[`RestImportService`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java)
writes through `createResourceDirect`, which never reaches the REST layer. An archive
carrying a six-field Quartz expression imported cleanly and stored a cron that every
later save through the API would reject with a 400. `prepareImportedRag` now runs the
same check, naming the source and preserving the cause.

**Reports named a null source.** `stateKey` keys on `IngestionSource.effectiveId()` (the
id, or the name when a ZIP import left none), but six report call sites in
[`IngestionPipeline`](../../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java)
used `getId()`, so an id-less source reported `sourceId: null` against state keyed by
name. All six now use `effectiveId()`.

**Two documentation and test corrections.** `docs/rag.md` claimed "Every field has a
default" while its own settings table listed `costPerThousandSegments` as unset;
corrected, with `startUrl` named as the one required field. And the Manager's
"does not offer Run for a disabled source" test unticked the checkbox, which marks the
editor dirty — the `hasUnsavedChanges` guard then disabled Run on its own, so the test
passed with the `source.enabled` guard removed entirely. It now loads a source that is
already disabled.

### Verification

Both behavioural fixes are mutation-checked, not merely green: computing `nextFire`
without assigning it fails `armsTheScheduleWithANextFire`, and removing
`source.enabled === false` from the Run guard fails the rewritten Manager test — which
the old one did not.

```regression-note
| 2026-09-21 | Every cron-scheduled RAG ingestion source was enabled but never fired | `syncSchedules` writes to `IScheduleStore` directly, so nothing computed the initial `nextFire`; both backends' due queries use `nextFire <= now`, which no null satisfies | `buildSchedule` computes the first fire and sets the time zone; a never-matching cron gets no schedule and an ERROR | `fix/rag-ingestion-schedule-arming` |
```
