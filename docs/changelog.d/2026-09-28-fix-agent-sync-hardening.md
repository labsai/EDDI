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
  ids. The dictionaries a parser document names are now resources of their own in
  the match: [`WorkflowExtensions.scanDocument`](../../src/main/java/ai/labs/eddi/backup/impl/WorkflowExtensions.java)
  keys each one under the document, both sources and the target derive the same
  keys, and the preview, the create and the update treat them like any other
  resource. [`NestedReferences`](../../src/main/java/ai/labs/eddi/backup/impl/NestedReferences.java)
  then swaps each reference in the parser for its matched counterpart, by source
  id. [`UpgradeExecutor`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java)
  writes parser documents after the dictionaries, so a parser names the version
  the run just wrote, and the preview already reports such a parser as UPDATE.
- **A resource named twice is created once.** `extractResourcesUris` returned a
  URI once per mention, so a dictionary both a parser step and its document named
  was created twice and the first copy orphaned. It now returns each distinct URI
  once, for every resource type.
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

```decision-log
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
```
