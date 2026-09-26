## 🐛 fix(backup, datastore): import limits, merge rollback, version-checked delete and in-place writes (2026-09-26)

**Repo:** EDDI (`fix/import-persistence`)

Fix-plan item 14 from the 2026-09-25 review: H15, M-P1 to M-P7, the merge's snippet
selection and rollback, and the delete version predicate.

### What changed and why

- **H15 — ZIP import had no size or entry limits.** A 2 MB upload that inflates to
  gigabytes filled the disk and inodes under `tmp/import`, and the importer then read
  each file whole into the heap. `ZipArchive.unzip` now enforces three limits, counted
  from the bytes actually inflated rather than from the entry header:
  `eddi.backup.import.max-entries` (10000), `eddi.backup.import.max-entry-bytes`
  (64 MiB) and `eddi.backup.import.max-uncompressed-bytes` (256 MiB). A breach throws
  `ZipLimitExceededException`, which every import path (create, merge, legacy preview,
  upgrade, upgrade preview, and live sync, which unpacks through the same method)
  answers with **413** and the limit's name. The scratch directory is removed in
  `finally` as before.
- **M-P1 — `ModifiableHistorizedResourceStore.set()`.** On a historized version it went
  through the archive's insert-if-absent path and was a silent no-op that still
  answered success; on the current version it replaced the row by id alone, so an
  update committed between the read and the write was rolled back. The current write
  is now version-checked (`storeIfCurrentVersion`), a version that moved into history
  in between is written there instead, and history rows are rewritten through a new
  `IResourceStorage.replaceHistory` (MongoDB `replaceOne`, PostgreSQL `UPDATE`). Two
  callers relied on the no-op being harmless: `PATCH /descriptorstore/descriptors/{id}`
  now answers **409** for a version that is not current — the live *resource* version
  is still accepted, because descriptor and resource versions drift and that is the
  number every client holds — and `ResourceSharingService.writeBack` reports a target
  whose descriptor moved on mid-write as **skipped** instead of applied.
- **M-P2 — regexes built from caller input.** The descriptor listing pasted the `type`
  query parameter into an unanchored regex (ReDoS, 500s, or one pattern that selected
  every descriptor of every type); `findByOriginId` did the same with archive file
  names, so a crafted name matched other resources' descriptors. The type is now an
  anchored, escaped prefix and the origin id an exact match (per-character escaping,
  because PostgreSQL's `~` has no `\Q…\E`). `StringUtilities.escapeRegexChars` became
  public for this.
- **M-P3 — foreign ids.** A PostgreSQL archive's UUIDs reached `new ObjectId(uuid)` on
  MongoDB during preview and merge and surfaced as 500/400. `MongoResourceStorage` now
  answers "not found" for an id that cannot be an ObjectId in `read`, `readHistory`,
  `readHistoryLatest` and `getCurrentVersion`, as the PostgreSQL backend already did.
- **M-P4 — merge selection and rollback.**
  - Snippets ignored `selectedResources`, so a merge overwrote live snippets the
    operator had unticked. They now honour it. The preview's snippet row carries the
    snippet's **archive id** (its file name) as `sourceId`, like every other row — it
    used to carry the local id, and a new snippet had none, so it could not be
    selected at all. The import also accepts the local id, which is what earlier
    previews returned.
  - A failed merge deleted what it had created and left every **update** in place, so
    the target agent was half-promoted. Every merge update (agent, workflow, the eight
    extension types, snippets) now goes through `updateTracked`, which reads the
    previous content and descriptor first and records a compensation that writes them
    back as a new version through the same REST bean — so validation, capability
    registration, RAG schedule sync and the snippet cache run as on the way in. The
    restore is version-checked; a resource someone else changed meanwhile is not
    overwritten, and the conflict is logged.
- **M-P5 — delete racing an update.** Deleting by id alone erased a version an update
  had just committed (never archived, so gone for good), and the delete's tombstone
  lost the insert race to the update's non-deleted history row, so the resource looked
  neither live nor deleted. `IResourceStorage.storeHistoryAndRemove` now takes the
  expected version and has no default. MongoDB upserts the tombstone, deletes with a
  version predicate and, if the resource moved on, takes the flag back off and throws
  `ResourceModifiedException`; PostgreSQL does the same inside its transaction, so the
  rollback drops the tombstone with it. A resource that a concurrent delete already
  removed counts as deleted.
- **M-P6 — legacy migration.** One document that could not be persisted aborted the
  rest of its collection, and the confirmation was written anyway, so nothing ever
  retried. Each document is now migrated in its own try, failures are counted, and
  the confirmation is written only when there were none; otherwise the run is logged
  at ERROR and repeats on the next start (the sweep is idempotent).
- **M-P7 — GridFS indexes.** `attachments.files` had no index on the metadata the store
  filters on, so every `storageRef` lookup, upload quota check, listing and GDPR
  per-conversation delete was a full scan. The store now creates ascending indexes on
  `metadata.storageRef`, `metadata.conversationId` and `metadata.grants` at startup;
  none is unique (legacy blobs have no `storageRef`) and a refusal is logged, not
  fatal.

### Decisions

- Merge compensation writes the old content **forward** as a new version rather than
  deleting the imported version: history is append-only everywhere else, and the
  imported version stays inspectable.
- `set()` on a history version now writes it rather than refusing, because callers
  such as the DELETE descriptor filter and the access-index backfill legitimately
  address older rows. The places where a stale write is a user-visible mistake (PATCH,
  sharing) refuse it explicitly.
- The unpacking limits live on `ZipArchive`, not the REST layer, so every path that
  unpacks — including live sync, whose download is capped separately at 256 MB — gets
  them.

### Compatibility

- Stored configs and archives are unchanged. The merge preview's snippet `sourceId`
  changed from the local id to the archive id; the import accepts both.
- `IResourceStorage.storeHistoryAndRemove(history, id)` became
  `storeHistoryAndRemove(history, id, expectedVersion)`; internal SPI, both backends
  implement it.

### Not done / follow-ups

- `LegacyDocumentMigrations` still catches a failing transform itself and returns
  `null` ("nothing to migrate"), so a document whose *transform* throws is skipped
  rather than counted. It is shared with the import path and was left alone.
- The upgrade strategy (`UpgradeExecutor`) reports per-resource failures as a 207 and
  does not compensate its own updates; unchanged here.
- The legacy `datastore.mongo.DescriptorStore` (unused in production) still builds the
  old unescaped type regex.

**Files:** [`ZipArchive.java`](../../src/main/java/ai/labs/eddi/backup/impl/ZipArchive.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`IResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/IResourceStorage.java),
[`HistorizedResourceStore.java`](../../src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java),
[`ModifiableHistorizedResourceStore.java`](../../src/main/java/ai/labs/eddi/datastore/ModifiableHistorizedResourceStore.java),
[`MongoResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java),
[`PostgresResourceStorage.java`](../../src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java),
[`DescriptorStore.java`](../../src/main/java/ai/labs/eddi/datastore/DescriptorStore.java),
[`GridFsAttachmentStore.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/GridFsAttachmentStore.java),
[`MigrationManager.java`](../../src/main/java/ai/labs/eddi/configs/migration/MigrationManager.java),
[`RestDocumentDescriptorStore.java`](../../src/main/java/ai/labs/eddi/configs/descriptors/rest/RestDocumentDescriptorStore.java),
[`ResourceSharingService.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`import-export-an-agent.md`](../import-export-an-agent.md),
[`configuration-reference.md`](../configuration-reference.md)

```decision-log
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
```

```regression-note
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
```
