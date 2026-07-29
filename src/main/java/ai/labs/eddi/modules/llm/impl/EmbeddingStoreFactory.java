/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.mongo.MongoDriverInfoFactory;
import ai.labs.eddi.secrets.SecretResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.mongodb.MongoDbEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoDriverInformation;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Creates and caches {@link EmbeddingStore} instances based on
 * {@link RagConfiguration}.
 * <p>
 * Each knowledge base gets its own store instance (collection-per-KB
 * isolation). Supported store types: {@code in-memory}, {@code pgvector},
 * {@code mongodb-atlas}, {@code elasticsearch}, {@code qdrant}, {@code chroma}.
 * <p>
 * Cache is bounded (max 50 entries, 30-minute idle TTL) to prevent memory leaks
 * in multi-tenant or dynamic-config environments.
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFactory.class);
    private static final MongoDriverInformation DRIVER_INFO = MongoDriverInfoFactory.build();

    private static final int MAX_PG_IDENTIFIER_LENGTH = 63;
    private static final Pattern UNSAFE_IDENTIFIER_CHARS = Pattern.compile("[^a-z0-9_]");
    private static final Pattern TRAILING_UNDERSCORES = Pattern.compile("_+$");

    /**
     * Ceiling on distinct MongoDB deployments a single EDDI instance keeps clients
     * for. Generous relative to any realistic number of Atlas clusters behind one
     * deployment's knowledge bases, which is what makes size-based eviction safe:
     * it only fires in the pathological case the bound exists to contain.
     */
    static final int MAX_MONGO_CLIENTS = 20;

    private final Cache<String, EmbeddingStore<TextSegment>> cache = Caffeine.newBuilder().maximumSize(50).expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /**
     * MongoClients shared by every Atlas-backed store pointing at the same
     * deployment.
     * <p>
     * Bounded, keyed on a SHA-256 digest of the connection string rather than the
     * string itself, and closing the client (and therefore its connection pool)
     * when an entry leaves. The previous plain {@code ConcurrentHashMap} did none
     * of the three: it grew one never-closed client per distinct RAG config for the
     * lifetime of the process, and every one of those map keys was a live
     * credential-bearing URI sitting in the heap.
     * <p>
     * Deliberately size-bounded only — no idle TTL. The key is consulted on the
     * store <em>build</em> path, so an actively used store would not refresh it,
     * and an expiring entry would close a client that a live
     * {@code MongoDbEmbeddingStore} still holds.
     */
    private final Cache<String, MongoClient> mongoClientCache = Caffeine.newBuilder()
            .maximumSize(MAX_MONGO_CLIENTS)
            .removalListener((String key, MongoClient client, RemovalCause cause) -> closeQuietly(client))
            .build();

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;

    @Inject
    public EmbeddingStoreFactory(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
    }

    /**
     * Evict cached stores when a vault secret or a global variable changes.
     * <p>
     * Store parameters carry credentials — {@code password}, {@code apiKey},
     * {@code connectionString} — and the cache had no invalidation hook at all:
     * {@code expireAfterAccess} resets on every read, so a store serving traffic
     * never reached its TTL, and {@link #clearCache()} had no production caller.
     * Rotating a vector store's credential therefore kept the pre-rotation one
     * alive for the lifetime of the process.
     */
    @PostConstruct
    void registerInvalidation() {
        secretResolver.registerInvalidationListener(reference -> invalidateStores());
        globalVariableResolver.registerInvalidationListener(this::invalidateStores);
        LOGGER.info("EmbeddingStoreFactory registered for secret and global variable invalidation events");
    }

    /**
     * Drop cached stores so the next lookup rebuilds them from current credentials.
     * <p>
     * Deliberately does <em>not</em> close pooled MongoClients: an invalidation
     * fires for any secret in the deployment, including ones no vector store uses,
     * and closing a client would break a RAG query that is in flight on a store
     * another thread already holds. A rotated connection string simply hashes to a
     * new key and gets a new client; the superseded one is closed when the bounded
     * client cache evicts it.
     */
    private void invalidateStores() {
        cache.invalidateAll();
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
        return cache.get(cacheKey, k -> build(config, kbId));
    }

    private EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
        String storeType = config.getStoreType();
        LOGGER.infof("Building embedding store: type=%s, kbId=%s", sanitize(storeType), sanitize(kbId));

        return switch (storeType) {
            case "in-memory" -> new InMemoryEmbeddingStore<>();
            case "pgvector" -> buildPgVector(config, kbId);
            case "mongodb-atlas" -> buildMongoDbAtlas(config, kbId);
            case "elasticsearch" -> buildElasticsearch(config, kbId);
            case "qdrant" -> buildQdrant(config, kbId);
            case "chroma" -> buildChroma(config, kbId);
            default -> throw new IllegalArgumentException(
                    "Unsupported store type: " + storeType + ". Supported: in-memory, pgvector, mongodb-atlas, elasticsearch, qdrant, chroma");
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
     * <li>{@code password} — database password, supports {@code ${vault:...}}
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

        LOGGER.infof("Building pgvector store: host=%s, port=%d, database=%s, table=%s, dimension=%d", sanitize(host), port, sanitize(database),
                sanitize(table), dimension);

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
     * {@code ${vault:...}})</li>
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

        LOGGER.infof("Building MongoDB Atlas store: database=%s, collection=%s, index=%s", sanitize(databaseName), sanitize(collectionName),
                sanitize(indexName));

        // Keyed on a digest, never on the connection string itself: the string is a
        // credential and a cache key outlives the call that produced it.
        MongoClient mongoClient = mongoClientCache.get(connectionKey(connectionString), key -> {
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(connectionString))
                    .build();
            return MongoClients.create(settings, DRIVER_INFO);
        });

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
     * <li>{@code apiKey} — API key (optional, supports {@code ${vault:...}})</li>
     * <li>{@code indexName} — index name (default: auto-generated from kbId)</li>
     * </ul>
     */
    @SuppressWarnings("removal") // serverUrl/apiKey/userName/password deprecated in favor of
                                 // restClient(RestClient), but usable without direct ES REST client dependency
    private EmbeddingStore<TextSegment> buildElasticsearch(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String serverUrl = params.getOrDefault("serverUrl", "http://localhost:9200");
        String indexName = params.getOrDefault("indexName", "eddi_kb_" + UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_"));

        var builder = ElasticsearchEmbeddingStore.builder().serverUrl(serverUrl).indexName(indexName);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }
        if (params.containsKey("userName") && params.containsKey("password")) {
            builder.userName(params.get("userName"));
            builder.password(params.get("password"));
        }

        LOGGER.infof("Building Elasticsearch store: serverUrl=%s, index=%s", sanitize(serverUrl), sanitize(indexName));

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
     * {@code ${vault:...}})</li>
     * <li>{@code useTls} — use TLS (default: "false")</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildQdrant(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String host = params.getOrDefault("host", "localhost");
        int port = parseIntParam(params, "port", 6334);
        String collectionName = params.getOrDefault("collectionName", sanitizeCollection(kbId));
        boolean useTls = Boolean.parseBoolean(params.getOrDefault("useTls", "false"));

        LOGGER.infof("Building Qdrant store: host=%s, port=%d, collection=%s, tls=%b", sanitize(host), port, sanitize(collectionName), useTls);

        var builder = QdrantEmbeddingStore.builder().host(host).port(port).collectionName(collectionName).useTls(useTls);

        if (params.containsKey("apiKey")) {
            builder.apiKey(params.get("apiKey"));
        }

        return builder.build();
    }

    // ──────────────────────────────────────────────────
    // Chroma
    // ──────────────────────────────────────────────────

    /**
     * Builds a Chroma-backed embedding store.
     * <p>
     * Supported storeParameters:
     * <ul>
     * <li>{@code baseUrl} — Chroma server URL (default:
     * "http://localhost:8000")</li>
     * <li>{@code tenantName} — tenant name (default: "default_tenant")</li>
     * <li>{@code databaseName} — database name (default: "default_database")</li>
     * <li>{@code collectionName} — collection name (default: auto-generated from
     * kbId)</li>
     * </ul>
     */
    private EmbeddingStore<TextSegment> buildChroma(RagConfiguration config, String kbId) {
        Map<String, String> params = resolveParams(config);

        String baseUrl = params.getOrDefault("baseUrl", "http://localhost:8000");
        String tenantName = params.getOrDefault("tenantName", "default_tenant");
        String databaseName = params.getOrDefault("databaseName", "default_database");
        String collectionName = params.getOrDefault("collectionName", sanitizeCollection(kbId));

        ChromaApiVersion version = parseChromaApiVersion(params.getOrDefault("apiVersion", "V2"));

        LOGGER.infof("Building Chroma store: baseUrl=%s, tenant=%s, database=%s, collection=%s, apiVersion=%s", baseUrl, tenantName, databaseName,
                collectionName, version.toString());

        return ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .tenantName(tenantName)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .apiVersion(version)
                .build();
    }

    // ──────────────────────────────────────────────────
    // Utility methods
    // ──────────────────────────────────────────────────

    /**
     * Resolves global variable and vault references in store parameters.
     */
    private Map<String, String> resolveParams(RagConfiguration config) {
        Map<String, String> rawParams = config.getStoreParameters() != null ? config.getStoreParameters() : Map.of();
        Map<String, String> resolved = globalVariableResolver.resolveAll(rawParams);
        return secretResolver.resolveSecrets(resolved);
    }

    private ChromaApiVersion parseChromaApiVersion(String apiVersionStr) {
        try {
            ChromaApiVersion chromaApiVersion = (apiVersionStr == null || apiVersionStr.isBlank())
                    ? ChromaApiVersion.V2
                    : ChromaApiVersion.valueOf(apiVersionStr);
            return chromaApiVersion;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid '%s' ChromaApiVersion. Valid ChromaApiVersion '%s'",
                            apiVersionStr,
                            Arrays.toString(ChromaApiVersion.values())),
                    e);
        }
    }

    /**
     * Requires a parameter to be present and non-blank. Fails fast with a clear
     * error message.
     */
    private String requireParam(Map<String, String> params, String key, String storeType) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(storeType + " requires '" + key + "' in storeParameters (use ${vault:...} for secrets)");
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

    static String sanitizeCollection(String kbId) {
        return TRAILING_UNDERSCORES.matcher("eddi_kb_" + UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_")).replaceAll("");
    }

    /**
     * Converts a knowledge base ID into a safe PostgreSQL table name. Replaces
     * non-alphanumeric characters with underscores, lowercases, prefixes with
     * {@code eddi_kb_}, and truncates to the PostgreSQL identifier limit (63
     * chars).
     */
    static String sanitizeTableName(String kbId) {
        String sanitized = UNSAFE_IDENTIFIER_CHARS.matcher(kbId.toLowerCase()).replaceAll("_");
        String result = "eddi_kb_" + sanitized;
        if (result.length() > MAX_PG_IDENTIFIER_LENGTH) {
            result = result.substring(0, MAX_PG_IDENTIFIER_LENGTH);
        }
        return result;
    }

    /**
     * Stable, non-reversible cache key for a connection string. SHA-256 rather than
     * {@code hashCode()} so that two distinct clusters cannot collide onto one
     * shared client.
     */
    static String connectionKey(String connectionString) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(connectionString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void closeQuietly(MongoClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            LOGGER.warnf("Failed to close MongoClient: %s", e.getMessage());
        }
    }

    /**
     * Clears the store cache and closes every pooled MongoClient. Called on secret
     * rotation and global-variable edits (see {@link #registerInvalidation()}), and
     * by tests.
     */
    public void clearCache() {
        cache.invalidateAll();
        // invalidateAll fires the removal listener, which closes each client.
        mongoClientCache.invalidateAll();
        mongoClientCache.cleanUp();
    }
}
