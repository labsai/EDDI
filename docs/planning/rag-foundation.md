# Phase 8c — RAG Foundation Implementation Plan

> Self-contained plan for implementing Retrieval-Augmented Generation in EDDI v6.
> A new conversation can implement this without further context.

---

## 1. Architecture Overview

RAG in EDDI follows the **McpCalls/HttpCalls workflow-extension pattern**:

1. **`RagConfiguration`** is a first-class versioned resource (like `McpCallsConfiguration`)
2. Admin creates KB configs via REST API (embedding model, vector store, chunking)
3. KB configs are referenced in workflows as extension steps (`eddi://ai.labs.rag`)
4. **LLM tasks explicitly reference which KBs they use** via `knowledgeBases` array
5. At execution time, `AgentOrchestrator` resolves KB references, performs retrieval, and injects context

### Why explicit references (not pure auto-discovery)

Unlike tools (which the LLM *chooses* to call), RAG context is *always* injected. If you have 3 LLM tasks in one workflow (e.g., "classify intent", "answer question", "summarize"), only the "answer question" task should get RAG context. Explicit `knowledgeBases` references in the task config make this clear and controllable.

### Resolution order for RAG in an LLM task

1. If `knowledgeBases` array is set → use those specific KBs with per-reference overrides
2. Else if `enableWorkflowRag: true` → auto-discover all RAG steps from workflow, use `ragDefaults`
3. Else → no RAG for this task

---

## 2. Reference Architecture — The McpCalls Pattern

RAG mirrors the file structure and patterns of `configs/mcpcalls/`:

| McpCalls File | RAG Equivalent |
|---------------|---------------|
| `configs/mcpcalls/model/McpCallsConfiguration.java` | `configs/rag/model/RagConfiguration.java` |
| `configs/mcpcalls/IMcpCallsStore.java` | `configs/rag/IRagStore.java` |
| `configs/mcpcalls/IRestMcpCallsStore.java` | `configs/rag/IRestRagStore.java` |
| `configs/mcpcalls/rest/RestMcpCallsStore.java` | `configs/rag/rest/RestRagStore.java` |
| `configs/mcpcalls/mongo/McpCallsStore.java` | `configs/rag/mongo/RagStore.java` |
| `AgentOrchestrator.discoverMcpCallTools()` | `AgentOrchestrator.discoverRagConfigs()` |

### Existing constants to follow

- Resource base type: `eddi://ai.labs.rag`
- Resource URI: `eddi://ai.labs.rag/ragstore/rag/`
- REST path: `/ragstore/rag`
- MongoDB collection name (for `AbstractResourceStore`): `rag`
- Workflow step type: `eddi://ai.labs.rag`

---

## 3. Configuration Model

### 3.1 RagConfiguration (new resource type)

