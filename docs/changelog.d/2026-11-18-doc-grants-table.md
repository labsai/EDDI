## feat(attachments): grants in their own table, pruned with the conversation that held them (2026-11-18)

**Repo:** EDDI (`feat/doc-grants-table`). This is work package WP-2a.1 of the PDF documents plan. It is the first package in the serial chain of attachment-store edits.

### What changed and why

Attachment read grants were stored on the blob itself: `metadata.grants` on GridFS and `grants TEXT[]` on PostgreSQL. Nothing removed the grants that a deleted conversation *held* on other blobs, so a widely shared file collected grant entries without bound.

- **New storage.** Grants now go in `attachment_grants`.
  - MongoDB: a collection `{storageRef, conversationId, createdAt}` with a unique `(storageRef, conversationId)` index and an index on `conversationId`. Rows are keyed by the blob's canonical reference: `metadata.storageRef`, or the ObjectId hex for a blob stored before `storageRef` existed.
  - PostgreSQL: a table with primary key `(storage_ref, conversation_id)` and an index on `conversation_id`, created in `ensureSchema()`. A GIN index on the legacy `grants` column is built `CONCURRENTLY` on the backfill's thread, not on the request path, so it never blocks uploads.
- **Dual write, union read (decision P7).** `grantAccess` writes both shapes. On PostgreSQL that happens in one transaction, and the legacy `UPDATE` locks the blob row, so a concurrent delete cannot strand a grant row. On MongoDB it reads the blob from the primary. `load`, `getMetadata` and `listAccessible` accept a grant in either shape. On PostgreSQL that is still one statement (`EXISTS … AS granted`). On MongoDB, `listAccessible` makes two round trips that end in a single files query, so a blob granted in both shapes is listed once. A node of the previous release in a rolling upgrade therefore authorizes grants made here, and the reverse.
- **Pruning.** `delete(ref)` deletes the grants on the blob. `deleteByConversation(id)` deletes the conversation's blobs, the grants on them, and the grants `id` held on other blobs, in both shapes. It still returns the number of blobs.
  - On PostgreSQL, the blobs and grant rows go in one transaction; removing `id` from other blobs' legacy lists runs as a separate statement after the commit. Doing both in one transaction let two concurrent deletes of cross-granted conversations lock rows in opposite order and deadlock; a contract test now runs such deletes concurrently.
  - GDPR erasure, conversation purge and group-conversation deletion all call it unconditionally, so no new erasure participant is needed (plan §3.3).
- **Backfill, on every start.** `AttachmentGrantBackfill` runs on a virtual thread and never delays startup. Each pass:
  - waits for the grant indexes, so that nodes upserting at the same time cannot create duplicates before the unique index exists;
  - removes grant rows whose blob is gone, which an old node's delete leaves behind;
  - copies legacy grants in pages of 500, at most 2,000 pages per start, with the cursor kept in the store on both databases (`attachment_grants_backfill`).
  
  A copy is conditional on the blob still existing, and a grantee that disappears between the check and the write is taken back. It is a catch-up on every start, not a one-off, because an old node can write a legacy-only grant behind the cursor or after completion. Each page first reads the grant rows already present on its blobs in one query, and a grant already copied needs no further lookup; so after the first pass, a start costs one read per page rather than one existence check per grantee. Grantees that exist neither as a conversation nor as a group conversation are skipped. A lookup that fails skips only that grant, is counted, and keeps the pass from being marked complete.
- **Completion marker.** `attachment-grants-backfill-complete` records that a whole pass completed with no failed lookup. It switches nothing off and proves nothing for later. It is written only if absent, because every start runs a pass and the MongoDB migration log has no unique key.
- **Interfaces.** `IAttachmentStore` is unchanged apart from Javadoc. The backfill's hooks are on a separate `IAttachmentGrantMigration`, produced by `DataStoreProducers`. All four PostgreSQL transactions share one `inTransaction` helper.
- **First store ITs.** `AttachmentStoreContract` (following the `AlertStoreContract` pattern) runs on real MongoDB and PostgreSQL through `GridFsAttachmentStoreIT` and `PostgresAttachmentStoreIT`, with each database's real migration log and group-conversation store. It covers:
  - round trip; owner, grantee and stranger access
  - both-shape writes and idempotence; both rolling-upgrade directions
  - listing without duplicates; deletion and pruning in both shapes; concurrent cross-granted deletes
  - quota scopes
  - backfill skip, resumption across starts, catch-up after completion, failed lookups, a copy for a deleted blob, a grantee that disappears, and the dangling-row prune

### Upgrade note

The new `attachment_grants` collection or table (and `attachment_grants_backfill`) is created on its own and must be included in backups. Both grant shapes are written and read for this release.

**The release that drops the legacy read must first run the copy once more**, under a new marker key, after the last node of the previous release is gone. Until then, a node of the previous release can write a grant in the legacy shape only.

During the upgrade, a blob deleted by an old node leaves its `attachment_grants` rows behind. They authorize nothing and are removed on the next start of an upgraded node.

### Next

WP-2a.2 (`Derivation`, owner scope and key, lineage cascade, lifecycle events) is next in the attachment-store chain.

```regression-note
| 2026-11-18 | Legacy attachment grants (`metadata.grants` / `grants TEXT[]`) must keep authorizing for one release | Grants moved to `attachment_grants`; nodes of the previous release read only the legacy shape | Dual write, union read, catch-up copy on every start; `AttachmentStoreContract` tests both rolling-upgrade directions | feat/doc-grants-table |
```
