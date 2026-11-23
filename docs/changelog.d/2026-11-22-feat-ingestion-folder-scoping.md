## 📁 feat(ingestion): folder scoping for the Google Drive source (2026-11-22)

**Repo:** EDDI (`feat/ingestion-folder-scoping`, branched from `feat/google-drive-ingestion`, PR #1495)

Phase S-lite of the large-scale ingestion plan (§6): folders by path, excluded subtrees, a folder
picker, and scope changes that take effect at the next complete listing. It runs on today's full
listing; the work queue, the change feed and the folder index are later phases.

### What changed

- **Config** (`configs/rag/model/IngestionSource.java`): `googleDrive.folders` and
  `googleDrive.excludeFolders`, lists of `DriveFolder` — `{path}` from the drive's root or `{id}`,
  exactly one, plus an optional `label`. `folderIds` still works as `{id}` entries, for old configs
  and ZIPs; `folders` and `folderIds` together name at most 50 folders, `excludeFolders` at most 50.
  Paths are validated when the knowledge base is saved: no empty folder name, no control character,
  at most 32 levels and 1024 characters.
- **Resolution** (`modules/ingestion/drive/DriveScope.java`, new): each path is resolved one level at
  a time against the subfolders Drive lists (`DriveApi.listFolders`, folders only). Names are compared
  in Unicode NFC, exactly first and then ignoring case. Two matching folders at one level are an error
  naming both ids, and a missing path fails the run. The resolved id is recorded in the source's
  ingestion state (`resolved_scope`) and read by later runs, so a renamed or moved folder stays in
  scope. The run's log line, and a preview's message, say `'Sales/Offers' is now 'Sales/Quotes'`; the
  run history has no field for it. A recorded folder that is gone
  is resolved again.
- **Exclusion wins over inclusion at any depth.** An excluded folder is never listed. A configured
  folder inside an excluded one is dropped with a warning. A shortcut whose target lies inside one is
  not followed, judged by the target's real ancestors (`files.get … parents`, memoised per run). A
  scope where every folder is excluded is refused, not listed as empty.
- **Scope changes** (`IngestionPipeline.reconcileDeletions`), sparing every document this run listed: a discovery now returns a scope
  signature (`DiscoveryResult.scopeSignature`) over the root ids, the excluded ids and
  `includeSubfolders`. When it differs from the one the last completed reconciliation applied
  (`scope_signature`), the documents the complete listing did not see are removed at once instead of
  after `tombstoneAfterMissedRuns` runs. A partial listing removes nothing. The signature is recorded
  only once every such document has really been removed.
- **State** (`IIngestionStateStore`): `resolvedScope`/`recordResolvedScope` and
  `scopeSignature`/`recordScopeSignature`, on the per-source row beside `store_fingerprint`. The
  columns are `resolved_scope` and `scope_signature` on PostgreSQL (`ALTER TABLE … ADD COLUMN IF NOT
  EXISTS`) and fields of `rag_ingestion_sources` on MongoDB. `purgeSource` clears both. The connector
  reaches them through `DiscoveryContext.resolvedScope`/`recordResolvedScope`; a preview records
  nothing.
- **Picker** (`GET /ragstore/rags/{id}/sources/{sourceId}/drive/folders?version=&parentId=&pageToken=`,
  `GoogleDriveSourceConnector.listFolders`): one level of folders, at most 200 a page, through the saved
  source's connection. It needs EDIT, through the same guard as `runSource`. `parentId` is `root` or a
  Drive id checked against `DRIVE_ID`, and a folder of another drive is refused. The page token is
  capped at 2048 printable characters. Drive's refusals map to 400, 404, 422 (not 403), 429 with
  `Retry-After`, and 503.
- **Parent folder recorded:** each listed file carries `parentId`, the folder that holds it
  (`SourceMetadataKeys.PARENT_ID`), in its work item and document metadata. The pipeline does not copy
  it onto stored chunks yet.
- **Manager** (`ui/manager/src/components/editors/drive-folder-scope.tsx`, new): the folders to read
  and to leave out as chips, a path field, and a **Browse the drive** dialog. The dialog is a lazy tree
  loaded one level and 200 folders at a time, with Include and Exclude per folder. A picked folder is
  stored by id with its path as the label. Browsing needs a saved source with no unsaved edits. It
  replaces the comma-separated folder-id field; the strings are in all 11 locales, and the two old
  keys are gone.
- **Operator**: the knowledge-base section of the prompt explains `folders`, `excludeFolders`,
  precedence, path resolution and when removal happens. The picker is deliberately not on the
  allow-list, since it would send the operator's browsing to Google with the source's service account.
  Revision 83 → 84.
- **Docs**: `docs/rag.md` — the fields, a "Scoping by folder" section, the picker endpoint, and the
  Manager.

### Design decisions

- **The configuration shape follows §6.1** (`folders` and `excludeFolders` of `{path|id}`), not
  separate `folderPaths`/`excludeFolderIds`/`excludeFolderPaths` lists. Phase L and the Manager build
  on the same shape.
- **Path segments are matched locally, not with a `name = '…'` query.** The level's folders are listed
  (folders only, at most 10,000 per level) and compared in NFC. A server-side name query cannot match a
  decomposed name or fall back to ignoring case, and with no name in the query there is nothing to
  escape.
- **Fail rather than shrink.** A missing or ambiguous folder to read fails the run, since a smaller
  scope would reconcile documents away. An exclusion that never resolved fails too, since a typo would
  read what was meant to stay out. One that resolved before and has since gone is only reported.
- **Full-listing equivalent of §6.3.** The plan sets `missed_runs` to the threshold for documents
  outside the new roots. On a full listing, every document a complete listing of the new scope did not
  see is outside it or deleted, so the scope-changed run reconciles with a threshold of one. Two
  exceptions keep their grace: any document this run listed (or marked unreachable), even when its
  download or embedding failed, and the orphan-sweep marker. A source
  whose state predates the signature records one on its first run without fast-tracking.

### Review fixes

- **Retrieval applies the current scope** (§6.3, "effective at once"; `FolderScopeRetrievalFilter`,
  `drive/DriveRetrievalScope`). Every Drive chunk now records `folderIds`, the chain of folder ids above
  its file, as one scalar string. Agent retrieval (`RagContextProvider`) and Test Retrieval /
  `/evaluate` (`KnowledgeBaseInspector.search`) over-fetch, then drop chunks under an excluded folder or
  outside every folder to read, judged against the source's current configuration and resolved paths.
  It is a post-filter, so it behaves the same on every store. The chain is part of the citation
  fingerprint (`ContentHashes.citation(title, url, folderIds)`, unchanged when there is no chain), so a
  moved file and pre-chain chunks are stamped in place on stores that can rewrite metadata. Stores that
  cannot keep pre-chain chunks unstamped rather than re-embed. For Google-native files the chain is in
  the version token, so each is exported once after the upgrade.
- **A gone path exclusion stays recorded.** Before, the next run took the path for a typo and failed
  for good.
- **Id exclusions must be seen to exist.** An unknown id fails the run. One seen before that Drive now
  answers 404 for stops the run as transient: a bare 404 is no proof, and reading on would embed the
  subtree. A folder in the trash or another drive stays excluded.
- Path names are trimmed (`Sales / Offers`). A shortcut to a folder that no longer exists is skipped
  instead of leaving every listing incomplete. Excluding a folder in the picker replaces its include.

### Not done

- **Retrieval filtering has two gaps** (see "Review fixes" below): a typed path that no run has resolved
  yet, and chunks on stores without metadata updates that were written before chains were recorded.
  Both are kept until the next complete listing.
- **No Drive unit limiter.** The picker and the resolution requests go through `DriveApi`'s
  retry-and-backoff but are not counted against a quota; the limiter is phase Q.
- `MAX_FOLDERS` stays 50; 500 needs the folder index (phase L).
