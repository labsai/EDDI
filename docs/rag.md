# RAG (Retrieval-Augmented Generation)

> **Phase 8c** — Config-driven knowledge base retrieval integrated into the LLM pipeline.

## Overview

EDDI's RAG system is a first-class workflow extension that adds contextual knowledge retrieval to LLM conversations. Knowledge bases are versioned configurations — just like behavior rules or httpCalls — managed via REST API and wired into workflows.

At execution time, the `LlmTask` discovers RAG configurations from the agent's workflow, performs vector similarity search against the user's query, and injects the retrieved context into the LLM system message — all automatically and transparently.

> **Start here:** a knowledge base reaches an agent through **three** configurations, not two. Naming a KB on the LLM task is not enough to bind it — the agent's workflow must also carry an `eddi://ai.labs.rag` step. See [Configuration](#configuration) for all three, and [Troubleshooting](#troubleshooting) if retrieval is silently doing nothing.

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

A knowledge base reaches an agent through **three** configurations. All three are required for
vector RAG (Options 1 and 2 below); only `httpCallRag` (Option 3) works without them.

| # | What | Where | Purpose |
|---|---|---|---|
| 1 | `RagConfiguration` | `/ragstore/rags/{id}` | Defines the KB — embedding provider, vector store, chunking |
| 2 | **Workflow step** `eddi://ai.labs.rag` | the agent's workflow | **Binds** the KB to the agent. This is what retrieval actually discovers |
| 3 | `knowledgeBases` / `enableWorkflowRag` | the LLM task (`langchain.json`) | Selects which bound KBs *this task* retrieves from |

> **The most common failure is configuring 1 and 3 but not 2.** `RagContextProvider` matches
> `knowledgeBases[].name` against RAG configs discovered **from the workflow document**, so with no
> `eddi://ai.labs.rag` step there is nothing to match against. Retrieval then returns nothing at
> all — no context, no `rag:trace:*` entry, no error, and no log line above `DEBUG`. The KB config
> and the task reference can both be perfectly correct and the agent will still answer "I don't
> know". See [Troubleshooting](#troubleshooting).

### 1. RagConfiguration (Knowledge Base)

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

### 2. Workflow Step (binds the KB to the agent)

The agent's workflow must declare the knowledge base as a step. This is the binding that retrieval
discovers — without it, Options 1 and 2 below retrieve nothing.

```json
{
  "workflowSteps" : [ {
    "type" : "eddi://ai.labs.parser",
    "extensions" : { },
    "config" : { }
  }, {
    "type" : "eddi://ai.labs.behavior",
    "extensions" : { },
    "config" : {
      "uri" : "eddi://ai.labs.rules/rulestore/rulesets/{rulesId}?version=1"
    }
  }, {
    "type" : "eddi://ai.labs.rag",
    "extensions" : { },
    "config" : {
      "uri" : "eddi://ai.labs.rag/ragstore/rags/{ragId}?version=1"
    }
  }, {
    "type" : "eddi://ai.labs.llm",
    "extensions" : { },
    "config" : {
      "uri" : "eddi://ai.labs.llm/llmstore/llms/{llmId}?version=1"
    }
  } ]
}
```

Order does not matter for the RAG step — retrieval reads the workflow document rather than running
in pipeline order — but keeping it before the LLM step matches how the rest of the pipeline reads.

Add one step per knowledge base the agent should be able to retrieve from. In a ZIP export the
configuration file is `{ragId}.rag.json`.

The step does no work at conversation time: `RagTask.execute()` is deliberately a no-op, because
retrieval happens inside the LLM task where the user's query is known. The step exists to *declare*
the binding, and `RagTask.configure()` resolves the referenced KB so a broken URI fails when the
workflow is deployed rather than silently returning no context on the first conversation.

> **Requires EDDI with `ai.labs.rag` registered.** `WorkflowStoreClientLibrary` rejects any workflow
> step whose type is not a registered lifecycle extension, so on a build without it a workflow
> containing a RAG step cannot be deployed at all, and the Manager's step chooser never offers one.
> Confirm with `GET /extensionstore/extensions` — if `eddi://ai.labs.rag` is absent, vector RAG
> cannot be wired up on that deployment at all and only `httpCallRag` works end to end.

