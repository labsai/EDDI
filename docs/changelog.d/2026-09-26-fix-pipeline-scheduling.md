## 🐛 fix(scheduling, pipeline, sync): DST-correct cron, capabilityMatch templating, batch cap, fair due polling, safe schedule edits and dismissals, sync descriptor catch-up (2026-09-26)

**Repo:** EDDI (`fix/pipeline-scheduling`)

Seven review findings in the scheduler, two pipeline conditions/tasks and agent sync. Each fix has a regression test.

### What changed and why

- **M-Q1: cron across DST.** `CronParser.computeNextFire` walked real time minute by minute. So `30 2 * * *` in `Europe/Berlin` fired twice on 2026-10-25, when 02:30 happens twice, and not at all on 2026-03-29, when 02:30 never happens. The fix follows Vixie cron:
  - A **fixed-time** expression has no `*` in either the minute or the hour field. It now walks local wall-clock times. A skipped local time fires at the transition instant; a repeated one fires only at its first occurrence.
  - **Wildcard** expressions (`*/15 * * * *`, `5 * * * *`) keep the real-time walk, so they keep a true real-time cadence through the repeated hour.
  - Schedules in UTC (the default) are unaffected.
  - Two consequences of the Vixie rule are documented in [`scheduling.md`](../scheduling.md) and pinned by tests:
    - Two fixed times that both resolve to the transition fire once: `0 2,3 * * *` fires a single time on the spring-forward day. `30 2,3` still fires twice.
    - A fixed hour *range* (`0 0-23 * * *`) counts as fixed-time, so it does not repeat the doubled hour on fall-back. `0 * * * *` does.
  - Covered beyond whole-hour shifts: Lord Howe (30-minute gap and overlap) and Havana (transition at midnight).
  - Files: [`CronParser.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/CronParser.java), tests in `CronParserTest` (DST section).
- **M-Q2: `capabilityMatch` templating never ran.** `resolveTemplate` only sent a value to the engine when it contained `{{`. No Qute expression contains that, so the documented `{properties.requiredSkill}` was used literally as the skill name and the condition silently failed. Its test mocked the engine, so it could not catch this.
  - The gate is now any `{`.
  - A skill that renders blank fails without querying the registry. A strategy that renders blank falls back to `highest_confidence`.
  - The new tests use the real `TemplatingEngine`, set up with the production NOOP missing-property mapper.
  - The Javadoc and [`capability-match-guide.md`](../capability-match-guide.md) no longer describe a "known limitation". Double-brace values were never resolved, so no stored config that worked before changes behaviour, with one theoretical exception:
  - One theoretical behaviour change: a *literal* skill or strategy name containing `{` followed by an identifier (say `c{sharp}`) is now rendered as a template instead of used verbatim. Nothing documented produces such a name. Qute's `{|…|}` keeps one literal if ever needed.
- **M-Q3: batch fan-out was uncapped.** A fire-and-forget `preRequest.batchRequests` sent one request per element of an array that usually comes from an upstream response or from an LLM.
  - New config field `batchRequests.maxBatchSize`. Unset or `<= 0` means the deployment default (`100`). Saving a value above the deployment ceiling (`1000`) is **refused with 400** by `RestApiCallsStore`. A value stored before an operator lowered the ceiling runs at the ceiling, with a WARN. Both numbers are operator-configurable: `eddi.httpcalls.batch.default-max-size` and `eddi.httpcalls.batch.max-size-ceiling`, in [`configuration-reference.md`](../configuration-reference.md) and `application.properties`. Raising the default restores the old behaviour for a deployment without editing stored configs. The keys sit under `eddi.httpcalls.*` next to the existing httpcalls knobs. The review suggested `eddi.apicalls.batch.*` only as an example.
  - A batch over the limit is **refused as a whole**, before any request is built or sent. The turn fails with a message that names the knob. A truncated batch would have reported success while dropping the tail.
  - Files: [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`BatchRequestBuildingInstruction.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/BatchRequestBuildingInstruction.java), [`httpcalls.md`](../httpcalls.md), test `ApiCallExecutorBatchCapTest`.
- **M-Q4: unsorted due query.** The reviewer confirmed the verified downgrade: this was never starvation, only fairness once more than one poll batch (100) is due.
  - `findDueSchedules` now orders by `nextFire`, then id, on both Mongo and Postgres, so the most overdue fire goes first. The existing `(enabled, nextFire, fireStatus)` index serves the sort.
- **UI High 5, backend half: PUT turned system schedules into chat schedules.** `carryOverNonEditableFields` now also carries over `metadata`, `tenantId` and `allowSelfScheduling`, but **only when the body omits them**.
  - `metadata` selects the fire path (dream, team cadence, RAG ingestion, HITL). The Manager never echoed it, so an edited system schedule began messaging the agent instead.
  - Naming a field still sets it. `"metadata": null` and `{}` both clear it.
  - "Absent" is tracked by a non-serialized marker set by the setter, for `metadata` and for `allowSelfScheduling`, which is a primitive. `tenantId` has no marker: null or absent keeps the stored tenant.
  - **Authorization, tightened in the same change** (review MAJOR 1). A metadata-less PUT used to strip the ingestion marker, which was harmless: the result was a chat schedule. Keeping the marker let anyone with USE on some agent and VIEW on a knowledge base re-cron that knowledge base's crawl to every minute. That bypassed the EDIT gate that `fireNow` and the ingestion endpoints enforce. `updateSchedule` now judges the **stored** row:
    - **RAG ingestion:** refused for everyone with **409**. The row is minted from the knowledge base's ingestion source, whose `cron` is the source of truth and is re-applied on every knowledge-base save. Change it there.
    - **Team cadence:** requires **EDIT on the group**, the gate `addCadence` applies. There is no cadence-update endpoint, so this PUT stays the way to change a cadence's cron.
  - The Manager needs no change. Its own `buildConfig` fix belongs to `fix/manager-ops-pages`.
- **Dead-letter dismiss could reset a live claim.** `dismissDeadLetter` called the unfenced `markCompleted`, which matches the row in any state. The Manager offers "Dismiss" on failed fire *logs*, whose schedule may be running again. Dismissing reset the live claim, the next poll fired the schedule a second time, and `lastFired` moved although nothing had fired.
  - New `IScheduleStore.dismissDeadLetter(id, nextFire)`, implemented on Mongo and Postgres. The write is conditional on `fireStatus = DEAD_LETTERED` and leaves `lastFired` alone.
  - The contract declares it: `@APIResponse(409)` on `IRestScheduleStore.dismissDeadLetter`. The Manager still offers "Dismiss" on failed fire logs and shows a generic toast on 409. That fix is a follow-up for `fix/manager-ops-pages`.
  - REST returns **409** when the schedule is not dead-lettered, and also when the state changes between the read and the conditional write. A schedule that is dead-lettered still gets 200.
- **G1: sync could never recover a lagging descriptor.** `bumpDescriptor` read the descriptor at exactly the version just written. After one lost bump the descriptor stays behind the resource, so that read failed on every later sync, and the resource could never be deployed. The existing "heals" test answered a descriptor at *any* version, so it passed anyway.
  - The executor now walks a lagging descriptor forward one version at a time, at most 50 versions in one run. Every skipped resource version gets the descriptor that a deployment of that version looks up.
  - The "heals" test now uses a real lagging fixture.
- **G5: sync counters.**
  - Rewritten workflows, and extensions their new version references, count as `updated` only once the **agent** that loads them has been written. An agent write the store refuses without throwing is now reported as a failure instead of leaving a null URI. Earlier, an extension counted as `updated` as soon as it was written.
  - A changed workflow the operator **deselected** is no longer counted as `skipped`, which means "identical".
  - A snippet create counts as `created` only when the store accepted it (201) and its descriptor was written. Before, it could appear under both `created` and `failures`.

### Compatibility

- **Stored configs:** new optional field `maxBatchSize`. A batch config whose array grows past 100 now fails the turn instead of sending everything; raise `maxBatchSize` (up to 1000) if that is intended.
- **REST:** `POST /schedulestore/schedules/{id}/dismiss` answers 409 for a schedule that is not dead-lettered. It used to answer 200, after silently resetting whatever state the schedule was in.
- `PUT /schedulestore/schedules/{id}` on a RAG-ingestion schedule now answers 409; on a team-cadence schedule it needs EDIT on the group.
- **Properties:** new `eddi.httpcalls.batch.default-max-size` (100) and `eddi.httpcalls.batch.max-size-ceiling` (1000).
- **REST, httpcalls:** create/update of an apicalls config whose `maxBatchSize` exceeds the ceiling answers 400.
- `PUT /schedulestore/schedules/{id}`: omitted `metadata`, `tenantId` and `allowSelfScheduling` are kept instead of being nulled or reset to false.

### Coordination

- `ScheduleFireExecutor` is untouched. `fix/conversation-turn-state` edits its rollover logic.
- Authorization: one check was **added** — the stored-row guard on `updateSchedule` for ingestion and team-cadence schedules — because the carry-over would otherwise have widened what an existing check lets through. No other authorization check changed. Workspace scoping of schedules remains with `fix/workspace-authz-scoping`, which should expect this guard in `updateSchedule`.

```decision-log
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
```
