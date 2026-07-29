/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
     * {@code taskType}</li>
     * <li>{@code "vertex"} — Google Vertex AI. Params: {@code project},
     * {@code location}, {@code model}</li>
     * </ul>
     */
    private String embeddingProvider = "openai";

    /**
     * Provider-specific parameters. Keys vary by provider:
     * <ul>
     * <li>OpenAI: {"model": "text-embedding-3-small", "apiKey":
     * "${vault:tenant/agent/openai-key}"}</li>
     * <li>Azure OpenAI: {"endpoint": "https://my.openai.azure.com/", "apiKey":
     * "${vault:...}", "deploymentName": "text-embedding-3-small"}</li>
     * <li>Ollama: {"model": "nomic-embed-text", "baseUrl":
     * "http://localhost:11434"}</li>
     * <li>Mistral: {"model": "mistral-embed", "apiKey": "${vault:...}"}</li>
     * <li>Bedrock: {"model": "amazon.titan-embed-text-v2:0", "region":
     * "us-east-1"}</li>
     * <li>Cohere: {"model": "embed-english-v3.0", "apiKey": "${vault:...}"}</li>
     * <li>Gemini: {"model": "gemini-embedding-001", "apiKey": "${vault:...}",
     * "taskType": "RETRIEVAL_DOCUMENT"}</li>
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

    /** The only chunking strategy the ingestion pipeline implements. */
    public static final String DEFAULT_CHUNK_STRATEGY = "recursive";

    /**
     * Chunking strategies the ingestion pipeline actually implements. Anything else
     * is either normalized (see {@link #LEGACY_CHUNK_STRATEGIES}) or rejected by
     * {@link #validate()} instead of being silently downgraded to recursive
     * splitting (finding I3).
     */
    public static final Set<String> SUPPORTED_CHUNK_STRATEGIES = Set.of(DEFAULT_CHUNK_STRATEGY);

    /**
     * Strategies this class' javadoc used to advertise as valid. They never had a
     * reader — ingestion has always built a recursive splitter — so configurations
     * carrying them are rewritten to {@code "recursive"} on write instead of being
     * rejected. Rejecting them would make knowledge bases that were created against
     * the old documentation (and that work perfectly) impossible to update, import
     * or duplicate.
     */
    public static final Set<String> LEGACY_CHUNK_STRATEGIES = Set.of("paragraph", "sentence");

    /**
     * Chunking strategy for ingestion. Only {@code "recursive"} is implemented —
     * ingestion builds a {@code DocumentSplitters.recursive} splitter
     * unconditionally.
     * <p>
     * Finding I3: {@code "paragraph"} and {@code "sentence"} were documented and
     * accepted but had zero readers, so they silently produced recursive chunking.
     * They are now {@linkplain #normalizeLegacyChunkStrategy() normalized} to
     * {@code "recursive"} when a configuration is written, and any other
     * unimplemented value is rejected by {@link #validate()} at the create/update
     * boundary. Retrieval never fails on this field — it is an ingestion-time
     * setting and already-embedded documents stay queryable regardless.
     */
    private String chunkStrategy = DEFAULT_CHUNK_STRATEGY;

    /** Chunk size in characters (default: 512) */
    private Integer chunkSize = 512;

    /** Chunk overlap in characters (default: 64) */
    private Integer chunkOverlap = 64;

    // --- Default Retrieval Parameters (overridable per LLM task) ---

    /** Default max results to return (top-K) */
    private Integer maxResults = 5;

    /** Default minimum similarity score (0.0–1.0) */
    private Double minScore = 0.6;

    /**
     * Describes every setting the engine cannot honour as written.
     * <p>
     * Read paths log this and carry on — a knowledge base whose documents are
     * already embedded stays fully retrievable no matter what {@code chunkStrategy}
     * says. {@link #validate()} turns the same finding into an exception, but only
     * at the create/update boundary.
     *
     * @return an actionable message, or {@code null} when the configuration is
     *         implementable as written
     */
    public String findUnsupportedSettings() {
        if (chunkStrategy == null || chunkStrategy.isBlank()) {
            return null;
        }
        if (SUPPORTED_CHUNK_STRATEGIES.contains(chunkStrategy.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }

        return "Unsupported chunkStrategy '" + chunkStrategy + "' for knowledge base '" + name
                + "'. Only recursive splitting is implemented (supported: " + SUPPORTED_CHUNK_STRATEGIES
                + "); documents are chunked recursively regardless.";
    }

    /**
     * Rewrites a historically documented but never implemented
     * {@code chunkStrategy} to the behavior ingestion has always applied.
     *
     * @return a message describing what was rewritten, or {@code null} when nothing
     *         changed
     */
    public String normalizeLegacyChunkStrategy() {
        if (chunkStrategy == null || chunkStrategy.isBlank()) {
            return null;
        }
        if (!LEGACY_CHUNK_STRATEGIES.contains(chunkStrategy.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }

        String legacyValue = chunkStrategy;
        chunkStrategy = DEFAULT_CHUNK_STRATEGY;
        return "chunkStrategy '" + legacyValue + "' is not implemented and has always produced recursive splitting"
                + " — stored as '" + DEFAULT_CHUNK_STRATEGY + "'.";
    }

    /**
     * Validate settings that the engine cannot honour as written. Called at the
     * create/update boundary so an unusable configuration can never be persisted;
     * read paths use {@link #findUnsupportedSettings()} and degrade gracefully
     * instead.
     *
     * @throws IllegalArgumentException
     *             with an actionable message when {@code chunkStrategy} names a
     *             strategy that is not implemented
     */
    public void validate() {
        String unsupported = findUnsupportedSettings();
        if (unsupported != null) {
            throw new IllegalArgumentException(unsupported);
        }
    }

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
