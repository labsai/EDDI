## 🔒 fix(security): close a group of authorization/IDOR gaps across REST, tools and health (2026-09-26)

**Repo:** EDDI (`fix/security-access-control`)

### What changed and why

A batch of verified access-control gaps were hardened. Each is scoped so that
legitimate flows (the Manager, admins, internal orchestrators, auth-disabled dev)
keep working, while the missing owner/role/redaction checks are added.

- **Active-conversation endpoints role-gated.** `GET /conversationstore/conversations/active/{agentId}`
  and `POST /conversationstore/conversations/end` were reachable by any authenticated
  token. The same gap was closed independently on `main` by the soft-delete owner-check
  fix (`fix-conversation-access-guard`), which also adds an agent EDIT check under
  workspace enforcement and server-side state for `/end`; that is the contract kept:
  `eddi-admin`/`eddi-editor` (the tier the Manager's conversation-monitoring page runs
  as), plus EDIT on the agent when workspaces are on. This batch adds
  `SecurityAccessControlAnnotationsTest`, which pins the role set by reflection.
- **`converse_with_agent` cross-user access.** The tool now requires a model-supplied
  `conversationId` to belong to the same user the tool is bound to, so the LLM can no
  longer continue another user's conversation by supplying its id. New conversations
  (started as the bound user) and internal group-orchestrator callers are unaffected.
  A conversation that records no owner is refused too (fail closed): its ownership
  cannot be established, and the model supplied the id.
- **Schedule `persistentConversationId`.** Create now nulls a caller-supplied
  `persistentConversationId` (mirroring import); the fire path additionally refuses to
  reuse a persistent conversation owned by a different user than the schedule.
- **Schedule ownership.** Reads (`readSchedule`, `readAllSchedules`) and the remaining
  state-changers (`delete`, `enable`, `disable`, `retry`, `dismiss`) now apply the same
  owner check `create`/`update`/`fire` already used, so an editor cannot see or mutate
  another user's schedule; unowned/system schedules stay shared.
- **Agent-trigger ownership.** Triggers carry no owner field, so `delete`/`update` are
  now gated on USE access to the agents the stored trigger routes to — a foreign editor
  can no longer re-point or remove another team's trigger.
- **`UserMemoryTool` visibility guardrail.** The tool now applies the configured
  `defaultVisibility`, restricts writable visibilities to a configurable allow-set
  (default `self`), and refuses overwriting a `global` key owned by another agent unless
  configured — including a global key whose entry records no owning agent (legacy or
  migrated data), since the guard cannot show it is this agent's. Two new `guardrails`
  fields, documented in `docs/user-memory.md`: `allowedVisibilities`, `allowGlobalKeyOverwrite`.
- **`returnDetailed` step-data exposure.** The detailed conversion now drops sensitive
  internal keys (`audit:*`, `*:trace:*`, `*Error`) and runs values through
  `SecretRedactionFilter`, matching the SSE path; full-fidelity debugging remains on the
  owner/admin-gated raw endpoint. Redaction recurses into `Map` keys and values,
  collection elements and `Object[]` elements, so a secret embedded in a structured value
  (e.g. a deserialized httpCall response body under an agent-chosen key, or a
  token-keyed map) is masked too, not just top-level strings. A value under a
  credential-named key (`apiKey`, `token`, `secret`, `password`, `authorization`, at any
  depth) is masked outright even without a credential shape, since walking a map
  separates the name from the value the name-bound filter rules need; the filter's
  usual exemptions (under 8 characters, a vault reference) still apply. A map, list or
  array under a credential-named key is replaced as a whole, so a credential used as a
  map key beneath it cannot leak either.
- **Semantic parser endpoint.** `POST /parser/{parserId}` was role-less; it now requires
  `eddi-admin`/`eddi-editor` and a `VIEW` check on the specific parser configuration.
- **Postgres health readiness.** The anonymous readiness payload no longer returns the
  JDBC URL or raw exception text — status only; the failure is logged server-side.
- **A2A `tasks/send`.** The JSON-RPC endpoint now requires a real role (`eddi-user` and
  up) rather than mere authentication, and the reply returns only the text output instead
  of the serialized `ConversationOutput` map.
- **`deleteConversationLog`.** Now uses the strict owner check, so a legacy no-owner
  conversation is not deletable (soft or permanent) by an arbitrary token. The strict
  check resolves the owner the same way as the soft-delete fix on `main` (live
  descriptor, else the archived one; no descriptor at all is a 404 unless admin), so a
  soft-deleted conversation can only be permanently deleted by its owner or an admin.
  A pre-v5.1.6 descriptor that records no owner (live or archived) takes its owner from
  the memory snapshot — the same fallback the listing uses — so the recorded owner can
  still delete their own legacy conversation; with no owner anywhere it stays admin-only.
- **Schedule delete status codes.** `DELETE /schedulestore/schedules/{id}` rethrows a
  downstream 403/404 instead of flattening it into a 500, like the other mutation paths.

### Tests

Focused unit tests added/extended, each mutation-checked (revert the fix, confirm the
test fails, restore): `RestScheduleStoreTest`, `ConverseWithAgentToolOwnershipTest`,
`UserMemoryToolTest`, `RestAgentTriggerStoreTest`, `ConversationMemoryUtilitiesTest`,
`ConversationAccessGuardTest`, `SecurityAccessControlAnnotationsTest`,
`RestSemanticParserTest`, `A2ATaskHandlerTest`, `RestConversationStoreOwnershipTest`,
`PostgresHealthCheckTest`.

**Files:** [`ConversationAccessGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java),
[`ConversationMemoryUtilities.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java),
[`RestScheduleStore.java`](../../src/main/java/ai/labs/eddi/engine/schedule/rest/RestScheduleStore.java),
[`UserMemoryTool.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java),
[`RestA2AEndpoint.java`](../../src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java).

```decision-log
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
```