```java
package ai.labs.eddi.configs.rag.model;

import java.util.Map;

/**
 * First-class versioned knowledge base configuration — analogous to
 * {@link ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration}.
 *
 * Defines HOW to embed and store documents, and default retrieval parameters.
 * Referenced in workflows and consumed by the LLM task at execution time.
 */
public class RagConfiguration {

    /** Display name for this knowledge base */
    private String name;

    // --- Embedding Model ---

    /**
     * Embedding model provider. Matches the LLM provider pattern.
     * Values: "openai", "ollama", "vertex", "google", "huggingface", "jlama"
     */
    private String embeddingProvider = "openai";

    /**
     * Provider-specific parameters. Keys vary by provider:
     * - OpenAI: {"model": "text-embedding-3-small", "apiKey": "${eddivault:openai-key}"}
     * - Ollama: {"model": "nomic-embed-text", "baseUrl": "http://localhost:11434"}
     * - Vertex: {"model": "text-embedding-005", "project": "my-project", "location": "us-central1"}
     */
    private Map<String, String> embeddingParameters;

    // --- Vector Store ---

    /**
     * Vector store type.
     * Values: "in-memory" (default, for dev/test), "pgvector", "mongodb-atlas", "qdrant"
     */
    private String storeType = "in-memory";

    /**
     * Store-specific connection parameters:
     * - pgvector: {"host": "localhost", "port": "5432", "database": "eddi", "table": "embeddings"}
     * - mongodb-atlas: {"indexName": "vector_index", "databaseName": "eddi"}
     * - qdrant: {"host": "localhost", "port": "6334", "collectionName": "kb-xxx"}
     * - in-memory: {} (no params needed)
     */
    private Map<String, String> storeParameters;

    /**
     * Tenant/KB isolation strategy.
     * - "collection" (default): each KB gets its own collection/table — stronger isolation
     * - "metadata": single store, filter by kbId metadata — simpler ops
     */
    private String isolationStrategy = "collection";

    // --- Chunking (for ingestion) ---

    /** Chunking strategy: "recursive" (default), "paragraph", "sentence" */
    private String chunkStrategy = "recursive";

    /** Chunk size in characters (default: 512) */
    private Integer chunkSize = 512;

    /** Chunk overlap in characters (default: 64) */
    private Integer chunkOverlap = 64;

    // --- Default Retrieval Parameters (overridable per LLM task) ---

    /** Default max results to return (top-K) */
    private Integer maxResults = 5;

    /** Default minimum similarity score (0.0–1.0) */
    private Double minScore = 0.6;

    // --- Getters and Setters ---
    // (follow standard Java bean pattern, same as McpCallsConfiguration)
}
```

### 3.2 LlmConfiguration.Task additions

Add these fields to `LlmConfiguration.Task` (alongside existing `enableHttpCallTools`, `enableMcpCallTools`, etc.):

```java
// === RAG Configuration ===

/**
 * Explicit knowledge base references. Each entry names a KB from the workflow
 * and optionally overrides retrieval parameters.
 *
 * Resolution: at execution time, AgentOrchestrator discovers all RAG steps
 * from the workflow (WorkflowTraversal), then matches by name.
 *
 * Example JSON:
 * "knowledgeBases": [
 *   { "name": "product-docs", "maxResults": 5, "minScore": 0.7 },
 *   { "name": "faq", "maxResults": 3 }
 * ]
 */
private List<KnowledgeBaseReference> knowledgeBases;

/**
 * Convenience flag: auto-discover all RAG steps from the workflow.
 * Only used when knowledgeBases is null/empty.
 * Default: false (explicit wiring preferred).
 */
private Boolean enableWorkflowRag = false;

/**
 * Default retrieval parameters when using enableWorkflowRag=true.
 * Ignored when knowledgeBases is set (each reference has its own overrides).
 */
private RagDefaults ragDefaults;

/**
 * Zero-infrastructure RAG: name of an httpCall in the workflow to
 * execute before the LLM call. The httpCall's response is injected
 * as context into the system message.
 * This is Phase 8c-0 (quick-win, no vector store needed).
 */
private String httpCallRag;
```

### 3.3 KnowledgeBaseReference (new inner class in LlmConfiguration)

```java
/**
 * Reference from an LLM task to a specific knowledge base in the workflow.
 * The name must match a RagConfiguration.name in the workflow.
 */
public static class KnowledgeBaseReference {
    /** Name of the RagConfiguration resource in the workflow */
    private String name;

    /** Override: max results (null = use KB default) */
    private Integer maxResults;

    /** Override: min similarity score (null = use KB default) */
    private Double minScore;

    /** Override: injection strategy — "system_message" (default), "user_message" */
    private String injectionStrategy;

    /** Override: custom context template (null = use default formatting) */
    private String contextTemplate;

    // Getters and setters
}
```

### 3.4 RagDefaults (new inner class in LlmConfiguration)

```java
/**
 * Default retrieval parameters for enableWorkflowRag=true mode.
 */
public static class RagDefaults {
    private Integer maxResults = 5;
    private Double minScore = 0.6;
    private String injectionStrategy = "system_message";

    // Getters and setters
}
```

### 3.5 Deprecation of existing field

