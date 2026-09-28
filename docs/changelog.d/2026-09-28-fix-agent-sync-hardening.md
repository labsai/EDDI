## 🔒 fix(backup): agent sync keeps the target's secrets, carries structural changes, and stops overwriting local edits (2026-09-28)

**Repo:** EDDI (`fix/agent-sync-hardening`)

### What changed and why

Agent Sync was driven end to end between two packaged instances — staging and
production on separate databases and vault keys — through about 25 scenarios over
the REST API and the production Manager: iterate on staging several times, promote
once, roll back, add and remove steps and whole workflows, hotfix production, sync
in batches and concurrently. The basic loop held up. What did not, all confirmed
against every open branch before fixing:

**The source's plaintext secrets reached the target.** `RemoteApiResourceSource`
read each document with a plain store `GET`, which answers with raw configuration.
A first promotion goes through the export archive and is scrubbed; every later
sync was not — staging's `Authorization: Bearer <token>` was shown in the preview
(so in the production operator's browser) and written into production. Every
document a live sync reads now goes through `SecretScrubber` first
([`RemoteApiResourceSource`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java));
`RestImportService` injects the container's scrubber and falls back to a default
one, so a missed wiring cannot switch scrubbing off.

**Production's own vault references were overwritten.** The scrubber leaves
`${vault:…}` legible on purpose, so it travelled and replaced the target's
pointer: staging's `${vault:openai-key}` over production's
`${vault:prod-openai-key}`, breaking every LLM call. A value holding a vault
reference is now bound to the target like a placeholder
([`ScrubbedSecrets`](../../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java)):
the target's own value at the same place wins; where it has none, the reference
travels as the hint of which entry to create.

**Adding a workflow wrote an undeployable agent and answered 201.** The new
workflow was stored as the source had it, naming resource ids only the source
has, and none of its resources were created. Removing it again on the source was
a `200 nothing to write`, so no sync could repair it. `UpgradeExecutor` now creates
the workflow's resources first and writes the workflow naming them; a workflow the
source removed comes off the agent.

**An added step could not be promoted at all.** The preview said `CREATE`, the
execute answered `207` and refused the whole workflow update, telling the operator
to hand-edit production — and once a sync had removed a step, it could never add
it back. A resource for a step the source added is now created when the workflow
adoption that places it is certain; that is decided *before* anything is written
(`adoptionBlocker`), so a refused adoption no longer leaves an unreferenced copy.

**The preview disagreed with the execute.** Agents and workflows were compared
with each side's own ids, so every agent showed two phantom changes forever;
removals were invisible; and agent-level settings (HITL, capabilities, memory
policy) previewed as an agent `UPDATE` that was never written.
[`StructuralMatcher`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java)
now compares the source rewritten onto the target's references (and identity),
emits `REMOVE` rows for steps and workflows the source no longer has, and the
executor writes the agent's settings — keeping the target's instance-bound
`identity`.

**A production hotfix was overwritten silently.** `DocumentDescriptor.syncedVersion`
now records the version a sync or import wrote; a target version past it was made
locally, and a resource changed on both sides is a `CONFLICT`, written only when
named in `selectedResources` (the Manager's **Overwrite local change**). The agent's own
settings and a workflow's steps are guarded the same way, each compared with its
synced version rewritten onto today's references so that Manager edits cascading
new versions do not count; when they are left alone, the rest of the sync still
lands. A dictionary only a parser document names is created or updated only when
that document will be written, and a batch entry names the agent it was written
into.

**Smaller ones.** A sync without `targetAgentId` now finds the agent an earlier
promotion made (by `originId`) instead of creating a copy on every call — `409`
when several exist, `createNew=true` to force one. `selectedResources` present but
empty is a `400` instead of "everything". Duplicate snippet names travel as the one
the runtime renders, with a preview `warning` — export, sync and import agree on
which. A nonexistent target is a `404` with a body, not an empty `500`; every sync
error answers `{"error": …}`; a failed remote read is logged once, at WARN. Snippets
an import creates get a named descriptor.

**Manager.** The sync page matches local agents by `originId` before name, forces a
copy only when the operator picks *Create new*, adopts the target a preview
resolved, shows `REMOVE`/`CONFLICT` rows with their diffs and the preview's
warnings, lets a conflict be overwritten per row, and says per agent what was
written and that it is not live until deployed. The import dialog leaves `CONFLICT`
rows unticked, pre-selects the local copy promoted from the chosen agent as soon as
it is picked, and creates a new agent only when *Create new agent* is chosen
explicitly — so leaving the default never duplicates and choosing a copy is always
honoured. i18n in all 11 locales.

Three commits that followed the merge of #829 on `fix/agent-sync-promotion` —
parser documents travelling with the agent — were never pushed; they are the base
of this branch (entry below).

### How it is guarded

`RemoteApiResourceSourceScrubbingTest` (real scrubber and serializer),
`StructuralMatcherSyncTest`, `UpgradeExecutorSyncTest`, vault-reference cases in
`ScrubbedSecretsTest`, and five sync-page and one import-dialog vitest — each
mutation-checked: the fix reverted, the test confirmed red. The live two-instance
run was repeated on the fixed build.

**Files:**
[`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`ScrubbedSecrets.java`](../../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`StructuralMatcher.java`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`RestExportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestExportService.java),
[`ImportPreview.java`](../../src/main/java/ai/labs/eddi/backup/model/ImportPreview.java),
[`DocumentDescriptor.java`](../../src/main/java/ai/labs/eddi/configs/descriptors/model/DocumentDescriptor.java),
[`sync-page.tsx`](../../ui/manager/src/pages/sync-page.tsx),
[`preview-step.tsx`](../../ui/manager/src/components/agents/import-steps/preview-step.tsx),
[`agent-sync-guide.md`](../agent-sync-guide.md),
[`agent-sync-architecture.md`](../agent-sync-architecture.md)

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
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
```

```regression-note
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
```
