package ai.labs.caching.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.inject.Inject;

@Slf4j
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