The existing `Task.retrievalAugmentor` field (`RetrievalAugmentorConfiguration`) should be marked `@Deprecated` and eventually removed. The new `knowledgeBases` / `enableWorkflowRag` / `httpCallRag` fields replace it.

---

## 4. Sample JSON Configurations

### 4.1 RagConfiguration resource

```json
{
  "name": "product-docs",
  "embeddingProvider": "openai",
  "embeddingParameters": {
    "model": "text-embedding-3-small",
    "apiKey": "${eddivault:openai-key}"
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

### 4.2 Workflow referencing RAG step

```json
{
  "workflowSteps": [
    { "type": "eddi://ai.labs.rules", "config": { "uri": "eddi://ai.labs.rules/rulestore/rules/abc?version=1" } },
    { "type": "eddi://ai.labs.rag", "config": { "uri": "eddi://ai.labs.rag/ragstore/rag/kb001?version=1" } },
    { "type": "eddi://ai.labs.llm", "config": { "uri": "eddi://ai.labs.llm/llmstore/llms/xyz?version=1" } },
    { "type": "eddi://ai.labs.output", "config": { "uri": "eddi://ai.labs.output/outputstore/outputsets/out1?version=1" } }
  ]
}
```

### 4.3 LLM task config referencing KB (explicit mode)

```json
{
  "tasks": [{
    "id": "answer-question",
    "type": "openai",
    "actions": ["answer"],
    "parameters": {
      "model": "gpt-4o",
      "systemMessage": "You are a product expert. Use the provided context to answer questions.",
      "apiKey": "${eddivault:openai-key}"
    },
    "knowledgeBases": [
      { "name": "product-docs", "maxResults": 5, "minScore": 0.7 },
      { "name": "faq", "maxResults": 3, "minScore": 0.5 }
    ]
  }, {
    "id": "classify-intent",
    "type": "openai",
    "actions": ["classify"],
    "parameters": {
      "model": "gpt-4o-mini",
      "systemMessage": "Classify the user's intent."
    }
  }]
}
```

### 4.4 LLM task config with auto-discovery (convenience mode)

```json
{
  "tasks": [{
    "type": "openai",
    "actions": ["*"],
    "parameters": { "systemMessage": "You are helpful." },
    "enableWorkflowRag": true,
    "ragDefaults": { "maxResults": 5, "minScore": 0.6 }
  }]
}
```

### 4.5 LLM task config with httpCall RAG (zero-infra mode, Phase 8c-0)

```json
{
  "tasks": [{
    "type": "openai",
    "actions": ["*"],
    "parameters": { "systemMessage": "You are helpful." },
    "httpCallRag": "search-knowledge-base"
  }]
}
```

---

## 5. Runtime Flow

### 5.1 RAG resolution in AgentOrchestrator

New method: `discoverRagConfigs()` — mirrors `discoverMcpCallTools()`:

```java
private static final String RAG_TYPE = "eddi://ai.labs.rag";

/**
 * Discovers RAG configurations from the workflow and performs retrieval.
 * Returns formatted context string for injection into LLM messages.
 */
