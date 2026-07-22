/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
 * <strong>The TTL-aware overloads ignore their TTL.</strong> Caffeine's
 * standard builder has no per-entry expiry without a custom {@code Expiry}, so
 * every {@code (lifespan, unit)} overload below delegates to its untimed
 * counterpart and entries are removed by size-based eviction alone.
 * <p>
 * Nothing compensates for this downstream: {@code ToolCacheService} — the only
 * consumer that passes a TTL — computes a per-tool TTL, hands it to
 * {@link #put(Object, Object, long, TimeUnit)} and then never re-checks it, so
 * a cached tool result lives until it is evicted for capacity. (An earlier
 * version of this javadoc claimed the service tracked expiry itself via a
 * {@code CachedResult.expiresAt} field. No such field exists; the wrapper
 * records only {@code cachedAt}, which is used for a debug log line.)
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
    // Expiry, so these delegate to the non-TTL versions and the lifespan argument
    // is discarded. Consequences, stated plainly:
    // 1. ToolCacheService's per-tool TTLs do not actually expire anything
    // 2. Other consumers don't use TTL puts at all
    // 3. Size-based eviction is therefore the ONLY eviction strategy

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
