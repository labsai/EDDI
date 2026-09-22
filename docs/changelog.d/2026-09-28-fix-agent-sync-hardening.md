## 🧩 feat(backup): parser documents, and the dictionaries they name, travel with the agent (2026-09-28)

**Repo:** EDDI (`fix/agent-sync-hardening`)

### What changed and why

`ai.labs.parser` was not in the backup registry, so neither an export nor a live
sync carried a parser document. Every agent the setup wizard creates has a parser
step whose `config.uri` names one, so every promoted agent arrived naming a parser
that existed only on the source — and a dictionary that only that document named
did not travel at all. Parser documents are now exported, imported, previewed and
synced like every other extension ([`WorkflowExtensions`](../../src/main/java/ai/labs/eddi/backup/impl/WorkflowExtensions.java),
[`AbstractBackupService`](../../src/main/java/ai/labs/eddi/backup/impl/AbstractBackupService.java)),
and the dictionaries a parser document names are found in the document, carried,
and the document repointed at the target's copies.

What that took, beyond registering the type:

- **References inside a document.** Workflow-level matching repoints a *step*; it
  never looked inside a document. A parser document holds its dictionaries' ids
  itself, and those differ between instances by construction, so compared as
  written it read as changed on every sync and was then written with the source's
  ids. [`NestedReferences`](../../src/main/java/ai/labs/eddi/backup/impl/NestedReferences.java)
  pairs them — by position against the target's copy, which is how the workflow
  already pairs steps, and by source id for the dictionaries the workflow itself
  names. [`UpgradeExecutor`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java)
  writes parser documents after the dictionaries, so a parser names the version
  the run just wrote, and writes one the preview called unchanged when a
  dictionary it names moved on.
- **Nothing that used to import stops importing.** Every archive written before
  this carries the parser step without the document. `RestImportService` imports
  only the parser documents an archive actually carries; one it lacks leaves the
  step and its reference exactly as before, rather than dropping the step on a
  `create` (the rule for every other type) or refusing a `merge` that has no local
  copy to answer it with.
- **Dangling references are healed, not fatal.** An agent promoted before this —
  or imported from such an archive — names a parser its instance never had. The
  export now skips a parser document that no longer exists instead of failing the
  whole backup, and a sync recreates a resource the target's step names but the
  target does not have, repointing the step. Only a resource the store *confirms*
  is gone is recreated: a read that failed for any other reason would otherwise
  swap a live resource for a copy.

None of this changes how an agent runs: the pipeline builds its parser from the
workflow step (`WorkflowStoreClientLibrary`) and never loads the document. The
document matters to everything else that reads it — the `/parser/{parserId}`
endpoint, orphan detection, cascade delete — and to a promotion that is meant to
leave no reference behind.

RAG was checked for the same gap and has none: knowledge-base configurations, with
their ingestion sources, are exported, imported and synced, and a first promotion
recreates their cron schedules. What a knowledge base has *ingested* is data in the
vector store and does not travel; [`agent-sync-guide.md`](../agent-sync-guide.md)
now says what travels, what does not, and how to fill a promoted knowledge base.

**Tests:** `NestedReferencesTest`, `RestImportServiceParserTravelTest`,
`RestExportServiceParserTest`, `StructuralMatcherParserTest`,
`UpgradeExecutorParserTest` — each behaviour above mutation-checked (the fix
reverted, the test confirmed red).

