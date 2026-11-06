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

## 🐛 fix(audit): an erasure or export through MCP records who asked for it (2026-11-06)

**Repo:** EDDI (`fix/admin-action-audit`)

### What changed and why

The MCP tools `delete_user_data` and `export_user_data` call `GdprComplianceService` directly, so
`AdminActionAuditFilter` never saw them, and the service's own `GDPR_ERASURE` / `GDPR_EXPORT` event names
the subject's pseudonym but no actor. The ledger therefore showed that user X was erased, but not by
whom — while the same erasure through `DELETE /admin/gdpr/X` was recorded with the caller. Who erased a
user is the first reason the administrative-action log exists.

The record-building half of the filter is now `AdminActionRecorder` (`@ApplicationScoped`, constructor
injection, never throws, switched by the same `eddi.audit.admin-actions.enabled`). The filter delegates
to it, and the two MCP tools record every call through it in a `finally`: method `MCP`, path
`/mcp/<tool>/<pseudonym of the subject>`, the MCP caller as actor, and as status what the REST surface
answers for the same outcome — `200`, `207` (incomplete erasure or export), `400`, `403` (role refusal;
the `ForbiddenException` still propagates) or `500`. The subject is pseudonymised whole rather than
matched against path segments, so an id containing `/` cannot leave part of itself behind. The
recorder's failure WARN names the method and endpoint, no longer the request path, which could hold the
raw id the record pseudonymises.

The entry tests moved from `AdminActionAuditFilterTest` to the new `AdminActionRecorderTest`; the filter
test now runs a real recorder over a mocked ledger and checks what reaches it.

**Files:** [`AdminActionRecorder.java`](../../src/main/java/ai/labs/eddi/engine/audit/rest/AdminActionRecorder.java),
[`AdminActionAuditFilter.java`](../../src/main/java/ai/labs/eddi/engine/audit/rest/AdminActionAuditFilter.java),
[`McpGdprTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpGdprTools.java),
[`AdminActionRecorderTest.java`](../../src/test/java/ai/labs/eddi/engine/audit/rest/AdminActionRecorderTest.java),
[`McpGdprToolsTest.java`](../../src/test/java/ai/labs/eddi/engine/mcp/McpGdprToolsTest.java),
[`audit-ledger.md`](../audit-ledger.md#administrative-actions)

**Next:** the Manager's administrative-actions method filter offers `POST`/`PUT`/`PATCH`/`DELETE` only, so
an MCP row is listed under "All methods" but not under a method; and a REST `GET /admin/gdpr/{userId}/export`
is a read, which the filter does not record, so a REST export still has no actor on record.
