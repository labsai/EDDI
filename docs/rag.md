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

RAG is wired into LLM tasks via three fields on `LlmConfiguration.Task`:

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
| `vertex` | `text-embedding-005` | `project` | `location` (default: `us-central1`); uses GCP credentials |

## Vector Stores

| Store Type | Required Parameters | Notes |
|---|---|---|
| `in-memory` | — | Ephemeral, for dev/test only |
| `pgvector` | `password` | PostgreSQL + pgvector; `host`, `port`, `database`, `user`, `table`, `dimension` |
| `mongodb-atlas` | `connectionString` | MongoDB Atlas Vector Search; `databaseName`, `collectionName`, `indexName` |
| `elasticsearch` | — | `serverUrl` (default: `localhost:9200`); optional `apiKey` or `userName`+`password`; `indexName` |
| `qdrant` | — | `host` (default: `localhost`), `port` (default: `6334`); optional `apiKey`, `useTls`; `collectionName` |

## Status

- ✅ **Phase 8c**: RAG Foundation — config-driven knowledge base retrieval
- ✅ **Phase 8c-0**: httpCall-based RAG (zero infrastructure)
- ✅ **Phase 8c-β**: Persistent vector stores (pgvector)
- ✅ **Phase 8c-γ**: RAG provider expansion (8 embedding models + 6 vector stores)
- ✅ **Phase 8c-M**: Manager UI — RAG editor with full provider parity + document ingestion
- ✅ **REST ingestion endpoint**: `POST /ragstore/rags/{id}/ingest`

## Future Enhancements

- Advanced retrieval: re-ranking, hybrid search, metadata filtering
- ONNX in-process embeddings (air-gapped / edge deployments)
