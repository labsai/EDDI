package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Cache;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed implementation of {@link ICache}.
 * <p>
 * TTL-aware put methods use Caffeine's {@code policy().expireVariably()} when
 * available, but since Caffeine's standard builder doesn't support per-entry
 * TTL out of the box without a custom Expiry, we store entries normally and
 * rely on the global max-size eviction. For tool caching (the only consumer
 * using TTL puts), the ToolCacheService already tracks expiry internally via
 * {@code CachedResult.expiresAt}.
 */
public class CacheImpl<K, V> implements ICache<K, V> {
    private final String cacheName;
    private final Cache<K, V> cache;

    public CacheImpl(String cacheName, Cache<K, V> cache) {
        this.cacheName = cacheName != null ? cacheName : "default";
        this.cache = cache;
    }

    @Override
    public String getCacheName() {
        return cacheName;
    }

    // --- TTL-aware operations ---
    // Caffeine's standard API doesn't support per-entry TTL without a custom
    // Expiry.
    // These delegate to the non-TTL versions since:
    // 1. ToolCacheService tracks expiry internally (CachedResult.expiresAt)
    // 2. Other consumers don't use TTL puts at all
    // 3. Global max-size eviction is the primary eviction strategy

    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        return put(key, value);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
        return putIfAbsent(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
        putAll(map);
    }

    @Override
    public V replace(K key, V value, long lifespan, TimeUnit unit) {
        return replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
        return replace(key, oldValue, value);
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return put(key, value);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return putIfAbsent(key, value);
    }

    // --- ConcurrentMap delegate methods ---

    private ConcurrentMap<K, V> asMap() {
        return cache.asMap();
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return asMap().putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return asMap().remove(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return asMap().replace(key, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        return asMap().replace(key, value);
    }

    @Override
    public int size() {
        return (int) cache.estimatedSize();
    }

    @Override
    public boolean isEmpty() {
        return cache.estimatedSize() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return asMap().containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return asMap().containsValue(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        return cache.getIfPresent((K) key);
    }

    @Override
    public V put(K key, V value) {
        V prev = cache.getIfPresent(key);
        cache.put(key, value);
        return prev;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        V prev = cache.getIfPresent((K) key);
        cache.invalidate((K) key);
        return prev;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        cache.putAll(m);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public Set<K> keySet() {
        return asMap().keySet();
    }

    @Override
    public Collection<V> values() {
        return asMap().values();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return asMap().entrySet();
    }
}
