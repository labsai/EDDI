# RAG (Retrieval-Augmented Generation)

> **Phase 8c** — Config-driven knowledge base retrieval integrated into the LLM pipeline.

## Overview

EDDI's RAG system is a first-class workflow extension that adds contextual knowledge retrieval to LLM conversations. Knowledge bases are versioned configurations — just like behavior rules or httpCalls — managed via REST API and wired into workflows.

At execution time, the `LlmTask` discovers RAG configurations from the agent's workflow, performs vector similarity search against the user's query, and injects the retrieved context into the LLM system message — all automatically and transparently.

## Architecture

```
User Query
    │
    ▼
┌─────────────────── LlmTask.executeTask() ───────────────────┐
│                                                              │
│  1. Extract user input from conversation memory              │
│  2. RagContextProvider.retrieveContext()                     │
│     ├── WorkflowTraversal.discoverConfigs() → find RAG steps │
│     ├── Match KBs (explicit refs or auto-discover all)       │
│     ├── EmbeddingModelFactory → cached embedding model       │
│     ├── EmbeddingStoreFactory → cached vector store          │
│     ├── EmbeddingStoreContentRetriever → similarity search   │
│     └── Store audit trace in conversation memory             │
│  3. Inject context: systemMessage += "## Relevant Context"   │
│  4. Build chat messages and call LLM                         │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## Configuration

### RagConfiguration (Knowledge Base)

A `RagConfiguration` is a versioned resource at `/ragstore/rags/`. It defines:

```json
{
  "name": "product-docs",
  "embeddingProvider": "openai",
  "embeddingParameters": {
    "model": "text-embedding-3-small",
    "apiKey": "${vault:tenant/agent/openai-key}"
  },
  "storeType": "in-memory",
  "storeParameters": {},
  "chunkStrategy": "recursive",
  "chunkSize": 512,
  "chunkOverlap": 64,
  "maxResults": 5,
  "minScore": 0.6
}
```

| Field | Default | Description |
|---|---|---|
| `name` | — | Display name / identifier for this knowledge base |
| `embeddingProvider` | `openai` | Provider (see Embedding Providers table below) |
| `embeddingParameters` | — | Provider-specific params (model, apiKey, baseUrl, etc.) |
| `storeType` | `in-memory` | Vector store (see Vector Stores table below) |
| `storeParameters` | — | Store-specific connection params |
| `chunkStrategy` | `recursive` | Document chunking strategy |
| `chunkSize` | `512` | Chunk size in characters |
| `chunkOverlap` | `64` | Chunk overlap in characters |
| `maxResults` | `5` | Default top-K results |
| `minScore` | `0.6` | Default minimum similarity score (0.0–1.0) |

### LLM Task RAG Configuration

RAG is wired into LLM tasks via four fields on `LlmConfiguration.Task`. Three of them choose what
is retrieved:

The fourth bounds the result. `maxRagContextChars` (default `20000`) caps the assembled
RAG context in characters, across every matched knowledge base and any `httpCallRag` response.
Without it the prompt grows with the corpus until the provider rejects the request. Set `-1`
or `0` to disable the cap.

#### Option 1: Explicit Knowledge Base References

```json
{
  "tasks": [{
    "actions": ["*"],
    "type": "openai",
    "knowledgeBases": [
      { "name": "product-docs", "maxResults": 5, "minScore": 0.7 },
      { "name": "faq", "maxResults": 3 }
    ],
    "parameters": {
      "systemMessage": "You are a helpful assistant."
    }
  }]
}
```

Each reference names a KB from the workflow and optionally overrides retrieval parameters.

#### Option 2: Auto-Discovery

```json
{
  "tasks": [{
    "enableWorkflowRag": true,
    "ragDefaults": { "maxResults": 5, "minScore": 0.7 }
  }]
}
```

When `enableWorkflowRag` is `true`, the system discovers all RAG steps from the workflow automatically.

#### Option 3: httpCall RAG (Phase 8c-0)

```json
{
  "tasks": [{
    "httpCallRag": "search-api"
  }]
}
```

Zero-infrastructure RAG: execute a named httpCall and inject its response as `## Search Results:` context. The user's input is available as `{userInput}` in httpCall templates. No vector store needed. Both httpCall RAG and vector RAG can be active simultaneously.

#### Context Injection