String discoverAndRetrieveRagContext(
        IConversationMemory memory,
        LlmConfiguration.Task task,
        String userQuery) {

    // Step 1: Determine which KBs to use
    List<KnowledgeBaseReference> kbRefs = task.getKnowledgeBases();
    boolean useWorkflowDiscovery = (kbRefs == null || kbRefs.isEmpty())
            && Boolean.TRUE.equals(task.getEnableWorkflowRag());

    if ((kbRefs == null || kbRefs.isEmpty()) && !useWorkflowDiscovery) {
        return null;  // No RAG for this task
    }

    // Step 2: Discover all RAG configs from workflow
    var ragSteps = WorkflowTraversal.discoverConfigs(
            memory, RAG_TYPE, RagConfiguration.class,
            restAgentStore, restWorkflowStore, resourceClientLibrary);

    if (ragSteps.isEmpty()) return null;

    // Step 3: Match KBs by name (or use all if auto-discovery)
    List<RetrievalResult> allResults = new ArrayList<>();

    for (var step : ragSteps) {
        RagConfiguration ragConfig = step.config();
        String kbName = ragConfig.getName();

        // Determine retrieval params
        int maxResults;
        double minScore;

        if (useWorkflowDiscovery) {
            // Use ragDefaults or KB defaults
            var defaults = task.getRagDefaults();
            maxResults = defaults != null && defaults.getMaxResults() != null
                    ? defaults.getMaxResults() : ragConfig.getMaxResults();
            minScore = defaults != null && defaults.getMinScore() != null
                    ? defaults.getMinScore() : ragConfig.getMinScore();
        } else {
            // Find matching reference
            var ref = kbRefs.stream()
                    .filter(r -> kbName.equals(r.getName()))
                    .findFirst()
                    .orElse(null);
            if (ref == null) continue;  // This KB not referenced by task

            maxResults = ref.getMaxResults() != null
                    ? ref.getMaxResults() : ragConfig.getMaxResults();
            minScore = ref.getMinScore() != null
                    ? ref.getMinScore() : ragConfig.getMinScore();
        }

        // Step 4: Build EmbeddingModel + EmbeddingStore + ContentRetriever
        EmbeddingModel embeddingModel = buildEmbeddingModel(ragConfig);
        EmbeddingStore<TextSegment> store = getOrCreateStore(ragConfig);

        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        // Step 5: Retrieve
        List<Content> relevant = retriever.retrieve(Query.from(userQuery));
        allResults.addAll(relevant.stream()
                .map(c -> new RetrievalResult(kbName, c))
                .toList());
    }

    if (allResults.isEmpty()) return null;

    // Step 6: Format context
    return formatRagContext(allResults);
}
```

### 5.2 Context injection in LlmTask

In `LlmTask.executeTask()`, after building `systemMessage` and before building chat messages (around line 156):

```java
// === RAG Context Injection ===
// httpCall RAG (Phase 8c-0): execute named httpCall, inject response
String httpCallRag = task.getHttpCallRag();
if (httpCallRag != null && !httpCallRag.isBlank()) {
    String httpRagContext = executeHttpCallForRag(memory, httpCallRag, templateDataObjects);
    if (httpRagContext != null) {
        systemMessage += "\n\n## Relevant Context:\n" + httpRagContext;
    }
}

// Vector store RAG (Phase 8c-α+): retrieve from knowledge bases
String userInput = extractUserInput(memory);
if (userInput != null) {
    String ragContext = agentOrchestrator.discoverAndRetrieveRagContext(memory, task, userInput);
    if (ragContext != null) {
        systemMessage += "\n\n## Relevant Context:\n" + ragContext;
    }
}
```

This works for **both** legacy chat mode and agent mode — the augmented system message flows through to either path.

### 5.3 Audit trail

RAG retrieval results are stored in memory for observability:

```java
// After retrieval, store in memory for audit
var ragTraceData = dataFactory.createData("rag:trace:" + task.getId(), ragTraceEntries);
currentStep.storeData(ragTraceData);

var ragContextData = dataFactory.createData("rag:context:" + task.getId(), formattedContext);
currentStep.storeData(ragContextData);
```

This satisfies Pillar 6 (Transparent Observability). The debugger/Manager UI can display:
- Which KBs were queried
- What chunks were retrieved (with scores)
- What context was injected into the prompt

---

## 6. Embedding Model Factory

New class: `modules/llm/impl/EmbeddingModelFactory.java`

Builds `EmbeddingModel` instances from `RagConfiguration`:

```java
/**
 * Creates EmbeddingModel instances based on RagConfiguration.
 * Caches models by provider+parameters hash for reuse.
 * Follows the same pattern as ChatModelRegistry for LLM models.
 */
@ApplicationScoped
public class EmbeddingModelFactory {

    private final Map<String, EmbeddingModel> cache = new ConcurrentHashMap<>();
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingModelFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public EmbeddingModel getOrCreate(RagConfiguration config) {
        String cacheKey = config.getEmbeddingProvider() + ":" +
                config.getEmbeddingParameters().hashCode();
        return cache.computeIfAbsent(cacheKey, k -> build(config));
    }

