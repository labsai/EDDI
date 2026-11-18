## feat(attachments): grants in their own table, pruned with the conversation that held them (2026-11-18)

**Repo:** EDDI (`feat/doc-grants-table`). This is work package WP-2a.1 of the PDF documents plan. It is the first package in the serial chain of attachment-store edits.

### What changed and why

Attachment read grants were stored on the blob itself: `metadata.grants` on GridFS and `grants TEXT[]` on PostgreSQL. Nothing removed the grants that a deleted conversation *held* on other blobs, so a widely shared file collected grant entries without bound.

- **New storage.** Grants now go in `attachment_grants`: a MongoDB collection `{storageRef, conversationId, createdAt}` with a unique `(storageRef, conversationId)` index and an index on `conversationId`, created in `ensureIndexes()` within the existing time budget. On PostgreSQL it is a table with primary key `(storage_ref, conversation_id)` and an index on `conversation_id`, plus a GIN index on the legacy `grants` column, all in `ensureSchema()`. On MongoDB, rows are keyed by the blob's canonical reference: `metadata.storageRef`, or the ObjectId hex for a blob stored before `storageRef` existed.
- **Dual write, union read, for one release (decision P7).** `grantAccess` writes both shapes. On PostgreSQL that happens in one transaction, and the legacy `UPDATE` locks the blob row, so a concurrent delete cannot strand a grant row. `load`, `getMetadata` and `listAccessible` accept a grant in either shape. On PostgreSQL that is still one statement (`EXISTS … AS granted`). On MongoDB, `listAccessible` makes two round trips that end in a single files query, so a blob granted in both shapes is listed once and a grant row whose blob is gone lists nothing. A node of the previous release in a rolling upgrade therefore authorizes grants made here, and the reverse.
- **Pruning.** `delete(ref)` deletes the grants on the blob. `deleteByConversation(id)` deletes the conversation's blobs, the grants on them, and the grants `id` held on other blobs, in both shapes (`$pull` / `array_remove`). On PostgreSQL this is one transaction. The return value is still the number of blobs. GDPR erasure, conversation purge and group-conversation deletion all go through it unconditionally, so no new erasure participant is needed (plan §3.3).
- **Backfill.** `AttachmentGrantBackfill` starts on a virtual thread at startup and never delays it. It reads blobs with a non-empty legacy list in pages of 500, at most 2,000 pages per start. Inserts are idempotent, so several nodes can run it at once. The cursor is kept in the migration log where the store supports that (MongoDB). It skips a grantee that exists neither as a conversation nor as a group conversation, and counts the skips in its log line. If an existence check fails rather than answering "absent", the pass ends without the marker and the next start retries. Completion is recorded as `attachment-grants-backfill-complete`. The marker switches nothing off: the union read stays for this release, because an old node may still write only the legacy shape.
- **Interfaces.** `IAttachmentStore` is unchanged apart from Javadoc. The backfill's hooks (`readLegacyGrants`, `copyGrant`) are on a separate `IAttachmentGrantMigration`, produced by `DataStoreProducers`.
- **First store ITs.** `AttachmentStoreContract` (following the `AlertStoreContract` pattern) runs on real MongoDB and PostgreSQL through `GridFsAttachmentStoreIT` and `PostgresAttachmentStoreIT`. It covers round trip; owner, grantee and stranger access; both-shape writes and idempotence; both rolling-upgrade directions; listing without duplicates; deletion and pruning in both shapes; quota scopes; and backfill skip, resumability and idempotence.

### Upgrade note

The new `attachment_grants` collection or table is created on its own and must be included in backups. Both grant shapes are written and read for this release. The next release drops the legacy write and read.

### Next

WP-2a.2 (`Derivation`, owner scope and key, lineage cascade, lifecycle events) is next in the attachment-store chain.

```regression-note
| 2026-11-18 | Legacy attachment grants (`metadata.grants` / `grants TEXT[]`) must keep authorizing for one release | Grants moved to `attachment_grants`; nodes of the previous release read only the legacy shape | Dual write, union read; `AttachmentStoreContract` tests both rolling-upgrade directions | feat/doc-grants-table |
```
