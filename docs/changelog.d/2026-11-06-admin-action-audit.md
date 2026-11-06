## 🐛 fix(audit): admin-only endpoints under a data-plane root are recorded as administrative actions (2026-11-06)

**Repo:** EDDI (`fix/admin-action-audit`)

### What changed and why

`AdminActionAuditFilter` decided what is administrative by the first path segment alone, so every
mutating request under `/agents`, `/userconversationstore` and the other data-plane roots went
unrecorded — including the endpoints there that only an admin may call. Resetting a stuck
conversation's state (`PATCH /agents/{conversationId}/state`, `@RolesAllowed("eddi-admin")`) and
creating or deleting a user's managed-conversation mapping
(`POST`/`DELETE /userconversationstore/userconversations/{intent}/{userId}`, admin-only at class level)
left no record of who did it. The test that was meant to pin the exclusion listed three paths no
endpoint serves (`/userconversationstore/x/say`, `/a2a/agent1`, `/connections/abc/callback`).

The path rule stays, and one rule decided by the matched endpoint joins it: a mutating request on a
data-plane path is still administrative when the endpoint's effective `@RolesAllowed` does not admit
`eddi-user`. The roles are already declared on every endpoint, so a new admin-only endpoint under those
roots needs nothing remembered. The lookup follows Quarkus: a method's own `@RolesAllowed`,
`@PermitAll` or `@Authenticated` before its class's, the implementation before the interface that
declares the method (`ResourceInfo` returns the implementation method, and Java does not inherit
interface annotations — which is where EDDI declares its roles). The answer is cached per resource
method, so the reflection runs once per endpoint, not on every chat turn.

The data-plane test now uses real paths and a resource method that admits `eddi-user`, so it still
exercises the path rule.

**Files:** [`AdminActionAuditFilter.java`](../../src/main/java/ai/labs/eddi/engine/audit/rest/AdminActionAuditFilter.java),
[`AdminActionAuditFilterTest.java`](../../src/test/java/ai/labs/eddi/engine/audit/rest/AdminActionAuditFilterTest.java),
[`audit-ledger.md`](../audit-ledger.md#administrative-actions)