    private EmbeddingModel build(RagConfiguration config) {
        Map<String, String> params = resolveSecrets(config.getEmbeddingParameters());
        return switch (config.getEmbeddingProvider()) {
            case "openai" -> OpenAiEmbeddingModel.builder()
                    .modelName(params.getOrDefault("model", "text-embedding-3-small"))
                    .apiKey(params.get("apiKey"))
                    .build();
            case "ollama" -> OllamaEmbeddingModel.builder()
                    .modelName(params.getOrDefault("model", "nomic-embed-text"))
                    .baseUrl(params.getOrDefault("baseUrl", "http://localhost:11434"))
                    .build();
            // ... vertex, google, huggingface, jlama
            default -> throw new IllegalArgumentException(
                    "Unsupported embedding provider: " + config.getEmbeddingProvider());
        };
    }
}
```

---

## 7. Vector Store Factory

New class: `modules/llm/impl/EmbeddingStoreFactory.java`

```java
/**
 * Creates EmbeddingStore instances based on RagConfiguration.
 * Handles isolation strategy (collection-per-KB vs metadata filtering).
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private final Map<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();

    public EmbeddingStore<TextSegment> getOrCreate(RagConfiguration config, String kbId) {
        String cacheKey = config.getStoreType() + ":" + kbId;
        return cache.computeIfAbsent(cacheKey, k -> build(config, kbId));
    }

    private EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
        return switch (config.getStoreType()) {
            case "in-memory" -> new InMemoryEmbeddingStore<>();
            case "pgvector" -> buildPgVector(config, kbId);
            // case "mongodb-atlas" -> buildMongoDbAtlas(config, kbId);
            // case "qdrant" -> buildQdrant(config, kbId);
            default -> throw new IllegalArgumentException(
                    "Unsupported store type: " + config.getStoreType());
        };
    }

    private EmbeddingStore<TextSegment> buildPgVector(RagConfiguration config, String kbId) {
        Map<String, String> params = config.getStoreParameters();
        String tableName = "collection".equals(config.getIsolationStrategy())
                ? "embeddings_" + kbId.replaceAll("[^a-zA-Z0-9_]", "_")
                : "embeddings";

        return PgVectorEmbeddingStore.builder()
                .host(params.getOrDefault("host", "localhost"))
                .port(Integer.parseInt(params.getOrDefault("port", "5432")))
                .database(params.getOrDefault("database", "eddi"))
                .user(params.getOrDefault("user", "eddi"))
                .password(params.getOrDefault("password", ""))
                .table(tableName)
                .dimension(Integer.parseInt(params.getOrDefault("dimension", "1536")))
                .createTable(true)
                .build();
    }
}
```

---

## 8. Ingestion Pipeline

### 8.1 Async ingestion via NATS (preferred) or virtual threads (fallback)

```java
@ApplicationScoped
public class RagIngestionService {

    @Inject @ConfigProperty(name = "eddi.nats.enabled", defaultValue = "false")
    boolean natsEnabled;

    // NATS subject: "eddi.rag.ingest.{kbId}"

    /**
     * Ingest a document into a knowledge base.
     * Uses NATS JetStream if available, otherwise runs on a virtual thread.
     */
    public String ingest(String kbId, String documentContent, String documentName,
                         RagConfiguration ragConfig) {
        String ingestionId = UUID.randomUUID().toString();

        if (natsEnabled) {
            publishToNats(kbId, ingestionId, documentContent, documentName);
        } else {
            Thread.startVirtualThread(() ->
                    processIngestion(kbId, ingestionId, documentContent, documentName, ragConfig));
        }

        return ingestionId;  // caller can poll status
    }

    private void processIngestion(String kbId, String ingestionId,
                                   String documentContent, String documentName,
                                   RagConfiguration ragConfig) {
        // 1. Parse document (PDF, TXT, etc.)
        Document document = Document.from(documentContent,
                Metadata.from("source", documentName).put("kbId", kbId));

        // 2. Chunk
        DocumentSplitter splitter = DocumentSplitters.recursive(
                ragConfig.getChunkSize(), ragConfig.getChunkOverlap());

        // 3. Embed + Store
        EmbeddingModel model = embeddingModelFactory.getOrCreate(ragConfig);
        EmbeddingStore<TextSegment> store = embeddingStoreFactory.getOrCreate(ragConfig, kbId);

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(model)
                .embeddingStore(store)
                .build();

        ingestor.ingest(document);

        // 4. Update status
        updateIngestionStatus(ingestionId, "completed");
    }
}
```

### 8.2 Ingestion REST API

```
POST   /ragstore/rag                              ← create KB config
GET    /ragstore/rag/descriptors                   ← list KB descriptors
GET    /ragstore/rag/{id}                          ← read KB config
PUT    /ragstore/rag/{id}                          ← update KB config
DELETE /ragstore/rag/{id}                          ← delete KB config

