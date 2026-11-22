## 🔒 fix(email): a mail-started turn cannot shed its taint through EDDI's own API (2026-11-21)

**Repo:** EDDI (`fix/email-guard-start-paths`, on top of `feat/email-sender-lists`) — closes the known gap phase C1
of the email channel v3 control plan left in the §5.3 escalation guard.

### What changed and why

C1 refused the guarded tools and endpoints in a tainted turn, but that turn could still start an **untainted**
conversation through EDDI's own API — an `httpcalls` call to `POST /agents/{id}/start` or `/schedulestore/schedules`,
an MCP client calling `chat_with_agent`, `create_schedule` or `start_group_discussion` — and that conversation could
then change sender lists or integrations, or decide approvals. The requests arrived without the taint. Now they
carry it, and EDDI honours it:

- **Sent.** Every HTTP request of a tainted turn carries `X-Eddi-Turn-Origin: email`: `ApiCallExecutor` sets it after
  the configured headers and after `AgentCallMarker`, so no configuration replaces it; `OutboundHttp` sets it on every
  hop, the cross-origin redirect hop included; `McpToolProviderManager` writes it into `params._meta`
  (`ai.labs.eddi/turnOrigin`) on the calling thread and mirrors it into the header, as the trace context, so the
  transport's session-expiry retry on an HTTP-client thread keeps it; `LoopbackCallerAuthFilter` puts it on the
  internal loopback of the MCP admin tools and setup-api.
- **Honoured.** `TurnOrigin.current()` now also reads the header of the inbound request served on the thread
  (`CurrentVertxRequest`, which Quarkus REST sets for every resource method and the MCP HTTP transport sets when it
  activates the request context for a tool call). The start and `say` choke points of `ConversationService` and
  `createGroupConversation` already read `current()`, so every conversation start, turn and group start that a
  tainted request makes is tainted — REST, streaming, `/v1`, the MCP conversation and group tools.
- **Carried across thread hops**, where the request is gone: `ScheduleFireExecutor.fire` binds the schedule's taint
  around *every* branch (the team cadence discussion used to start untainted); `RestScheduleStore` stamps
  `metadata.turnOrigin` on create, keeps a stored taint on every edit and adds the request's (sticky), binds it around
  a manual fire and stamps the schedule before a dead-letter retry; team cadences (`RestGroupWorkspace`) and
  lesson-consolidation schedules (`RestGroupLessons`) are stamped at creation; the streamed group continuation binds
  it on the executor; an agent test run binds it on its run thread and on every attempt's thread.
- **Groups continued later.** A continuation or a follow-up from a tainted turn taints the stored discussion, and
  `MemberTurnExecutor` and the follow-up put `turnOrigin` into the context of *every* member turn, not only a member's
  first — so members an untainted round started are tainted too.
- **Approval-like writes refused.** `EscalationGuard.GUARDED_PATHS` gains the oversight endpoints
  `AgentOriginatedRequestFilter` already keeps from agents (`/hitl/samples/**`, `/hitl/autonomy/**`,
  `/hitl/analytics/rebuild`, all of `/administration/spend-control-changes/**`) and the team handoff decisions
  (`…/workspace/handoffs/{id}/accept|decline`); `GUARDED_TOOLS` gains `accept_team_handoff` and
  `decline_team_handoff` ("a human decision").
- **The receiving side checks too.** `TaintedRequestFilter` answers `403` (`EMAIL_ORIGIN`) to a request that carries
  the header and writes to a guarded path; `McpErrorResultInterceptor` fails a guarded MCP tool called from such a
  request before it runs, whatever name the client listed it under.

