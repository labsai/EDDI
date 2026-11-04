## 🔒 fix(backup): agent-findings review — repeated references, parser references, export vetting (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-backup`)

### What changed and why

Verified the 43 CodeRabbit "agent findings" on backup / import / export / sync against the
current code. 18 had been scanned before the review fixes of 2026-10-31 and most of those were
already closed there; the rest were checked on today's code.

**One document, one copy, one request**

- The ZIP and remote source readers read and parse a workflow once per id and version, however
  often the source agent names it; every further position shares that record
  (`IResourceSource.atPosition`). `UpgradeExecutor` now repoints a copy of an adopted source
  config (`copyOf`) instead of the shared one.
- The ZIP reader reads an extension file once per archive path; both readers scan a parser
  document for its dictionaries once, not once per step naming it.
- The remote reader remembers failed reads, so a repeated failing reference is not requested
  again, and stops after `eddi.backup.sync.max-requests` requests (default `10000`) with `502`.
- Export reads each referenced resource once (`IResourceId` has no value equality, so the map kept
  one copy per reference); the legacy merge preview scans each archived workflow once.
- Import: two spellings of one workflow URI are one workflow; versions of one id, which read the
  same archive file, are decoded and written once and every reference names the one resource;
  a workflow directory holding more than one workflow document is a `400`.
- `StructuralMatcher` indexes source extensions by id instead of scanning them per diff row.
- Export finds `${connection:…}` references with the linear `ConnectionReference.findAll`
  instead of the backtracking regex.

**Authorization**

- Export no longer counts an agent or group reference (checked for `USE` only when the workflow
  was written) as vetting the id it carries, so a configuration named elsewhere under that id
  needs its own `VIEW`.
- A parser document created by import or upgrade runs `RestParserStore`'s reference check: every
  dictionary it names needs `VIEW` (`WorkflowReferenceAccess.requireAccessToParserReferences`).
- A structural preview's REMOVE row names a target workflow only for a caller with `VIEW` on it.
- A failed merge's rollback puts back the name, description, origin and sync baseline it rewrote
  onto the *current* descriptor, so a sharing change the owner made meanwhile is not undone.

**Secrets**

- `ScrubbedSecrets` compares the destination fields of both sides: a source that drops the
  target's `baseUrl` (provider default) or carries a non-string `host` counts as a changed
  destination, and the target's literal credential is not restored into it.
- `SecretScrubber` judges a header or query map serialized into a string (an LLM task's
  `customHeaders` / `customQueryParams`) entry by entry under the map's name, as the editor read
  does, so an opaque credential header no longer leaves in an export.

**Verdicts** (43 findings):

| Verdict | Count | Notes |
| --- | --- | --- |
| FIXED | 24 | this branch |
| ALREADY_FIXED | 16 | the 2026-10-31 backup review fixes (#1189) |
| ACCEPTED_RISK | 2 | DNS rebinding between check and connect (documented in `security.md`); `VIEW` on an agent covering its step references (documented in `import-export-an-agent.md`) |
| NEEDS_DECISION | 1 | per-name connection lookups on export |

### Design decisions

- Repeated references are collapsed, not refused: an agent naming a workflow twice and a stale
  parser document naming an older dictionary version are legitimate and keep importing.
- The request budget is a count, configurable, far above any real agent; an elapsed-time budget
  for the whole sync was not added (each request already has its own 30 s timeout).
- Not done: export resolves each distinct `${connection:…}` name through
  `IConnectionStore.idOfName` + `readByName`, each of which reads every connection; a batched
  lookup needs a store API change (left as a decision).

**Files:** [`IResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/IResourceSource.java),
[`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`ZipResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/ZipResourceSource.java),
[`RestExportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestExportService.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`ScrubbedSecrets.java`](../../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`WorkflowReferenceAccess.java`](../../src/main/java/ai/labs/eddi/backup/impl/WorkflowReferenceAccess.java),
[`SecretScrubber.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java),
[`import-export-an-agent.md`](../import-export-an-agent.md), [`agent-sync-guide.md`](../agent-sync-guide.md),
[`configuration-reference.md`](../configuration-reference.md)
