## 🔁 fix(backup): agent sync works more than once, can create, and says what it did (2026-09-22)

**Repo:** EDDI (`fix/agent-sync-promotion`)

### What changed and why

Live Agent Sync was tested end to end against two real instances for the first
time, and it did not survive the ordinary use it exists for — promoting an agent
from staging to production and then keeping it current. Four defects, each in the
seam between `UpgradeExecutor` and something it does not own, plus two decisions
that made the feature unusable on the network shape most self-hosted deployments
have.

**Version resolution always answered 1.** Both
[`StructuralMatcher`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java)
and [`UpgradeExecutor`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java)
asked for the target's current version with `readDescriptor(id, null)`. The
descriptor store is historized and its read does `checkNotNull(version)`, so that
call *always* threw; the exception was swallowed and a fallback of 1 stood in. So
every sync diffed against the target's version 1 — showing the operator pre-sync
content as "target" — and then wrote against version 1, which the store refuses
once the first sync has moved the resource to version 2. A sync therefore worked
exactly once per target agent and then answered `207` with "the store did not
accept the update", writing nothing, for ever. Both now use
`readCurrentDescriptor`.

**Nothing moved the descriptors.** An upgrade calls the configuration stores
in-process, so no JAX-RS filter runs and nothing did what `DocumentDescriptorFilter`
does for a `PUT` through the API. The descriptor is what `WorkflowStoreService`
reads to deploy an agent — so a synced version was written correctly and then
refused to deploy at all, with "Resource not found" for a workflow that was
demonstrably in the database — and it is what the Manager lists, so the UI kept
showing the pre-sync version. `UpgradeExecutor` now moves the descriptor after
each write, and reports a resource whose descriptor could not be moved as a
failure rather than as a success.

**A successful sync answered 500.** The endpoint answers `201` with the agent's
new-version URI, which `DocumentDescriptorFilter` read as a creation and tried to
give a second descriptor — duplicate key, after every write had already landed.
Backup endpoints keep their own descriptors and are now skipped by that filter.

**A first promotion was impossible.** With no `targetAgentId` — which the API
documents as "create new" and which the Manager sends for any agent it cannot
match by name — the request went into `UpgradeExecutor`, whose every path assumes
a target. It read the agent `null`, answered `500`, and left the workflow it had
already created behind: one orphan per attempt, pointing at resource ids that
only exist on the other instance. A sync with no target now fetches the source's
own export archive and imports it with `strategy=create`, so a first promotion
lands exactly what the same archive would land by hand — schedules, connections,
capability registration and rollback included — rather than through a second,
thinner create path that would drift from it.

**Snippets are scoped to the agent.** `readSnippets` returned the remote
instance's entire snippet store, so promoting one agent proposed copying
staging's whole snippet library — unreleased drafts and other teams' snippets —
onto production. It now scans the agent's own documents for `{snippets.<name>}`,
which is what the export has always done; the one definition now lives in
`SnippetReferences` and both sides use it.

**The source address policy is configurable.** HTTPS-only and the
private-address refusal were compiled in, so two instances on one internal
network — staging and production as neighbouring services, the ordinary
self-hosted shape — could not sync at all, whatever the operator wanted, and no
setting existed to say otherwise. `eddi.backup.sync.require-https`,
`eddi.backup.sync.allow-private-targets` and an exact-origin
`eddi.backup.sync.allowed-sources` now express it, all defaulting to today's
strict behaviour, each independent of the others. A refused URL is a `400`
naming the setting that would allow it, not an unexplained `500`.

The Manager stops reporting a failed sync as a successful one. It read only "the
mutation resolved", and `executeSyncBatch` deliberately resolves on `500` too —
that status means every mapping failed and the body carries the reasons — so a
batch in which nothing was written rendered a green "Sync complete". It now reads
the outcome from the results, lists the per-resource reasons, and surfaces the
server's own message instead of `res.statusText`.

### How it is guarded now

[`AgentSyncIT`](../../src/test/java/ai/labs/eddi/integration/AgentSyncIT.java)
runs the promotion an operator performs — create, update, update *again*, a
no-op, then deploy what was promoted — over real HTTP against the real stores.
The existing 768 backup unit tests could not have caught any of this: every one
of them mocks the stores, and every mocked target sat at version 1, the one
version the broken resolution answered correctly.

**Files:**
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java),
[`SourceUrlValidator.java`](../../src/main/java/ai/labs/eddi/backup/impl/SourceUrlValidator.java),
[`SnippetReferences.java`](../../src/main/java/ai/labs/eddi/backup/impl/SnippetReferences.java),
[`DocumentDescriptorFilter.java`](../../src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java),
[`sync-page.tsx`](../../ui/manager/src/pages/sync-page.tsx),
[`backup.ts`](../../ui/manager/src/lib/api/backup.ts),
[`agent-sync-guide.md`](../agent-sync-guide.md)

```decision-log
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
```

```regression-note
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
```