Docs: [`email-integration.md`](../email-integration.md#a-mail-cannot-change-its-own-rules) — the route table and the
header rule.

### Design decisions

- **Propagate, don't refuse, the start paths.** Starting a conversation, a schedule or a discussion from a mail is
  legitimate; it simply runs tainted. Only the approval-like writes are refused.
- **Header trust.** The header is taken from any caller, authenticated or not — the internal loopback carries no
  credential — because it can only restrict. A header can add the taint and never remove it: what it says is only
  ever combined with the other sources, and `none`, junk or no header leave a bound or stored taint in place.
- **Sent to every target, not only to this deployment.** `AgentCallMarker` marks only requests it recognises as
  EDDI; the taint does not, because an EDDI reached under a name the self-check does not know would otherwise start an
  untainted conversation. The value says only that a mail started the turn, and only a tainted turn sends it — A2A
  already sent it to any peer.
- **The taint is not put on `CallerIdentity`.** `capture()` returns nothing for an anonymous caller, which is exactly
  the unauthenticated loopback.
- **Schedules keep it in `metadata`**, as the check-back already did, so neither datastore needs a column.

### Not covered

- `cancel_conversation`, `cancel_group_discussion`, `review_conversation_rating` and `acknowledge_alert_event` stay
  allowed: none widens who can make EDDI act or approves anything.
- (The backlog and the over-the-socket test are covered by the follow-up entry below.)

### Decision log

```decision-log
| 2026-11-21 | A tainted turn's HTTP requests carry X-Eddi-Turn-Origin to every target, and an inbound request carrying it runs tainted; the header can add the taint, never remove it | Email v3 §5.3: a mail-started turn must not shed the taint by starting a conversation, schedule or discussion through EDDI's own API | Refusing every start path from a tainted turn (breaks legitimate mail-driven work); sending the header only to recognised self hosts (a miss silently drops the taint); carrying it on CallerIdentity (null for the anonymous loopback) |
```

### Regression notes

```regression-note
| 2026-11-21 | Saving an email integration also fails (400) when a target agent holds accept_team_handoff or decline_team_handoff, or an httpcalls write to /hitl/samples, /hitl/autonomy, /hitl/analytics/rebuild, /administration/spend-control-changes or a handoff accept/decline path; a request carrying X-Eddi-Turn-Origin: email that writes to a guarded path now answers 403 | The email v3 §5.3 guard lists widened | Remove the tool or httpcall from that agent; send the request from an untainted turn | fix/email-guard-start-paths |
```

## 🔒 fix(email): the escalation guard after its security review — backlog, configuration, credentials (2026-11-22)

**Repo:** EDDI (`fix/email-guard-review`, on top of `fix/email-guard-start-paths`) — the findings of an independent
security review of the §5.3 guard. Threat model: whoever gets a mail processed controls the text and, through it, the
model's tool calls.

### What changed and why

- **The team backlog no longer launders the taint (high).** `SharedTaskList.TaskItem` and
  `WorkspaceHandoffs.HandoffOrigin` carry the origin (`origin`, `turnOrigin`), stored in both datastores (the workspace
  is a JSON document). `SharedTaskList.addTask`/`addAgentTask` stamp `TurnOrigin.current()` on every write — REST
  `POST …/workspace/backlog`, MCP `add_team_task`, a handoff (AUTO), a member's task tool — and every replacement
  (`updateTask`, each status transition) keeps the stored taint, so a positional copy cannot drop it. A REVIEW
  handoff a person accepts keeps the filer's taint; a decision-rule handoff of a tainted discussion is filed under its
  taint. `TeamCadenceService` binds the combined taint of the pulled tasks around `prepareCadenceDiscussion`, so the
  run's discussion — and every member conversation — is tainted, and a tainted run's writeback taints the tasks.
- **Configuration writes need a person (medium).** New `EscalationGuard.CONFIG_TOOLS`/`CONFIG_PATHS`: agent, workflow
  and extension stores (v5 names too), snippets, variables, triggers, group configs, deploy/undeploy, setup, import,
  the config MCP tools and `createSubAgent`/`retainAgent`. A tainted turn's write is refused unless it is the approved
  execution of a gated call — `ApprovedCallScope`, which `ToolLoopResumer` binds around exactly that call
  (`isActiveFor(conversationId)`, so another conversation's approval does not count; `OutboundHttp` honours it on the
  first hop only). Across EDDI's own API the sender says so in `X-Eddi-Approved-Call`, engine-owned like the taint
  header (httpcalls, `OutboundHttp`, MCP `_meta` → header, the internal loopback of the MCP admin tools): set only
  during the approved call, replaced everywhere else.
- **Credentials and sharing (medium).** `GUARDED_PATHS` gains resource shares/requests/visibility/space/owner, the
  secrets vault (tenant and space, grants, rotation), connections and their settings, a user's connection
  authorize/grant, and workspace settings. No user or role administration endpoint exists; no MCP tool writes any
  of these.
- **The receiving side binds explicitly (medium).** `TaintedRequestFilter` binds the header on a request-scoped
  `InboundTurnOrigin`; `McpErrorResultInterceptor` does the same before every MCP tool (the MCP transport is Vert.x,
  no JAX-RS filter sees it). `TurnOrigin.current()` reads the holder and the request header; an active request
  context whose taint cannot be read now counts as **tainted**, not untainted. New `EmailOriginTaintHttpIT`
  exercises it over real HTTP: a REST start and an MCP `create_conversation` with the header give conversations whose
  `update_email_senders`-shaped httpcall is never sent, and both receiving sides refuse.
- **`confirmOpen` and the audit on every save (medium-low).** `updateChannel` diffs the stored and saved lists:
  emptying a non-empty allowlist needs `?confirmOpen=true` (MCP `update_channel_integration` `confirmOpen`), warns
  `email.allowlist-emptied`, and every change writes `EMAIL_SENDERS_CHANGED`; `updateEmailSenders` delegates, so it
  audits once. The Manager's page Save asks before such a save.
- `GroupConversation.setOrigin` never clears or replaces a set taint. Docs: the header goes to third parties on
  purpose; a relay that drops it sheds the taint. Operator revision 84.

### Not covered

- ~~The in-engine approval is per thread: a nested turn that an approved call runs synchronously on the same thread
  would share it for `OutboundHttp`.~~ Closed by the CodeRabbit round below (`ApprovedCallScope.approvesHere`).
- The MCP binding reads the request through `CurrentVertxRequest`, which quarkus-mcp-server sets; the IT is what
  proves it, and fails if a release stops doing so.
- The fail-closed branch (an active request context whose taint cannot be read) has no test.
- The backlog exploit test stands in for `createGroupConversation` and runs the member turn under the bound taint;
  the #1499 tests cover those two links. The Manager's confirm dialog before a page Save is not tested at page
  level (the helper and the API call are).
- Other workspace content a cadence quotes (lessons, outcomes) was not reviewed for the same laundering.
- Found on the way: the interceptor test's `@Tool` fixtures named like real tools broke every `@QuarkusTest` boot on
  #1499 (duplicate feature methods); renamed.

### Decision log

```decision-log
| 2026-11-22 | A tainted turn may write configuration only in a call a person approved through the tool-approval gate; shares, secrets, connections and workspace settings stay refused whoever approves | Security review of the email guard: config writes carry mail text into untainted conversations, credential writes widen who can use EDDI | Refusing configuration writes outright (breaks approved, human-seen changes); allowing them with a save-time warning only |
| 2026-11-22 | Backlog tasks and handoff origins carry the taint, stamped on every write and sticky through every copy; a cadence that pulls a tainted task runs a tainted discussion | The cadence fires from an untainted schedule and would otherwise run mail text untainted | Refusing backlog writes from tainted turns (breaks mail-driven team intake) |
```

### Regression notes

```regression-note
| 2026-11-22 | A mail-started conversation's write to an agent/workflow/extension store, snippets, variables, triggers, a group config, deploy/undeploy, setup or import, and its config MCP tools, now fail with "unless a person approves that call"; a full PUT of an email integration that empties a non-empty allowlist answers 400 without confirmOpen=true | Security review of the email escalation guard | Gate the tool with hitlConfig.toolApprovals so a person approves the call; add confirmOpen=true to the save | fix/email-guard-review |
```

## 🔒 fix(email): the guard after CodeRabbit's review of #1499 — pinning, approval scope, sticky schedules (2026-11-22)

**Repo:** EDDI (`fix/pr1499-coderabbit`, onto `fix/email-guard-start-paths`, PR #1499) — CodeRabbit's review of
`1c7010074f`: eight inline findings and one outside the diff.

### What changed and why

- **A gated `httpcalls` configuration write of a mail-started turn is pinned again (major).** The approval preview
  (`ApiCallExecutor.resolve`) ran the escalation guard as an unapproved call: no approval exists at gate time, and the
  resume re-check runs outside `ApprovedCallScope` too. So the preview was refused, and the call was approved unpinned,
  on its tool name and arguments, not on the request that runs. A preview now judges the configuration write as the
  approved execution would. It sends nothing, the guarded endpoints (channels, approvals) are still refused in it, and
  the real build at execution still needs the approved scope. (An MCP configuration tool has no request resolver and
  is approved on its name and arguments, as before.)
- **An approval is stated only for its own conversation (major).** The MCP `approvedCall` meta, `OutboundHttp`'s
  first hop and the internal loopback's `X-Eddi-Approved-Call` used the unscoped `ApprovedCallScope.isActive()`. A
  conversation that an approved call starts or continues inline runs its tool calls on the same thread and inherited
  the approval. `ToolExecutionService` now binds the conversation of every tool it runs
  (`ApprovedCallScope.inConversation`). The three granting sites use `approvesHere()`, which needs the approval to
  belong to that conversation. `isActive()` stays where it restricts (`checkOutgoing`).
- **The schedule taint survives a concurrent clean edit (major).** Both stores' `updateSchedule` now keep a stored
  `turnOrigin: email` inside the write. On Postgres a `CASE` in the `UPDATE` does it. On Mongo a clean write is
  conditional on a clean row and is redone with the taint when it matches nothing. The REST merge alone read the row
  first, so a clean save of an older copy could write the taint away. The match agrees with `TurnOrigin.normalise`
  (case-insensitive, trimmed).
- **Enabling or dismissing a schedule from a tainted request taints it (major).** This is done before the write, as
  `retryDeadLetter` already did. Disabling stamps nothing.
- **`PUT ?version=0` can no longer empty an email allowlist unconfirmed (major).** The sender-list baseline was read
  with the raw `0`, which the store does not resolve. The read failed and the `confirmOpen` check and the audit entry
  were skipped. The version is now resolved first, the audit records the resolved version, and a baseline that cannot
  be read refuses the save (a missing version still gets the update's own 404/409).
- **Backlog transitions return the stored task (minor).** All eleven `SharedTaskList` lifecycle methods returned their
  own positional copy, which has no origin. They now return what `replaceTask` stored, taint included.
- **A lesson-consolidation reschedule keeps the replaced schedule's taint (minor).** The old schedule is read before
  it is deleted. A failed read leaves the old schedule in place.
- **`EmailOriginTaintHttpIT` checks the tainted turns' responses.** An unrelated 404 or 500 left the request count
  unchanged, so the test passed for the wrong reason.
- **Manager wording.** The empty-allowlist confirmation and the notice after such a save say that sender
  authentication limits who can run the agent only when it is enabled. Changed in all 11 locales and in both
  fallbacks.
- **Tests from the review's nitpicks.** The continuation test captures the first round write as it is made, and a
  member-turn test runs a plain round and then a tainted one on the same discussion.

### Design decisions

- **Preview as approved, not a gate-time exemption.** The gate and the resume re-check must resolve the same
  fingerprint. Exempting only the gate would make every pinned configuration write fail closed at resume.
- **Bind the running conversation rather than suspend the approval in nested turns.** Suspending it would also drop
  `SelfUngatingGuard` from the outer call's requests. The binding narrows only the places that grant.
- **The sticky-taint rule lives in the stores**, so every caller of `updateSchedule` gets it (the REST stamp, retry,
  enable and dismiss), not only the REST `PUT`.

### Not covered

- `stampTurnOrigin` (retry, enable, dismiss) still writes the whole row it read. The taint itself can no longer be
  lost, but a concurrent configuration edit in that window is overwritten, as before.
- `McpErrorResultInterceptor` (the MCP server side) still reads the unscoped `isActive()`. It runs on an inbound
  request thread, where no approval is bound.
- Nested pipeline work that is not a tool call (a loopback REST request from a pipeline task of a conversation that
  an approved call runs inline) still sees the outer call's conversation as the one running.
- The gate and the resume re-check resolve the pinned request under the same bound taint by construction; a real
  pause and resume of a tainted configuration write is not exercised by a test.
- Other delete-and-recreate schedule paths (`EmailPollScheduler`, `ConversationContinuations`) were not reviewed
  for the reschedule rule applied to lesson consolidation here.

### Decision log

```decision-log
| 2026-11-22 | An approval preview builds a gated call's request as its approved execution would, guarded endpoints still refused | A refused preview left mail-started configuration writes approved unpinned | Exempting the gate only (resume re-check fails closed); approving such calls unpinned |
| 2026-11-22 | The engine states a person's approval only on requests of the tool call's own conversation (`ApprovedCallScope.approvesHere`) | A conversation an approved call ran inline inherited the approval over MCP, OutboundHttp and the loopback | Suspending the approval binding inside nested turns (drops the outer call's self-ungating check) |
```
