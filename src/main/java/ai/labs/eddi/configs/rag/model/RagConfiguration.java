/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
     * Embedding model provider. Values:
     * <ul>
     * <li>{@code "openai"} — OpenAI (default). Params: {@code model},
     * {@code apiKey}</li>
     * <li>{@code "azure-openai"} — Azure OpenAI. Params: {@code endpoint},
     * {@code apiKey}, {@code deploymentName}</li>
     * <li>{@code "ollama"} — Ollama (local). Params: {@code model},
     * {@code baseUrl}</li>
     * <li>{@code "mistral"} — Mistral AI. Params: {@code model},
     * {@code apiKey}</li>
     * <li>{@code "bedrock"} — Amazon Bedrock Titan. Params: {@code model},
     * {@code region}</li>
     * <li>{@code "cohere"} — Cohere. Params: {@code model}, {@code apiKey}</li>
     * <li>{@code "gemini"} — Google Gemini. Params: {@code model}, {@code apiKey},
     * {@code tasktype}</li>
     * <li>{@code "vertex"} — Google Vertex AI. Params: {@code project},
     * {@code location}, {@code model}</li>
     * </ul>
     */
    private String embeddingProvider = "openai";

    /**
     * Provider-specific parameters. Keys vary by provider:
     * <ul>
     * <li>OpenAI: {"model": "text-embedding-3-small", "apiKey":
     * "${eddivault:tenant/agent/openai-key}"}</li>
     * <li>Azure OpenAI: {"endpoint": "https://my.openai.azure.com/", "apiKey":
     * "${eddivault:...}", "deploymentName": "text-embedding-3-small"}</li>
     * <li>Ollama: {"model": "nomic-embed-text", "baseUrl":
     * "http://localhost:11434"}</li>
     * <li>Mistral: {"model": "mistral-embed", "apiKey": "${eddivault:...}"}</li>
     * <li>Bedrock: {"model": "amazon.titan-embed-text-v2:0", "region":
     * "us-east-1"}</li>
     * <li>Cohere: {"model": "embed-english-v3.0", "apiKey":
     * "${eddivault:...}"}</li>
     * <li>Gemini: {"model": "gemini-embedding-001", "apiKey": "${eddivault:...}",
     * "tasktype": "RETRIEVAL_DOCUMENT"}</li>
     * <li>Vertex: {"project": "my-project", "location": "us-central1", "model":
     * "text-embedding-005"}</li>
     * </ul>
     */
    private Map<String, String> embeddingParameters;

    // --- Vector Store ---

    /**
     * Vector store type. Values:
     * <ul>
     * <li>{@code "in-memory"} (default) — Ephemeral, for dev/test</li>
     * <li>{@code "pgvector"} — PostgreSQL + pgvector extension</li>
     * <li>{@code "mongodb-atlas"} — MongoDB Atlas Vector Search</li>
     * <li>{@code "elasticsearch"} — Elasticsearch vector search</li>
     * <li>{@code "qdrant"} — Qdrant vector database</li>
     * <li>{@code "chroma"} — ChromaDB vector database</li>
     * </ul>
     */
    private String storeType = "in-memory";

    /**
     * Store-specific connection parameters:
     * <ul>
     * <li>pgvector: {"host", "port", "database", "user", "password", "table",
     * "dimension"}</li>
     * <li>mongodb-atlas: {"connectionString", "databaseName", "collectionName",
     * "indexName"}</li>
     * <li>elasticsearch: {"serverUrl", "indexName", "apiKey", "userName",
     * "password"}</li>
     * <li>qdrant: {"host", "port", "collectionName", "apiKey", "useTls"}</li>
     * <li>chroma: {"baseUrl", "tenantName", "databaseName", "collectionName"}</li>
     * <li>in-memory: {} (no params needed)</li>
     * </ul>
     */
    private Map<String, String> storeParameters;

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
