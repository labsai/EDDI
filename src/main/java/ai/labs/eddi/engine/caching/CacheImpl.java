/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed implementation of {@link ICache}.
 * <p>
 * <strong>The TTL-aware overloads honour their TTL per entry.</strong> Caches
 * handed out by {@code CacheFactory} are built with a {@link WriteExpiry}
 * policy, which makes Caffeine's {@link VarExpiration} view available; each
 * {@code (lifespan, unit)} overload below writes through that view, so two
 * entries in the same cache can carry two different lifespans. Expiry is
 * measured from the write — a read never extends an entry's life.
 * <p>
 * A negative lifespan means unlimited, per the {@link ICache} contract.
 * Caffeine rejects negative durations outright, so it is translated into an
 * effectively infinite one instead of being passed through.
 * <p>
 * If the wrapped cache was built <em>without</em> a variable expiry policy —
 * only possible by constructing this class directly rather than through
 * {@code CacheFactory} — the TTL overloads degrade to their untimed
 * counterparts, entries live until size eviction removes them, and the
 * constructor logs a warning saying so.
 */
public class CacheImpl<K, V> implements ICache<K, V> {
    private static final Logger LOGGER = Logger.getLogger(CacheImpl.class);

    private final String cacheName;
    private final Cache<K, V> cache;
    private final VarExpiration<K, V> variableExpiry;

    public CacheImpl(String cacheName, Cache<K, V> cache) {
        this.cacheName = cacheName != null ? cacheName : "default";
        this.cache = cache;
        this.variableExpiry = cache.policy().expireVariably().orElse(null);

        if (this.variableExpiry == null) {
            LOGGER.warnf("Cache '%s' was built without a variable expiry policy: per-entry TTLs will be ignored "
                    + "and entries removed by size eviction only", this.cacheName);
        }
    }

    @Override
    public String getCacheName() {
        return cacheName;
    }

    // --- TTL-aware operations ---
    // These route through Caffeine's variable-expiry view so the lifespan applies
    // to the individual entry rather than to the cache as a whole.

    /**
     * {@inheritDoc}
     * <p>
     * Atomic: Caffeine substitutes the cache-wide expiry policy with this lifespan
     * and returns the replaced value in the same operation.
     */
    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        if (variableExpiry == null) {
            return put(key, value);
        }

        return variableExpiry.put(key, value, toNanos(lifespan, unit), TimeUnit.NANOSECONDS);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
        if (variableExpiry == null) {
            return putIfAbsent(key, value);
        }

        return variableExpiry.putIfAbsent(key, value, toNanos(lifespan, unit), TimeUnit.NANOSECONDS);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
        if (variableExpiry == null) {
            putAll(map);
            return;
        }

        long lifespanNanos = toNanos(lifespan, unit);
        map.forEach((key, value) -> variableExpiry.put(key, value, lifespanNanos, TimeUnit.NANOSECONDS));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not atomic: the value is replaced first and the lifespan applied afterwards,
     * so a concurrent write between the two steps decides the final lifespan.
     * Caffeine offers no replace-with-duration primitive.
     */
    @Override
    public V replace(K key, V value, long lifespan, TimeUnit unit) {
        V previous = replace(key, value);
        if (previous != null && variableExpiry != null) {
            variableExpiry.setExpiresAfter(key, toNanos(lifespan, unit), TimeUnit.NANOSECONDS);
        }

        return previous;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not atomic, for the same reason as
     * {@link #replace(Object, Object, long, TimeUnit)}.
     */
    @Override
    public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
        boolean replaced = replace(key, oldValue, value);
        if (replaced && variableExpiry != null) {
            variableExpiry.setExpiresAfter(key, toNanos(lifespan, unit), TimeUnit.NANOSECONDS);
        }

        return replaced;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code maxIdleTime} is <strong>not supported</strong>: Caffeine cannot
     * combine a per-entry write duration with a per-entry idle duration, so only
     * {@code lifespan} is applied. This overload has no callers in EDDI.
     */
    @Override
    public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return put(key, value, lifespan, lifespanUnit);
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code maxIdleTime} is <strong>not supported</strong>, as for
     * {@link #put(Object, Object, long, TimeUnit, long, TimeUnit)}.
     */
    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return putIfAbsent(key, value, lifespan, lifespanUnit);
    }

    /**
     * Converts an {@link ICache} lifespan into nanoseconds for Caffeine. A negative
     * lifespan is unlimited by contract; Caffeine throws on negative durations, so
     * it becomes an effectively infinite duration instead.
     */
    private static long toNanos(long lifespan, TimeUnit unit) {
        return lifespan < 0 ? WriteExpiry.NEVER_NANOS : unit.toNanos(lifespan);
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
