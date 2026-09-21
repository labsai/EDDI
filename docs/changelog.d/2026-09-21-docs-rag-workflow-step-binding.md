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
- **Vector Stores**: `in-memory` is no longer described only as "ephemeral, for dev/test only". The
  cached object *is* the data, and `EmbeddingStoreFactory` holds it in a Caffeine cache bounded at 50
  stores with a 30-minute `expireAfterAccess` and a full invalidation on any secret or global-variable
  change — so an in-memory KB empties itself after 30 idle minutes, on restart, and on credential
  rotation, then returns no context rather than an error.
- **New `## Troubleshooting`** section: an ordered five-step check for the silent-no-context case, and
  the `quarkus.log.category` line that makes the early return visible.
- **Status**: the workflow-step bullet said "Options 1 and 2 below" while they are above it.

### `docs/langchain.md`

The task-parameter reference documented `maxRagContextChars` and nothing else about RAG — the one
knob that merely *bounds* a feature the page never introduced. Added `knowledgeBases`,
`enableWorkflowRag`, `ragDefaults` and `httpCallRag` under a **Retrieval (RAG)** group whose header
carries the workflow-step requirement and links to `rag.md`.

### Not changed

The engine. No code defect was found on the retrieval path. Two real gaps are recorded here rather
than fixed: there is no retrieval REST endpoint for a knowledge base (so no supported way to search
one from outside a conversation), and `ai.labs.rag` registration is on `main` but in no release tag,
so deployments on `6.4.0` or earlier cannot wire up vector RAG at all.

```decision-log
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
```

```regression-note
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
```
