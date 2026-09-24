## RAG docs: document the workflow step that actually binds a knowledge base (2026-09-21)

**Repo:** EDDI · **Branch:** `docs/rag-workflow-step-binding`

A user reported that `product-docs` retrieval "does not work" on their deployment and concluded the
`RagContextProvider` wiring was missing from the build. The wiring is present and has been since
Phase 8c — `LlmTask` calls `retrieveContext` and appends `## Relevant Context:`. What was missing was
the third side of the binding, which the docs never showed.

### The failure the docs were producing

A knowledge base reaches an agent through three configurations, not two:

1. the `RagConfiguration` at `/ragstore/rags/{id}`,
2. an `eddi://ai.labs.rag` **step in the agent's workflow**,
3. `knowledgeBases` / `enableWorkflowRag` on the LLM task.

`RagContextProvider` matches `knowledgeBases[].name` against configs discovered from the **workflow
document** (`WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.rag", …)`). With no such step
`ragSteps.isEmpty()` is true and retrieval returns at a `LOGGER.debug` line, before the trace entry,
before the store build, before any INFO log. Every symptom in the report falls out of that one early
return: no `## Relevant Context:` block, no `rag:trace:*`/`rag:context:*`, and no `RagContextProvider`
or `EmbeddingStoreFactory` lines while other tool providers logged on every turn.

A second cause presents identically and the troubleshooting section now says so: when the workflow
*does* bind a step but no `knowledgeBases[].name` matches its KB name, every step is `continue`d past,
`traceEntries` stays empty so no trace is stored, and `allResults.isEmpty()` returns null just the
same. Only the `DEBUG` line distinguishes them — it is logged for the missing-step case alone.

A third cause sits even earlier and was missed in the first pass at this section: `retrieveContext`
checks the *task* before it ever looks at the workflow — `if (!hasExplicitRefs && !useWorkflowDiscovery)
return null;` at the top of the method. A task with an empty `knowledgeBases` and no
`enableWorkflowRag: true` returns here, before `WorkflowTraversal.discoverConfigs` runs at all, so it
produces no discovery, no trace, no store build, no INFO log — and, unlike the missing-step cause, no
`DEBUG` line either, because that line lives inside the `ragSteps.isEmpty()` branch this return never
reaches. The troubleshooting table now leads with checking the task's own RAG settings before touching
the workflow.

`rag.md` mentioned the requirement only in a subordinate clause ("Each reference names a KB from the
workflow") and in a Status bullet at the bottom of the page. Its setup path showed the KB config and
the LLM task and nothing else — so a reader who followed it end to end built exactly the broken
two-sided configuration that was reported. That is a documentation defect, not a user error.

### `docs/rag.md`

- **Configuration** now opens with the three-sided binding as a table, and names configuring 1 and 3
  without 2 as the most common failure — explicitly including that it produces no context, no trace,
  no error and no log above `DEBUG`.
- **New `### 2. Workflow Step`** section with the workflow JSON, the `{ragId}.rag.json` ZIP name, why
  `RagTask.execute()` is a no-op while `configure()` resolves the KB, and the
  `GET /extensionstore/extensions` check for builds where `ai.labs.rag` is not registered.
- Existing sections renumbered to `1.`/`3.` to match. No inbound anchor links existed.
- **Fixed a dangling sentence**: "Three of them choose what is retrieved:" was followed by nothing.
  The three are now named.
- Options 1 and 2 state that the name matches the KB's `name` (not its id) and that an unmatched name
  is skipped silently; Option 3 states that `httpCallRag` calls an *external* API and cannot query an
  EDDI knowledge base.
- **REST API**: states outright that no `/query`, `/search` or `/retrieve` endpoint exists and that
  those return `404`. Users were trying `httpCallRag` against their own KB as a workaround and
  hitting 404s with nothing telling them the endpoint was never meant to exist.
- **Document Ingestion**: warns off the `kbId` query parameter, found while reviewing this change.
  `RagContextProvider` keys the embedding store on `ragConfig.getName()` and cannot be pointed
  elsewhere, but `RestRagIngestion` lets the caller override that key (`effectiveKbId`, favouring
  the `kbId` param over the name). Any `kbId` other than the KB's exact `name` therefore ingests
  into a store nothing reads — `202`, status `completed`, documents genuinely embedded and stored,
  retrieval empty for ever. Ingestion *sources* are unaffected: `IngestionPipeline` keys on
  `knowledgeBase.getName()`, and `IngestionRetrievalRoundTripTest` pins that round trip after an
  earlier draft shipped exactly this divergence on the source path.
- **Vector Stores**: `in-memory` is no longer described only as "ephemeral, for dev/test only". The
  cached object *is* the data, and `EmbeddingStoreFactory` holds it in a Caffeine cache bounded at 50
  stores with a 30-minute `expireAfterAccess` and a full invalidation on any secret or global-variable
  change — so an in-memory KB empties itself after 30 idle minutes, on restart, and on credential
  rotation, then returns no context rather than an error.
- **New `## Troubleshooting`** section: an ordered seven-step check for the silent-no-context case
  (task-level RAG settings checked first, ahead of the workflow-binding checks), and the
  `quarkus.log.category` line that makes the missing-step early return visible — called out as not
  covering the task-level or unmatched-name causes, which log nothing at any level.
- **Status**: the workflow-step bullet said "Options 1 and 2 below" while they are above it.

### `docs/langchain.md`

The task-parameter reference documented `maxRagContextChars` and nothing else about RAG — the one
knob that merely *bounds* a feature the page never introduced. Added `knowledgeBases`,
`enableWorkflowRag`, `ragDefaults` and `httpCallRag` under a **Retrieval (RAG)** group whose header
carries the workflow-step requirement and links to `rag.md`.

### Not changed

The engine. No defect was found on the retrieval path itself. Three gaps are recorded here rather
than fixed, each arguably worth its own issue:

1. **`kbId` on `/ingest` can write where nothing reads** (above). The parameter has no correct
   non-default value, because retrieval cannot be pointed at a custom key — so the fix is probably
   to reject a `kbId` that does not equal the KB's `name`, or to drop the parameter, rather than to
   document it. Documented here because a doc change cannot make a `202` mean something else.
2. **No retrieval REST endpoint** for a knowledge base, so there is no supported way to test that
   ingestion worked without running a conversation — which is what sent the reporting user to
   `httpCallRag` and a wall of 404s.
3. **`ai.labs.rag` registration is in no release tag.** It is on `main`; the newest tag is `6.4.0`.
   Deployments on `6.4.0` or earlier cannot wire up vector RAG at all, whatever the docs now say.

```decision-log
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
```

```regression-note
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
```
