package ai.labs.eddi.engine.caching;

import org.infinispan.Cache;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        return this.cache.put(key, value, lifespan, unit);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
        return this.cache.putIfAbsent(key, value, lifespan, unit);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
        this.cache.putAll(map, lifespan, unit);
    }

    @Override
    public V replace(K key, V value, long lifespan, TimeUnit unit) {
        return this.cache.replace(key, value, lifespan, unit);
    }

    @Override
    public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
        return this.cache.replace(key, oldValue, value, lifespan, unit);
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return this.cache.put(key, value, lifespan, lifespanUnit, maxIdleTime, maxIdleTimeUnit);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return this.cache.putIfAbsent(key, value, lifespan, lifespanUnit, maxIdleTime, maxIdleTimeUnit);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return this.cache.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return this.cache.remove(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return this.cache.replace(key, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        return this.cache.replace(key, value);
    }

    @Override
    public int size() {
        return this.cache.size();
    }

    @Override
    public boolean isEmpty() {
        return this.cache.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.cache.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.cache.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return this.cache.get(key);
    }

    @Override
    public V put(K key, V value) {
        return this.cache.put(key, value);
    }

    @Override
    public V remove(Object key) {
        return this.cache.remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        this.cache.putAll(m);
    }

    @Override
    public void clear() {
        this.cache.clear();
    }

    @Override
    public Set<K> keySet() {
        return this.cache.keySet();
    }

    @Override
    public Collection<V> values() {
        return this.cache.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return this.cache.entrySet();
    }
}
