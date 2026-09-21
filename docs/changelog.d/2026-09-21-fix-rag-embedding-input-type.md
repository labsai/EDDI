## 🎯 fix(rag): stop embedding queries as documents (2026-09-21)

**Repo:** EDDI (`fix/rag-embedding-input-type`)

Every Gemini knowledge base has been embedding its **queries** as though they were
documents, quietly costing retrieval quality. Nothing failed; recall was just worse than
the model is capable of.

### The mechanism

Some embedding models are asymmetric — the same text produces a different vector
depending on which side of the search it is on. `GoogleAiEmbeddingModel.toTaskType` maps a
per-request `EmbeddingInputType.QUERY` to `RETRIEVAL_QUERY` and `DOCUMENT` to
`RETRIEVAL_DOCUMENT`, and **when no input type is given falls back to whatever
`taskType` the model was built with**.

`RagIngestionService` and `RagContextProvider` both called
`embeddingModelFactory.getOrCreate(ragConfig)` with the same configuration, so they hit
the same cache entry and shared one instance. `EmbeddingModelFactory` defaults Gemini's
`taskType` to `RETRIEVAL_DOCUMENT`. Retrieval therefore embedded the search key as a
document.

### What changed

- `getOrCreate` now takes the role as a **required** parameter. Required rather than an
  optional overload deliberately: a caller cannot forget it, and the compiler names every
  site that has to choose. There are two.
- The role is part of the cache key — to an asymmetric provider the two roles are two
  different models, and sharing one entry is the defect.
- `InputTypedEmbeddingModel` (new) attaches the role via `defaultRequestParameters()`,
  which is what carries it through the `embed(String)` convenience overload that
  `EmbeddingStoreContentRetriever` uses. There is no way to pass a per-call parameter
  through that retriever, so the role has to travel with the instance.

### Why the capability check is not optional

`EmbeddingModel.embed` validates against `supportedParameters()` and throws
`UnsupportedFeatureException` for anything unsupported **rather than ignoring it**.
Verified against the resolved jars: only **2 of the 8** providers EDDI builds declare
`INPUT_TYPE` — `gemini` and `cohere`. Setting it unconditionally would have turned every
OpenAI, Azure, Ollama, Bedrock, Vertex and Mistral knowledge base into a hard failure.
Those six are handed the provider's model unchanged.

The check reads the live model's own `supportedParameters()` rather than a table of
provider names, so it cannot go stale on a dependency bump. Mutation-tested: removing it
produces exactly `UnsupportedFeatureException: ... does not support the following
per-call parameter(s): inputType`.

### No re-ingestion needed

Stored vectors were always correct — ingestion's implicit `RETRIEVAL_DOCUMENT` was the
right value for documents. Only the query side was wrong, so existing knowledge bases
improve on the next query with no migration.

### Tests

`InputTypedEmbeddingModelTest` (9, new) covers both roles reaching the provider, the
unsupported-provider passthrough, delegation, and that attaching a role does not drop the
delegate's `modelName`/`dimensions`. Its first test pins the *defect* — an undecorated
model reports no input type at all. Two call-site tests assert ingestion asks for
`DOCUMENT` and retrieval asks for `QUERY`, which is what makes the fix real: the decorator
is useless if both sites still ask for the same thing.

`EmbeddingModelFactoryTest.PinnedTaskTypeDecision` (7, new) grades
`pinsNonRetrievalTaskType` directly. It is package-private and tested on its own because
every path through `build()` constructs a live provider client, which needs a socket — so
the model-level tests only run where one is available, and the decision itself has to be
gradeable anywhere. `PinnedTaskType` (6, new) covers the same decision through
`getOrCreate` and runs in CI.

`docs/rag.md` gained an "Asymmetric models" section covering which providers are affected,
what happens to a pinned `taskType`, and that no re-ingestion is required.

### Follow-up: a pinned Gemini `taskType` is no longer overridden by the role

Raised in review of [#810](https://github.com/labsai/EDDI/pull/810). Attaching the role to
every RAG call fixes the query side, but `GoogleAiEmbeddingModel.toTaskType` falls back to
the build-time `taskType` **only when no input type is given**; a role maps
unconditionally onto `RETRIEVAL_QUERY` / `RETRIEVAL_DOCUMENT`. A knowledge base configured
with `taskType: SEMANTIC_SIMILARITY` (or `CLASSIFICATION`, or `CLUSTERING`) would
therefore have stopped sending it, and everything ingested afterwards would have used
`RETRIEVAL_DOCUMENT` — two incompatible geometries in one index, with nothing failing to
say so.

`EmbeddingModelFactory.pinsNonRetrievalTaskType` now detects that case and leaves the role
off, so the configured task type keeps reaching the provider on both sides. Three
boundaries are deliberate:

- **`RETRIEVAL_DOCUMENT` and `RETRIEVAL_QUERY` do not pin.** The first is the default this
  factory applies and is precisely the value that produced the defect; honouring it would
  leave the bug in place for anyone who had written the default out by hand.
- **`gemini-embedding-2` never pins.** langchain4j sends no `task_type` for any model whose
  name contains `embedding-2` and applies a role instruction instead, so a pinned task type
  is already inert there and skipping the role would cost the instruction for nothing.
- **`taskType` is a Gemini parameter.** A stray one on Cohere or OpenAI pins nothing.

---



---
