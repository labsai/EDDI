## feat(ingestion): Google Drive Shared Drive source, Phase G1 (2026-11-18)

**Repo:** EDDI (`feat/google-drive-ingestion`)

### What changed and why

A knowledge base can now keep itself in sync with a Google **Shared Drive**, or folders of one —
Phase G1 of [`rag-ingestion-source-types-plan.md`](../../planning/rag-ingestion-source-types-plan.md)
§6. The `OAUTH2_JWT_BEARER` auth type it needs already existed; this is the source.

- **Config.** `IngestionSource.TYPE_GOOGLE_DRIVE` (`"googleDrive"`) with a `googleDrive` block:
  `connection`, `sharedDriveId`, `folderIds`, `includeSubfolders`, `mimeTypes` (format names:
  `google-docs`, `google-sheets`, `google-slides`, `pdf`, `docx`, `xlsx`, `pptx`, `text`, `markdown`,
  `csv`, `html`), `excludeNamePatterns`, `maxFiles`, `maxFileBytes`. `captureAcl: true` is **refused**
  at save, since `DOCUMENT_ACL` does not exist. Ids are held to `[A-Za-z0-9_-]` because they are
  spliced into Drive's query; the connection name to the connections framework's rule.
- **Credentials (§3.4 SERVICE plumbing).** `SourceContext` gains `credentials()`, a `SourceCredentials`
  the pipeline field-injects (`ServiceConnectionCredentials` over `ConnectionResolver`; a pipeline
  built by hand gets `SourceCredentials.NONE`, which refuses). It reads the binding first and refuses
  `PER_USER` and `CALLER_SUPPLIED` by name before anything is resolved, then resolves with no
  principal, per request, for that request's URL — so `baseUrlAllowlist` holds on every call.
- **Connector** (`modules/ingestion/drive`). `GoogleDriveSourceConnector` behind a `DriveTransport`
  seam (`OutboundDriveTransport` over `OutboundHttp`, `DEFAULT` profile); `DriveApi` makes the calls,
  retries throttles with capped backoff charged to the run's deadline, and classifies errors by
  Drive's `reason` — `403 rateLimitExceeded` is a throttle, `403 insufficientPermissions` is access.
  Listing is breadth-first, Shared-Drive-scoped (`corpora=drive`), follows folder shortcuts with a
  cycle guard and skips file shortcuts. A root that lists nothing has its access re-checked
  (`drives.get` / `files.get`) before the listing counts as coverage. Docs export as Markdown (HTML
  fallback, inlined base64 images stripped), Sheets as XLSX (CSV carries only the first sheet),
  Slides as text; stored files are typed from their bytes. Citation: `webViewLink`.
- **Framework.** `DiscoveryContext.reportSkipped` counts a deliberately skipped document as skipped,
  not failed. `DiscoveryResult.summary` is returned as a preview's `message`, and logged for a run.
  A source whose `type` changes (not only upload ↔ web) now counts as removed, so what it ingested is
  discarded (`IngestionSource.sameTypeAs`). The documents listing gives a non-web document no URL.
- **Manager.** A third source type, **Google Drive**, with its fields, the audience warning, and the
  preview summary; strings in all 11 locales; MSW mocks for a Drive-fed knowledge base and a
  `google-drive-sa` connection. The Platform Operator learns the type and its failure checklist
  (revision 82).
- **Docs.** [`docs/rag.md`](../rag.md#google-shared-drive-type-googledrive): the source, the
  service-account setup, how a run reads the drive, failure modes.

### Design decisions

- **No change feed yet.** The pipeline does not persist cursors (`Discovery.cursor()` is always empty
  and `nextCursor` is dropped), so the connector is listing-only (style C) and reports
  `FULL_LISTING`. Version tokens make an unchanged drive cost only its listing. `changes.list` is the
  next step once cursors are stored.
- **Native version as a validator, not the change key.** Drive's `version` moves on renames and
  sharing changes, so it is stored as the document's validator (`etag`) and an equal one reports
  `UNCHANGED` without exporting; a different one exports, and the export's hash decides.
- **Preview downloads nothing.** A Drive preview is a listing pass: counts, bytes and MIME types
  in its `message`. A cheap dry run, rather than a new endpoint.
- **Export overflow is a skip.** An export over Google's limit or a download over `maxFileBytes` is
  returned as empty text, which the pipeline counts as skipped.
- `SourceFilters` / `SourcePartitions` (the shared config models) are not adopted: the source follows
  the plan's §6 config shape. Unifying them is a later decision.

### Not done

- No save-time check of the connection's binding or allowlist (the run refuses, naming the rule).
- `changes.list` cursors, `captureAcl` / `DOCUMENT_ACL`, per-user Drive (Phase A), slide numbers as
  citation metadata, the `sourceType` meter tag.
- Not run against a real Google tenant.
