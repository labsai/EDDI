## 🔒 fix(backup): review findings on export, import and upgrade (2026-10-31)

**Repo:** EDDI (`fix/review-backup`)

### What changed and why

Verified findings from the CodeRabbit security review and the October code review, all on
the backup / import / sync path. Several authorization findings only matter with
`eddi.workspaces.enabled=true`; they are fixed regardless.

**Export**

- The agent and workflow documents are scrubbed like every other archived document — a
  Dream job's model `apiKey` and legacy `channels[].config` tokens used to leave verbatim.
- The export scrubber now redacts query parameters whose *name* marks a credential
  (`key`, `sig`, `signature`, `accessKey`, … — `ConfigSecretRedactor`'s rule) in relative
  paths and absolute URLs, so a signed-URL signature an editor's GET hides no longer
  leaves in the archive. Form bodies are not affected.
- A URI found anywhere in a workflow other than its step references (which the authoring
  check vetted) needs `VIEW` before export or preview reads it; previously a URI in a free
  field of a step's `config` pulled another space's configuration into the archive.
- Snippets are exported only with `VIEW` (a `USE`-only listing no longer suffices).
- Connections go through `ConnectionReadRedactor` before the generic scrubber, so a
  pre-validation literal `passwordRef` is not exported.

**Import**

- A workflow written straight to the store runs `RestWorkflowStore`'s reference check
  (now reused via `WorkflowReferenceAccess`, walking `RestWorkflowStore.referencedResources`).
- A workflow directory without a workflow document is a 400, not a kept foreign reference.
- Repeated workflow URIs in an agent are imported once (no orphan copies).
- `updateParser` now goes through `updateTracked`, so a failed merge rolls it back too.
- Connections are created only for an administrator, as over REST; an editor's import
  skips (and counts) them.

**Upgrade / sync**

- A source resource two workflows share is matched per workflow: extension diffs carry
  their workflow's `workflowIndex`, and the executor looks diffs up per workflow (#8).
- A 409 is retried against the store's version only when the descriptor lags the store
  (a lost descriptor write) or when the newer version is one this run wrote itself (a
  target resource two workflows share is written once per workflow); any other version
  whose descriptor moved is somebody's edit and is reported, not overwritten.
- `createExtension` decodes the JSON before `requireMayAuthor`, so a JSON-escaped
  `${caller:token}` no longer passes the check; a created RAG config gets
  `KnowledgeBaseStorageGuard.prepareNew`.
- A newly created workflow runs the reference check before any of its resources is
  written.
- `ScrubbedSecrets` restores a *literal* target credential only when the object's
  destination (`…Url`, `uri`, `endpoint`, `host`, absolute `path`) is unchanged, compared
  by origin with scheme and host case-folded and a default port equal to an omitted one;
  vault references still come from the target. The identity lookup for array elements is
  indexed once per list instead of a scan per element.
- Source readers refuse more than 256 workflow references; the remote source fetches each
  document once per sync.

**Archive extraction:** a ZIP directory entry carrying data, or an entry nested more than
32 levels deep, is refused.

**Dead code:** removed `StructuralMatcher.readLatestVersionOrDefault` and
`UpgradeExecutor.readLatestVersion`; moved the stacked Javadoc in `StructuralMatcher` onto
`readLatestVersion`.

### Design decisions

- Export keeps "VIEW on the agent covers the graph beneath it" for the structural step
  references (a non-cascading share still exports); only references the authoring check
  never saw need their own `VIEW`. That includes the dictionaries a *parser document*
  names (the parser store does not vet them on write), so a recipient of a non-cascading
  share whose agent uses parser-document dictionaries they cannot see now gets a 403
  instead of an archive.
- Minimal edits outside `backup/`: `RestWorkflowStore.referencedResources` and
  `ConnectionReadRedactor.redactLegacyLiterals` made public for reuse;
  `SecretScrubber` gained the credential-query-name rule.

### Not done

- Consolidating `RestImportService`'s per-type create/update methods with
  `UpgradeExecutor.resolveExtensionOps` into one table: the import side carries
  per-type behaviour (RAG preparation, storage namespace, v5 LLM defaults) that makes it
  a larger refactor than this pass.

**Files:** [`RestExportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestExportService.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`ScrubbedSecrets.java`](../../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`WorkflowReferenceAccess.java`](../../src/main/java/ai/labs/eddi/backup/impl/WorkflowReferenceAccess.java),
[`ZipArchive.java`](../../src/main/java/ai/labs/eddi/backup/impl/ZipArchive.java),
[`SecretScrubber.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java),
[`import-export-an-agent.md`](../import-export-an-agent.md), [`agent-sync-guide.md`](../agent-sync-guide.md)
