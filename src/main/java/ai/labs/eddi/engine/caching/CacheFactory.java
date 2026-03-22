package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CacheFactory implements ICacheFactory {
    private final ConcurrentHashMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    // Cache size configs (previously in infinispan-embedded.xml)
    private static final Map<String, Long> CACHE_SIZES = Map.of(
            "userConversations", 10_000L,
            "agentTriggers", 1_000L,
            "conversationState", 1_000L,
            "local", 1_000L,
            "parser", 1_000L);

    private static final long DEFAULT_MAX_SIZE = 1_000L;

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> getCache(String cacheName) {
        String name = cacheName != null ? cacheName : "local";
        Cache<K, V> cache = (Cache<K, V>) caches.computeIfAbsent(name, n -> Caffeine.newBuilder()
                .maximumSize(CACHE_SIZES.getOrDefault(n, DEFAULT_MAX_SIZE))
                .recordStats()
                .build());
        return new CacheImpl<>(name, cache);
    }
}
