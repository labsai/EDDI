## 🔒 fix(security): workspace and owner scoping for groups, schedules, triggers, exports and listings (2026-09-26)

**Repo:** EDDI (`fix/workspace-authz-scoping`)

A batch of authorization gaps from the 2026-09-25 whole-repo review. Most only matter with
`eddi.workspaces.enabled=true` (with enforcement off `ResourceAccessGuard.seesEverything()` is
true and behaviour is unchanged); the items marked **always** apply whenever authentication is on.

### What changed, per finding

- **H1 — groups had no USE check.** `POST /groups/{id}/conversations` (plain and streaming), the
  continuation endpoints and the MCP `discuss_with_group`, `start_group_discussion` and
  `continue_group_discussion` tools now call `requireUseAccess(groupId, "group")`. A discussion fans
  out to every member at the owner's cost, and member turns run below the agent USE gate, so the
  group gate is the only one a borrower meets. Streaming answers a refusal with a terminal
  `group_error` event.
- **H2a + NEW (schedules).** A schedule that runs as a real user belongs to that user **(always)**:
  the rule `requireOwnUserId` already applied to update and fire now covers list, read, fire logs,
  delete, enable, disable, retry and dismiss, so an editor can no longer list, disable or delete
  another user's dream schedule. Unowned (`system:*`) schedules keep their old visibility with
  workspaces off; with them on, they are manageable by their creator and by whoever may EDIT what
  they drive (the agent, the RAG configuration of an ingestion schedule, the group of a cadence).
  The listing pushes this into the query (`IScheduleStore.ListingScope`, both backends) for the same
  paging reason the HITL redaction lives there. `readSchedule` now hides HITL timeouts from
  non-admins like the listing does, and fire logs are visible exactly when their schedule is.
  `fireNow` rethrows `ForbiddenException` instead of turning the ingestion EDIT gate's 403 into a 500.
- **H2e — forged team cadences.** Create/update reject a body carrying `teamCadenceType` (like the
  HITL and ingestion markers), `fireNow` on a cadence needs EDIT on its group, and
  `TeamCadenceService.processScheduledFire` now takes the firing schedule's id and refuses unless it
  is the `Cadence.scheduleRef` the workspace registered, as a failure so the impostor dead-letters.
- **H2b — triggers.** Update and delete need EDIT on every agent the stored trigger currently routes
  to (new targets still need USE); the listing and single read show only triggers whose targets the
  caller may USE, and an unshown intent answers like an absent one.
- **H2c — export download.** Archive keys are `<slug>--<agentId>-<version>-<128-bit token>.zip`; the
  download parses the agent id back out and checks VIEW, like the export. The saved name
  (`Content-Disposition`) is still `<slug>-<agentId>-<version>.zip`. Archives written before this
  change (≤ 60 minutes old at upgrade) are no longer downloadable.
- **H2d — MCP backlog tools.** `add_team_task` needs EDIT and `list_team_backlog` VIEW on the group,
  matching `RestGroupWorkspace`.
- **H2f — parser.** `POST /parser/{id}` declares `eddi-admin`/`eddi-editor` (it had no roles, so any
  authenticated principal could call it) and checks VIEW on the parser configuration.
- **H2g + NEW — agent listings and descriptor redaction (redaction always).**
  `GET /administration/{env}/deploymentstatus`, and through it MCP `list_agents` and
  `discover_agents`, return only agents the caller may USE and run every descriptor through
  `redactUnlessOwner`, so grant lists and access indexes no longer leave the server to viewers. The
  channel router, which must see every agent, reads the new unscoped `IDeploymentStatusReader`.
- **H2h — `/v1` name oracle.** `AgentModelResolver.resolve` admits only agents the caller may use: a
  refused agent resolves exactly like an absent one, and is left out of the name/slug candidate sets
  before uniqueness is judged. That also gates reuse of an existing chat mapping, which is resolved
  first.
- **H3 — team memories by group id.** `GET /usermemorystore/memories/{userId}/visible?groupId=` and the MCP
  `get_visible_memories` tool require USE on every named group before recalling
  (`ResourceAccessGuard.requireUseAccessToEach`). The context-key path is branch
  `fix/reserved-context-keys`.