### 3. LLM Task RAG Configuration

RAG is wired into LLM tasks via four fields on `LlmConfiguration.Task`. Three of them choose what
is retrieved, one per option below — `knowledgeBases` (Option 1), `enableWorkflowRag` with
`ragDefaults` (Option 2), and `httpCallRag` (Option 3).

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

Each reference names a KB **that the workflow binds via an `eddi://ai.labs.rag` step** (see [step 2](#2-workflow-step-binds-the-kb-to-the-agent)) and optionally overrides retrieval
parameters. The `name` must match the `name` field of the `RagConfiguration`, not its id. A name that matches no bound KB is skipped silently.

#### Option 2: Auto-Discovery

```json
{
  "tasks": [{
    "enableWorkflowRag": true,
    "ragDefaults": { "maxResults": 5, "minScore": 0.7 }
  }]
}
```

When `enableWorkflowRag` is `true`, the system retrieves from every KB the workflow binds, with no per-KB list to maintain. It still discovers those KBs from the workflow's `eddi://ai.labs.rag` steps — an agent with no such step has nothing to auto-discover.

#### Option 3: httpCall RAG (Phase 8c-0)

```json
{
  "tasks": [{
    "httpCallRag": "search-api"
  }]
}
```

Zero-infrastructure RAG: execute a named httpCall and inject its response as `## Search Results:` context. The user's input is available as `{userInput}` in httpCall templates. No vector store and no workflow RAG step needed. Both httpCall RAG and vector RAG can be active simultaneously.

> This calls an **external** search API. It cannot be pointed at an EDDI knowledge base: `/ragstore/rags/` exposes configuration and ingestion only, with no retrieval endpoint — see [REST API](#rest-api).

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

> **There is no retrieval endpoint.** `/ragstore/rags/` covers configuration and ingestion only —
> there is no `/query`, `/search` or `/retrieve`, and requests to those return `404`. Retrieval is
> reachable only from inside a conversation, through the LLM task. That also means `httpCallRag`
> cannot be used as a workaround to search an EDDI knowledge base; it needs an external search API.

### Document Ingestion

| Method | Path | Description |
|---|---|---|
| `POST` | `/ragstore/rags/{id}/ingest?version=N&documentName=...` | Ingest a text document (returns 202 + ingestion ID). Also accepts `kbId` — **see the warning below before using it** |
| `GET` | `/ragstore/rags/{id}/ingestion/{ingestionId}/status` | Poll ingestion status |

> **Leave `kbId` unset.** It overrides the key the documents are stored under, and it defaults to the knowledge base's `name`, which is the key **retrieval always uses** — `RagContextProvider` keys the store on `ragConfig.getName()` and has no way to be pointed anywhere else. So passing a `kbId` that is anything other than the KB's exact `name` ingests into a store nothing reads: the call returns `202`, the status goes to `completed`, the documents are really embedded and really stored, and retrieval finds nothing, permanently. Ingestion *sources* are not affected — `IngestionPipeline` keys on the name and cannot diverge.

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

## Troubleshooting

### No context is injected, and there is no error

Symptoms: the model answers as though it had never seen the corpus, the compiled prompt carries no
`## Relevant Context:` block, conversation memory holds no `rag:trace:*` or `rag:context:*` entry,
and the logs show nothing from `RagContextProvider` or `EmbeddingStoreFactory` while other providers
log on every turn.

That combination means **no knowledge base was matched** — but two different causes produce it, and
from the outside they look identical. Either the workflow binds no `eddi://ai.labs.rag` step at all,
or it binds one whose name none of `knowledgeBases[].name` matches. Either way retrieval returns
before doing any work: the trace entry, the store build and the INFO log are all downstream of a
match, so none of them appear.

At `DEBUG` the two causes do separate — `No RAG steps found in workflow` is logged only for the
first — so raise the level before guessing if you can. Otherwise work through it in this order:

| # | Check | How |
|---|---|---|
| 1 | Is `ai.labs.rag` a registered extension? | `GET /extensionstore/extensions`. If absent, this build cannot deploy a RAG step at all — upgrade; only `httpCallRag` works until then |
| 2 | Does the agent's workflow carry an `eddi://ai.labs.rag` step? | Read the workflow config. **This is the usual cause** — see [step 2](#2-workflow-step-binds-the-kb-to-the-agent) |
| 3 | Does `knowledgeBases[].name` match the KB's `name`? | Compare against the `RagConfiguration`. It matches on `name`, not id, and a miss is skipped silently |
| 4 | Is the deployed agent version the one you edited? | Retrieval reads the workflow of the agent version in the conversation, and configs are versioned |
| 5 | Was anything actually ingested — and is it still there? | Poll the ingestion status. On an `in-memory` store, confirm nothing has evicted it since (see [Vector Stores](#vector-stores)) |
| 6 | Did ingestion write where retrieval reads? | If you passed `kbId` to `/ingest`, it must equal the KB's `name` exactly, or the documents are in a store retrieval never opens (see [Document Ingestion](#document-ingestion)) |

Raise `RagContextProvider` to `DEBUG` to see the early return directly:

```properties
quarkus.log.category."ai.labs.eddi.modules.llm.impl.RagContextProvider".level=DEBUG
```

### Context is retrieved but the answer ignores it

Check `rag:context:{taskId}` in conversation memory for what was actually injected. If it ends in a
`[... N further retrieved passage(s) omitted: RAG context limit (X chars) reached ...]` marker, the
block hit `maxRagContextChars` — raise it, or lower `maxResults` or the number of knowledge bases.
If the passages are present but irrelevant, lower `minScore` to widen the search or raise it to
tighten it.

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

## Vector Stores

| Store Type | Required Parameters | Notes |
|---|---|---|
| `in-memory` | — | Ephemeral, for dev/test only — **loses every ingested document** on restart, after 30 minutes without a query, or on any secret rotation (see below) |
| `pgvector` | `password` | PostgreSQL + pgvector; `host`, `port`, `database`, `user`, `table`, `dimension` |
| `mongodb-atlas` | `connectionString` | MongoDB Atlas Vector Search; `databaseName`, `collectionName`, `indexName` |
| `elasticsearch` | — | `serverUrl` (default: `localhost:9200`); optional `apiKey` or `userName`+`password`; `indexName` |
| `qdrant` | — | `host` (default: `localhost`), `port` (default: `6334`); optional `apiKey`, `useTls`; `collectionName` |
| `chroma` | — | `baseUrl` (default: `http://localhost:8000`); `collectionName` |

> **`in-memory` is not a small-corpus option, it is a dev/test option.** For this store the cached
> object *is* the data, and `EmbeddingStoreFactory` holds it in a bounded Caffeine cache — max 50
> stores, `expireAfterAccess` of 30 minutes, and a full invalidation whenever a vault secret or a
> global variable changes. So an in-memory KB silently empties itself after 30 minutes with no
> retrieval, on every restart, and on any credential rotation, and the next query returns no context
> rather than an error. Re-ingesting refills it until the next eviction. Any agent expected to answer
> from a knowledge base tomorrow needs a persistent store. Note that `storeType` still **defaults to**
> `in-memory`, so persistence is opt-in: `pgvector` is the recommended choice, and — with `in-memory`
> — one of the two stores whose document-replacement semantics are verified (see
> [Ingestion Sources](#ingestion-sources)).

## Status

- ✅ **Phase 8c**: RAG Foundation — config-driven knowledge base retrieval
- ✅ **Phase 8c-0**: httpCall-based RAG (zero infrastructure)
- ✅ **Phase 8c-β**: Persistent vector stores (pgvector)
- ✅ **Phase 8c-γ**: RAG provider expansion (8 embedding models + 6 vector stores)
- ✅ **Phase 8c-M**: Manager UI — RAG editor with full provider parity + document ingestion
- ✅ **REST ingestion endpoint**: `POST /ragstore/rags/{id}/ingest`
- ✅ **Workflow step registration**: `eddi://ai.labs.rag` is a registered lifecycle extension, so a workflow can declare a knowledge-base step and the Manager offers it. On a build without it, [Options 1 and 2](#3-llm-task-rag-configuration) can be saved but not deployed, and only `httpCallRag` works end to end — check with `GET /extensionstore/extensions`

## Future Enhancements

- Advanced retrieval: re-ranking, hybrid search, metadata filtering
- ONNX in-process embeddings (air-gapped / edge deployments)
