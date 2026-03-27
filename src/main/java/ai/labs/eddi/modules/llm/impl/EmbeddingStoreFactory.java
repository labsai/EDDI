package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches {@link EmbeddingStore} instances based on
 * {@link RagConfiguration}. Handles isolation strategy (collection-per-KB vs
 * metadata filtering).
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFactory.class);

    private final Map<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();
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
        // Include storeParameters in cache key for pgvector (different connection
        // params = different store)
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
            default -> throw new IllegalArgumentException("Unsupported store type: " + storeType);
        };
    }

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
        Map<String, String> rawParams = config.getStoreParameters() != null ? config.getStoreParameters() : Map.of();
        Map<String, String> params = secretResolver.resolveSecrets(rawParams);

        String host = params.getOrDefault("host", "localhost");
        int port = Integer.parseInt(params.getOrDefault("port", "5432"));
        String database = params.getOrDefault("database", "eddi");
        String user = params.getOrDefault("user", "eddi");
        String password = params.getOrDefault("password", "");
        int dimension = Integer.parseInt(params.getOrDefault("dimension", "1536"));

        // Table name: use explicit param, or derive a safe name from kbId
        String table = params.getOrDefault("table", sanitizeTableName(kbId));

        LOGGER.infof("Building pgvector store: host=%s, port=%d, database=%s, table=%s, dimension=%d", host, port, database, table, dimension);

        return PgVectorEmbeddingStore.builder().host(host).port(port).database(database).user(user).password(password).table(table)
                .dimension(dimension).createTable(true).build();
    }

    /**
     * Converts a knowledge base ID into a safe PostgreSQL table name. Replaces
     * non-alphanumeric characters with underscores, lowercases, and prefixes with
     * {@code eddi_kb_}.
     */
    static String sanitizeTableName(String kbId) {
        String sanitized = kbId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return "eddi_kb_" + sanitized;
    }

    /**
     * Clears the store cache. Useful for testing or config hot-reload.
     */
    public void clearCache() {
        cache.clear();
    }
}
