package ai.labs.eddi.engine.caching;

import java.time.Duration;

public interface ICacheFactory {
    /**
     * @param cacheName
     *            name of the cache to be returned, null is default cache
     * @param <K>
     *            a key in order to find the value stored together with it
     * @param <V>
     *            the value to be stored in the cache
     * @return instance of at.sdo.server.cache.ICache
     */
    public <K, V> ICache<K, V> getCache(String cacheName);

    /**
     * Get or create a cache with a time-to-live (TTL) expiration policy. Entries
     * are automatically evicted after the specified duration, regardless of access.
     * Use this for caches where temporal freshness matters (e.g., event
     * deduplication, session-scoped data).
     *
     * @param cacheName
     *            name of the cache
     * @param ttl
     *            time-to-live for cache entries (entries expire after this
     *            duration)
     * @param <K>
     *            key type
     * @param <V>
     *            value type
     * @return the cache instance
     */
    <K, V> ICache<K, V> getCache(String cacheName, Duration ttl);
}