Retrieved vector-RAG context (Options 1 and 2) is **always** appended to the LLM **system message** under a `## Relevant Context:` heading. `RagContextProvider` returns one formatted block covering every matched knowledge base, and `LlmTask` appends it. There is no per-knowledge-base or per-task switch for the injection point or for the formatting.

> **Note for existing configurations:** older `langchain.json` documents may still carry `injectionStrategy` (on `knowledgeBases[]` or `ragDefaults`) or `contextTemplate` (on `knowledgeBases[]`). Neither key was ever read by the engine — context has always gone to the system message — and both were removed from `LlmConfiguration`. Stored configurations remain valid: the leftover keys are ignored on load and dropped the next time the configuration is saved. No migration is required.

## REST API

### Configuration Management

| Method | Path | Description |
|---|---|---|
| `GET` | `/ragstore/rags/jsonSchema` | JSON Schema for validation |
| `GET` | `/ragstore/rags/descriptors` | List KB descriptors |
| `GET` | `/ragstore/rags/{id}?version=N` | Read a KB configuration |
| `POST` | `/ragstore/rags` | Create a new KB |
| `PUT` | `/ragstore/rags/{id}?version=N` | Update a KB |
| `POST` | `/ragstore/rags/{id}?version=N` | Duplicate a KB |
| `DELETE` | `/ragstore/rags/{id}?version=N` | Delete a KB |

### Document Ingestion

| Method | Path | Description |
|---|---|---|
| `POST` | `/ragstore/rags/{id}/ingest?version=N&kbId=...&documentName=...` | Ingest a text document (returns 202 + ingestion ID) |
| `GET` | `/ragstore/rags/{id}/ingestion/{ingestionId}/status` | Poll ingestion status |

**Example: Ingest a document**

```bash
curl -X POST http://localhost:7070/ragstore/rags/abc123/ingest?version=1\&documentName=readme.txt \
  -H "Content-Type: text/plain" \
  -d "This is the document content to be chunked, embedded, and stored."
```

Response: `202 Accepted`
```json
{
  "ingestionId": "550e8400-e29b-41d4-a716-446655440000",
  "kbId": "product-docs",
  "status": "pending"
}
```

**Poll status:**
```bash
curl http://localhost:7070/ragstore/rags/abc123/ingestion/550e8400-e29b-41d4-a716-446655440000/status
```

Response:
```json
{
  "ingestionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed"
}
```

Status values: `pending` → `processing` → `completed` | `failed: <error message>`

## Ingestion Sources

A knowledge base can pull its own documents instead of being fed one at a time. Sources live on the
knowledge base (`sources[]` on the RAG configuration), because the vector store is keyed by the
knowledge base — a source that named its target by string could, and in an earlier draft did, write to
one table while retrieval read another.

```json
{
  "name": "product-docs",
  "sources": [{
    "name": "public-docs",
    "type": "web",
    "cron": "0 2 * * *",
    "web": {
      "startUrl": "https://example.com/docs/",
      "pathPrefix": "/docs/",
      "maxDepth": 3,
      "maxPages": 200,
      "excludePatterns": ["*.pdf", "**/changelog/**"],
      "requestDelayMs": 500,
      "respectRobots": true
    },
    "settings": {
      "tombstoneAfterMissedRuns": 2,
      "maxSegmentsPerRun": 20000,
      "timeBudgetMinutes": 10
    }
  }]
}
```

Every field has a default; omitting `settings` entirely means "all defaults". `excludePatterns` are
globs matched against the URL **path** (`*` stays inside one segment, `**` crosses them).

**`cron` is a standard five-field expression** — `min hour dom month dow` — the same form the schedule
API takes. Six- and seven-field Quartz expressions with a seconds column are **refused when the
knowledge base is saved**, with a 400 naming the source: stored, they would have become a schedule
that never fires while every screen showed the source as scheduled. Omit `cron` for a source that only
runs when someone asks.

#### Every field

`web` — what to crawl:

| Field | Default | What it does |
| --- | --- | --- |
| `startUrl` | required | Where the crawl begins |
| `sameSiteOnly` | `true` | Stay on the seed's site. Turning it off lets links take the crawl anywhere the other limits allow |
| `includeSubdomains` | `false` | Treat `docs.example.com` as the same site as `example.com` |
| `pathPrefix` | `/` | Only paths under this prefix are ingested |
| `maxDepth` | `3` | How many links from the seed |
| `maxPages` | `200` | Pages ingested per run |
| `excludePatterns` | none | Globs matched against the path |
| `requestDelayMs` | `500` | Politeness delay between requests to one host. A `Crawl-delay` in robots.txt wins when it is slower |
| `timeoutSeconds` | `15` | Per-request timeout. The body gets a multiple of it before it is cut off |
| `userAgent` | EDDI's default | Sent on every request, and matched against robots.txt groups |
| `respectRobots` | `true` | Honour robots.txt, its `Crawl-delay` and its `Sitemap` entries |

`settings` — what to do with what was crawled:

| Field | Default | What it does |
| --- | --- | --- |
| `maxContentLength` | `100000` | Characters kept per document after conversion to Markdown. A longer page is truncated, never split across documents |
| `maxBytesPerPage` | `5242880` | Cap on one response body. Must be positive — a non-positive value would read as "no cap" |
| `maxSegmentsPerRun` | `20000` | Hard ceiling on embedded chunks per run: the cost control |
| `costPerThousandSegments` | unset | Optional rate used to report a run's cost in the run history |
| `tombstoneAfterMissedRuns` | `2` | Consecutive complete runs a document may be missing before its vectors go |
| `timeBudgetMinutes` | `10` | Wall-clock ceiling for one run, 1–1440 |

`enabled` (default `true`) is on the source itself: a disabled source keeps its configuration and its
history, loses its schedule, and is **refused** by a manual run with a 409 — rather than accepted and
then recorded as a failure.

**What a run does.** Crawls within the scope, converts each page to Markdown, compares a content hash
against the last successful ingest, and re-embeds only what changed — replacing that document's chunks
rather than adding to them. Pages that disappear from the source lose their vectors after
`tombstoneAfterMissedRuns` consecutive *complete* runs miss them; a run that stopped at a limit
concludes nothing. ETag and Last-Modified from the previous run are sent back, so an unchanged page
costs one 304.

`robots.txt` is honoured by default, including `Crawl-delay` and `Sitemap` discovery. Turn
`respectRobots` off only for a site you own.

**When absence counts as deletion.** Removing a document is the one irreversible thing a run does, so
it happens only when the crawl actually saw the source. A run that stopped at a limit, was cancelled,
or reached nothing at all concludes nothing. "Reached nothing" is deliberate: an unreachable seed, a
connection failure, a 5xx, a 429, and a 401 or 403 are the server saying nothing about its content, and
a robots.txt that disallows everything is the same. A **404 or 410 is the opposite** — the server
saying the page is gone — so a start page that 404s does reconcile, and one dead link on a site that
otherwise answered never blocks reconciliation.

**A page that could not be read is not a page that is gone.** A document behind a 5xx, a 429, a 401 or
a 403, or one whose response could not be parsed, does not count as missing: the run records that it
looked and learned nothing. Without that, the tail of a rate-limited site is deleted after
`tombstoneAfterMissedRuns` runs while every run reports success.

**A crawl that learns nothing definitive concludes nothing.** If a run produces no usable document and
every failure was of the kind above — a site behind a JavaScript challenge or a maintenance page, which
answers 200 for everything — the run reports `tombstoningSkipped` and removes nothing.

**Vectors are replaced by adding first and removing afterwards.** A provider failure or a crash leaves
the previous version of the document retrievable rather than leaving it with no vectors at all, and
chunks record which source ingested them, so two sources of one knowledge base that overlap on a URL
keep their own copies instead of deleting each other's.

**A document is tombstoned only after its vectors are actually gone.** On a store that refuses the
delete, the document stays live and the next run tries again, rather than being marked gone with its
chunks still retrievable.

**One run at a time per source.** A run is claimed before the request is answered, so a second "run
now" while one is in flight gets a 409 rather than a second crawl into the same store. Purging is
refused while a run is in flight, because it would delete the very row that guarantees this. A run
whose process died is reaped — for that source only, so a short-budget source cannot reap the live run
of one configured for hours.

**Renaming the knowledge base clears what its sources have ingested.** The vector store is addressed by
the knowledge base's name while ingestion state is keyed by its id, so a rename moves retrieval to a
new, empty store. Clearing the state makes the next run repopulate it. The chunks under the old name
are left where they are.