POST   /ragstore/rag/{id}/documents                ← upload document (async)
GET    /ragstore/rag/{id}/documents                ← list ingested documents
DELETE /ragstore/rag/{id}/documents/{docId}         ← remove document + embeddings
GET    /ragstore/rag/{id}/documents/{docId}/status  ← ingestion progress
```

---

## 9. Files to Create / Modify

### New files

| # | File | Lines (est.) | Description |
|---|------|-------------|-------------|
| 1 | `configs/rag/model/RagConfiguration.java` | ~120 | Configuration POJO (see §3.1) |
| 2 | `configs/rag/IRagStore.java` | ~15 | Store interface extending `IResourceStore<RagConfiguration>` |
| 3 | `configs/rag/IRestRagStore.java` | ~70 | JAX-RS interface (see McpCalls pattern) |
| 4 | `configs/rag/rest/RestRagStore.java` | ~100 | REST implementation |
| 5 | `configs/rag/mongo/RagStore.java` | ~25 | MongoDB store (extends `AbstractResourceStore`) |
| 6 | `modules/llm/impl/EmbeddingModelFactory.java` | ~80 | EmbeddingModel creation + caching |
| 7 | `modules/llm/impl/EmbeddingStoreFactory.java` | ~80 | EmbeddingStore creation + caching |
| 8 | `modules/llm/impl/RagContextProvider.java` | ~120 | Retrieval logic: discover → match → retrieve → format |
| 9 | `modules/rag/RagIngestionService.java` | ~100 | Document ingestion (NATS or virtual thread) |

### Modified files

| # | File | Change |
|---|------|--------|
| 1 | `modules/llm/model/LlmConfiguration.java` | Add `knowledgeBases`, `enableWorkflowRag`, `ragDefaults`, `httpCallRag` fields + inner classes `KnowledgeBaseReference`, `RagDefaults`. Deprecate `retrievalAugmentor` field. |
| 2 | `modules/llm/impl/AgentOrchestrator.java` | Add `discoverRagConfigs()` method (mirrors `discoverMcpCallTools()`). Inject `EmbeddingModelFactory` and `EmbeddingStoreFactory`. |
| 3 | `modules/llm/impl/LlmTask.java` | Add RAG context injection before message building (both httpCall and vector store paths). Inject `RagContextProvider`. |

### POM dependencies

```xml
<!-- Phase 8c-α: In-memory embedding store (already in langchain4j core, no new dep) -->

<!-- Phase 8c-β: pgvector -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-pgvector</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

---

## 10. Phased Delivery

### Phase 8c-0 — httpCall RAG (2 SP)

**Goal**: Zero-infrastructure RAG. Execute a named httpCall and inject its response as context.

**Files**: Modify `LlmConfiguration.java` (add `httpCallRag` field), modify `LlmTask.java` (6-line injection block).

**Test**: Create an httpCall that returns fake search results, configure `httpCallRag` in LLM task, verify the response includes context.

---

### Phase 8c-α — Full RAG resource type + in-memory store (5 SP)

