# RAG (Retrieval-Augmented Generation)

> **In short:** a knowledge base config (`RagConfiguration`) + an `eddi://ai.labs.rag` workflow step + the LLM task's `knowledgeBases` or `enableWorkflowRag`. Config-driven knowledge base retrieval integrated into the LLM pipeline.

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
| `embeddingParameters` | — | Provider-specific params — which ones each provider honours is in [Embedding Providers](#embedding-providers). An endpoint parameter (`baseUrl`, `endpoint`) the provider cannot honour is refused rather than ignored |
| `storeType` | `in-memory` | Vector store (see Vector Stores table below) |
| `storeParameters` | — | Store-specific connection params |
| `storeNamespace` | `id` for every new KB | How the vector store is addressed: `id` (by this KB's id — its own store) or, for a KB created before 6.6, absent/`name` (the 6.5.0 layout, by name). Set by EDDI; see [Knowledge base isolation](#knowledge-base-isolation) |
| `chunkStrategy` | `recursive` | Document chunking strategy. Only `recursive` is implemented; `paragraph` and `sentence` are accepted and rewritten to `recursive` on save, any other value is rejected |
| `chunkSize` | `512` | Chunk size in characters, 1–32768 |
| `chunkOverlap` | `64` | Chunk overlap in characters, at most half of `chunkSize`. Saving a larger value is refused; a stored configuration that predates the check is clamped when it is ingested or imported |
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

When `enableWorkflowRag` is `true`, the system retrieves from every KB the workflow binds, with no per-KB list to maintain. **If `knowledgeBases` is non-empty, `enableWorkflowRag` and `ragDefaults` are ignored** and only the named KBs are searched. It still discovers those KBs from the workflow's `eddi://ai.labs.rag` steps — an agent with no such step has nothing to auto-discover.

#### Option 3: httpCall RAG

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

Retrieved vector-RAG context (Options 1 and 2) is **always** appended to the LLM **system message** under a `## Relevant Context:` heading. `RagContextProvider` returns one formatted block covering every matched knowledge base, and `LlmTask` appends it. There is no per-knowledge-base or per-task switch for the injection point or for the formatting. By default the block is wrapped in a `[retrieved context - source ...]` provenance envelope (about 200 extra characters on top of `maxRagContextChars`); `markRagProvenance: false` on the LLM task turns that off, see [langchain.md](langchain.md). The `## Search Results:` block of `httpCallRag` is wrapped the same way.

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
| `DELETE` | `/ragstore/rags/{id}?version=N` | Delete a KB (`permanent=true` removes the history too) |
| `GET` | `/ragstore/rags/{id}/currentversion` | Current version number |
| `POST` | `/ragstore/rags/{id}/currentversion` | `303` redirect to the latest version |

> **There is no retrieval endpoint.** `/ragstore/rags/` covers configuration and ingestion only —
> there is no `/query`, `/search` or `/retrieve`, and requests to those return `404`. Retrieval is
> reachable only from inside a conversation, through the LLM task. That also means `httpCallRag`
> cannot be used as a workaround to search an EDDI knowledge base; it needs an external search API.

### Document Ingestion

| Method | Path | Description |
|---|---|---|
| `POST` | `/ragstore/rags/{id}/ingest?version=N&documentName=...` | Ingest a text document (returns 202 + ingestion ID). Add `replace=true` to supersede an earlier version of the same document (see below). `kbId` is deprecated — see below |
| `DELETE` | `/ragstore/rags/{id}/documents?version=N&documentName=...` | Remove a document ingested through `/ingest` (see [Deleting a document](#deleting-a-document)) |
| `GET` | `/ragstore/rags/{id}/ingestion/{ingestionId}/status` | Poll ingestion status |
| `GET` | `/ragstore/rags/{id}/vector-space?version=N` | Which embedding model the vector store holds and which one the configuration names (see [Changing the embedding model](#changing-the-embedding-model)) |
| `POST` | `/ragstore/rags/{id}/reembed?version=N` | Remove the vectors and embed every source again, in the model the configuration names now (202) |

> **Documents always go into the knowledge base in the path.** `kbId` no longer selects a store. Up
> to 6.5.0 it did: an editor of one knowledge base could pass another's name as `?kbId=` and write
> into — and with `replace=true` delete from — a store they had no access to. It is still accepted
> when it names the knowledge base in the path (its id, or its `name`, which is what it used to
> default to), so existing clients keep working; any other value is refused with `400` and nothing
> is ingested. Omit it. The `kbId` in the `202` response is the knowledge base's id.

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
  "kbId": "abc123",
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

Status values: `pending` → `processing` → `completed` | `failed: <error message>`. An id that was never issued, whose status has expired (statuses are kept for at most one hour), or that belongs to a different knowledge base than the one in the path, answers 404 with status `unknown`. In a cluster (`eddi.messaging.type=nats`) the status is shared through the `RAG_INGESTION` KV bucket, so the poll may reach any instance, not only the one that accepted the upload. That holds while NATS is reachable: during an outage `SharedKvCache` falls back to node-local values, so a poll on another instance can answer 404 (or an older status) for a valid ingestion. A status written during the outage stays on the instance that wrote it. When that instance next reads the key (a poll of it does), the status is published to the bucket by compare-and-set, and only if no other instance wrote or deleted the key since: otherwise the bucket's newer value wins and the older local one is dropped. Until then other instances keep the older value or 404. Republishing starts a new one-hour bucket TTL (the KV API cannot preserve the original expiry), so after an outage a status can stay readable for up to about twice the TTL after its last update (the writing instance drops its own copy after one hour, so a status is republished only within that hour).

**Re-ingesting a document.** By default this endpoint only adds: ingesting the same `documentName`
twice stores both copies, and retrieval returns both — the old text alongside the new. Pass
`replace=true` to supersede instead:

```bash
curl -X POST "http://localhost:7070/ragstore/rags/abc123/ingest?version=1&documentName=pricing.md&replace=true" \
  -H "Content-Type: text/plain" --data-binary @pricing.md
```

The new chunks are stored first and the previous ones removed afterwards, so a failure part-way
leaves the old version retrievable rather than leaving the document with no vectors. Chunks from
before this option existed are superseded too. `replace=true` needs an explicit `documentName` and
is refused with a `400` without one: every document ingested without a name shares `unnamed`, and
replacing that would delete all of them. On a vector store that cannot delete by metadata, the
ingestion still completes and the status response carries a `warning` saying the previous version is
still retrievable. The Manager's drop zone offers the same thing as a checkbox.

#### Deleting a document

```bash
curl -X DELETE "http://localhost:7070/ragstore/rags/abc123/documents?version=1&documentName=pricing.md"
```

Removes everything ingested through `/ingest` under that `documentName` in this knowledge base — the
same chunks `replace=true` supersedes: every version of the name, including chunks written before
chunks carried an ingestion id, and nothing of another knowledge base sharing the store. It needs
EDIT on the knowledge base, like ingesting, and it is synchronous.

| Status | Meaning |
| --- | --- |
| `200` `{"status": "deleted", ...}` | The document's chunks are gone. A name nothing was ingested under is also `200`: there is nothing left to remove |
| `200` with `warning` | The vector store cannot delete by metadata, so nothing was removed and the text is still retrievable (`status: "not_deleted"`) |
| `400` | `documentName` is missing, blank or `unnamed` — every document ingested without a name shares `unnamed`, and deleting that would delete all of them |
| `409` | An [ingestion source](#ingestion-sources) of this knowledge base holds a document by that name (an uploaded file's name or id, or a page a crawl has ingested), or a replacement of the same name is still embedding after 30 seconds |
| `503` | The vector store (or the ingestion state, while checking the sources) failed. Nothing may have been removed; retry |

**A delete and a replacement of one name never interleave.** Both take the same per-document lock, so
a delete waits for a replacement in flight and then removes its chunks too, rather than landing
between the replacement's store and its cleanup. As with replacements, the lock is per instance. An
ingestion *without* `replace` takes no lock, so one still in flight when the delete answers can store
its chunks afterwards.

**What a source ingested is removed through the source.** Its chunks carry `sourceName` and a
`sourceKey`, never the `source` key this endpoint matches on, so a delete could not touch them — and
answering "deleted" while the source's text stays retrievable would be wrong. The endpoint therefore
refuses a name a source holds with `409` and the source's id: delete an uploaded file through
[its endpoint](#file-endpoints), or exclude a crawled page and run the source. The check is by name,
so a document pushed through `/ingest` under the same name as a source's file cannot be deleted here
until that file is gone.

For documents that change over time, an [upload source](#uploaded-files-type-upload) is usually the
better fit — it replaces by file name automatically and keeps the files, so a re-embed never needs
the originals again.

### Changing the embedding model

Vectors made by one embedding model mean nothing next to vectors of another: a search over a mixture
ranks by meaningless scores and still answers. So a knowledge base on an external vector store
(`pgvector`, `mongodb-atlas`, `elasticsearch`, `qdrant`, `chroma`) **knows which embedding space its
store holds** and refuses to work in any other.

> **Upgrading to the version with this check.** Nothing has to be done.
>
> - Older configurations keep importing, syncing and upgrading: values an interactive save now refuses
>   (an unknown store type, a host with a port, a blank name, `minScore` outside 0..1, `maxResults` below 1)
>   are brought into range or kept, with a WARN naming the knowledge base and the field. Only
>   `POST`/`PUT /ragstore/rags` refuse them.
> - Every existing knowledge base **adopts the space it is configured for on its first use** after the
>   upgrade (logged once). That cannot be checked against the vectors already stored: one whose model was
>   changed before the upgrade stays as wrong as it was, and the next change is caught.
> - Two knowledge bases that **share a table or collection** each get their own record, so they may hold
>   different models of the same size. A pgvector table keeps one vector size: a model of another size is
>   refused for the knowledge base that tries it, with the real column size named, and re-embedding it
>   is refused (`SHARED_TABLE_SIZE`, naming the others) until it has a table of its own. Two same-named
>   knowledge bases in the 6.5.0 name layout share their chunks outright, so neither can be re-embedded
>   (`SHARED_CHUNKS`) until they are given separate tables.

**What is recorded — two things, because two different things are true.**

- *Which model made a knowledge base's vectors*: the embedding provider, the model (or Azure
  `deploymentName`) and Gemini's `taskType`. That is a fact about the **knowledge base**: retrieval and
  deletion are filtered by the chunk tag, so two knowledge bases that share one table can hold different
  models without touching each other. The record is keyed by the store *location* (store type, server,
  database, table or collection) **and the chunk tag** — the knowledge base's id, or in the 6.5.0 name
  layout its name, so two same-named knowledge bases there share one record, by definition.
- *The vector size*: configured through `dimensions`/`outputDimensionality`, or measured from the first
  embedding an ingestion makes. That is a fact about the **table**: its column has one size. It is
  recorded per location, and for pgvector checked against the table's real column (`atttypmod`), which
  nothing can contradict — a legacy `vector(1536)` table with `dimension: 3072` configured is refused with
  the real size named.

Credentials and endpoints are not part of either: moving a model to another host does not change a
vector. Both live in EDDI's own database (next to the ingestion state), under a digest. It is the same
mechanism for every store type; a pgvector-only metadata table was rejected because no other store has an
equivalent place, and a record kept two ways has to be kept in step. The cost: a vector store wiped or
restored behind EDDI's back keeps its record until the knowledge base is re-embedded, which resets it.

**What is refused.** When the configured space differs from the recorded one, search, `/ingest`, and
every source run answer with a message that names both spaces and the way out. The REST answer is
`409` with `"code": "VECTOR_SPACE_MISMATCH"`; at retrieval time the agent gets no context from that
knowledge base (a `rag:trace:*` entry says why) rather than context from the wrong space. An ingestion
status polls as `failed: <the same message>`. The vector size is checked too, before anything is
written: the first embedding of a run is compared with the record and, for pgvector, with
`storeParameters.dimension` (default `1536`), so a model of another size is refused with the parameter
to change instead of a driver error from the first `INSERT`. A run also **stops** at the first
rejected credential, unknown model or space mismatch instead of trying every remaining document.

**A knowledge base from before this check** has no record. It adopts the configured space on its first
use, under its own key (logged once) — so one knowledge base's adoption never tells another to
re-embed. That cannot be verified — nothing says what the existing vectors were made with — so a
knowledge base whose model was changed before the upgrade is as wrong as it was; it keeps working, and
the next change is caught. Nothing is adopted, judged or reset while a `${vars:…}` reference in the
embedding settings cannot be resolved: the call is refused (retryable) instead, so a record never holds
the reference's text.

**Visibility.** A refusal repeats on every turn of every agent that uses the knowledge base. It is
counted in `eddi_rag_vector_space_refused_total{reason}` (see [metrics](metrics.md)), written to the log
once a minute per knowledge base without a stack trace, and put into `rag:trace:*` as the same
client-safe sentence the REST answers carry — never the raw exception text.

**Saving a changed model.** `PUT /ragstore/rags/{id}` still saves, because the save is how a wrong
model is fixed and a knowledge base that holds nothing yet loses nothing by it. When the new version
changes the embedding space while keeping the store location, the `200` carries a body
`{"warnings": [...], "code": "EMBEDDING_SPACE_CHANGED"}` next to the usual `Location` header, naming
the old and the new model. The refusal above is what then makes the change safe. Moving the vectors
elsewhere (another table, collection or server) starts empty and warns about nothing.

A node that still holds a store for a table another node dropped reads it as empty rather than failing
with "relation does not exist", and rebuilds it on the next write. How long another node can be behind is
under **In a cluster** below.

**Re-embedding.** `GET /ragstore/rags/{id}/vector-space?version=N` shows the recorded and the
configured space and whether they match. `POST /ragstore/rags/{id}/reembed?version=N` (EDIT) then:

0. refuses, with `409` and nothing changed, what cannot be done safely:
   - the version asked for is not the latest (`NOT_LATEST_VERSION`, because agents use the latest and the
     record would describe the wrong model);
   - an embedding setting or a store parameter names a variable that cannot be resolved now
     (`EMBEDDING_SETTINGS_UNRESOLVED`, retryable);
   - another knowledge base keeps its chunks under the same tag in the same store (`SHARED_CHUNKS`, the
     6.5.0 name layout: same name, same table — deleting by the tag would take its chunks too; give them
     separate tables and re-embed each);
   - the **embedding probe** fails (`EMBEDDING_PROBE_FAILED`): one embedding call is made with the new
     settings before anything is touched. It proves the credentials and the model work — a re-embed that
     cannot embed must not delete — and measures the size the new vectors will have, which every check
     below uses;
   - the new size does not fit where the vectors go, **per store type**:
     - *pgvector*: the table must be an EDDI pgvector table (`NOT_A_PGVECTOR_TABLE`);
       `storeParameters.dimension` must be the new size, because the table is recreated from it
       (`DIMENSION_SETTING_MISMATCH` names the value to set); and a table whose column has another size is
       dropped and recreated, which is refused while other knowledge bases keep rows in it
       (`SHARED_TABLE_SIZE`);
     - *Qdrant, Chroma, Elasticsearch, MongoDB Atlas*: a collection keeps its vector size and EDDI cannot
       recreate it. If a size is recorded for it and differs, the re-embed is refused —
       `SHARED_TABLE_SIZE` while other knowledge bases keep vectors there, `STORE_CANNOT_CHANGE_SIZE` when
       this one is alone, which says to recreate the collection with the new size (or point
       `collectionName`/`indexName` at a new one) and re-embed. Most collections have no recorded size
       (nothing records one for vectors written before sizes were tracked), so EDDI then **searches the
       collection once with the probe vector**: a collection of another size rejects it, and the re-embed is
       refused (`STORE_PROBE_FAILED`) before anything is deleted. The same answer comes when the collection
       does not exist yet (run the sources instead) or the store cannot be reached (retry); the log has the
       store's own words;
     - *in-memory*: nothing to decide, the store is replaced;
   - the other knowledge bases cannot all be checked (`503` `KNOWLEDGE_BASE_SCAN_INCOMPLETE`): one could not
     be read, or its store parameters could not be resolved, so it cannot be ruled out that it shares the
     store. The log names it. Only a knowledge base that can be read can be classified, so one unreadable
     document anywhere blocks re-embedding until it is fixed — deliberately: a sharer that is not seen is
     the one whose data is lost;
1. claims every source, so nothing runs while the vectors go — a run in flight anywhere answers `409`
   and changes nothing;
2. **marks the knowledge base "rebuilding"**, durably and for every node: from here search, `/ingest` and
   every source run are refused (`409` `VECTOR_SPACE_MISMATCH`, "being re-embedded, or a re-embed of it did
   not finish") until step 5 replaces the mark. A write anywhere in the cluster sees the mark at once (it
   reads the record from the database, not from the node's cache); a search on another node within 30
   seconds. A re-embed that dies or fails part-way **leaves the mark**: the knowledge base is refused, not
   answered from half its vectors, until it is re-embedded again. A refusal before anything was removed
   takes the mark back;
3. **forgets every source's content hashes**, so that after a crash at any later point the next run —
   scheduled or manual — embeds everything again instead of reporting "unchanged" over an empty store;
4. removes every vector of this knowledge base:
   - *pgvector, same vector size*: its rows are deleted by its chunk tag; a table that is then empty is
     dropped under an exclusive lock (and recreated by the next write); one that is busy or still holds
     other knowledge bases' rows is left as it is, which is fine — its size is already the new one;
   - *pgvector, another vector size*: the **whole table is dropped in one transaction**, under an exclusive
     lock, and only while every row in it carries this knowledge base's tag; the next write recreates it
     for the new size. It waits up to 5 seconds for searches and writes in flight. If the table stays busy
     (`409` `TABLE_BUSY`) or rows of another knowledge base appeared (`409` `SHARED_TABLE_SIZE`), **nothing is
     removed** and the record is as it was; re-embed again later;
   - *another store*: the chunks are deleted by tag; the collection stays. A store that cannot delete by
     metadata answers `501` and nothing is removed — empty it by hand first;
   - *in-memory*: the store is dropped.

   A table that is not an EDDI pgvector table is never touched. The answer's `tableRecreated` is `true`
   when the pgvector table was dropped (or did not exist), `false` when only this knowledge base's rows
   were deleted;
5. records the configured space as this knowledge base's (which clears the mark), and forgets the
   location's vector size when nothing else keeps vectors there;
6. forgets each source's ingestion state and starts an ordinary run of every enabled source.

The whole contract, for a client (the RAG screens of the Manager read this table):

| Status and `code` | When | What is left |
| --- | --- | --- |
| `202` | Vectors removed, runs started. Body below | Record = the configured space; runs filling it |
| `409` `NOT_LATEST_VERSION` | `version` is not the latest. `latestVersion` says which is | Unchanged |
| `409` `EMBEDDING_SETTINGS_UNRESOLVED` | An embedding setting or a store parameter names a variable that cannot be resolved now; retry | Unchanged |
| `409` `SHARED_CHUNKS` | Another knowledge base keeps its chunks under the same tag in the same store (6.5.0 name layout). `knowledgeBases` lists them | Unchanged |
| `409` `EMBEDDING_PROBE_FAILED` | The embedding call with the new settings failed. The error says why (credentials, model, rate limit) | Unchanged |
| `409` `NOT_A_PGVECTOR_TABLE` | `storeParameters.table` names a table that is not an EDDI pgvector table | Unchanged |
| `409` `DIMENSION_SETTING_MISMATCH` | pgvector: `storeParameters.dimension` is not the size the new model returns | Unchanged |
| `409` `SHARED_TABLE_SIZE` | The vector size would change in a table or collection other knowledge bases keep vectors in. `knowledgeBases` lists them where known | Unchanged (when found under the lock, the content hashes are already forgotten) |
| `409` `STORE_CANNOT_CHANGE_SIZE` | A collection with a recorded size of another size, used by this knowledge base alone; the error says what to do | Unchanged |
| `409` `STORE_PROBE_FAILED` | The collection rejected a search with a vector of the new size, does not exist, or could not be reached | Unchanged |
| `409` `TABLE_BUSY` | pgvector, size change: the table stayed in use past the lock timeout | Vectors and record unchanged; content hashes forgotten |
| `409` (no `code`, `sourceId`) | A run is in flight for that source | Unchanged |
| `501` | The store cannot delete by metadata | Vectors and record unchanged; content hashes forgotten |
| `503` `KNOWLEDGE_BASE_SCAN_INCOMPLETE` | Another knowledge base could not be read or resolved, so sharers cannot be ruled out. The log names it | Unchanged |
| `503` `REEMBED_NOT_STARTED` | The re-embed could not be prepared (the state store or the vector store failed before anything was removed); retry | Vectors unchanged |
| `503` `REEMBED_INCOMPLETE` | The removal failed part-way | Some vectors may be gone; **marked "rebuilding"** (refused everywhere) and content hashes forgotten. Re-embed again |

The `202` body: `{"status": "started", "kbId", "sources": [{"sourceId", "runId" or null, "note" or null}],
"notReEmbedded": [text], "tableRecreated": true | false | null (not pgvector), "warnings": []}` — `warnings` is
always present and today always empty: a situation that would need one is refused instead. The `409` and
`503` bodies are `{"error": text, "code": …}`; a refused search, ingest or run uses `code: VECTOR_SPACE_MISMATCH`.
`GET …/vector-space` answers `rebuilding: true` while the mark is set.

**What counts as "the same place".** Two knowledge bases share a store when their locations are equal after
global variables are resolved the way the store factory resolves them (`${vars:host}` and the host it stands
for are one place); unresolvable variables refuse the operation instead of guessing. Aliases the comparison
cannot see are **not** recognised: `localhost` and `127.0.0.1`, two DNS names of one server, a vault
reference and the secret it holds, a connection string written two ways. Where such aliases exist, give each
knowledge base a table or collection of its own — which is the safe setup anyway.

**In a cluster.** The records and the "rebuilding" mark live in EDDI's shared database. Each node caches a
record for up to 30 seconds for **searches** — so for that long after a model change or a re-embed on another
node a search there may still be answered under the old record (and, during a re-embed, from a store being
emptied and refilled), or refused under it. **Writes never use the cache**: a source run reads the record
from the database once when it starts, and `POST /ingest` reads it before the store is built and again just
before the vectors are written, so no node writes into a space another node has just changed or marked. The
column size read from a pgvector table is remembered for up to a minute (or until the store is rebuilt or
its table dropped); a stale value there is fail-safe, because PostgreSQL itself refuses a vector of the wrong
size. Sources are claimed in the shared state store, so concurrent runs and a concurrent second re-embed (on
any node) are refused with `409` and change nothing.

**Cost per call.** Steady state, the check adds no query to a search or a document: per knowledge base and
node, two state-store reads every 30 seconds (the model and the size record), and for pgvector three catalogue
queries a minute (table, layout, column size). A write adds one state-store read per source run, and two per
`POST /ingest` call. A search of a pgvector knowledge base whose table does not exist yet costs one catalogue
query (the table is not created by a read). A re-embed reads every knowledge base's current configuration once
(to find the ones sharing its store) and, for pgvector, scans the table once for other knowledge bases' rows.

The `202` answer has, per source, the `runId` to follow in
`GET /ragstore/rags/{id}/sources/{sourceId}/runs`, and a list `notReEmbedded`. **Documents sent
through `POST /ingest` cannot be rebuilt**: EDDI embeds them and keeps no copy, so they are removed
with the old vectors and have to be sent again. Uploaded files are kept and web pages are fetched
again, so those sources come back by themselves — which is also why an upload source is the better home
for text that must survive a model change. An in-memory knowledge base is not tracked: its store is
replaced when its embedding configuration changes, as described under
[Vector Stores](#vector-stores).

### Knowledge base isolation

Every knowledge base has a vector store of its own, addressed by its **id** — the id in
`/ragstore/rags/{id}`, which is the same for every version and unique. Ingestion through
`/ingest`, ingestion sources and retrieval all address the store the same way, and nothing a
request carries can point them at another knowledge base's store.

| | `storeNamespace: "id"` (every KB created, duplicated or imported from 6.6 on) | absent or `"name"` (a KB created by 6.5.0 or earlier) |
| --- | --- | --- |
| Default location | `eddi_kbid_<id>` (table, collection or index) | Derived from the `name`, exactly as 6.5.0 did: pgvector `eddi_kb_<name, sanitised>`, Atlas `eddi_kb_<name>`, Elasticsearch/Qdrant/Chroma `eddi_kb_<name, sanitised>` |
| Chunks are tagged | `kbId=<id>` | `kbId=<name>` (what 6.5.0 wrote) |
| Retrieval | Filtered to `kbId=<id>`: the KB sees only its own chunks, even in a table it shares (not on an Atlas default collection — see the note below) | Not filtered — chunks written by 6.5.0 carry no id |
| Renaming | Changes nothing about the store | Moves the KB to `"id"` (see below) |
| Same name as another KB | Harmless | Two such KBs share one store — as they always did. Migrate them |

**Upgrading from 6.5.0.** Nothing has to be done for a knowledge base to keep working: one without
`storeNamespace` keeps reading and writing the table, collection or index it always has, and its
pgvector table is not touched. What it does not get is isolation from another *pre-existing*
knowledge base with the same name, which in 6.5.0 shared its store. To migrate one, save it with
`"storeNamespace": "id"` (`PUT /ragstore/rags/{id}?version=N`). That points it at a new, empty
`eddi_kbid_<id>` location; EDDI clears its sources' ingestion state so their next run re-ingests
everything, and documents ingested through `/ingest` have to be ingested again. The old location is
left in place — drop the table or collection by hand once nothing uses it. Renaming a 6.5.0 knowledge
base does the same migration automatically: in 6.5.0 a rename already moved it to a new, empty store —
the one named after its new name, which could be another knowledge base's. Removing the explicit
`table`/`collectionName`/`indexName` of a 6.5.0 knowledge base migrates it the same way, since its default
location would otherwise be whatever its (possibly changed) name maps to — and so does changing its
`storeType`, or the server it connects to (`host`, `port`, `database`, `databaseName`, `connectionString`,
`serverUrl`, `baseUrl` or `tenantName`): there its name maps to that server's default store, which can be
another 6.5.0 knowledge base's of the same name. A knowledge base with an
explicit `table`/`collectionName`/`indexName` stays in the same table when it is switched: its old
chunks are no longer tagged with the id retrieval filters on, so they stop being returned (and stay
in the table); sources re-ingest and re-tag everything, `/ingest` documents have to be ingested again.
Importing an archive over a knowledge base you already have never changes its layout — the archive's
`storeNamespace` is ignored for an update.

Rules enforced when a knowledge base is saved:

- A new knowledge base always gets `"id"`. Asking for `"name"` on create is refused with `400`; a
  duplicate or an import of a 6.5.0 knowledge base gets `"id"` too, so a copy no longer shares — and
  overwrites — the original's documents.
- An update that omits `storeNamespace` keeps the stored value, so a client that does not know the
  field cannot move a store by saving. Switching from `"id"` back to `"name"` is refused.
- An explicit location (`storeParameters.table`, `collectionName` for Atlas/Qdrant/Chroma, `indexName`
  for Elasticsearch) may not start with `eddi_kb` — that prefix is where EDDI's own default locations
  live; a schema-qualified `public.eddi_kb_x` or a differently-cased spelling counts too — and may name a
  location another knowledge base already uses only if you may edit that knowledge base too (`public.t`
  and `t` are the same pgvector table). A pgvector `table` must be a plain identifier, optionally
  qualified with a schema (letters, digits, `_`, `$`; no quotes, spaces or punctuation), because the
  store puts it into SQL as it is. An Elasticsearch `indexName` must name one index (lower-case letters,
  digits, `_`, `-`, `.`, not starting with `-`/`_`/`+`): Elasticsearch reads it as an index expression, so
  a wildcard (`*`), a list (`a,b`) or an exclusion would search indexes the check never compared. If the
  deployment holds more knowledge bases than the collision check can scan (20,000), an explicit location is
  refused rather than saved unchecked. So is a save during which another knowledge base cannot be read: it
  may be the one that owns the location, so the save fails with a server error and the server log names it.
  Try again, or repair or delete that knowledge base. The location must be written out: a `${vars:...}` or `${vault:...}` reference there is refused when saved (global variables are editable by any editor, so a reference could later resolve to another knowledge base's table). **Upgrading:** a knowledge base stored with such a reference as its location stops working — ingestion and retrieval report that the location must be written out — until it is saved once with the literal name. It is refused whatever the reference currently resolves to, because changing the variable later would re-point the knowledge base. Two knowledge bases in the `"id"` layout that share a table still see only their
  own chunks. These checks run when the value is new or changed; a stored value is never refused on a
  save that leaves it alone. The comparison is by store type and name only (not host), so it can ask for
  edit rights on a knowledge base that is actually on another server.

> **MongoDB Atlas.** Atlas Vector Search can only filter on fields its search index declares. For a
> knowledge base in the `"id"` layout **that sets an explicit `collectionName`** (a collection it may
> share), declare `metadata.kbId` as a `filter` field in the collection's vector index, or retrieval
> fails with an index error (reported in the `rag:trace` entry). On the default `eddi_kbid_<id>`
> collection nothing is filtered, because nothing else can be in it.

## Ingestion Sources

A knowledge base can pull its own documents instead of being fed one at a time. Sources live on the
knowledge base (`sources[]` on the RAG configuration), because the vector store is keyed by the
knowledge base — a source that named its target by string could, and in an earlier draft did, write to
one table while retrieval read another.

There are two kinds, set by `type`:

| `type` | Where documents come from | Config block |
| --- | --- | --- |
| `web` (default) | A crawl of a website | `web` |
| `upload` | Files an operator uploaded, held by EDDI | `upload` (optional) |

Everything that is not about *fetching* is the same for both: `settings`, `cron`, run history,
preview, purge, and the deletion rules below.

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

**Three fields have no default and are rejected when missing:** `name`, the `web` block, and its
`startUrl`. Everything else may be omitted — `type` defaults to `web` (the other type is [`upload`](#uploaded-files-type-upload)),
`id` is generated and then never changes, and omitting `settings` entirely means "all defaults". Two
optional fields are simply absent rather than defaulted: without `cron` the source runs only when
somebody asks, and without `costPerThousandSegments` a run reports no dollar figure.
`excludePatterns` are globs matched against the URL **path** (`*` stays inside one segment, `**`
crosses them). Links longer than 8,192 characters are not followed, and sitemap entries longer than the
protocol's 2,048 characters are ignored.

**`cron` is a standard five-field expression** — `min hour dom month dow` — the same form the schedule
API takes. Six- and seven-field Quartz expressions with a seconds column are **refused when the
knowledge base is saved**, with a 400 naming the source: stored, they would have become a schedule
that never fires while every screen showed the source as scheduled. So is an expression that parses
but can never match a date (`0 0 30 2 *`). The same check runs when a knowledge base arrives in an
import archive, which used to be the way around it. Omit `cron` for a source that only runs when
someone asks.

The cron is read in **UTC**, which is written onto the schedule rather than left to the deployment's
`eddi.schedule.default-timezone`, so the first run and every later one are computed the same way. It
is held to the deployment's `eddi.schedule.min-interval-seconds` like every other schedule: a cron
that fires more often is refused with a 400 naming the source.

**A scheduled run is started by its fire, not run inside it.** The fire claims the run and hands it to
its own worker, exactly as **Run now** does, and returns. The scheduler cancels a fire it has waited
a lease for (five minutes by default) while a crawl's default budget is ten, so a crawl run inside the
fire was interrupted mid-run and never reconciled a deletion. The fire log therefore records that the
run started; if the run later fails, a second `FAILED` entry for the same fire says so (it does not
count towards the schedule's retries or dead-lettering — see [scheduling.md](scheduling.md#fire-logging)).
What the run did in detail is in the source's run history. Runs in flight when an instance shuts
down gracefully are closed as `CANCELLED` rather than left to be reaped.

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
| `excludePatterns` | empty | Globs matched against the path |
| `sitemapUrls` | empty | Sitemaps to discover pages from, besides any robots.txt lists — see [Sitemaps](#sitemaps). At most 20 |
| `requestDelayMs` | `500` | Politeness delay between requests to one host. A `Crawl-delay` in robots.txt wins when it is slower |
| `timeoutSeconds` | `15` | Per-request timeout. The body gets a multiple of it before it is cut off |
| `userAgent` | `EDDI-Crawler/1.0 (+https://eddi.technology)` | Sent on every request, and matched against robots.txt groups |
| `respectRobots` | `true` | Honour robots.txt, its `Crawl-delay` and its `Sitemap` entries |

`settings` — what to do with what was crawled:

| Field | Default | What it does |
| --- | --- | --- |
| `maxContentLength` | `100000` | Characters kept per document after conversion to Markdown. A longer page is truncated, never split across documents |
| `visibleTextOnly` | upload: `true`, crawl: `false` | Leave out text the document hides from a reader — see [Hidden text](#hidden-text) |
| `maxBytesPerPage` | `5242880` | Cap on one response body. Must be positive — a non-positive value would read as "no cap" |
| `maxSegmentsPerRun` | `20000` | Hard ceiling on embedded chunks per run: the cost control. Checked before a document is embedded: a document that would cross it waits for the next run, and one that alone needs more chunks than the ceiling is reported as failed rather than embedded in part |
| `costPerThousandSegments` | unset | Optional rate used to report a run's cost in the run history |
| `tombstoneAfterMissedRuns` | `2` | Consecutive complete runs a document may be missing before its vectors go |
| `timeBudgetMinutes` | `10` | Wall-clock ceiling for one run, 1–1440 |

`enabled` (default `true`) is on the source itself: a disabled source keeps its configuration and its
history, loses its schedule, and is **refused** by a manual run with a 409 — rather than accepted and
then recorded as a failure.

**What a run does.** Crawls within the scope, converts each page to Markdown — dropping text no reader can
ever see (HTML comments, text of 1px or less, transparent text, and text the same colour as a background
set explicitly on it or an ancestor), a common carrier for prompt injection. Content a page hides until
script reveals it — accordions, tab panels, `display:none`/`hidden`/`opacity:0` sections, screen-reader
text — is kept. It then compares a content hash
against the last successful ingest, and re-embeds only what changed — replacing that document's chunks
rather than adding to them. Pages that disappear from the source lose their vectors after
`tombstoneAfterMissedRuns` consecutive *complete* runs miss them; a run that stopped at a limit
concludes nothing.

**Conditional requests are made only where a 304 hides nothing.** ETag and Last-Modified from the
previous run are sent back for a page at `maxDepth`, whose links the crawl would not follow anyway, so
such a page that has not changed costs one 304. A page above that depth is downloaded in full,
because a 304 has no body and so no links: its children were never reached, a crawl that otherwise
covered the site called them missing, and a couple of runs later every page below an unchanged parent
had lost its vectors. Whether a downloaded page is re-embedded is still decided by its content hash —
an unchanged page costs a download, never an embedding — and the validators it came with replace the
stored ones, so a server that rotates its ETag without changing the text is revalidated with the ETag
it issued last.

**A page is stored under the URL that served it.** A `<link rel="canonical">` is honoured as
de-duplication, not as identity: a page naming another page on the same host (and inside the scope)
as canonical defers to it — that page is fetched and stored under its own URL, with its own content —
and is stored itself only if the page it names produced nothing. The links of a deferring page are
still followed, so the second page of a listing that names the first as canonical still leads to the
entries it lists. (Taking the canonical as the identity let any page store its content under another
page's id.)

`robots.txt` is honoured by default, including `Crawl-delay` and `Sitemap` discovery. When robots.txt
names no sitemap — or is not read, because `respectRobots` is off — the conventional `/sitemap.xml`
on the seed's host is tried instead, unless robots.txt disallows it. A sitemap index is followed to
the sitemaps it lists; every sitemap read counts against the same cap of 20. Turn `respectRobots` off
only for a site you own.

Pages listed in a sitemap are queued at depth 0, so `maxDepth` does not narrow them: a source that
stayed under `maxPages` by depth alone can reach the page limit once its site's sitemap is read — and
a crawl that stops at a limit reconciles no deletions. Such a crawl logs a warning naming the sitemap;
raise `maxPages`, or narrow the scope with `pathPrefix` or `excludePatterns`.

#### Sitemaps

A sitemap lists a site's pages, so a crawl finds pages nothing links to and does not depend on link
structure. Before the first page is fetched, a run reads — in this order, each at most once:

1. the sitemaps in `web.sitemapUrls`, for a site that publishes one without listing it in robots.txt
   (and for a crawl with `respectRobots` off, which reads no robots.txt at all);
2. every `Sitemap:` line in robots.txt — a relative one is resolved against robots.txt;
3. only when neither gave anything, the conventional `/sitemap.xml`, at the cost of one 404 where
   there is none.

Every form the [sitemap protocol](https://www.sitemaps.org/protocol.html) allows is read:

| Form | What is taken |
| --- | --- |
| `<urlset>` | each `url/loc`. The `loc` of the image, video and news extensions sits under its own element and is **not** a page; `xhtml:link` hreflang alternates are not followed either — the scope decides which languages are crawled |
| `<sitemapindex>` | each `sitemap/loc` **on the index's own host**, read as a further sitemap — indexes of indexes included. A child on another host is skipped, as the protocol requires, so a site cannot point the crawler at arbitrary hosts; configured and robots.txt sitemaps may be on any host |
| RSS 2.0 / Atom | each `item/link`, or each `entry/link` whose `rel` is absent or `alternate` |
| Plain text | one URL per line |
| gzip | any of the above compressed (`.xml.gz`), recognised by its content rather than its name or Content-Type |

Namespace prefixes (`<sm:urlset>`), entities, CDATA, surrounding whitespace, a byte-order mark and
UTF-16 are all handled; only absolute `http(s)` URLs are taken. What a sitemap lists is held to the
same scope as a linked page — `pathPrefix`, `sameSiteOnly` and `excludePatterns` all apply — because a
sitemap is written by the site, not by the operator.

Bounds: 20 sitemaps per run, indexes and their children included; 5,000 page URLs across all of them;
1 MB per sitemap as fetched and 16 MB once decompressed, so a small gzip body cannot inflate without
limit. `maxPages` still decides how many pages are ingested.

A run that hit one of these bounds — or found a sitemap it could not read (a 5xx, 429, 401 or 403,
a transport error, a body that would not parse) or that arrived cut short (past the 1 MB fetch cap,
or an XML sitemap that does not end with its closing root tag — trailing comments, processing
instructions and a self-closing root are fine) — **concludes nothing about deletions**, like a run
that stopped at a limit: the unread part may list pages the crawl never queued. A sitemap answering
404 is not such a case; that is a definite "no sitemap here".

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
now" while one is in flight gets a 409 rather than a second crawl into the same store. A purge takes
the same claim, so it is refused with a 409 while a run is in flight — decided by the claim itself,
not by a check made first, which let a run start between the check and the purge. A run whose process
died is reaped — for that source only, so a short-budget source cannot reap the live run of one
configured for hours.

**A run that loses its source stops.** If the source's state is cleared under a run — the source was
removed, or (in the 6.5.0 name layout) the knowledge base renamed — or the run is reaped, it notices before its next embedding
and stops, instead of carrying on to the end of its crawl writing chunks and state rows for a source
that has just been cleared. When it has stopped, what it wrote in the meantime is settled: a removed
source's content is removed again, and a renamed knowledge base's state is cleared again. The rename
frees the source, so a new run may already hold it by then and may have read a row the old run wrote
as "unchanged"; the state cannot be purged from under that run, so every document's content hash and
validators are forgotten instead, and the next run embeds each page into the store the new name
addresses.

**A purge forgets what a source ingested, and later complete runs remove what it no longer has.**
Chunks stay retrievable after a purge, so there is no gap while the next run re-embeds everything it
finds. A document that had disappeared before the purge is never found again, and with its state gone
nothing would reconcile it, so the first run after a purge (a run that starts from no state at all)
leaves a marker in the state. The marker is missed by every complete run, exactly like a vanished
page, and once it has been missed `tombstoneAfterMissedRuns` times a run removes every chunk of the
source that no run since the purge wrote. A page only briefly absent after the purge therefore gets
the same grace as any other: once it is back it is re-embedded and survives. The sweep runs only on a
complete run that read every document it found: a document the server would not serve (a timeout, a
5xx, a 429, a 401 or 403) or that failed to embed still has only its old chunks, so such a run leaves
them alone and the next run without one sweeps. A failure that answered the question does not hold it
back — a 404 or 410 says the page is gone, and an uploaded file with no readable text has had its old
version retired already — so one dead link does not keep every orphan retrievable.

A run counts as dead once it has been in flight for its `timeBudgetMinutes` plus 15 minutes — the
budget it was **started** under, recorded as its deadline when the run is claimed, so lowering a
source's budget while it runs cannot have the live run declared dead. It is reaped at that point by whatever touches the source next — a run starting, **reading the run history,
or a purge or file delete** — and shows as `FAILED` with "Run abandoned". Only a run starting used to
reap, so on a source with no cron a dead run read as `RUNNING`, and refused purges and file deletes
with a 409, until someone started another. Before that threshold a crashed run is indistinguishable
from a live one on another instance, and still shows as `RUNNING`.

**Renaming a knowledge base that is still in the 6.5.0 name layout clears what its sources have ingested.** In that layout the vector store is addressed by
the knowledge base's name while ingestion state is keyed by its id, so a rename moves retrieval to a
new, empty store. A knowledge base in the `id` layout (every new one) is unaffected by a rename; see [Knowledge base isolation](#knowledge-base-isolation). Clearing the state makes the next run repopulate it. The chunks under the old name
are left where they are.

**Removing a source takes what the knowledge base learned from it.** Dropping a source from
`sources[]`, changing its `type`, or deleting the last version of the knowledge base removes its chunks
from the vector store and forgets its state — for a crawl as much as for uploaded files. A crawl used
to be skipped on the theory that its documents come back on the next run; a removed source has no next
run, so its chunks stayed retrievable with nothing left to list or delete them.

**Ingestion schedules are minted by EDDI, not by clients.** A schedule whose metadata declares
`ragIngestion` is refused by the schedule API on create and update, firing one by hand requires EDIT on
the knowledge base it names, and a fire refuses a schedule whose name does not match that metadata.
Without those, anyone who could create a schedule could have the server crawl, re-embed and delete from
a knowledge base they have no access to.

### Uploaded files (`type: "upload"`)

An upload source reads files EDDI holds on its behalf. Dropping a file in stores it; **running the
source** is what extracts its text and embeds it — the same verb every other source answers to.

```json
{
  "name": "handbooks",
  "type": "upload",
  "upload": {
    "maxFiles": 500,
    "maxFileBytes": 26214400,
    "maxTotalBytes": 524288000
  },
  "settings": { "maxContentLength": 100000 }
}
```

| Field | Default | What it does |
| --- | --- | --- |
| `maxFiles` | `500` | Files this source may hold |
| `maxFileBytes` | `25 MB` | Size of one file. Refused above 50 MB at save time: the request carrying it has to fit inside `quarkus.http.limits.max-body-size` (60 MB), and a larger file is refused by the server with a bare 413 before anything can explain why. That 60 MB applies to this upload only — every other endpoint is held to `eddi.http.limits.default-max-body-size` (25 MB), raised to what the largest allowed attachment needs once base64-encoded: about 27.7 MB with the default 20 MB `eddi.attachments.max-size-bytes` |
| `maxTotalBytes` | `500 MB` | Size of everything the source holds |

**The files are kept, not just their embeddings.** That is what makes this a source rather than a
one-way import: changing the chunk size and re-running re-ingests from what is stored, changing the
embedding model and calling [`/reembed`](#changing-the-embedding-model) rebuilds every vector from the
files, a purge is recoverable, and deleting a file removes its vectors through the same reconciliation
a crawl uses. Embedding on upload and keeping nothing would make each of those "ask the operator to
upload two hundred files again".

**A file is identified by its name.** Uploading `handbook.pdf` twice replaces it — the blob, the
ingestion-state row and the vectors all key on an id derived from the name, so the corrected version
supersedes the old one everywhere at once. A generated id would leave both retrievable with nothing to
say which is current.

**A run compares the file's bytes, not its text.** An unchanged 20 MB manual costs one metadata query
per run rather than a download and a full parse, and improving an extractor does not silently re-embed
every file in the knowledge base.

**A file is read when it is uploaded, and refused if it yields no text.** The upload runs the same
extraction a run will, stopped after the first few characters, so an encrypted PDF, a scan with no text
layer, a damaged file or one whose name claims a format its content is not (a text file renamed
`.pdf`) is refused with the reason, rather than stored, listed as waiting to be indexed and skipped by
every run with nothing saying why. A file over `maxFileBytes` is refused on the size the request
declares, before its bytes are read. The probe runs on the upload request, so it gets 10 seconds per
file rather than a run's 60; a file that cannot show text in that time is refused. Images are never
decoded by it, so a large image does not count against a PDF.

**A replacement that cannot be read retires the version it replaced.** If a file stored before these
checks — or one that changes under an extractor — turns out unreadable or empty when a run reads it,
the run reports it as failed and removes the previous version's chunks: the stored file is all an
upload source has, so reading it and failing is a definitive answer, not a page that failed to load.
Keeping the old text left a document the operator had replaced answering questions for good.

#### What can be read

| Format | Extensions | What comes out |
| --- | --- | --- |
| PDF | `.pdf` | Text, page by page. An encrypted PDF is refused, and so is a scan with no text layer — at upload, with a message saying so |
| Word | `.docx` | Paragraphs, with headings kept as Markdown headings and numbered paragraphs as list items |
| Excel | `.xlsx` | One Markdown table per sheet, under the sheet's name. Dates and currency are stored as numbers with a display format applied elsewhere, so a date reads as its serial |
| PowerPoint | `.pptx` | One section per slide, in the order the presentation's own index lists them — not the part numbering, which survives a reorder — headed `## Slide N` |
| Text | `.txt`, `.md`, `.markdown`, `.json`, `.xml`, `.yaml`, `.yml`, `.log` | As written. UTF-8 unless a byte-order mark says otherwise (UTF-8, UTF-16 either way round), or the bytes are not valid UTF-8, in which case Windows-1252 |
| Tabular | `.csv`, `.tsv` | A Markdown table. RFC 4180 quoting, and the delimiter (comma, semicolon or tab) is taken from the first line |
| HTML | `.html`, `.htm` | Through the same converter the crawler uses, so a page saved to disk ingests as it would if crawled |

The **content decides the format**, not the name or the MIME type the browser attached: `.docx`,
`.xlsx` and `.pptx` are all ZIP archives, so neither distinguishes them, and a spreadsheet saved under
a `.docx` name would otherwise be refused as corrupt. The extension is consulted only for the text
formats, which have no signature to read.

Older `.doc`, `.xls` and `.ppt` files are **not** supported — the message says to save them as the
modern format. Images and scanned pages carry no text layer and there is no OCR; a PDF of scans
produces an empty document.

#### Hidden text

Retrieved text is prompt input, and text a person never sees is the classic way to put instructions in
front of a model: a document that looks clean to whoever uploads it would otherwise carry them into
every prompt that retrieves it. So an **uploaded** file is read the way a person sees it
(`settings.visibleTextOnly`, default `true` for an upload):

| Format | Left out |
| --- | --- |
| Word | runs marked hidden (`w:vanish`, `w:specVanish` or `w:webHidden`, unless switched off) and runs set at one point or smaller (`w:sz` 2 or less). A paragraph whose *mark* is hidden keeps its text |
| Excel | sheets marked `hidden` or `veryHidden`, rows and columns hidden by hand (columns are kept as empty cells, so the others stay aligned). **Kept:** a hidden row that lies inside the sheet's `autoFilter` range (a filter can be the reason), and rows or columns collapsed by grouping (`outlineLevel`) — also when they are explicitly hidden as well, because a collapsed group is not the author hiding text. **Dropped:** a row or column hidden by hand that is neither grouped nor inside a filter's range; a row without a row number (`r`) is kept, since its place cannot be told. A workbook whose every sheet is hidden is refused at upload with a message saying so ("only hidden sheets"), not "unreadable" |
| PowerPoint | slides marked "hide slide" (`show="0"`) and any shape marked hidden — text boxes, pictures, groups (with everything in them), connectors and any other shape kind; a hidden shape ends where it ends and never takes the next one's text with it |
| HTML | elements the markup hides outright — the `hidden` attribute, `display:none`, zero `opacity`, off-screen or zero-size elements — on top of what the page converter always drops (comments, one-pixel text, transparent text, text the same colour as an explicitly set background) |

**Not covered, and why.** White-on-white and tiny-but-not-one-point text in Word cannot be told from
legitimate styling without rendering the page (white text on a dark table cell is everywhere), and
character styles that carry `vanish` are not resolved (only the run's own properties are read), and a row hidden by a filter is dropped like a hand-hidden one if the sheet's `autoFilter` element lies beyond the rows that are read (the row limit). **A PDF is not filtered**: its invisible text
(render mode 3) is also the only text layer of an OCR'd scan, so dropping it would empty every
searchable scan; a PDF is therefore only as trustworthy as whoever made it. HTML stylesheet rules
(`<style>` blocks) are not read for an upload, as they are for an e-mail body.

**For a crawl the default is `false`**, because a page hides accordions, tab panels and "read more"
sections that script reveals and that a knowledge base wants — the crawler keeps those on purpose. Set
`visibleTextOnly: true` on a web source whose content you do not control to remove the elements the
markup hides outright as well; set it to `false` on an upload whose hidden sheets are data you do want.
This is a behaviour change for existing knowledge bases: hidden text in an **uploaded** file is no longer
extracted. Documents already embedded are not touched until the file changes or the source is
re-embedded.

#### Limits, and why each one is there

An uploaded file is not the operator's own data in any useful sense — it is whatever somebody dragged
into a browser — so extraction is bounded in these ways:

- **100,000 characters** per document (`settings.maxContentLength`, shared with the crawl), so one file
  cannot fill a knowledge base. A document that hits it is logged by name — it is embedded truncated,
  and silently embedding the first third of a manual as though it were the whole thing is the failure
  worth naming.
- **500 pages, slides or sheets**, and **5,000 rows × 64 columns** per sheet. A sheet can declare cells
  out at column XFD whether or not anything was ever typed there.
- **64 MB decompressed per archive** — counted across *every* entry the reader walks over, not only the
  ones it keeps. Moving to the next ZIP entry decompresses the rest of the current one, so an entry
  nobody wants is otherwise the cheapest place to hide a bomb: the work happens either way and nothing
  counts it. The same budget applies while merely working out which Office format a file is, because
  that runs inside the upload request.
- **DTDs and external entities are refused** outright, so an Office file cannot expand entities into
  gigabytes (the billion-laughs attack) or reach out to a URL while being parsed.
- **A PDF may decode at most 128 MB in total, and gets 60 seconds.** PDFBox decodes a compressed stream
  whole into memory with no limit of its own, and deflate expands about a thousandfold. So every
  filter PDFBox uses is wrapped, while a document is read, in one that counts what it writes: the count
  covers every stream, every stage of a filter chain, every time a stream is referenced (one stream
  named twenty times in a page's `/Contents` is decoded twenty times at once), and encrypted streams
  after decryption. Past twice `maxUncompressedBytes` — 128 MB — the document is refused. Before that,
  a cheap scan of the raw bytes refuses one stream that alone expands past 64 MB, without letting
  PDFBox allocate anything; it decodes each stream's declared filter chain (Flate, LZW, RunLength,
  ASCIIHex, ASCII85) and passes over image streams, which text extraction never decodes. A page's
  program is checked against the deadline as it runs, and a page that lays out far more glyphs than
  the character cap could ever keep is stopped rather than held in memory whole — keeping the text it
  laid out up to that point, so a dense first page is not mistaken for a scan.
- **An archive naming the same part twice is refused.** A real Office file never does, and two entries
  under one name means two readers can disagree about the contents.

Apache POI would read these formats too — at seven extra jars and about 14 MB, built on reflection and
with a long history of parser CVEs. For pulling text out of a handful of known parts, the JDK's own ZIP
and StAX readers are the smaller surface and the one whose limits can be stated exactly, as above. PDF
goes through PDFBox, which EDDI already depends on.

#### File endpoints

| Method | Path | Access | Purpose |
| ------ | ---- | ------ | ------- |
| `POST` | `/ragstore/rags/{id}/sources/{sourceId}/files?version=N` | EDIT | Store files (`multipart/form-data`, parts named `files`). Each file is accepted or refused on its own |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/files?version=N` | VIEW | What the source holds: names, types, sizes, content hashes |
| `DELETE` | `/ragstore/rags/{id}/sources/{sourceId}/files/{fileId}?version=N` | EDIT | Remove a file **and the vectors it produced**, at once |

The upload answers `200` with `{"stored": [...], "rejected": [{"fileName", "reason"}]}` when anything
was stored, and `400` with the same body when nothing was — a batch where one file of thirty failed is
a success with a caveat, and a client that treats `4xx` as "nothing happened" would have the operator
re-uploading files that are already there. `409` means the source is not of type `upload`.

Deleting removes the vectors **before** the file, and immediately rather than at the next run: an
operator who removes a document because it should not have been there is told it is gone, and a source
with no cron has no next run to make that true. Where the vector store cannot delete by metadata, the
answer carries a `warning` saying the text is still retrievable — the Manager shows it rather than
reporting a clean success. Where the store accepts the delete and then fails it, the answer is `503`
and **the file is kept**, so the delete can be tried again once the store recovers; deleting the file
anyway stranded its chunks for good, since nothing would ever reconcile a document that is gone.

A delete takes the source's **run claim**, the same one a run takes, and answers `409` if it cannot get
it. Merely checking for a run first is not enough: one that starts between the check and the delete
lists the file, loads the bytes that are about to go, embeds them, and records the document as
ingested — clearing the tombstone the delete just wrote. The file would be gone and its content still
retrievable, for a source with no cron indefinitely. The claim is released as a maintenance claim,
which the run history does not list: it used to appear as a successful run that saw nothing, and push
the source's real last run out of sight.

Uploads to one source are measured against its limits one file at a time, against a fresh listing and
under a lock, so two uploads arriving together cannot both fill the same headroom. Across instances a
new file that finds the source over its limit once stored is taken back out.

**Removing an upload source takes its files *and* what the knowledge base learned from them.** Dropping
it from `sources[]`, changing its `type` to `web`, or deleting the last version of the knowledge base
all delete its files and remove its chunks from the vector store. Deleting the files and leaving the
vectors would be the worst of the three outcomes: agents would keep citing a document the operator
believes is gone, and no endpoint could list or delete it, because the source it belonged to is no
longer in the configuration. The Manager confirms before removing an upload source, and says what goes.

A **purge** deliberately does none of that: it forgets what was ingested so the next run re-ingests,
which is only useful because the files are still there.

**A ZIP export does not carry uploaded files,** and neither does duplicating a knowledge base. An
imported or duplicated upload source arrives empty and its files have to be uploaded again — the backup
format carries configuration, and a knowledge base's documents can be hundreds of megabytes of
somebody's contracts. Nor do the vectors move: an imported or duplicated knowledge base has a store of
its own (see [Knowledge base isolation](#knowledge-base-isolation)), empty until its sources run or
documents are ingested into it.

**A run over uploaded files honours `settings.timeBudgetMinutes`,** as a crawl does. A run that
outlived its budget would eventually be treated as abandoned, and the next fire would start a second
worker embedding into the same store — each deleting the other's fresh chunks, because replacement
filters on the run id.

### Ingestion source endpoints

| Method | Path | Access | Purpose |
| ------ | ---- | ------ | ------- |
| `POST` | `/ragstore/rags/{id}/sources/{sourceId}/run?version=N` | EDIT | Start a run (202 with its `runId`, or 409 if one is in flight) |
| `POST` | `/ragstore/rags/{id}/sources/{sourceId}/preview?version=N` | EDIT | Crawl and report what would change, embedding nothing. Capped at 2 minutes and a small page count, and at three previews per instance — the rest get 429 with `Retry-After` |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/runs?version=N&limit=20` | VIEW | Run history with counters, cost and errors — including the run in flight, whose counters move while it works |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/schedule?version=N` | VIEW | The source's schedule: next and last fire, failure count, the scheduler's disable reason, and the recent fires with their errors |
| `GET` | `/ragstore/rags/cron/preview?cron=...&count=3` | — | Whether a cron would be accepted on save (parseable, able to match a date, no more frequent than `eddi.schedule.min-interval-seconds`), and its next fire times in UTC |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/documents?version=N&q=&offset=&limit=` | VIEW | The documents the source has ingested — URL or file name, first and last embedded, missed runs, removed — most recent first, filterable |
| `GET` | `/ragstore/rags/{id}/sources/{sourceId}/chunks?version=N&documentId=...` | EDIT | The chunks one document was stored as, in document order (one embedding call; 501 on a store that cannot filter by metadata). At most `limit` chunks (default 100, ceiling 200) come back, and `"truncated": true` says the document has more — the stores cannot list everything, so the chunks are the best similarity matches |
| `POST` | `/ragstore/rags/{id}/search?version=N` | EDIT | Test retrieval: `{query, maxResults?, minScore?, sourceId?}` searched exactly as an agent's retrieval does, with each chunk's score and metadata (one embedding call; 502 with a fixed sentence — rejected credentials, unknown model, store error — never the provider's or the driver's text, which stays in the server log; 409 `VECTOR_SPACE_MISMATCH` when the store holds another embedding model's vectors) |
| `POST` | `/ragstore/rags/{id}/evaluate?version=N` | EDIT | Benchmark retrieval: golden queries `{query, expected[], k?}` run through the same search and scored — first-hit rank, hit@k, reciprocal rank, recall@k, MRR — optionally against a baseline (one embedding call per query; see [Evaluating retrieval](#evaluating-retrieval)) |
| `DELETE` | `/ragstore/rags/{id}/sources/{sourceId}/documents?version=N` | EDIT | Forget what the source has ingested |

Running needs EDIT rather than VIEW because a published knowledge base grants VIEW to everyone by
design, and a run rewrites what every agent using it retrieves. Reading chunks, searching and evaluating need EDIT
for the same reason in the other direction: they return the knowledge base's text and cost an
embedding call each, while the document listing returns only ingestion state and needs VIEW.

#### What a run records

Each run row carries, besides its counters, **why it stopped** (`stopReason`: `COMPLETED`, or the limit
it hit — `PAGE_LIMIT`, `FETCH_LIMIT`, `BYTE_LIMIT`, `TIME_LIMIT` — or `CANCELLED`, which also covers a
run that was superseded or reached its chunk budget), **what started it** (`trigger`: `MANUAL` or
`SCHEDULED`; absent on rows written before it was recorded) and how many documents were **skipped** for
having no text worth embedding. A running run writes its counters every couple of seconds
(`IIngestionStateStore.reportProgress`), fenced on the row still being `RUNNING`, so a late write can
neither reopen nor overwrite a finished run. A run cancelled at shutdown keeps the counters it had got to.

A source with a `cron` gets a schedule named `rag-ingestion:{ragConfigId}:{sourceId}`, kept in step with
the configuration whenever the knowledge base is saved and removed when it is deleted. `{sourceId}` is
the source's id, or its name when it has none. A ZIP import writes through the store rather than the
REST layer, so it assigns the ids, validates the sources and creates their schedules itself — without
that, an imported source was addressed by name, and the first save in the Manager re-keyed it, orphaned
its history and re-embedded everything.

### In the Manager

The knowledge-base editor has an **Ingestion Sources** section: add and remove sources, choose between
**Website** and **Files**, edit the scope and the limits, and for a source that has been saved once,
**Run now**, **Preview**, **Purge state** and the run history with its counters and errors.

A **Files** source shows a drop zone instead of the crawl settings. Files can be dropped or chosen,
several at once, and each is uploaded on its own request with its own progress bar — so a batch that
fails three quarters of the way through does not lose what had already arrived, and a file the server
refuses shows the server's own sentence ("This PDF is encrypted") rather than a generic failure. Below
it, the files the source holds, each marked **Indexed**, **Changed** or **Not indexed** — so an
uploaded file, one that is in the knowledge base, and one that was replaced after it was indexed are
told apart without running the source and comparing counters. While anything is waiting, a line says so
with a **Run now** beside it. Deleting a file confirms first and says what it removes.

While a run is in flight the card shows its **progress**: documents looked at so far against what the
last complete run saw (or, for a Files source, the number of files), each counter as it moves, the
trigger, and the time used against the source's time budget. Each past run opens to everything it
recorded — skipped, failed, removed, chunks stored, duration, estimated cost and how it ended — and a
failed **Run now**, **Preview** or **Purge** shows the server's own reason rather than a guess.

The **schedule** is chosen from presets (hourly, daily, weekly, monthly — all UTC) or typed as a custom
cron, which is checked against the server's rules as it is typed and shows its next fire times in the
viewer's local time. Once saved, the card shows what the scheduler is actually doing: the next run, the
last fire, a warning when a saved cron produced no schedule, and recent fires with their errors —
failing, disabled by the scheduler and dead-lettered are each said in words. On the **Schedules** page an
ingestion schedule links back to its knowledge base.

**Ingested documents** lists what the source has put into the knowledge base — filterable, paged, each
marked **Live**, **Missed by the last N runs** or **Removed** — and opens a document to the chunks it was
stored as. **Test Retrieval** searches the knowledge base, or one source, exactly as an agent would and
shows each chunk with its score and origin.

Run and Preview address the source by id and version, so they crawl the **saved** configuration. While
the editor has unsaved changes both are disabled, with a line saying why — otherwise editing a start
URL and pressing Run would silently crawl the old one. A source that has never been saved shows the
same explanation instead of the buttons, because it has no id for the endpoints to address.

### Store support for replacement

Replacing a document's chunks needs `removeAll(Filter)` on the vector store, with a compound filter
(document id and owning source). Verified for `in-memory`, `pgvector` and `chroma` — Chroma by
`ChromaReplacementIT`, which runs a changed page, a page removed from the site and a deleted upload
through the real ingestion pipeline against a `chromadb/chroma:1.1.0` container (the image
`docker-compose.chroma.yml` ships, on the default `apiVersion` `V2`) and finds the stale chunks gone
with `replaceUnsupported` not raised. The other supported stores — `mongodb-atlas`,
`elasticsearch`, `qdrant` — are expected to support it through their langchain4j drivers but are not
covered by these tests. Where a store does not, the run still succeeds and reports
`replaceUnsupported`, meaning re-ingested documents accumulate stale chunks on that backend.
The same `removeAll(Filter)` serves `replace=true` and
[deleting a document](#deleting-a-document) on `/ingest`'s documents; where it is missing, both answer
with a `warning` instead.

## Evaluating retrieval

`POST /ragstore/rags/{id}/search` shows what one query retrieves. To tell whether a change — a new
chunk size, another embedding model, a different `minScore` — made retrieval better or worse, run a
fixed set of **golden queries** through the same search and score the results:

```
POST /ragstore/rags/{id}/evaluate?version=N
```

Each query names the documents a good retrieval returns. The endpoint runs every query exactly as
`/search` does (same embedding model in query mode, same isolation filter, same `sourceId` narrowing)
and reports, per query, the rank of the first hit, hit@k and the reciprocal rank, and over all
queries the hit rate, recall@k and MRR. It needs **EDIT**, like `/search`, and costs **one embedding
call per query**; the request is synchronous.

```json
{
  "k": 5,
  "queries": [
    { "id": "refunds", "query": "How do I get my money back?", "expected": ["refund-policy"] },
    { "id": "shipping", "query": "Do you ship to Austria?", "expected": ["shipping-eu", "faq"], "k": 10 }
  ]
}
```

| Field | Default | Meaning |
| ----- | ------- | ------- |
| `queries` | — (required) | The golden queries: `query` (required, at most 2,000 characters), `expected` (required, at least one non-blank identifier, at most 100), optional `id` and `k` |
| `k` | the knowledge base's `maxResults`, else 5 | Cut-off for queries that set none. Between 1 and 50, the most a search returns |
| `minScore` | the knowledge base's `minScore`, else 0 | As for `/search`; between 0 and 1 |
| `sourceId` | — | Narrows every query to one ingestion source's chunks |
| `matchKeys` | `["source", "documentId", "url"]` | Which chunk metadata keys identify a document (at most 10) |
| `baseline` | — | An earlier run to compare with (below) |

At most `eddi.rag.evaluation.max-queries` queries (default 100) go in one request; more is a 400
naming the setting, as is every other malformed request — all checked before the first embedding
call. A provider or store failure is a 502 with the `queryIndex` it failed at; nothing partial is
returned.

### What counts as a hit

A retrieved chunk is a hit for an expected document when the value of one of the `matchKeys` in the
chunk's metadata **equals** the expected identifier — exactly and **case-sensitively** (the expected
value is trimmed; nothing else is normalized). The defaults cover both ways documents get in:

- `source` — the `documentName` of a document ingested with `POST /{id}/ingest`.
- `documentId` and `url` — what an [ingestion source](#ingestion-sources) stores: the page URL for a
  web source, the file id for an upload source (see the source's documents listing).

To match on something else, say the file name an upload source stores as `title`, send
`"matchKeys": ["title"]`. Several chunks of one document count once, and one chunk can satisfy two
expected documents if it carries both identifiers.

### The numbers

| Metric | Per query | Aggregate (`summary`) |
| ------ | --------- | --------------------- |
| `firstHitRank` | 1-based rank of the first chunk that hits any expected document within the top `k`; null without one | — |
| `hit` / `hitRate` | hit@k: whether there is a first hit | Share of queries with a hit |
| `reciprocalRank` / `mrr` | `1 / firstHitRank`, 0 without a hit | Mean: MRR@k |
| `recall` | recall@k: share of the **distinct** expected documents found in the top `k` | Mean over queries |

Both hit rate and recall are returned because they differ as soon as a query expects more than one
document: a query expecting `["a", "b"]` that retrieves only `a` has a hit but a recall of 0.5. Each
query also lists `found`, `missing` and `retrieved` — every result's rank, score, identifiers and
whether it was relevant — so a miss can be read without a second search. Chunk text is not returned;
that is what `/search` is for.

```json
{
  "k": 5,
  "minScore": 0.0,
  "matchKeys": ["source", "documentId", "url"],
  "summary": { "queries": 2, "scoredQueries": 2, "hitRate": 1.0, "recall": 0.75, "mrr": 0.75 },
  "queries": [
    {
      "id": "refunds", "query": "How do I get my money back?", "k": 5, "expected": ["refund-policy"],
      "resultCount": 5, "firstHitRank": 2, "hit": true, "reciprocalRank": 0.5, "recall": 1.0,
      "found": ["refund-policy"], "missing": [],
      "retrieved": [
        { "rank": 1, "score": 0.83, "ids": ["faq"], "relevant": false },
        { "rank": 2, "score": 0.81, "ids": ["refund-policy"], "relevant": true }
      ]
    }
  ]
}
```

(The second query is left out above.)

### Comparing with a baseline

Send an earlier response's `queries` back as `baseline` — pasting the whole earlier response works,
fields the comparison does not read are ignored — and the response gains a `comparison`:

```json
{
  "queries": [ ... ],
  "baseline": { "queries": [ { "id": "refunds", "k": 5, "hit": true, "reciprocalRank": 1.0, "recall": 1.0 } ] }
}
```

```json
"comparison": {
  "comparedQueries": 1, "hitRateDelta": 0.0, "recallDelta": 0.0, "mrrDelta": -0.5,
  "regressions": 1, "improvements": 0, "regressed": true,
  "queries": [ { "key": "refunds", "hitBefore": true, "hitNow": true, "reciprocalRankDelta": -0.5,
                 "recallDelta": 0.0, "regressed": true, "improved": false, "kChanged": false } ],
  "onlyInCurrent": [], "onlyInBaseline": []
}
```

- Queries are paired by `id`, or by their text when they have none. Two queries with one key on the
  same side are a 400 — the pairing would be a guess.
- A query **regressed** when its reciprocal rank or its recall went down; either is enough, so a
  query that ranks its first hit higher but loses a second document is still flagged. It
  **improved** when something went up and nothing went down.
- The aggregate deltas are taken over the queries **both runs contain**, so adding or dropping a
  query never moves them; those are listed in `onlyInCurrent` and `onlyInBaseline` instead.
- `regressed` at the top is true when any query regressed or any aggregate went down.
- `kChanged` marks a query scored at a different cut-off than in the baseline, where the deltas
  compare different things. A baseline value left out (a hand-written baseline with only `recall`,
  say) is simply not compared.

The scores move with the knowledge base as well as with the configuration: re-ingesting or crawling
in between changes what there is to find. Compare runs over the same content when the question is
whether a configuration change helped.

### Source connectors (for contributors)

Each source `type` is served by one **source connector**, a CDI bean implementing
`SourceConnector` (`modules/ingestion/connector`). The ingestion pipeline owns everything that is the
same for every source: run claims, change detection, text extraction, embedding, chunk replacement,
deletion reconciliation, budgets and run history. It never names a provider. A connector owns how to
list the source, how to read one document, and what the provider's failures mean. `web`
(`WebSourceConnector`, around the crawler) and `upload` (`UploadSourceConnector`, over the file store)
are the two today. A new source type is one bean: `SourceConnectors` collects them by `type()` and
refuses two beans for one type at startup.

| Part | What it is |
| --- | --- |
| `discover(DiscoveryContext)` | Lists one partition from its cursor and hands each document to `context.accept(WorkItem)` as it is found, streamed rather than returned. Downloads nothing it does not have to. Returns a `DiscoveryResult`: the stop reason, the `Coverage`, and the next cursor |
| `fetch(WorkItem, SourceContext)` | Reads one document as a `SourceDocument`. Not called for an item that arrived `prefetched`, which is what a crawler does, since it reads the page to find the next link |
| `listPartitions` / `verifyAccess` | Drives, sites, mailboxes. The default is one implicit partition that is always accessible. **This version runs exactly one partition per source** |
| `WorkItem` | `documentId`, `versionToken`, `action` (`UPSERT`, `UNCHANGED` meaning the provider confirmed the stored version, e.g. an HTTP 304, or `DELETE`), `partitionId`, connector-private `hints`, and an optional prefetched document. When a not-yet-fetched item carries a `versionToken` equal to the stored one, it is unchanged and never fetched |
| `SourceDocument` | `content`, which is one of three kinds: `HtmlPage` (page converter; blank text means navigation and is skipped), `FileBytes` (extracted by MIME type; no text means a definitive failure that retires older chunks) or `Text`. It also carries `versionToken` (the change key when present, otherwise the text hash), HTTP `validators`, `truncated` and `metadata` under `SourceMetadataKeys` |
| `Coverage` | `FULL_LISTING`: an authoritative listing, where absence means deletion, even when the listing is empty. `FULL_TRAVERSAL`: a crawl; absence means deletion only if the run learned something definitive. `INCREMENTAL`: a change feed; absence means nothing, and deletions arrive as `DELETE` items. `PARTIAL`: nothing is concluded |
| `SourceError` | `TRANSIENT`, `THROTTLED` (each with an optional retry-after), `AUTH`, `NOT_FOUND`, `PERMANENT`, `REJECTED`, `CURSOR_INVALID`. Only `NOT_FOUND` and `PERMANENT` count as evidence about a document. Use `PERMANENT` only when the answer concerns the document itself, such as a format that cannot be exported. A request the provider refuses as you made it (malformed, an unknown field) is `REJECTED`: it is a fault in the connector, and counting it would delete every document it touched. The others mean "could not look", and the document is kept. A failure reported without a document id is never evidence. Per-document failures go to `context.reportFailure` or are thrown from `fetch` as `SourceException`. A whole-source failure thrown from `discover` fails the run |
| Cursor | Opaque to the framework: one string per partition, JSON by convention, versioned and read by the connector. **It is not stored yet**: the pipeline passes no cursor, so every run lists in full |

The connector chooses its own discovery style: (A) a listing plus a change feed, (B) a single delta
call, or (C) a listing only, as `web` and `upload` do. The framework depends only on the coverage the
connector reports.

`SourceMetadataKeys` also reserves `acl.principals`, a list of namespaced principals such as
`google:user:a@x.com`, `entra:group:<object-id>`, `link:organization` or `anyone`. It is schema only,
and nothing enforces it yet. (The shared `filters` and `partitions` config blocks this section used to
describe were never read by any source and are gone; a file-shaped connector that needs them adds them
with its first reader.)

## Observability

RAG operations write audit traces to conversation memory:

| Memory Key | Content |
|---|---|
| `rag:trace:{taskId}` | Per-KB retrieval metadata (provider, storeType, maxResults, minScore, retrievedCount) |
| `rag:context:{taskId}` | Formatted context string injected into the LLM |
| `rag:httpcall:trace:{taskId}` | httpCall RAG execution metadata (httpCall name, context length) |

All three are stored in the conversation document. `rag:context:{taskId}` is returned by a snapshot with `returnDetailed=true`; the two `:trace:` keys are withheld from that view and are read from the raw conversation (`GET /conversationstore/conversations/{conversationId}`, the conversation's owner or an admin). The audit ledger does not copy them, but its `llmDetail.compiledPrompt` contains the injected context.

## Troubleshooting

### No context is injected, and there is no error

Symptoms: the model answers as though it had never seen the corpus, the compiled prompt carries no
`## Relevant Context:` block, conversation memory holds no `rag:trace:*` or `rag:context:*` entry,
and the logs show nothing from `RagContextProvider` or `EmbeddingStoreFactory` while other providers
log on every turn.

That combination means **no context was produced** — but three different causes produce it, and from
the outside they look identical. If the task's `knowledgeBases` is null or empty *and*
`enableWorkflowRag` is not `true`, `RagContextProvider.retrieveContext` returns before it even
discovers workflow steps — no discovery, no trace, no store build, no INFO log, and no DEBUG message,
regardless of what the workflow itself binds. Otherwise discovery does run, and the other two causes
apply: either the workflow binds no `eddi://ai.labs.rag` step at all, or it binds one whose name none
of `knowledgeBases[].name` matches. In both of those, the trace entry, the store build and the INFO
log are still downstream of a match, so none of them appear either.

At `DEBUG` only the missing-step cause is distinguishable: `No RAG steps found in workflow` is logged
once discovery runs and finds nothing. The task-level early return and the unmatched-name case log
nothing at any level — so before raising the level, first confirm the task actually asks for RAG at
all. Otherwise work through it in this order:

| # | Check | How |
|---|---|---|
| 1 | Does the task request RAG at all? | Task config: `knowledgeBases` must be non-empty, or `enableWorkflowRag: true`. Neither means `RagContextProvider` returns immediately — no discovery, no trace, no log line at any level |
| 2 | Does the agent's workflow carry an `eddi://ai.labs.rag` step? | Read the workflow config. **This is the usual cause** — see [step 2](#2-workflow-step-binds-the-kb-to-the-agent) |
| 3 | Does `knowledgeBases[].name` match the KB's `name`? | Compare against the `RagConfiguration`. It matches on `name`, not id, and a miss is skipped silently |
| 4 | Is the deployed agent version the one you edited? | Retrieval reads the workflow of the agent version in the conversation, and configs are versioned |
| 5 | Was anything actually ingested — and is it still there? | Poll the ingestion status. On an `in-memory` store, check whether EDDI has restarted since, or the knowledge base changed its embedding model or moved to a new store (below): each empties it until the next run of each source re-embeds it (see [Vector Stores](#vector-stores)) |
| 5b | Is there a `rag:trace:*` entry with an `error`? | The knowledge base was found and retrieval failed. If the error names two embedding models, the vector store holds another model's vectors than the configuration names: `GET /ragstore/rags/{id}/vector-space?version=N`, then `POST …/reembed` (see [Changing the embedding model](#changing-the-embedding-model)) |
| 6 | Did ingestion write where retrieval reads? | Both address the store by the knowledge base's id, so they cannot diverge any more. If the KB was renamed while in the 6.5.0 name layout, or switched to `storeNamespace: "id"`, it now has a new, empty store — re-ingest (see [Knowledge base isolation](#knowledge-base-isolation)) |

Raise `RagContextProvider` to `DEBUG` to see the missing-step early return directly (it will not show
the task-level or unmatched-name cases — rule those out with checks 1 and 3 above):

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

| Provider | Default Model | Required Parameters | Every parameter it honours |
|---|---|---|---|
| `openai` | `text-embedding-3-small` | `apiKey` | `model`, `apiKey`, `baseUrl` (any OpenAI-compatible endpoint; default OpenAI's), `timeout`, `organizationId`, `projectId`, `dimensions`, `maxRetries` |
| `azure-openai` | `text-embedding-3-small` | `endpoint`, `apiKey`, `deploymentName` | `deploymentName`, `apiKey`, `endpoint`, `timeout`, `dimensions`, `maxRetries` |
| `ollama` | `nomic-embed-text` | — | `model`, `baseUrl` (default `http://localhost:11434`), `timeout`, `maxRetries` |
| `mistral` | `mistral-embed` | `apiKey` | `model`, `apiKey`, `baseUrl`, `timeout`, `maxRetries` |
| `bedrock` | `amazon.titan-embed-text-v2:0` | — | `model`, `region` (default `us-east-1`), `dimensions`; uses the AWS credentials chain |
| `cohere` | `embed-english-v3.0` | `apiKey` | `model`, `apiKey`, `baseUrl`, `timeout` |
| `gemini` | `gemini-embedding-2` | `apiKey` | `model`, `apiKey`, `baseUrl`, `timeout`, `maxRetries`, `taskType`, `outputDimensionality` |
| `vertex` | `text-embedding-005` | `project` | `model`, `project`, `location` (default `us-central1`), `endpoint`, `maxRetries`; uses GCP credentials |

- **`model` and `modelName` are the same parameter** — LLM tasks call it `modelName`, so either is
  accepted. Setting both to different values is refused.
- **`timeout` is in milliseconds**, as it is for LLM tasks.
- **Every value may be a vault reference** (`${vault:…}`) or a global variable; both are resolved when
  the model is built, and an unresolvable reference fails the build rather than reaching the provider.
- **An endpoint the provider cannot honour is an error, not a silent fallback.** Until 6.6 the
  `openai` provider ignored `baseUrl`, so a knowledge base configured for a private OpenAI-compatible
  endpoint sent every document and query to `api.openai.com`. An endpoint-like parameter
  (`baseUrl`, `endpoint`, `url`, `host`, …) that the provider does not honour is now refused when the
  knowledge base is saved (`400`) and again when the model is built — so a stored configuration
  carrying one fails its ingestion and retrieval with a message naming the parameter. Other
  unrecognised parameters are logged as a warning and ignored.

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

The role is only applied to providers that accept one. In langchain4j 1.23.0 that is
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
| `in-memory` | — | Held in the EDDI process, one store per knowledge base, for dev/test — **loses every ingested document on restart**; ingestion sources re-embed on their next run (see below) |
| `pgvector` | `password` | PostgreSQL + pgvector; `host` (default `localhost`; one hostname or IP address — no port, path, query or comma-separated list; the port goes in `port`), `port` (default `5432`), `database` (default `eddi`), `user` (default `eddi`), `table`, `dimension` (default `1536` — must match the embedding model's vector size). `table` defaults to a location of the knowledge base's own — see [Knowledge base isolation](#knowledge-base-isolation). Index and throughput settings — `layout`, `vectorIndex`, `hnswM`, `hnswEfConstruction`, `hnswEfSearch`, `insertBatchSize` — are in [pgvector at scale](#pgvector-at-scale) |
| `mongodb-atlas` | `connectionString` | MongoDB Atlas Vector Search; `databaseName` (default `eddi`), `collectionName`, `indexName` (default `vector_index`) |
| `elasticsearch` | — | `serverUrl` (default: `http://localhost:9200`); optional `apiKey` or `userName`+`password`; `indexName` |
| `qdrant` | — | `host` (default: `localhost`), `port` (default: `6334`); optional `apiKey`, `useTls`; `collectionName` |
| `chroma` | — | `baseUrl` (default: `http://localhost:8000`); `collectionName`; `tenantName` (default `default_tenant`), `databaseName` (default `default_database`), `apiVersion` (`V2` default, or `V1`) |

> **`in-memory` is a dev/test option, not a small-corpus one.** For this store the object in the
> EDDI process *is* the data, so it is kept apart from the cache of external-store clients: it is
> not evicted after a period without queries, not dropped when a vault secret or a global variable
> changes, and not pushed out by other knowledge bases. It goes away only when:
>
> - **EDDI restarts.** Everything ingested is gone. The next run of each
>   [ingestion source](#ingestion-sources) notices that the store is not the one its state was
>   written against and **re-embeds every document** — it no longer reports them "unchanged" over an
>   empty store, so a scheduled source refills the knowledge base without a purge. Until that run,
>   retrieval returns no context rather than an error. Documents sent to `/ingest` have no
>   source and have to be sent again.
> - **The knowledge base is deleted** (its last version), **moves to a new store** — renamed
>   while still in the 6.5.0 name layout, or switched to `storeNamespace: "id"`, exactly as for
>   every other store type (see [Knowledge base isolation](#knowledge-base-isolation)) — **moves to
>   another `storeType`**, or **changes how its vectors are made**: another embedding provider,
>   model (`model` and `modelName` count as one setting), Azure `deploymentName`, endpoint
>   (`baseUrl`/`endpoint`) or `dimensions`/`outputDimensionality`. These are compared after
>   `${vars:…}` references are resolved, so a global variable that names a new model counts too.
>   The old vectors are unreachable or meaningless. A new version that only changes chunking, retrieval settings or sources keeps
>   the data, and so do rotating the embedding API key and renaming a knowledge base in the id
>   layout.
>
> Each knowledge base has its own in-memory store, addressed like every other store by its id:
> two knowledge bases with the same name do not share one, and deleting one cannot empty another.
>
> All in-memory knowledge bases together may hold at most `eddi.rag.in-memory.max-segments`
> segments (default `50000`; `0` disables the cap — see the
> [configuration reference](configuration-reference.md#knowledge-bases-rag)). A write past the cap
> fails — the run reports `FAILED` with an error naming the setting — and nothing is evicted to make
> room. A run that is still writing when its store is replaced or dropped (the knowledge base
> changed its embedding model, moved, or was deleted) stops the same way, with an error saying the
> store was replaced or removed. Its remaining writes would otherwise land in a store nothing reads,
> outside the cap. The next run re-embeds into the current store. Any agent expected to answer from a knowledge base after a restart needs a persistent store.
> `storeType` still **defaults to** `in-memory`, so persistence is opt-in: `pgvector` is the
> recommended choice, and — with `in-memory` and `chroma` — one of the three stores whose
> document-replacement semantics are verified (see [Ingestion Sources](#ingestion-sources)).


### pgvector at scale

A pgvector knowledge base whose table EDDI creates gets the **v2 layout**, built for tables of
millions of chunks:

- an **HNSW index** on the embedding (cosine distance, the operator retrieval orders by), so a query
  reads a few hundred index entries instead of scanning every row;
- metadata stored as **`jsonb`** with b-tree indexes on `(documentId, sourceKey)` and
  `(sourceKey, runId)` — the keys every document replacement and every post-run sweep filters on —
  so re-ingesting a changed document deletes its old chunks through an index instead of a full scan;
- inserts in **batches** of `insertBatchSize` rows, so a large document is several short
  transactions rather than one long one;
- **pooled connections**, shared by every knowledge base on the same server and login
  (`host`, `port`, `database`, `user`, password) instead of a new connection for every search,
  insert and delete. A pool opens at most `eddi.rag.pgvector.pool.max-size` connections (default
  `10`, see the [configuration reference](configuration-reference.md#knowledge-bases-rag)) and closes
  idle ones after two minutes.

| Parameter | Default | Meaning |
|---|---|---|
| `layout` | `auto` | `auto`: an existing table keeps the layout it has, a new table is created in `v2`. `v1` creates a new table the way EDDI did before (json metadata, no index). An explicit value that contradicts an existing table is refused |
| `vectorIndex` | `hnsw` | `none` builds no vector index, so a new table searches exactly. An HNSW index that already exists stays in place and still serves searches; drop it by hand to return to exact search — for a small knowledge base, or to load a large one first and add the index by hand afterwards |
| `hnswM` | `16` | HNSW graph degree (2–100). Higher: better recall, larger index, slower inserts. Applies when the index is created |
| `hnswEfConstruction` | `64` | HNSW build-time candidate list (4–1000, at least 2 × `hnswM`). Applies when the index is created |
| `hnswEfSearch` | `40` | HNSW query-time candidate list (1–1000), set on each connection the store borrows. Raise it if retrieval misses relevant chunks; it must be at least `maxResults` to return that many |
| `insertBatchSize` | `500` | Rows per INSERT transaction (1–10000). Also applies to tables in the `v1` layout |

**Existing tables are not changed.** A knowledge base whose table already exists — every pgvector
knowledge base created before this layout — keeps reading and writing it exactly as before: EDDI
recognises the old layout by its `json` metadata column, adds no index to it and converts nothing,
because building an HNSW index over millions of rows blocks writes to the table for as long as it
takes. Such a knowledge base still gets pooled connections and batched inserts. To move one to
the v2 layout, re-embed it into a new table: set `storeParameters.table` to a new name (or, for a
knowledge base still in the 6.5.0 name layout, migrate it to `"storeNamespace": "id"` — see
[Knowledge base isolation](#knowledge-base-isolation)), then run its sources again. The old table
is left in place; drop it once nothing uses it. Asking for `"layout": "v2"` on an existing v1 table
is refused with that explanation rather than silently ignored.

Limits worth knowing:

- pgvector cannot build an HNSW index over more than **2000 dimensions**. A `v2` table with a larger
  `dimension` (OpenAI `text-embedding-3-large` at its full 3072, for example) is created without the
  vector index and logs a warning; retrieval then scans every row. Use the model's `dimensions`
  setting to stay at or below 2000 for an indexed knowledge base.
- HNSW needs **pgvector 0.5.0** or later. On an older server the index cannot be built; EDDI logs a
  warning and the knowledge base works with exact scans, as before.
- Retrieval filters on `kbId` after the index scan. On a table of its own (the default) that filter
  matches every row and costs nothing. On a table **shared** by several knowledge bases, a query can
  return fewer than `maxResults` chunks when the nearest `hnswEfSearch` candidates belong to another
  knowledge base — raise `hnswEfSearch`, or give each knowledge base its own table.
- `hnsw.ef_search` is a session setting; it is applied every time a connection is borrowed, one
  extra statement per operation, because connections are shared by knowledge bases that may set
  different values.

Changing `hnswM` or `hnswEfConstruction` on an existing v2 table does not rebuild its index; drop
the table's HNSW index by hand and EDDI recreates it with the new values the next time it
builds the store — a build that blocks writes to a large table while it runs. To avoid that, create
it yourself under the name EDDI looks for, with `CREATE INDEX CONCURRENTLY <index> ON <table> USING hnsw
(embedding vector_cosine_ops) WITH (m = …, ef_construction = …)`; EDDI then finds it and leaves it alone.
`<index>` is **unqualified** — an index lives in its table's schema — while `<table>` is qualified as the
table is (`rag.kb`). It is the lower-case table name without its schema plus `_hnsw_idx`; when that
would exceed 63 characters EDDI shortens it and adds a digest, so take it from the log line
`pgvector table <table>: HNSW index name is <index>` written whenever the store is built. If a
concurrent build fails, PostgreSQL leaves an invalid index under that name: EDDI refuses to build the
store, naming the index, until you `DROP INDEX` it and build again.

## Capabilities

Config-driven knowledge bases for LLM tasks, httpCall-based RAG, six vector stores and eight embedding providers, REST and scheduled ingestion (crawl a website, or upload PDF, Word, Excel, PowerPoint, text, Markdown, CSV and HTML files), and a RAG editor in the Manager. `eddi://ai.labs.rag` is a registered lifecycle extension, so a workflow can declare a knowledge-base step.

## Future Enhancements

- More ingestion source types — email, Google Drive, OneDrive (sitemap discovery already ships with web sources — see [Sitemaps](#sitemaps))
- OCR for scanned PDFs and images
- Advanced retrieval: re-ranking, hybrid search, metadata filtering
- ONNX in-process embeddings (air-gapped / edge deployments)
