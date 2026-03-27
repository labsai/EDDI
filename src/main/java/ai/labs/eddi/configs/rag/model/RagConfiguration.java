package ai.labs.eddi.configs.rag.model;

import java.util.Map;

/**
 * First-class versioned knowledge base configuration — analogous to
 * {@link ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration}.
 *
 * <p>
 * Defines HOW to embed and store documents, and default retrieval parameters.
 * Referenced in workflows and consumed by the LLM task at execution time.
 * </p>
 */
public class RagConfiguration {

    /** Display name for this knowledge base */
    private String name;

    // --- Embedding Model ---

    /**
     * Embedding model provider. Matches the LLM provider pattern. Values: "openai",
     * "ollama", "vertex", "google", "huggingface", "jlama"
     */
    private String embeddingProvider = "openai";

    /**
     * Provider-specific parameters. Keys vary by provider:
     * <ul>
     * <li>OpenAI: {"model": "text-embedding-3-small", "apiKey":
     * "${vault:openai-key}"}</li>
     * <li>Ollama: {"model": "nomic-embed-text", "baseUrl":
     * "http://localhost:11434"}</li>
     * <li>Vertex: {"model": "text-embedding-005", "project": "my-project",
     * "location": "us-central1"}</li>
     * </ul>
     */
    private Map<String, String> embeddingParameters;

    // --- Vector Store ---

    /**
     * Vector store type. Values: "in-memory" (default, for dev/test), "pgvector",
     * "mongodb-atlas", "qdrant"
     */
    private String storeType = "in-memory";

    /**
     * Store-specific connection parameters:
     * <ul>
     * <li>pgvector: {"host": "localhost", "port": "5432", "database": "eddi",
     * "table": "embeddings"}</li>
     * <li>mongodb-atlas: {"indexName": "vector_index", "databaseName": "eddi"}</li>
     * <li>qdrant: {"host": "localhost", "port": "6334", "collectionName":
     * "kb-xxx"}</li>
     * <li>in-memory: {} (no params needed)</li>
     * </ul>
     */
    private Map<String, String> storeParameters;

    /**
     * Tenant/KB isolation strategy.
     * <ul>
     * <li>"collection" (default): each KB gets its own collection/table — stronger
     * isolation</li>
     * <li>"metadata": single store, filter by kbId metadata — simpler ops</li>
     * </ul>
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public Map<String, String> getEmbeddingParameters() {
        return embeddingParameters;
    }

    public void setEmbeddingParameters(Map<String, String> embeddingParameters) {
        this.embeddingParameters = embeddingParameters;
    }

    public String getStoreType() {
        return storeType;
    }

    public void setStoreType(String storeType) {
        this.storeType = storeType;
    }

    public Map<String, String> getStoreParameters() {
        return storeParameters;
    }

    public void setStoreParameters(Map<String, String> storeParameters) {
        this.storeParameters = storeParameters;
    }

    public String getIsolationStrategy() {
        return isolationStrategy;
    }

    public void setIsolationStrategy(String isolationStrategy) {
        this.isolationStrategy = isolationStrategy;
    }

    public String getChunkStrategy() {
        return chunkStrategy;
    }

    public void setChunkStrategy(String chunkStrategy) {
        this.chunkStrategy = chunkStrategy;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(Integer chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }
}
