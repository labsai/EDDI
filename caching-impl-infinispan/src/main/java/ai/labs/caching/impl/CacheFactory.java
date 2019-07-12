package ai.labs.caching.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import lombok.extern.slf4j.Slf4j;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.enterprise.context.ApplicationScoped;

@Slf4j
@ApplicationScoped
public class CacheFactory implements ICacheFactory {
    private final EmbeddedCacheManager cacheManager;

    public CacheFactory() {
        this.cacheManager = new DefaultCacheManager();
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
