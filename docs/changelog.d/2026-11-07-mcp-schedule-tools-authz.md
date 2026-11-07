## 🐛 fix(mcp): schedule tools go through the REST schedule store under the caller's identity (2026-11-07)

**Repo:** EDDI (`fix/mcp-schedule-tools-authz`)

### What changed and why

The six MCP schedule tools (`create_schedule`, `list_schedules`, `read_schedule`,
`delete_schedule`, `fire_schedule_now`, `retry_failed_schedule`) addressed `IScheduleStore`,
`ScheduleFireExecutor` and `SchedulePollerService` directly and kept their own copy of the
parts of `RestScheduleStore` someone had remembered to copy. The copy had drifted:

- `create_schedule` ran none of REST's validation, so a conversation strategy such as
  `sometimes` and a `userId` in the agent-test namespace were stored, and an unknown time
  zone or a cron that never matches answered an internal error instead of the reason.
- It never stamped `createdBy`, `creatorTeams` or `creatorAdmin`. With `createdBy` null,
  `ScheduleFireExecutor` skips the fire-time re-check of the creator's access to the agent
  for every fire of that schedule.
- `fire_schedule_now` refused only HITL timeouts and continuations. REST also requires
  `EDIT` on the knowledge base, integration or group an ingestion, email-poll, team-cadence
  or lesson-consolidation schedule drives, refuses a marker that names none (400), and runs
  its owner and manage checks.

The tools are admin-only, so this was drift rather than a bypass reachable today. They are
now thin adapters over the in-process `IRestScheduleStore` bean, which reads the request's
`SecurityIdentity` (the MCP caller), the pattern `McpAlertTools` and `McpRatingTools`
already use. `McpAdminTools` loses its three schedule dependencies, the
`eddi.schedule.min-interval-seconds` copy and the duplicated create and fire logic.

`McpToolUtils.responseJson` now keeps a plain-text response body as the message (the
schedule resource answers its refusals as text, which used to degrade to "The request
failed (HTTP 409)") and maps 504 to `errorCode: TIMEOUT`.

### Behaviour changes

- Refusals carry REST's text and code: `invalid_schedule` for a schedule REST would not
  create, `CONFLICT`/`FORBIDDEN`/`BAD_REQUEST`/`TIMEOUT` on a manual fire,
  `SYSTEM_SCHEDULE` on deleting the server's own schedule.
- An unset `timeZone` on `create_schedule` takes `eddi.schedule.default-timezone`; the tool
  argument no longer defaults to `UTC` itself.
- A `heartbeatIntervalSeconds` makes a schedule a heartbeat even with `triggerType: CRON`,
  as over REST. An unknown `triggerType` is refused with `invalid_schedule`.
- `list_schedules` with an `agentId` is capped at 100 like the unfiltered listing, and both
  report `truncated: true` on a full page. `cronDescription` now also describes heartbeats.
- `delete_schedule` on an id that does not exist succeeds, as `DELETE` does over REST.

**Files:** [`McpAdminTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java),
[`McpToolUtils.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java),
[`McpRoles.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpRoles.java),
[`McpScheduleParityTest.java`](../../src/test/java/ai/labs/eddi/engine/schedule/rest/McpScheduleParityTest.java),
[`McpScheduleToolsTest.java`](../../src/test/java/ai/labs/eddi/engine/mcp/McpScheduleToolsTest.java),
[`mcp-server.md`](../mcp-server.md#schedule-management-tools-6)

### Next

Widening the schedule tools from admin-only to REST's admin/editor tier is now a decision
about the tier alone, since REST's owner rules apply to the MCP caller. Left for a
separate change.
