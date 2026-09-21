## 🧩 fix(rag): register `ai.labs.rag` so RAG workflow steps can be deployed (2026-09-17)

**Repo:** EDDI (`feat/rag-workflow-extension`)

### Why

A workflow step of type `eddi://ai.labs.rag` could not be deployed at all. `WorkflowStoreClientLibrary`
resolves a step by `URI.getHost()` against the `@LifecycleExtensions` map and throws
`UnrecognizedExtensionException` when the key is absent — and no module ever registered `ai.labs.rag`.
The nine registered types were parser, behavior/rules, property, httpcalls/apicalls, output, llm,
mcpcalls and templating.

So the two knowledge-base options documented in [`rag.md`](../rag.md) were undeployable; only `httpCallRag`
worked end to end. `RestWorkflowStepStore` builds the Manager's step chooser from the same map, so the
real backend never offered the step either — the Manager's MSW fixture hard-codes it, which is why its
UI suite stayed green.

Nothing caught this: `RagContextProvider` discovers RAG steps by reading the workflow document directly
(`WorkflowTraversal`), and its tests build `WorkflowConfiguration` objects by hand, so no test ever
traversed the deploy path.

### What changed

- **`modules/rag/RagTask.java`** (new) — the config-carrier task. `execute` is a deliberate no-op:
  retrieval stays in `RagContextProvider` inside the LLM task (the Phase 8c decision stands), because
  that is where the user's query is known. `configure` resolves the referenced `RagConfiguration`, so a
  broken knowledge-base binding fails when the workflow is deployed instead of silently returning no
  context on the first conversation.
- **`modules/rag/bootstrap/RagModule.java`** (new) — registers the task under `ai.labs.rag`, matching the
  host of the documented step URI.

`getType()` returns the stage name `"rag"`, not the `eddi://` URI. Per the `ILifecycleTask` contract the
type is a lifecycle stage identifier used for ordering and partial-execution filters; the URI form
matches no filter. (The draft this was salvaged from returned the URI — see Decision Log.)

### Tests

- **`RagTaskTest`** (10) — id/type contract, `configure` happy path, missing/blank/null/malformed URI,
  `ServiceException` wrapping, no-op `execute`, descriptor shape.
- **`RagWorkflowDeploymentTest`** (6) — deploys a real workflow containing a RAG step through
  `WorkflowStoreClientLibrary`, including the negative case that pins the bug: without the registration
  the whole workflow is rejected.
- **`RagModuleTest`** (3) — registration key equals `URI.create("eddi://ai.labs.rag").getHost()`.
- **`integration/RagWorkflowExtensionIT`** — asserts against live CDI wiring via `RestWorkflowStepStore`
  (the bean that feeds the Manager's chooser) that the RAG step is offered, and that every documented
  workflow step type resolves. This is the test that would have caught the original bug; it is an `IT`
  because the repo runs every `@QuarkusTest` in the integration job.

Mutation-checked: reverting `getType()` to the URI and the registration key to a wrong value fails 4 of
the tests.

### Notes

This is the first of six PRs salvaging the scheduled RAG ingestion work from PR #529, which has been
stale and conflicting since 2026-07-02. This piece is independent of that feature — main needs it either
way.
