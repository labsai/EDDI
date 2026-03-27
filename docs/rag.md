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
    "apiKey": "${vault:openai-key}"
  },
  "storeType": "in-memory",
  "storeParameters": {},
  "isolationStrategy": "collection",
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
| `embeddingProvider` | `openai` | Provider: `openai`, `ollama` |
| `embeddingParameters` | — | Provider-specific params (model, apiKey, baseUrl) |
| `storeType` | `in-memory` | Vector store: `in-memory` (dev), `pgvector` (persistent) |
| `storeParameters` | — | Store-specific connection params |
| `isolationStrategy` | `collection` | `collection` (per-KB store) or `metadata` (shared store) |
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

## REST API

| Method | Path | Description |
|---|---|---|
| `GET` | `/ragstore/rags/jsonSchema` | JSON Schema for validation |
| `GET` | `/ragstore/rags/descriptors` | List KB descriptors |
| `GET` | `/ragstore/rags/{id}?version=N` | Read a KB configuration |
| `POST` | `/ragstore/rags` | Create a new KB |
| `PUT` | `/ragstore/rags/{id}?version=N` | Update a KB |
| `POST` | `/ragstore/rags/{id}?version=N` | Duplicate a KB |
| `DELETE` | `/ragstore/rags/{id}?version=N` | Delete a KB |

## Observability

RAG operations write audit traces to conversation memory:

| Memory Key | Content |
|---|---|
| `rag:trace:{taskId}` | Per-KB retrieval metadata (provider, storeType, maxResults, minScore, retrievedCount) |
| `rag:context:{taskId}` | Formatted context string injected into the LLM |
| `rag:httpcall:trace:{taskId}` | httpCall RAG execution metadata (httpCall name, context length) |

These are visible in the conversation memory snapshot and the audit ledger.

## Document Ingestion

The `RagIngestionService` provides async document ingestion:

1. Documents are split into chunks using the configured strategy
2. Chunks are embedded using the configured embedding model
3. Embeddings are stored in the configured vector store
4. Status can be polled via ingestion ID

Ingestion runs on virtual threads (Java 21+) for non-blocking operation.

## Embedding Providers

| Provider | Model Default | Notes |
|---|---|---|
| `openai` | `text-embedding-3-small` | Requires `apiKey` (use `${vault:...}`) |
| `ollama` | `nomic-embed-text` | Requires `baseUrl` (default: `localhost:11434`) |

## Status

- ✅ **Phase 8c**: RAG Foundation — config-driven knowledge base retrieval
- ✅ **Phase 8c-0**: httpCall-based RAG (zero infrastructure)
- ✅ **Phase 8c-β**: Persistent vector stores (pgvector)

## Future Enhancements

- Additional vector stores: MongoDB Atlas, Qdrant
- **Manager UI**: RAG configuration editing in the admin dashboard
- Advanced retrieval: re-ranking, hybrid search, metadata filtering
