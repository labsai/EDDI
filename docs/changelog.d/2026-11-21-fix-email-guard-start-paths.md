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

- `add_team_task` and team handoffs queue *content* that a later, untainted cadence run works on; the backlog task
  carries no taint. The decisions on handoffs are refused, the queueing is not.
- `cancel_conversation`, `cancel_group_discussion`, `review_conversation_rating` and `acknowledge_alert_event` stay
  allowed: none widens who can make EDDI act or approves anything.
- The two HTTP halves (header sent, header honoured) are unit-tested separately; nothing joins them over a real
  socket. The MCP server half rests on `ContextSupport.requestContextActivated` setting `CurrentVertxRequest`
  (quarkus-mcp-server 2.0.4, read from the bytecode), not on a running server.

### Decision log

```decision-log
| 2026-11-21 | A tainted turn's HTTP requests carry X-Eddi-Turn-Origin to every target, and an inbound request carrying it runs tainted; the header can add the taint, never remove it | Email v3 §5.3: a mail-started turn must not shed the taint by starting a conversation, schedule or discussion through EDDI's own API | Refusing every start path from a tainted turn (breaks legitimate mail-driven work); sending the header only to recognised self hosts (a miss silently drops the taint); carrying it on CallerIdentity (null for the anonymous loopback) |
```

### Regression notes

```regression-note
| 2026-11-21 | Saving an email integration also fails (400) when a target agent holds accept_team_handoff or decline_team_handoff, or an httpcalls write to /hitl/samples, /hitl/autonomy, /hitl/analytics/rebuild, /administration/spend-control-changes or a handoff accept/decline path; a request carrying X-Eddi-Turn-Origin: email that writes to a guarded path now answers 403 | The email v3 §5.3 guard lists widened | Remove the tool or httpcall from that agent; send the request from an untainted turn | fix/email-guard-start-paths |
```
