## ✨ feat(email): sender roles — it matters who sent the mail, and a role only ever narrows (2026-11-22)

**Repo:** EDDI (`feat/email-sender-roles`) — phase C5 of the email channel v3 control plan (§4, §9, §12).

**Base.** Branched from `fix/inbound-mail-slack-hardening` (#1394: DKIM passes bound to signed headers, replay
protection by signature), with three merges: `feat/email-sender-lists` (#1498, C1: the taint, the escalation guard,
the targeted-edit pattern), `feat/email-addressing` (#1496, v2 phase 3: per-target `allowedSenders`, which roles sit
on top of), and `fix/email-guard-start-paths` (the C1 follow-up with `TaintedRequestFilter`). C4 (the approval gate)
was not merged: it exists only as unpushed local work.

### What changed and why

**`emailControl`, a typed object (§4).** `ChannelIntegrationConfiguration.emailControl` holds the new settings next
to `platformConfig` (maps and ordered lists cannot live in the flat string map). Both datastores persist it with the
document; `ChannelIntegrationStoreEmailControl{Mongo,Postgres}ContainerTest` round-trip it, versions included.
Validation (`EmailSenderRoles.validate`) runs on every save and on the targeted edit; `emailControl` on any other
channel type is a `400`.

**Roles (§9.1).** `senderRoles` is an ordered list of `{role, senders}` (first match wins); `rolePolicies` maps a role
to `{targets, tools, maxTurnsPerSenderPerDay, approvalPolicy, sendOut}`. `EmailSenderRoleResolver` decides the role
once per mail in `EmailInboundProcessor`, after the sender lists, `requireAuthentication` and replay protection, from
the sender of the signed view:

- an unauthenticated mail (anything but an aligned pass) is `anonymous` **before** any pattern is looked at — a
  forged `From` never earns a role — and never gets a tool;
- an authenticated sender no entry names is `default` (today's behaviour); without any roles nothing changes;
- a role without a policy refuses the mail (`blocked:role_without_policy`): fail closed.

**Only narrowing, by construction.** Save refuses a widening policy, and the runtime intersects again:
`targets` is checked *in addition to* the target's `allowedSenders` (`blocked:role_target_not_allowed`, never a
reroute; a continuation is judged by its owner agent), the cap is the lower of the role's and the integration's, and
`tools` is a **ceiling** (`TurnToolCeiling`). The ceiling rides like the C1 taint: in the reserved context key
`emailToolCeiling` of the conversation start and every mail turn, stored on the conversation memory
(`toolCeiling`, only ever intersected), bound as a fourth component of `ResolutionPrincipal` and inherited by every
conversation a turn starts, carried in a dead-letter record for its replay. It is enforced in `ToolExecutionService`
(every LLM tool source, the HITL resume loop, `mcpcalls`) and in `ApiCallsTask` for rule-driven `httpcalls`. A role
with `tools` cannot reach a GROUP target, whose members use their own tools. The turn sees `mail.senderRole`, for
prompts only.

**Editing (REST and MCP parity).** `GET`/`PUT /channelstore/channels/{id}/email-roles` and the MCP tools
`list_email_roles` / `update_email_roles` (124 tools): `expectedVersion` (409 with the current roles), a new version
through the ordinary save, one `EMAIL_SENDER_ROLES_CHANGED` audit entry (role names only, reason scrubbed of
addresses). A whole-document save that leaves `emailControl` out while the stored version has roles is refused, so a
client that does not know the field cannot drop them silently.

**Guards.** `update_email_roles` was already on `EscalationGuard.GUARDED_TOOLS`, and `channelstore/channels/**`
covers the new path for `ApiCallExecutor`, `OutboundHttp` and `TaintedRequestFilter`; tests now pin both.

**Manager, operator, docs.** The email integration page shows the roles under the sender lists, with the trust note
and a JSON editor applied on its own (11 locales). The operator reads the roles (`GET .../email-roles`) and its
prompt explains them; the `PUT` stays off its allow-list, because `planning/operator-write-scope-plan.md` allows
exactly one channelstore write. Operator revision 86. `docs/email-integration.md` "Sender roles", `docs/mcp-server.md`,
the `eddi-admin` skill, tool counts, the OpenAPI operations snapshot (edited by hand: the refresh needs a live
instance).

### Design decisions

- **Settings of phases that have not shipped are refused, not ignored.** `approvalPolicy` accepts only `none` (C4's
  gate is not in this tree) and `sendOut` only `false` (C6). `anonymous` means "no tools" until C4's `readOnlyTools`
  exists.
- **The tool ceiling is sticky per conversation**, like the taint: a later mail can narrow it, never widen it. Widening
  a role's tools applies to new conversations.
- **Tools are matched by the exact name the model calls**; rule-driven `httpcalls` by call name.

### Verification

Unit tests (JUnit 5 + Mockito) for the validation, the resolver, the processor (forged `From`, unauthenticated
mail, replay, target and cap narrowing, groups), the REST and MCP edits, the redacted read, the ceiling through
`startConversation`, delegation and `say` (`ConversationServiceToolCeiling{Start,Say}Test`), `ToolExecutionService`
and `ApiCallsTask`; container round trips on MongoDB and PostgreSQL. Each security link was mutation-checked one
mutant at a time (a forged `From` gets the role, a role widens a target's rules or cap, unauthenticated mail
inherits a role, a replayed mail reuses one, a tainted turn edits roles by tool or path, the ceiling is not
enforced or not stored): every mutant fails a test that names it.

### What's next

- C4 (approval gate) to give `approvalPolicy` and `anonymous`'s `readOnlyTools` meaning; C6 for `sendOut`.
- Whether the operator may write roles is a decision for the plan's owner.
- A live run against the test mailbox was not done.
