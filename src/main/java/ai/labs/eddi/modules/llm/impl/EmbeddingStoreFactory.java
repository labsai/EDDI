package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches {@link EmbeddingStore} instances based on
 * {@link RagConfiguration}.
 * <p>
 * Each knowledge base gets its own store instance (collection-per-KB
 * isolation). Supported store types: {@code in-memory}, {@code pgvector},
 * {@code mongodb-atlas}, {@code elasticsearch}, {@code qdrant}.
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFactory.class);
    private static final int MAX_PG_IDENTIFIER_LENGTH = 63;

    private final Map<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();
    private final Map<String, MongoClient> mongoClientCache = new ConcurrentHashMap<>();
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingStoreFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * Returns a cached or newly created embedding store for the given configuration
     * and knowledge base ID.
     */
    public EmbeddingStore<TextSegment> getOrCreate(RagConfiguration config, String kbId) {
        // Include storeParameters in cache key (different connection params = different
        // store)
        String paramsKey = config.getStoreParameters() != null ? new TreeMap<>(config.getStoreParameters()).toString() : "";
        String cacheKey = config.getStoreType() + ":" + kbId + ":" + paramsKey;
        return cache.computeIfAbsent(cacheKey, k -> build(config, kbId));
    }

    private EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
        String storeType = config.getStoreType();
        LOGGER.infof("Building embedding store: type=%s, kbId=%s", storeType, kbId);

        return switch (storeType) {
            case "in-memory" -> new InMemoryEmbeddingStore<>();
            case "pgvector" -> buildPgVector(config, kbId);
            case "mongodb-atlas" -> buildMongoDbAtlas(config, kbId);
            case "elasticsearch" -> buildElasticsearch(config, kbId);
            case "qdrant" -> buildQdrant(config, kbId);
            default -> throw new IllegalArgumentException(
                    "Unsupported store type: " + storeType + ". Supported: in-memory, pgvector, mongodb-atlas, elasticsearch, qdrant");
        };
    }

    // ──────────────────────────────────────────────────
    // pgvector
    // ──────────────────────────────────────────────────

    /**
     * Builds a pgvector-backed {@link EmbeddingStore} from configuration
     * parameters.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code host} — PostgreSQL host (default: "localhost")</li>
     * <li>{@code port} — PostgreSQL port (default: 5432)</li>
     * <li>{@code database} — database name (default: "eddi")</li>
     * <li>{@code user} — database user (default: "eddi")</li>
     * <li>{@code password} — database password, supports {@code ${eddivault:...}}
     * (required)</li>
     * <li>{@code table} — table name (default: auto-generated from kbId)</li>
     * <li>{@code dimension} — embedding vector dimension (default: 1536 for OpenAI
     * text-embedding-3-small)</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildPgVector(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String host = params.getOrDefault("host", "localhost");
        int port = parseIntParam(params, "port", 5432);
        String database = params.getOrDefault("database", "eddi");
        String user = params.getOrDefault("user", "eddi");
        String password = requireParam(params, "password", "pgvector");
        int dimension = parseIntParam(params, "dimension", 1536);

        // Table name: use explicit param, or derive a safe name from kbId
        String table = params.getOrDefault("table", sanitizeTableName(kbId));

        LOGGER.infof("Building pgvector store: host=%s, port=%d, database=%s, table=%s, dimension=%d", host, port, database, table, dimension);

        return PgVectorEmbeddingStore.builder().host(host).port(port).database(database).user(user).password(password).table(table)
                .dimension(dimension).createTable(true).build();
    }

    // ──────────────────────────────────────────────────
    // MongoDB Atlas
    // ──────────────────────────────────────────────────

    /**
     * Builds a MongoDB Atlas Vector Search-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code connectionString} — MongoDB connection string (required, supports
     * {@code ${eddivault:...}})</li>
     * <li>{@code databaseName} — database name (default: "eddi")</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * <li>{@code indexName} — Atlas Search index name (default:
     * "vector_index")</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildMongoDbAtlas(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String connectionString = requireParam(params, "connectionString", "mongodb-atlas");
        String databaseName = params.getOrDefault("databaseName", "eddi");
        String collectionName = params.getOrDefault("collectionName", "eddi_kb_" + kbId);
        String indexName = params.getOrDefault("indexName", "vector_index");

        LOGGER.infof("Building MongoDB Atlas store: database=%s, collection=%s, index=%s", databaseName, collectionName, indexName);

        MongoClient mongoClient = mongoClientCache.computeIfAbsent(connectionString, MongoClients::create);

        return MongoDbEmbeddingStore.builder().fromClient(mongoClient).databaseName(databaseName).collectionName(collectionName).indexName(indexName)
                .build();
    }

    // ──────────────────────────────────────────────────
    // Elasticsearch
    // ──────────────────────────────────────────────────

    /**
     * Builds an Elasticsearch-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code serverUrl} — Elasticsearch URL (default:
     * "http://localhost:9200")</li>
     * <li>{@code apiKey} — API key (optional, supports
     * {@code ${eddivault:...}})</li>
     * <li>{@code indexName} — index name (default: auto-generated from kbId)</li>
     * </ul>
     */
    @SuppressWarnings("removal") // serverUrl/apiKey/userName/password deprecated in favor of
                                 // restClient(RestClient), but usable without direct ES REST client dependency
    private EmbeddingStore<TextSegment> buildElasticsearch(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String serverUrl = params.getOrDefault("serverUrl", "http://localhost:9200");
        String indexName = params.getOrDefault("indexName", "eddi_kb_" + kbId.toLowerCase().replaceAll("[^a-z0-9_]", "_"));

        var builder = ElasticsearchEmbeddingStore.builder().serverUrl(serverUrl).indexName(indexName);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }
        if (params.containsKey("userName") && params.containsKey("password")) {
            builder.userName(params.get("userName"));
            builder.password(params.get("password"));
        }

        LOGGER.infof("Building Elasticsearch store: serverUrl=%s, index=%s", serverUrl, indexName);

        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Qdrant
    // ──────────────────────────────────────────────────

    /**
     * Builds a Qdrant-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code host} — Qdrant host (default: "localhost")</li>
     * <li>{@code port} — Qdrant gRPC port (default: 6334)</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * <li>{@code apiKey} — Qdrant API key (optional, supports
     * {@code ${eddivault:...}})</li>
     * <li>{@code useTls} — use TLS (default: "false")</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildQdrant(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String host = params.getOrDefault("host", "localhost");
        int port = parseIntParam(params, "port", 6334);
        String collectionName = params.getOrDefault("collectionName", "eddi_kb_" + kbId.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        boolean useTls = Boolean.parseBoolean(params.getOrDefault("useTls", "false"));

        LOGGER.infof("Building Qdrant store: host=%s, port=%d, collection=%s, tls=%b", host, port, collectionName, useTls);

        var builder = QdrantEmbeddingStore.builder().host(host).port(port).collectionName(collectionName).useTls(useTls);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }

        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Utility methods
    // ──────────────────────────────────────────────────

    /**
     * Resolves vault references in store parameters.
     */
    private Map<String, String> resolveParams(RagConfiguration config) {
        Map<String, String> rawParams = config.getStoreParameters() != null ? config.getStoreParameters() : Map.of();
        return secretResolver.resolveSecrets(rawParams);
    }

    /**
     * Requires a parameter to be present and non-blank. Fails fast with a clear
     * error message.
     */
    private String requireParam(Map<String, String> params, String key, String storeType) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(storeType + " requires '" + key + "' in storeParameters (use ${eddivault:...} for secrets)");
        }
        return value;
    }

    /**
     * Parses an integer parameter with a default, providing a clear error on
     * invalid values.
     */
    private int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String raw = params.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for '" + key + "': " + raw, e);
        }
    }

    /**
     * Converts a knowledge base ID into a safe PostgreSQL table name. Replaces
     * non-alphanumeric characters with underscores, lowercases, prefixes with
     * {@code eddi_kb_}, and truncates to the PostgreSQL identifier limit (63
     * chars).
     */
    static String sanitizeTableName(String kbId) {
        String sanitized = kbId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String result = "eddi_kb_" + sanitized;
        if (result.length() > MAX_PG_IDENTIFIER_LENGTH) {
            result = result.substring(0, MAX_PG_IDENTIFIER_LENGTH);
        }
        return result;
    }

    /**
     * Clears the store cache. Useful for testing or config hot-reload.
     */
    public void clearCache() {
        cache.clear();
        mongoClientCache.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.warnf("Failed to close MongoClient: %s", e.getMessage());
            }
        });
        mongoClientCache.clear();
    }
}
