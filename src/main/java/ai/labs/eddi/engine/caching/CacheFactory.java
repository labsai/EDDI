/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CacheFactory implements ICacheFactory {
    private final ConcurrentHashMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    // Cache size configs (previously in infinispan-embedded.xml)
    private static final Map<String, Long> CACHE_SIZES = Map.ofEntries(
            Map.entry("userConversations", 10_000L),
            Map.entry("agentTriggers", 1_000L),
            Map.entry("conversationState", 1_000L),
            Map.entry("local", 1_000L),
            Map.entry("parser", 1_000L),

            // Tool-result entries are partitioned per user (see ToolCacheService),
            // so the keyspace is multiplied by the number of active users and the
            // 1_000 default would thrash.
            Map.entry("tool-results", 10_000L),

            // One entry holds an entire oversized tool response split into pages, so
            // entries are large. The 15-minute TTL is the primary eviction path here;
            // this cap only exists to bound memory if pages are produced faster than
            // they age out.
            Map.entry("paginated-tool-responses", 1_000L),

            // Replay protection is only as strong as the cache is deep: evicting a
            // nonce while its timestamp would still pass the freshness check re-opens
            // the replay window. 100_000 entries covers roughly 300 signed requests
            // per second sustained across the full ~5.5-minute window (330s * 300 =
            // 99_000); entries are a short string plus a shared Boolean.
            Map.entry("nonce-replay-protection", 100_000L));

    private static final long DEFAULT_MAX_SIZE = 1_000L;

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> getCache(String cacheName) {
        String name = cacheName != null ? cacheName : "local";
        // expireAfter (rather than no expiry policy at all) is what exposes
        // Caffeine's variable-expiry view, which CacheImpl needs to honour the
        // per-entry TTLs of ICache.put(key, value, lifespan, unit). Entries written
        // without a lifespan still never expire on their own.
        Cache<K, V> cache = (Cache<K, V>) caches.computeIfAbsent(name,
                n -> Caffeine.newBuilder()
                        .maximumSize(CACHE_SIZES.getOrDefault(n, DEFAULT_MAX_SIZE))
                        .expireAfter(WriteExpiry.<Object, Object>never())
                        .recordStats()
                        .build());
        return new CacheImpl<>(name, cache);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> getCache(String cacheName, Duration ttl) {
        if (ttl == null) {
            throw new IllegalArgumentException("TTL must not be null; use getCache(cacheName) for caches without expiry");
        }
        String name = cacheName != null ? cacheName : "local";
        // Use a distinct key to prevent collision with size-only caches
        String cacheKey = name + ":ttl=" + ttl.toSeconds();
        // WriteExpiry.of(ttl) rather than expireAfterWrite(ttl): the two are
        // mutually exclusive in Caffeine, and only the expireAfter form leaves the
        // per-entry override available. Entries written without their own lifespan
        // expire after ttl exactly as expireAfterWrite would have expired them.
        Cache<K, V> cache = (Cache<K, V>) caches.computeIfAbsent(cacheKey,
                n -> Caffeine.newBuilder()
                        .maximumSize(CACHE_SIZES.getOrDefault(name, DEFAULT_MAX_SIZE))
                        .expireAfter(WriteExpiry.<Object, Object>of(ttl))
                        .recordStats()
                        .build());
        return new CacheImpl<>(name, cache);
    }
}
