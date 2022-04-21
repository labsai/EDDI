package ai.labs.eddi.engine.caching;

public interface ICacheFactory {
    /**
     * @param cacheName name of the cache to be returned, null is default cache
     * @param <K>       a key in order to find the value stored together with it
     * @param <V>       the value to be stored in the cache
     * @return instance of at.sdo.server.cache.ICache
     */
    public <K, V> ICache<K, V> getCache(String cacheName);
}
