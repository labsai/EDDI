package ai.labs.eddi.engine.caching;

import lombok.Getter;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
public class CacheFactory implements ICacheFactory {
    @Getter
    private final EmbeddedCacheManager cacheManager;

    @Inject
    public CacheFactory(EmbeddedCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public <K, V> ICache<K, V> getCache(String cacheName) {
        Cache<K, V> cache;
        if (cacheName != null) {
            cache = this.cacheManager.getCache(cacheName, true);
        } else {
            cache = this.cacheManager.getCache();
        }

        return new CacheImpl<>(cacheName, cache);
    }
}
