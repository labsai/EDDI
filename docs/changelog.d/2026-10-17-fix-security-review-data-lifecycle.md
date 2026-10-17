## 🔒 fix(security): group-discussion attachments get an owner, quotas and a delete path (2026-10-17)

**Repo:** EDDI (`fix/security-review-data-lifecycle`) — security review 2026-10-17, finding M4 and its REST edge.

### What changed and why

A group discussion's inline attachments were stored with the five-argument `IAttachmentStore.store(...)`, which records no user. So no per-user quota counted them (one `eddi-user` could store 100 MB per discussion, without bound), deleting the discussion left the blobs behind with nothing pointing at them, and nothing keyed on the user could find them.

- **Owner.** `GroupAttachmentBinder` stores with the discussion's `userId`, so `eddi.attachments.max-per-user` and `max-total-bytes-per-user` count group uploads. A quota refusal no longer silently drops the file: the blobs the request had already stored are removed, the just-created discussion is failed, and the caller gets `400` (`GroupAttachmentRejectedException`; a `group_error` event on `/stream`).
- **REST edge.** `RestGroupConversation` re-checks the inline attachments against `eddi.attachments.max-size-bytes` per file and `max-per-conversation` / `max-total-bytes-per-conversation` for the discussion before anything is decoded or stored (`GroupAttachmentLimits`, computed from the base64 lengths), as it already re-checks its other request ceilings. The body itself stays bounded by `RequestBodyLimitGuard` before it is read. Human input (`submitHumanInput`) carries no attachments, so there was nothing to bound there.
- **Delete.** `GroupLifecycleOps.deleteGroupConversation` deletes the discussion's blobs before the document. A failure keeps the document (500, retryable) rather than orphaning the blobs.
- **`storageRef` guard.** `materializeAttachments` used to pass a `storageRef`-only attachment straight to `grantAccess` for every member. It now accepts one only from a trusted source conversation that can read the blob — the parent discussion, for a nested `GROUP` member, which `MemberTurnExecutor` passes through a new `discuss(...)` overload — and grants the child discussion access so the next level can repeat the check. A reference with no trusted source (anything a client could send) is refused. Unreachable from REST today; the guard keeps it that way.

### Files

[`GroupAttachmentBinder.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentBinder.java), [`GroupAttachmentLimits.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupAttachmentLimits.java), [`GroupLifecycleOps.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java), [`GroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java), [`MemberTurnExecutor.java`](../../src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java), [`RestGroupConversation.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java), [`IGroupConversationService.java`](../../src/main/java/ai/labs/eddi/engine/api/IGroupConversationService.java); docs [`attachments-guide.md`](../attachments-guide.md), [`group-conversations.md`](../group-conversations.md).

### Not done here

- Blobs stored by earlier releases still have no owner recorded, so they do not count towards a per-user quota. Deleting their discussion, or erasing its owner, now removes them.
- `close` still keeps the blobs (it keeps the transcript readable); only `delete` and erasure remove them.
- `McpGroupTools`' `delete_group_conversation` description and `docs/mcp-server.md` still name only artifacts and ephemeral agents (left to the workstream that owns the MCP group tools).

## 🔒 fix(gdpr): erasure and export reach group discussions; keyed database-log pseudonym (2026-10-17)

**Repo:** EDDI (`fix/security-review-data-lifecycle`) — security review 2026-10-17, findings M4 (erasure half) and L9.

### What changed and why

- **Erasure reaches group blobs (M4).** `GdprComplianceService.deleteUserData` resolved attachment owners from `getConversationIdsByUserId`, which returns 1:1 conversations only, so a discussion's shared files — stored under the group conversation's id — survived an Art. 17 erasure that reported success. The cascade now lists the user's group conversations (`GroupConversationStore.listAllForUser`, paged, exact-match re-check) before step 5c deletes them, and deletes the blobs of each discussion and of every member conversation it created. A failed lookup is reported as the step `groupConversationAttachments`.
- **Export completeness (L9b).** The Art. 15/20 bundle now carries `groupConversations` (question, transcript, state, dates) and `sharedArtifacts` (content and revision history, via the new `ISharedArtifactStore.listAllForUser`), each capped at 1,000 with `groupConversationsTruncated` / `sharedArtifactsTruncated`, plus `failedCategories` for a store that failed. All three count against `complete()`. The attachment inventory covers the discussions' blobs and their member conversations'. `omittedCategories` shrinks to `schedules` and `journalEntries`, so the endpoint still answers 207 until those land. MCP `export_user_data` reports the same fields.
- **Keyed pseudonym (L9a).** The database-log pseudonym (and the pseudonym in GDPR log lines and compliance records) was `AuditHmac.pseudonymFor`, an unsalted SHA-256 any list of candidate e-mail addresses reverses. New `GdprPseudonymizer`: the audit ledger's keyed pseudonym (`gdpr-erased:k1:…`) when the ledger signs; otherwise `gdpr-erased:s1:…`, an HMAC under a random per-deployment salt kept with `ISecretPersistence.setMetaValueIfAbsent("gdpr-pseudonym-salt", …)` — the metadata store and insert-if-absent pattern `VaultSaltManager` uses for the KEK salt, present whether or not the vault is on; the unsalted form only if that store fails (WARN).

### Design decisions

- **No migration of old pseudonyms.** Rows erased earlier keep `gdpr-erased:<sha256>`: the raw id is gone from them, so they cannot be re-keyed, and a re-run erasure matches on the raw id only.
- **Audit-ledger step 7b keeps the unsalted token.** Its blanket update reaches only v1–v4 and unsigned rows (the store already gives v5 rows the keyed pseudonym of their signing key), and a v3/v4 row signs exactly the unsalted token; anything else would break its signature. In the default `redact` mode step 7a has already moved every reachable row to the keyed pseudonym.

### Files

[`GdprComplianceService.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java), [`GdprPseudonymizer.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/GdprPseudonymizer.java), [`UserDataExport.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/UserDataExport.java), [`AuditHmac.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditHmac.java), [`GroupConversationStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/GroupConversationStore.java), [`ISharedArtifactStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/ISharedArtifactStore.java), [`SharedArtifactStore.java`](../../src/main/java/ai/labs/eddi/configs/groups/mongo/SharedArtifactStore.java), [`McpGdprTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpGdprTools.java), [`IRestGdprAdmin.java`](../../src/main/java/ai/labs/eddi/engine/gdpr/IRestGdprAdmin.java); docs [`gdpr-compliance.md`](../gdpr-compliance.md), [`troubleshooting.md`](../troubleshooting.md).

### Not done here

- `MongoUserMemoryStore` / `PostgresUserMemoryStore` still log the unsalted pseudonym in their erasure log lines (server log only, not stored data).
- The Manager's GDPR export view does not render the new sections yet.

```decision-log
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
```
