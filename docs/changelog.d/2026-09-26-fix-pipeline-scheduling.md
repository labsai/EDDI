## 🐛 fix(scheduling, pipeline, sync): DST-correct cron, capabilityMatch templating, batch cap, fair due polling, safe schedule edits and dismissals, sync descriptor catch-up (2026-09-26)

**Repo:** EDDI (`fix/pipeline-scheduling`)

Seven review findings in the scheduler, two pipeline conditions/tasks and agent sync. Each fix has a regression test.

### What changed and why

- **M-Q1: cron across DST.** `CronParser.computeNextFire` walked real time minute by minute. So `30 2 * * *` in `Europe/Berlin` fired twice on 2026-10-25, when 02:30 happens twice, and not at all on 2026-03-29, when 02:30 never happens. The fix follows Vixie cron:
  - A **fixed-time** expression has no `*` in either the minute or the hour field. It now walks local wall-clock times. A skipped local time fires at the transition instant; a repeated one fires only at its first occurrence.
  - **Wildcard** expressions (`*/15 * * * *`, `5 * * * *`) keep the real-time walk, so they keep a true real-time cadence through the repeated hour.
  - Schedules in UTC (the default) are unaffected.
  - Files: [`CronParser.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/CronParser.java), tests in `CronParserTest` (DST section).
- **M-Q2: `capabilityMatch` templating never ran.** `resolveTemplate` only sent a value to the engine when it contained `{{`. No Qute expression contains that, so the documented `{properties.requiredSkill}` was used literally as the skill name and the condition silently failed. Its test mocked the engine, so it could not catch this.
  - The gate is now any `{`.
  - A skill that renders blank fails without querying the registry. A strategy that renders blank falls back to `highest_confidence`.
  - The new tests use the real `TemplatingEngine`, set up with the production NOOP missing-property mapper.
  - The Javadoc and [`capability-match-guide.md`](../capability-match-guide.md) no longer describe a "known limitation". Double-brace values were never resolved, so no stored config that worked before changes behaviour.
- **M-Q3: batch fan-out was uncapped.** A fire-and-forget `preRequest.batchRequests` sent one request per element of an array that usually comes from an upstream response or from an LLM.
  - New config field `batchRequests.maxBatchSize`. Default `100`; values above `1000` are clamped to `1000`; unset or `<= 0` means the default.
  - A batch over the limit is **refused as a whole**, before any request is built or sent. The turn fails with a message that names the knob. A truncated batch would have reported success while dropping the tail.
  - Files: [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`BatchRequestBuildingInstruction.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/BatchRequestBuildingInstruction.java), [`httpcalls.md`](../httpcalls.md), test `ApiCallExecutorBatchCapTest`.
- **M-Q4: unsorted due query.** The reviewer confirmed the verified downgrade: this was never starvation, only fairness once more than one poll batch (100) is due.
  - `findDueSchedules` now orders by `nextFire`, then id, on both Mongo and Postgres, so the most overdue fire goes first. The existing `(enabled, nextFire, fireStatus)` index serves the sort.
- **UI High 5, backend half: PUT turned system schedules into chat schedules.** `carryOverNonEditableFields` now also carries over `metadata`, `tenantId` and `allowSelfScheduling`, but **only when the body omits them**.
  - `metadata` selects the fire path (dream, team cadence, RAG ingestion, HITL). The Manager never echoed it, so an edited system schedule began messaging the agent instead.
  - Naming a field still sets it. `"metadata": {}` clears it.
  - `allowSelfScheduling` is a primitive, so "absent" is tracked by a non-serialized marker that its setter sets.
  - The Manager needs no change. Its own `buildConfig` fix belongs to `fix/manager-ops-pages`.
- **Dead-letter dismiss could reset a live claim.** `dismissDeadLetter` called the unfenced `markCompleted`, which matches the row in any state. The Manager offers "Dismiss" on failed fire *logs*, whose schedule may be running again. Dismissing reset the live claim, the next poll fired the schedule a second time, and `lastFired` moved although nothing had fired.
  - New `IScheduleStore.dismissDeadLetter(id, nextFire)`, implemented on Mongo and Postgres. The write is conditional on `fireStatus = DEAD_LETTERED` and leaves `lastFired` alone.
  - REST returns **409** when the schedule is not dead-lettered, and also when the state changes between the read and the conditional write. A schedule that is dead-lettered still gets 200.
- **G1: sync could never recover a lagging descriptor.** `bumpDescriptor` read the descriptor at exactly the version just written. After one lost bump the descriptor stays behind the resource, so that read failed on every later sync, and the resource could never be deployed. The existing "heals" test answered a descriptor at *any* version, so it passed anyway.
  - The executor now walks a lagging descriptor forward one version at a time, at most 50 versions in one run. Every skipped resource version gets the descriptor that a deployment of that version looks up.
  - The "heals" test now uses a real lagging fixture.
- **G5: sync counters.**
  - An extension counts as `updated` only once the workflow the agent will load references its new version. Before, it was counted when written, even if the workflow write was then refused or could not place the reference.
  - A changed workflow the operator **deselected** is no longer counted as `skipped`, which means "identical".
  - A snippet create counts as `created` only when the store accepted it (201) and its descriptor was written. Before, it could appear under both `created` and `failures`.

### Compatibility

- **Stored configs:** new optional field `maxBatchSize`. A batch config whose array grows past 100 now fails the turn instead of sending everything; raise `maxBatchSize` (up to 1000) if that is intended.
- **REST:** `POST /schedulestore/schedules/{id}/dismiss` answers 409 for a schedule that is not dead-lettered. It used to answer 200, after silently resetting whatever state the schedule was in.
- `PUT /schedulestore/schedules/{id}`: omitted `metadata`, `tenantId` and `allowSelfScheduling` are kept instead of being nulled or reset to false.

### Coordination

- `ScheduleFireExecutor` is untouched. `fix/conversation-turn-state` edits its rollover logic.
- No authorization checks were changed. `fix/workspace-authz-scoping` owns schedule scoping.

```decision-log
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
```
