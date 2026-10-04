/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A node-local cache that tells the other nodes to drop their copy whenever
 * this node changes an entry — cluster mode only, for caches of values that
 * live in the database (agent triggers, user conversations, conversation
 * states, GDPR restriction verdicts).
 * <p>
 * Every mutation is applied locally first, then announced as an eviction (never
 * as the new value): a receiving node simply forgets the key and reads the
 * database the next time it needs it. Reads are untouched. A lost event is
 * bounded by the cache's TTL ({@code eddi.cluster.local-cache-ttl} for the
 * caches that had none).
 */
public class ClusterInvalidatingCache<K, V> implements ICache<K, V> {

    /** Announces evictions; implemented by {@link CacheFactory}. */
    public interface Announcer {
        void evicted(String cacheName, Object key);

        void cleared(String cacheName);
    }

    private final ICache<K, V> delegate;
    private final Announcer announcer;

    public ClusterInvalidatingCache(ICache<K, V> delegate, Announcer announcer) {
        this.delegate = delegate;
        this.announcer = announcer;
    }

    private <T> T announce(Object key, T result) {
        announcer.evicted(delegate.getCacheName(), key);
        return result;
    }

    @Override
    public String getCacheName() {
        return delegate.getCacheName();
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        return announce(key, delegate.put(key, value, lifespan, unit));
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
        V previous = delegate.putIfAbsent(key, value, lifespan, unit);
        return previous == null ? announce(key, null) : previous;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
        delegate.putAll(map, lifespan, unit);
        map.keySet().forEach(k -> announcer.evicted(getCacheName(), k));
    }

    @Override
    public V replace(K key, V value, long lifespan, TimeUnit unit) {
        return announce(key, delegate.replace(key, value, lifespan, unit));
    }

    @Override
    public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
        return announce(key, delegate.replace(key, oldValue, value, lifespan, unit));
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return announce(key, delegate.put(key, value, lifespan, lifespanUnit, maxIdleTime, maxIdleTimeUnit));
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        V previous = delegate.putIfAbsent(key, value, lifespan, lifespanUnit, maxIdleTime, maxIdleTimeUnit);
        return previous == null ? announce(key, null) : previous;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        V previous = delegate.putIfAbsent(key, value);
        return previous == null ? announce(key, null) : previous;
    }

    @Override
    public boolean remove(Object key, Object value) {
        return announce(key, delegate.remove(key, value));
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return announce(key, delegate.replace(key, oldValue, newValue));
    }

    @Override
    public V replace(K key, V value) {
        return announce(key, delegate.replace(key, value));
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        // A computed-on-miss value was read from the source of truth: nothing changed.
        return delegate.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return announce(key, delegate.computeIfPresent(key, remappingFunction));
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return announce(key, delegate.compute(key, remappingFunction));
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return announce(key, delegate.merge(key, value, remappingFunction));
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return delegate.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return delegate.get(key);
    }

    @Override
    public V put(K key, V value) {
        return announce(key, delegate.put(key, value));
    }

    @Override
    public V remove(Object key) {
        return announce(key, delegate.remove(key));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        delegate.putAll(m);
        m.keySet().forEach(k -> announcer.evicted(getCacheName(), k));
    }

    @Override
    public void clear() {
        delegate.clear();
        announcer.cleared(getCacheName());
    }

    @Override
    public Set<K> keySet() {
        return delegate.keySet();
    }

    @Override
    public Collection<V> values() {
        return delegate.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return delegate.entrySet();
    }
}
