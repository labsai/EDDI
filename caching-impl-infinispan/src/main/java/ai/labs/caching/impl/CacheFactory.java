package ai.labs.caching.impl;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import lombok.extern.slf4j.Slf4j;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class CacheFactory implements ICacheFactory {
    private final EmbeddedCacheManager cacheManager;

    @Inject
    public CacheFactory(InputStream configurationFile) {
        try {
            this.cacheManager = new DefaultCacheManager(configurationFile);
        } catch (IOException e) {
            final String message = "Unable to initialize CacheFactory!";
            log.error(e.getLocalizedMessage(), e);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public <K, V> ICache<K, V> getCache(String cacheName) {
        Cache<K, V> cache;
        if (cacheName != null) {
            cache = this.cacheManager.getCache(cacheName);
        } else {
            cache = this.cacheManager.getCache();
        }

        return new CacheImpl<>(cacheName, cache);
    }
}