- **A1 — anonymous agent-card fan-out.** `getDefaultAgentCard`/`listA2AAgents` reuse their roster
  scan for 30 s (empty result included), so a flood of anonymous `/.well-known/agent.json` requests
  costs one scan per window instead of ~200 queries each.
- **A3.** The agent-card WARN log sanitizes the anonymous path parameter.
- **E5 — `SelfUrlResolver` ignored `quarkus.http.host`.** With the listener bound to one specific
  non-loopback address, the derived self URL is that address instead of `127.0.0.1`, which might be
  another process and would have received `${caller:token}`'s self release. Wildcard and localhost
  binds keep `127.0.0.1`; a specific loopback or IPv6 address is used verbatim.

### Pre-push review follow-ups

- **Failed-fires view.** `GET /schedulestore/schedules/admin/failed` stays complete for admins; for
  anyone else each entry is kept only if its schedule passes the same read rule as
  `/{id}/fires`. A page can therefore hold fewer entries than `limit`, which is documented.
- **Export and preview** leave out schedules that run as another user (admins export everything).
- **Team cadences belong to their group.** A cadence runs as its creator, but callers with VIEW on
  the group can read it and callers with EDIT can toggle or delete it. With workspaces off every
  cadence is listed (`ListingScope.includeTeamCadences`); under enforcement co-editors reach them
  by id or through the group workspace. Firing and re-pointing still act as the creator.
- **Refused trigger reads** throw `IRestAgentTriggerStore.TriggerNotVisibleException`, a subclass
  of the not-found exception, so HTTP clients still get a 404 while MCP `chat_managed` no longer
  deletes the caller's conversation mapping on a refusal.
- **Group approvals.** `approveGroupPhase` (plain and streaming) USE-gates a transcript owner who
  is neither admin nor approver, so access revoked during a pause cannot drive the run to the end.
- **`/v1`.** A refused exact id match stops at "unknown" instead of falling through to name/slug
  matching.
- `requireOwnUserId` now exempts every `system:` identity, the same test the listing and `mayAccess`
  use.
- Streaming discuss refuses a missing USE grant with a plain 403 before the stream opens, the same
  contract `continueDiscussionStreaming` has for its ownership check (the review suggested the
  opposite direction; the existing continuation test documents the 403 contract).
- `ResourceAccessGuard.currentLevel` keeps VIEW for a resource with no descriptor: `VIEW.includes(USE)`
  holds, so it already agrees with `requireLegacyFallback`. A test now pins `hasAccess(USE)` for it.
- Testcontainers cases for the scoped listing in `datastore/mongo/MongoScheduleStoreTest` and
  `datastore/postgres/PostgresScheduleStoreTest`, plus docs in `scheduling.md`, `hitl.md`,
  `import-export-an-agent.md` and AGENTS.md.

### Design decisions

- `ResourceAccessGuard` gained `currentLevel`/`hasAccess` (non-throwing, current descriptor) for
  id-only listings, and `requireUseAccessToEach`.
- Trigger authority is EDIT on the *current* targets: a trigger has no owner, and what it controls is
  how those agents are reached.
- Schedule listing under enforcement without an `agentId` shows the caller's own schedules only;
  a team's shared system schedules appear when listing by an agent the caller may edit. "Agents the
  caller may edit" cannot be pushed into the schedule query, and filtering the page afterwards
  would bring back the short-page problem the HITL redaction was moved into the query to fix.

### Compatibility

- `IScheduleStore` gained `ListingScope` overloads; the old signatures are default methods.
- `TeamCadenceService.processScheduledFire(Map)` became `processScheduledFire(String, Map)`.
- Export download URLs changed shape (see H2c). `RemoteApiResourceSource` follows the `Location`
  header and is unaffected.
- Non-admin editors no longer see or manage other users' personal schedules even with workspaces
  off. `/parser` now needs an authoring role.

### Follow-ups

- `RestInterfaceFactory` still hard-codes `127.0.0.1:${quarkus.http.port}` for internal loopback
  calls (same E5 shape, no token release involved).
- The Manager saves an export under the URL's last segment, so the token appears in the saved file
  name; it could prefer `Content-Disposition`.

```decision-log
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-26 | Trigger update/delete need EDIT on the agents the stored trigger routes to | H2b: any editor could re-point another team's intent | An owner field on triggers (needs a migration for existing triggers) |
```
