## ✨ feat(email): sender lists editable over REST and MCP, and a mail can no longer change its own rules (2026-11-21)

**Repo:** EDDI (`feat/email-sender-lists`) — phase C1 of the email channel v3 control plan (§4, §5).

### What changed and why

**Sender lists have their own endpoint.** Allowing one more address used to mean reading and rewriting the whole
integration, which an agent does badly and which races with an admin editing the same document.
`GET`/`POST /channelstore/channels/{id}/email-senders` (`RestChannelIntegrationStore`, logic in
`EmailSenderLists`) read `{version, allow, block}` and apply `{expectedVersion, add, remove, reason, confirmOpen}`.
The read, the version check and the write are one step under the store's lock, and the write *is* `updateChannel`,
so a change is a new version with the same validation, deployment checks, warnings and poll re-registration as a
save. A stale `expectedVersion` is a `409` carrying the current lists; entries are compared normalised and no-ops are
reported in `unchanged` (a request of only no-ops writes nothing); an invalid pattern, an entry on both lists, more
than 200 entries, or an entry both added and removed is a `400`. Removing the last allowlist entry opens the
integration to every sender, so it needs `confirmOpen: true` and warns `email.allowlist-emptied`. Every save now also
warns `email.allowlist-broad` for an allowlist entry over a public suffix or the integration's own domain
(`EmailChannelSettings.allowlistIsBroad`, using the bundled Public Suffix List). MCP: `list_email_senders`,
`update_email_senders` (122 tools). Each change writes one audit-ledger entry (`EmailSenderListAudit`, task
`email.senders`): actor, integration, versions, diff, reason.

**The escalation guard (§5.3).** A turn that a mail started must never widen who can make EDDI act, nor decide its own
approvals. `TurnOrigin` is the taint, `EscalationGuard` the refusal:

- *Set* where the turn is submitted: `EmailInboundProcessor` puts the reserved context key `turnOrigin=email` on the
  conversation start and on every mail turn, and binds it around a group start.
- *Stored* on `ConversationMemory`/snapshot (`origin`, round-tripped like `resolutionProvenance`) and on
  `GroupConversation`, so a HITL resume next week, a self-continuation or a later group round still carries it.
- *Bound* per turn as a third component of `ResolutionPrincipal`, so it travels wherever the principal already did
  (`CallerIdentityContext.propagate`, the tool timeout executor, the HITL resume binding).
- *Inherited* at the two choke points of `ConversationService`: a start inherits the starting thread's taint
  whatever the child's user id (unlike provenance), and a `say` from a tainted thread taints the conversation it runs
  in — `converseWithAgent` continuing an existing conversation would otherwise shed it.
- *Refused* for a tainted turn: the channel-integration, sender-list and HITL-decision tools (and the planned
  `update_email_roles`, `update_email_send_out`, `decide_held_email`) in `ToolExecutionService` before the rate
  limiter, for every tool source; non-GET requests to `/channelstore/channels/**`, `/agents/{id}/resume`, group
  `approve`/`human-input` and spend-control `approve` in `ApiCallExecutor` and on every `OutboundHttp` hop, on a
  normalised path (percent-decoding, matrix parameters, `..`, any prefix). A tainted `httpcalls` write is not
  followed through a redirect.
- *Save time*, best effort: `EmailAgentToolsInspector.targetsHoldingGuardedTools` refuses an integration whose target
  agent holds a guarded tool by name or an `httpcalls` write to a guarded path.

Propagation points checked (§14 item 2): delegation (`converseWithAgent`, new and continued) and sub-agents
(`createSubAgent`) through the start/`say` inheritance; group member turns and nested groups through
`GroupConversation.origin` and the member start context; scheduled check-backs (`schedule_followup`) through the
schedule's metadata, re-applied by `ScheduleFireExecutor` to a `new`-strategy conversation (a `persistent` one is
the tainted conversation itself); A2A through the `X-Eddi-Turn-Origin` header, which `RestA2AEndpoint` binds for the
start and the turn it runs. "Consult" is not a separate feature on `main`; consulting another agent is
`converseWithAgent`.

**Manager.** An existing email integration's page shows the sender lists as chips with add, remove, a reason and
**Apply sender changes** (`EmailSenderListsEditor`), in all 11 locales. Applying moves the page to the new version,
so the draft is reseeded and a later save cannot write the old lists back; the editor is disabled while the page has
unsaved changes. The create dialog keeps the comma-separated fields.

**Platform Operator** (revision 82): `GET /channelstore/channels/descriptors`, `GET …/email-senders` and the gated
`POST …/email-senders` are on the allow-list — the one channelstore write, narrowing the exclusion in
`planning/operator-write-scope-plan.md` to exactly this endpoint — and the prompt says how to use them.

### Design decisions

- **Addresses in the audit ledger are pseudonymised.** A ledger entry can never be erased, so an exact address is
  written as its keyed pseudonym (the token inbound refusals and GDPR erasure use); domain patterns stay in clear.
- **The taint rides on `ResolutionPrincipal`** rather than a new thread-local, because the principal is already what
  every dispatch binds and propagates; a separate binding is how a propagation point gets missed. A small
  principal-less binding (`TurnOrigin.callWith`) exists only for the two places that start work on a thread no turn
  runs on (the poller's group start, a nested group).
- **Taking the taint from anyone is safe** — it only restricts — so the A2A header and the context key need no
  authentication; the key is still reserved in `ClientContextGuard`.
- **Reading the lists needs VIEW**, like reading the document; changing them needs EDIT.
- **Starts through EDDI's own API** (an `httpcalls` call to `POST /agents/{id}/start` or `/schedulestore/schedules`,
  an MCP client calling `chat_with_agent`, `create_schedule` or `start_group_discussion`) and the `/hitl/autonomy`
  and `/hitl/samples` writes are covered by the follow-up entry "a mail-started turn cannot shed its taint through
  EDDI's own API" (`fix/email-guard-start-paths`, same date).
- "The last change and who made it" in the Manager (§5.4) is not shown yet: the audit entry records it, but there is
  no read of it per integration.

### Decision log

```decision-log
| 2026-11-21 | The email origin taint rides on ResolutionPrincipal and is persisted on conversation memory and group conversations; sticky, inherited whatever the child's user id | Email v3 §5.3: a mail-started turn must not change its integration or decide approvals, through any hop | A separate thread-local (one more binding for every dispatch to forget); taint only at save time (best effort) |
| 2026-11-21 | The operator may call POST /channelstore/channels/{id}/email-senders, gated: the only channelstore write it holds | Email v3 §5.4; sender lists only, expectedVersion, confirmOpen, audited | Granting the whole-document channel PUT or the full channel read (credentials as stored) |
```

### Regression notes

```regression-note
| 2026-11-21 | Saving an email integration now fails (400) when a target agent holds a channel, sender-list or HITL-decision tool by name, or an httpcalls write to a guarded path | The email v3 §5.3 save-time check | Remove the tool from that agent, or point the target at another agent | feat/email-sender-lists |
```
