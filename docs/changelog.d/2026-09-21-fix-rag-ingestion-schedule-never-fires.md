## 🐛 fix(rag): a scheduled ingestion source was stored enabled and never ran (2026-09-21)

**Repo:** EDDI (`fix/rag-ingestion-schedule-never-fires`) — backend, `ui/manager/`, docs

Five defects that reached `main` because [PR #790](https://github.com/labsai/EDDI/pull/790)
was merged with its review threads still unresolved. The first one made the feature that
PR added not work at all.

### 1 — the schedule had no fire time, so no poll could ever select it

`RagSourceIngestionService.buildSchedule` built a CRON `ScheduleConfiguration` and never
set `nextFire`. Neither store computes one: `MongoScheduleStore.createSchedule` and
`PostgresScheduleStore.createSchedule` both persist whatever they are handed. Both
`findDueSchedules` implementations then select on `enabled = true AND nextFire <= now`,
and a null `nextFire` matches neither backend's comparison — BSON type bracketing
excludes null on Mongo, and `next_fire <= ?` is UNKNOWN for NULL on Postgres. So a source
with a cron was stored reading back `enabled`, showed as scheduled on every screen, and
**never fired**.

`buildSchedule` now arms the schedule the way `RestGroupWorkspace.addCadence` does, with
the same time-zone handling: the cron is read in a fixed **UTC**, and `timeZone` is
written onto the row rather than left null. That second half matters — `SchedulePollerService`
re-arms a fired schedule through `resolveTimeZone(schedule.getTimeZone())`, which falls
back to the deployment's `eddi.schedule.default-timezone`, so a first fire computed in UTC
and every later fire computed in some other zone would have drifted by the offset, once,
silently.

**Where the fix belongs.** In the caller, not in `createSchedule`:

- Putting it in `MongoScheduleStore.createSchedule` would **not** cover Postgres. The two
  stores share no base class, so "fix the store" means writing it twice — and fixing only
  the Mongo one leaves a Postgres deployment broken while looking fixed.
- The arming *policy* already exists, once, in `RestScheduleStore.computeRearmNextFire`:
  heartbeat interval, one-shot `oneTimeAt`, the unsatisfiable-cron translation, the
  configured default zone. A second, subtly different copy of it inside the persistence
  layer is exactly the divergence `MongoScheduleStore.updateSchedule`'s own comments
  record as having bitten before.
- A store that silently rewrites the object it is given also hides real bugs:
  `markCompleted(id, null)` *means* "one-shot finished, disable it".

Every other direct-to-store creator already arms its own schedule
(`RestGroupWorkspace`, `ConversationHitlService`, `GroupHitlCoordinator`,
`HitlCrashRecoveryObserver`). This one had simply forgotten, and that is where it is fixed.

**Existing rows are already broken**, so a fix to `buildSchedule` alone would leave every
schedule created before it dead for ever — repaired only if somebody happened to re-save
the knowledge base, which nothing would tell them to do. A startup sweep
(`RagSourceIngestionService.repairUnarmedSchedules`, `@Observes StartupEvent`, new
`eddi.rag.ingestion.schedule-repair.enabled`, default on) gives a fire time to any
*ingestion* schedule that is enabled, carries a cron and has no `nextFire` at all. It is
safe to run repeatedly by construction: after the first pass nothing matches, and two
nodes repairing the same row compute the same next occurrence. It arms through the
existing store-agnostic `setScheduleEnabled`, so it needs no new store method, and it
touches nothing outside this feature's metadata — a schedule left unarmed on purpose is
not a thing this repair can invent a cadence for.

Also refused now: a cron that parses but can never match a date (`0 0 30 2 *`).
`CronParser.validate` accepts it; `computeNextFire` gives up after two years with an
`IllegalStateException`. Stored, it was another source that showed as scheduled for ever.

### 2 — a ZIP could store a cron the REST API refuses

`RestImportService.createNewRags` writes straight to the store, so
`RestRagStore.prepareForWrite` never runs. Its `prepareImportedRag` assigned source ids
and called `RagConfiguration.validate()`, which does not look at the cron — so an archive
could carry a six-field Quartz expression that `POST /ragstore/rags` rejects with a 400.

The rule moved out of `RestRagStore` into `RagIngestionSchedules.requireValidCrons` and
both paths call it. A second copy of the check would only have been a second chance to
forget it.

**Reconciling `schedule.setNextFire(null)` with defect 1.** That line, in
`prepareScheduleForImport`, is about an *agent's* schedules from the archive's
`schedules/` directory, and it is correct: every write on that path goes through
`IRestScheduleStore`, which re-arms the schedule before storing it, and an archived
`nextFire` is either long past (the schedule fires during the import) or absent. The two
are not in contradiction — they are the same rule seen from both sides: **nothing in
either store computes a fire time, so whoever writes has to arm.** Via the REST bean, the
bean does it; direct to `IScheduleStore`, the caller does it. The javadoc now says so
explicitly and names the ingestion sync as the path that did not.

### 3 — run reports named a null source

`IngestionPipeline` used `source.getId()` at six call sites — the failed/skipped/already-running
reports, the abandoned reservation, the released reservation and `toReport`. A source that
arrived without an id is addressed, keyed and scheduled by its **name** everywhere else
(`IngestionSource.effectiveId()`, which `stateKey` already used), so those six reports
carried `sourceId: null` into the run history, the REST answer and the fire log. All six
now use `effectiveId()`, the record component documents the contract, and
`RagSourceIngestionService.sourceIdOf` delegates to `effectiveId()` rather than keeping a
third copy of the rule.

### 4 — `docs/rag.md` said every ingestion field has a default

It does not, and the table three lines below said so itself (`startUrl` | required).
Corrected against the code: `name`, the `web` block and its `startUrl` have no default and
are rejected when missing; `type` defaults to `web`; `id` is generated; `cron` and
`costPerThousandSegments` are simply absent when unset. `userAgent`'s "EDDI's default" is
now the actual string. The cron paragraph gained the unsatisfiable-expression rule, the
import path, and the UTC note.

### 5 — a Manager test passed for the wrong reason

`resource-detail-rag-sources.test.tsx` → "does not offer Run for a disabled source"
clicked the enabled toggle and then asserted the Run button was disabled. Toggling marks
the editor dirty, and `hasUnsavedChanges` disables Run on its own — so the assertion held
with `source.enabled === false` deleted from the button entirely. The source now **arrives**
disabled from an MSW override, nothing is dirty, and the test additionally asserts that
the unsaved-changes hint is absent and that Preview — gated on the same read-only and
dirty guards but not on `enabled` — is still offered. Only the disabled-source rule can
explain the result.

### Mutation checks

| Mutation | Test that failed |
| --- | --- |
| `buildSchedule` drops `setNextFire` | `RagSourceIngestionServiceTest` → "a source with a cron is due once its fire time arrives" |
| `buildSchedule` drops `setTimeZone` | "the fire time is the cron read in UTC, and the schedule says so" |
| `repairUnarmedSchedules` returns early | "an ingestion schedule with no fire time is given one" |
| `prepareImportedRag` drops `requireValidCrons` | `RestImportServiceRagCronTest` → both refusal cases |
| Run button drops `source.enabled === false` | "does not offer Run for a source that is saved as disabled" |

### Files

`modules/ingestion/RagSourceIngestionService.java`,
`modules/ingestion/RagIngestionSchedules.java`,
`modules/ingestion/IngestionPipeline.java`,
`configs/rag/rest/RestRagStore.java`, `backup/impl/RestImportService.java`,
`docs/rag.md`, `docs/configuration-reference.md`,
`ui/manager/src/pages/__tests__/resource-detail-rag-sources.test.tsx`.
Tests: `RagSourceIngestionServiceTest` (two new nested groups),
`RestImportServiceRagCronTest` (new).

```decision-log
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
```

```decision-log
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with an idempotent startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
```

```regression-note
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus an idempotent startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
```