**Goal**: Config-driven RAG with explicit KB references, in-memory vector store.

**Files**: All 9 new files + 3 modified files listed in §9.

**Commit order**:
1. `RagConfiguration` POJO + store + REST API (files 1-5)
2. `EmbeddingModelFactory` + `EmbeddingStoreFactory` (files 6-7)
3. `RagContextProvider` + wiring into `AgentOrchestrator` + `LlmTask` (file 8 + mods)
4. `LlmConfiguration.Task` additions (mod 1)
5. Tests

**Test**: Create RagConfiguration via REST, add to workflow, configure LLM task with `knowledgeBases` reference, ingest a test document, verify retrieved context appears in LLM response.

---

### Phase 8c-β — Persistent vector stores (3 SP)

**Goal**: pgvector integration with collection-per-KB isolation.

**Files**: Add `langchain4j-pgvector` dep, extend `EmbeddingStoreFactory` with `buildPgVector()`.

**Test**: Full cycle with pgvector Testcontainer: create KB → ingest → retrieve → verify.

---

### Phase 8c-γ — Ingestion pipeline (3 SP)

**Goal**: Async document upload with NATS-first, virtual-thread fallback.

**Files**: `RagIngestionService.java`, extend REST API with document endpoints.

**Test**: Upload a PDF, verify chunking + embedding + storage, poll ingestion status.

---

### Phase 10b — Advanced RAG (3 SP, future)

**Goal**: Query transformation, re-ranking, multi-KB retrieval, RAG-as-Tool option.

---

## 11. Verification Plan

### Unit tests

All test files in `src/test/java/ai/labs/eddi/`:

| Test Class | What It Verifies |
|-----------|-----------------|
| `configs/rag/rest/RestRagStoreTest.java` | CRUD operations for RagConfiguration |
| `modules/llm/impl/RagContextProviderTest.java` | KB matching logic: explicit refs, auto-discovery, no-RAG case |
| `modules/llm/impl/EmbeddingModelFactoryTest.java` | Model creation, caching, secret resolution |
| `modules/llm/impl/EmbeddingStoreFactoryTest.java` | Store creation, isolation strategies |
| `modules/rag/RagIngestionServiceTest.java` | Ingestion pipeline: chunk → embed → store |

### Integration test

Extend existing test suite:

```bash
# Run from EDDI root
./mvnw test -pl . -Dtest="RagIntegrationTest"
```

- Create agent with workflow containing RAG step
- Ingest sample document (small text)
- Send conversation message
- Assert retrieved context appears in conversation memory
- Assert audit trail contains RAG trace entries

### Build verification

```bash
npx tsc -b          # (Manager only if UI changes needed)
./mvnw compile       # Zero compilation errors
./mvnw test          # All tests pass
./mvnw package       # Production build succeeds
```

---

## 12. Manager UI Integration (Future)

When extending the Manager UI to support RAG:

1. Add `rag` to `RESOURCE_TYPES` in `src/lib/api/resources.ts` with:
   - slug: `rag`
   - store: `ragstore`
   - plural: `rag`
2. Create `RagEditor` component (form for embedding provider, store type, etc.)
3. Add RAG step support in the workflow pipeline builder
4. Add KB reference UI in the LangChain/LLM task editor

---

## 13. Cross-References

| Document | Purpose |
|----------|---------|
| `AGENTS.md` (this repo) | Project context, architecture patterns, task checklist |
| `docs/project-philosophy.md` | The 7 Pillars — especially Pillar 3 (new capability = new task) and Pillar 6 (observability) |
| `docs/architecture.md` | Configuration model, pipeline, DB-agnostic design |
| `docs/changelog.md` | Log all RAG implementation decisions and commits here |
| `configs/mcpcalls/` | Reference template for the full resource type stack |
| `modules/llm/impl/WorkflowTraversal.java` | Shared utility for discovering extension configs from workflows |
| `modules/llm/impl/AgentOrchestrator.java` | Where RAG discovery + retrieval integrates |
| `modules/llm/impl/LlmTask.java` | Where RAG context injection happens |