**Ingestion schedules are minted by EDDI, not by clients.** A schedule whose metadata declares
`ragIngestion` is refused by the schedule API on create and update, firing one by hand requires EDIT on
the knowledge base it names, and a fire refuses a schedule whose name does not match that metadata.
Without those, anyone who could create a schedule could have the server crawl, re-embed and delete from
a knowledge base they have no access to.

### Ingestion source endpoints

| Method | Path | Access | Purpose |
| ------ | ---- | ------ | ------- |
| `POST` | `/ragstore/rags/{id}/sources/{sourceId}/run?version=N` | EDIT | Start a run (202, or 409 if one is in flight) |
| `POST` | `/ragstore/rags/{id}/sources/{sourceId}/preview?version=N` | EDIT | Crawl and report what would change, embedding nothing. Capped at 2 minutes and a small page count, and at three previews per instance — the rest get 429 with `Retry-After` |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/runs?version=N&limit=20` | VIEW | Run history with counters, cost and errors |
| `DELETE` | `/ragstore/rags/{id}/sources/{sourceId}/documents?version=N` | EDIT | Forget what the source has ingested |

Running needs EDIT rather than VIEW because a published knowledge base grants VIEW to everyone by
design, and a run rewrites what every agent using it retrieves.

A source with a `cron` gets a schedule named `rag-ingestion:{ragConfigId}:{sourceId}`, kept in step with
the configuration whenever the knowledge base is saved and removed when it is deleted. `{sourceId}` is
the source's id, or its name when it has none. A ZIP import writes through the store rather than the
REST layer, so it assigns the ids, validates the sources and creates their schedules itself — without
that, an imported source was addressed by name, and the first save in the Manager re-keyed it, orphaned
its history and re-embedded everything.

### In the Manager

The knowledge-base editor has an **Ingestion Sources** section: add and remove sources, edit the scope
and the limits, and for a source that has been saved once, **Run now**, **Preview**, **Purge state**
and the run history with its counters and errors.

Run and Preview address the source by id and version, so they crawl the **saved** configuration. While
the editor has unsaved changes both are disabled, with a line saying why — otherwise editing a start
URL and pressing Run would silently crawl the old one. A source that has never been saved shows the
same explanation instead of the buttons, because it has no id for the endpoints to address.

### Store support for replacement

Replacing a document's chunks needs `removeAll(Filter)` on the vector store, with a compound filter
(document id and owning source). Verified for `in-memory` and `pgvector`. The other supported stores —
`mongodb-atlas`, `elasticsearch`, `qdrant`, `chroma` — are expected to support it through their
langchain4j drivers but are not covered by these tests. Where a store does not, the run still succeeds
and reports `replaceUnsupported`, meaning re-ingested documents accumulate stale chunks on that
backend.

## Observability

RAG operations write audit traces to conversation memory:

| Memory Key | Content |
|---|---|
| `rag:trace:{taskId}` | Per-KB retrieval metadata (provider, storeType, maxResults, minScore, retrievedCount) |
| `rag:context:{taskId}` | Formatted context string injected into the LLM |
| `rag:httpcall:trace:{taskId}` | httpCall RAG execution metadata (httpCall name, context length) |

These are visible in the conversation memory snapshot and the audit ledger.

## Embedding Providers

| Provider | Default Model | Required Parameters | Notes |
|---|---|---|---|
| `openai` | `text-embedding-3-small` | `apiKey` | Use `${vault:...}` for keys |
| `azure-openai` | `text-embedding-3-small` | `endpoint`, `apiKey`, `deploymentName` | Azure-hosted OpenAI models |
| `ollama` | `nomic-embed-text` | — | `baseUrl` (default: `localhost:11434`) |
| `mistral` | `mistral-embed` | `apiKey` | Mistral AI embedding model |
| `bedrock` | `amazon.titan-embed-text-v2:0` | — | Uses AWS credentials chain; `region` (default: `us-east-1`) |
| `cohere` | `embed-english-v3.0` | `apiKey` | Excellent multilingual support |
| `gemini` | `gemini-embedding-2` | `apiKey` | Google Gemini embeddings |
| `vertex` | `text-embedding-005` | `project` | `location` (default: `us-central1`); uses GCP credentials |

### Asymmetric models: queries and documents are embedded differently

Some embedding models are **asymmetric** — they produce a different vector for the same
text depending on whether it is a document being *stored* or a query being *searched
with*, and the retrieval quality they advertise assumes you tell them which. Google's
Gemini is the clearest example: it exposes `RETRIEVAL_DOCUMENT` and `RETRIEVAL_QUERY` as
distinct task types.

**EDDI handles this for you, and there is nothing to configure.** Ingestion asks for a
`DOCUMENT` model and retrieval asks for a `QUERY` one; the two are cached separately, and
the role travels with the model instance because `EmbeddingStoreContentRetriever` offers
no way to pass a per-call parameter.

The role is only applied to providers that accept one. In langchain4j 1.20.0 that is
**`gemini` and `cohere`**; the other six declare no `INPUT_TYPE` parameter and are handed
the provider's model unchanged. This is not a hard-coded list — EDDI reads each model's
own `supportedParameters()`, so it cannot drift out of date when the dependency is
upgraded. The distinction matters because langchain4j *rejects* an unsupported per-call
parameter rather than ignoring it.

> **Upgrading an existing Gemini knowledge base.** Before this behaviour existed, EDDI
> built one model per knowledge base and used it for both roles — so with Gemini's
> `taskType` defaulting to `RETRIEVAL_DOCUMENT`, queries were embedded as documents.
> Stored vectors were always correct; only the query side was wrong, so **no
> re-ingestion is needed**. Retrieval quality should improve on the next query.

> **A deliberately pinned `taskType` still wins.** Gemini's `taskType` is a build-time
> default that langchain4j consults *only when no role is given* — a role maps
> unconditionally onto `RETRIEVAL_DOCUMENT` / `RETRIEVAL_QUERY`. So if EDDI attached a role
> unconditionally, a `taskType` of `SEMANTIC_SIMILARITY`, `CLASSIFICATION` or `CLUSTERING`
> that you had set on purpose would stop reaching the provider, and everything ingested
> afterwards would land in the same index with a different geometry from what is already
> there. EDDI therefore leaves the role off when you have configured a non-retrieval
> `taskType`, and your setting continues to apply to both sides.
>
> `RETRIEVAL_DOCUMENT` and `RETRIEVAL_QUERY` are **not** treated that way.
> `RETRIEVAL_DOCUMENT` is the default EDDI applies when you configure nothing, and it is
> exactly the value that caused queries to be embedded as documents — writing it out by
> hand must not opt back into the defect. Both values say "this knowledge base is for
> retrieval", which is what the role refines.
>
> This only applies where `taskType` reaches Google at all. `gemini-embedding-2` — the
> default model — does not accept `task_type`; langchain4j sends none and prefixes a
> role-specific instruction to the text instead, so a `taskType` set there was already
> inert and the role is always attached.

## Vector Stores

| Store Type | Required Parameters | Notes |
|---|---|---|
| `in-memory` | — | Ephemeral, for dev/test only |
| `pgvector` | `password` | PostgreSQL + pgvector; `host`, `port`, `database`, `user`, `table`, `dimension` |
| `mongodb-atlas` | `connectionString` | MongoDB Atlas Vector Search; `databaseName`, `collectionName`, `indexName` |
| `elasticsearch` | — | `serverUrl` (default: `localhost:9200`); optional `apiKey` or `userName`+`password`; `indexName` |
| `qdrant` | — | `host` (default: `localhost`), `port` (default: `6334`); optional `apiKey`, `useTls`; `collectionName` |
| `chroma` | — | `baseUrl` (default: `http://localhost:8000`); `collectionName` |

## Status

- ✅ **Phase 8c**: RAG Foundation — config-driven knowledge base retrieval
- ✅ **Phase 8c-0**: httpCall-based RAG (zero infrastructure)
- ✅ **Phase 8c-β**: Persistent vector stores (pgvector)
- ✅ **Phase 8c-γ**: RAG provider expansion (8 embedding models + 6 vector stores)
- ✅ **Phase 8c-M**: Manager UI — RAG editor with full provider parity + document ingestion
- ✅ **REST ingestion endpoint**: `POST /ragstore/rags/{id}/ingest`
- ✅ **Workflow step registration**: `eddi://ai.labs.rag` is a registered lifecycle extension, so a workflow can declare a knowledge-base step and the Manager offers it (before this, Options 1 and 2 below could be saved but not deployed)

## Future Enhancements

- Advanced retrieval: re-ranking, hybrid search, metadata filtering
- ONNX in-process embeddings (air-gapped / edge deployments)
