## 🐛 fix(import): a refused merge update fails the import instead of creating a duplicate (2026-11-06)

**Repo:** EDDI (`fix/import-merge-refusals`)

### What changed and why

- **A store that refuses a merge update now fails the import.** `RestImportService.updateTracked` returned `null` on any non-200 answer, and every caller then created the resource instead. Only two stores answer a refusal rather than throwing — `RestDictionaryStore.updateRegularDictionary` (a regular expression that does not compile) and `RestApiCallsStore.updateApiCalls` (batch settings over the deployment ceiling) — and for both the fallback went through `createResourceDirect`, which runs neither check: the refused document was stored as a second resource with the archive's origin id, and the workflow was pointed at the copy. `updateTracked` now throws a `WebApplicationException` carrying the store's own response (its `400` and body reach the client), or a `500` for no answer or a non-error status; the import's existing rollback undoes what the merge had already written, and a `WARN` line names the refused resource.
- **The dead fallbacks are gone.** Every other store updates through `RestVersionInfo.update`, which answers 200 or throws, so the `updated != null ? updated : create…` branches for agents, workflows, parsers, behavior, LLM, property, output, MCP calls and RAG (with its second `applyStorageRulesToNewRag`) could never run. The snippet merge no longer "falls through to create" a second snippet of the name it had just matched; a refused snippet update costs that snippet, like any snippet failure.
- **Live sync takes only a resource id as `sourceAgentId`.** `RemoteApiResourceSource.readAgent` concatenated the id into the request path, and resolving that against the source URL normalised `../` away — an id like `../../backup/export/x` read another path on the source with the caller's token. `previewSync`, `executeSync` and both batch endpoints refuse anything `RestUtilities.isValidId` rejects with a `400` (a failed entry in a batch) before a request is sent, and `readAgent` encodes the id as one path segment, as `exportAgentArchive` already did.
- The Javadocs of `RestApiCallsStore`, `RestLlmStore`, `RestMcpCallsStore` and `RedactionMarkerGuard` that described the fallback as intended are rewritten.

### Docs

[`import-export-an-agent.md`](../import-export-an-agent.md) (when a merge fails part-way) and [`agent-sync-guide.md`](../agent-sync-guide.md) (the `sourceAgentId` parameter).

### Tests

`RestImportServiceMergeSharedExtensionTest` (refused dictionary and httpcalls updates fail the merge with the store's answer, roll back, and create nothing; a non-error or missing answer is a `500`), `RestImportServiceSnippetResilienceTest` (a refused snippet update creates no duplicate and does not fail the agent), `RestImportServiceSyncCoverageTest` (a crafted `sourceAgentId` is a `400` on preview, sync and both batches, with no request sent), `RemoteApiResourceSourceTest` (the id never leaves `/agentstore/agents/`). Each failed on `main` and was mutation-checked.

**Files:** [`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java), [`RemoteApiResourceSource.java`](../../src/main/java/ai/labs/eddi/backup/impl/RemoteApiResourceSource.java)

```decision-log
| 2026-11-06 | A non-200 merge update fails the import with the store's own response; a refused snippet update costs the snippet only | A dictionary or httpcalls update the store refused was created instead, unvalidated, as a duplicate with the same origin id | Validating in the direct create path and keeping the fallback (a merge target that exists must never be duplicated); failing the whole import on a snippet refusal (snippets are best-effort by design) |
```
