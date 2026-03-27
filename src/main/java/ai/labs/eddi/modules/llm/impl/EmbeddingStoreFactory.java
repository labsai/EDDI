package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Creates and caches {@link EmbeddingStore} instances based on
 * {@link RagConfiguration}. Handles isolation strategy (collection-per-KB vs
 * metadata filtering).
 */
@ApplicationScoped
public class EmbeddingStoreFactory {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFactory.class);

    private final Map<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();

    /**
     * Returns a cached or newly created embedding store for the given configuration
     * and knowledge base ID.
     */
    public EmbeddingStore<TextSegment> getOrCreate(RagConfiguration config, String kbId) {
        // TODO Phase 8c-β: include storeParameters in cache key when pgvector is added
        String cacheKey = config.getStoreType() + ":" + kbId;
        return cache.computeIfAbsent(cacheKey, k -> build(config, kbId));
    }

    private EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
        String storeType = config.getStoreType();
        LOGGER.infof("Building embedding store: type=%s, kbId=%s", storeType, kbId);

        return switch (storeType) {
            case "in-memory" -> new InMemoryEmbeddingStore<>();
            // Phase 8c-β: pgvector
            // case "pgvector" -> buildPgVector(config, kbId);
            default -> throw new IllegalArgumentException("Unsupported store type: " + storeType);
        };
    }

    /**
     * Clears the store cache. Useful for testing or config hot-reload.
     */
    public void clearCache() {
        cache.clear();
    }
}
