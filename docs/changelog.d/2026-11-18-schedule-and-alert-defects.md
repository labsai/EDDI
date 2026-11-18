## fix(schedule,alerts): agent tests no longer alert, sparse crons end cleanly, timeouts cancel their turn, caps (2026-11-18)

**Repo:** EDDI (`fix/schedule-and-alert-defects`)

### What changed and why

Findings L1-L6 and the confirmed parts of L8 of the November 2026 code review (scheduling, alerts, agent tests).

- **Agent-test conversations raise no alerts (L1).** `ConversationStepRunner.reportTurnOutcome` and `OutputMatchHook.onCleanTurn` skip a conversation whose user id is in the `agent-test:` namespace, so a test case that expects a failure no longer pages the on-call channel. Metrics and the test runner's own reporting do not go through this hook. Rating alerts are untouched (a test conversation is not rated).
- **A sparse cron no longer strands and re-fires its schedule (L2).** `SchedulePollerService.onFireCompleted` records the fire as completed even when no next fire can be computed, and then disables the schedule with `disabledReason: "no-next-fire"` (counter `eddi.schedule.nextfire.exhausted`) instead of leaving the row CLAIMED, where its lease ran out and it fired again about five times. The bookkeeping write is retried three times before it is left to the lease. `CronParser` now looks 9 years ahead (was 2): a leap-day cron needs up to 8 (2096 to 2104), so `0 9 29 2 *` keeps firing; an expression that can never match is still refused at save time.
- **A fire timeout cancels the turn it started (L3).** `ScheduleFireExecutor` cancels the running turn through `IConversationService.cancelConversation` when the fire times out, and every turn of one due fire carries the Idempotency-Key `schedule:<id>@<lastFired>`, so a retry queued behind a first turn that committed after all is answered as skipped, as `ContinuationFireHandler` does with its key.
- **Dead-lettered schedules leave the due scan (L4).** The poller query already excluded them by status; the cost was walking their index entries. Mongo gets an index `(enabled, fireStatus, nextFire)` and Postgres a partial index `(next_fire, id) WHERE enabled AND fire_status <> 'DEAD_LETTERED'` plus the matching predicate in `findDueSchedules`. Rows keep `enabled` and `nextFire`: retry and dismiss are unchanged, and `countPendingSchedulesByName` / `readScheduleByName` return what they returned before (a dead-lettered row still counts as pending).
- **Schedule caps and a warning (L5).** `eddi.schedule.max-per-owner` (200) and `eddi.schedule.max-total` (10000) bound the enabled schedules created through the API (`createdBy` set); create and enable beyond them answer `429 SCHEDULE_LIMIT`. `0` turns a limit off. A new `IScheduleStore.countEnabledSchedulesCreatedBy`. The create response carries `warnings` for a recurring schedule with no `maxCostPerFire` or a persistent conversation that never rolls over (a response-only field, never stored). No default cost cap was added.
- **Fresh persistent conversation (L6).** `POST /schedulestore/schedules/{id}/reset-conversation` clears the schedule's persistent conversation id and ends the old conversation when it is idle. Not added to the MCP tools (a new tool, not a pass-through).
- **L8, confirmed parts.** `AlertMaintenanceSchedule` removes duplicate `alerts-maintenance` schedules (keeps the oldest) at startup and right after creating one. Deleting a schedule ends its idle persistent conversation. The suspicion that editors may delete or disable system-managed schedules (e-mail poll, ingestion, lesson/cadence) with workspaces off is not a privilege gap: with workspaces off an editor may edit the integration or knowledge base the schedule belongs to, and each is re-created when that resource is saved; left as is.

**Files:** [`ConversationStepRunner.java`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java), [`OutputMatchHook.java`](../../src/main/java/ai/labs/eddi/engine/alerts/OutputMatchHook.java), [`SchedulePollerService.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/SchedulePollerService.java), [`CronParser.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/CronParser.java), [`ScheduleFireExecutor.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/ScheduleFireExecutor.java), [`RestScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/rest/RestScheduleStore.java), [`MongoScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/mongo/MongoScheduleStore.java), [`PostgresScheduleStore.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresScheduleStore.java), [`AlertMaintenanceSchedule.java`](../../src/main/java/ai/labs/eddi/engine/alerts/AlertMaintenanceSchedule.java), [`scheduling.md`](../scheduling.md)

### Review follow-up (same branch)

- The timeout cancel now stops only the fire's own turn. A `new`-strategy fire cancels its own conversation; a `persistent` fire calls the new `IConversationService.cancelInFlightTurn(conversationId, "scheduleFireId", runId)`, which signals the in-flight turn only when it carries that run id (no state change, no pause cancelled, no continuation disarmed, no approval audit). A turn still queued carries `callerGivesUpAtEpochMs` and is dropped at the front of the queue. The watchdog interrupt and a manual fire that times out cancel too; a shutdown drain (`ScheduleFireExecutor.beginShutdown`) does not.
- The run id and idempotency key derive from the occurrence's due time (`nextFire` at claim) instead of `lastFired`, so a skipped fire no longer makes the next regular fire reuse the key and be swallowed; a manual fire gets a key per click; a requeued dead letter is a new occurrence. A "skip" that is really an already-committed earlier attempt now completes the occurrence. The `window: "RUN"` tool-quota scope follows the same id.
- Delete and reset end the old conversation with a compare-and-set from the state read, not an unconditional end.
- `removeDuplicates` keeps an enabled, not dead-lettered copy in preference to an older broken one.
- Caps: with authorization off only the total limit applies; the docs state which schedules count and that the limit is soft.

### Design decisions

- Test conversations are excluded at the two turn hooks, not by adding an environment to alert rules (the finding's alternative); rules keep no notion of environment.
- The scan window is widened rather than a sparse-cron refusal added at save time: every satisfiable expression has a next fire within 8 years, and unsatisfiable ones were already refused.
- Caps count `createdBy` rather than `userId`: it is stamped from the authenticated principal, so it cannot be chosen by the caller and engine-minted schedules are not counted.
- The reset is its own endpoint (`/reset-conversation`, like `/enable`, `/retry`) rather than a flag on PUT, which would have changed the interface signature every caller uses.

### Behaviour changes an upgrader will notice

- A leap-day cron keeps firing; a schedule whose cron has no next fire is disabled with `no-next-fire`.
- Creating or enabling beyond 200 own / 10000 total enabled API-created schedules answers 429.
- A timed-out scheduled turn is cancelled, so it stops at its next task boundary.
- New indexes are created on first start (`idx_schedules_due_by_status` on Mongo, `idx_schedules_due_live` on Postgres).
- `POST /schedulestore/schedules/{id}/reset-conversation` is new; `ui/manager/src/test/mocks/openapi-operations.json` lists it.

### What is left

Rating alerts for test conversations, the MCP option for the reset, and the Platform Operator prompt and allow-list (the reset endpoint, the 429 and the `no-next-fire` reason) are not done here.
