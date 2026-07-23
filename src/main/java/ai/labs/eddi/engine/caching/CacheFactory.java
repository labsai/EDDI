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

            // Floor only — the real capacity is derived from the TTL the cache is
            // asked for, see RATE_SIZED_CACHES. This entry applies solely if something
            // ever takes the nonce cache from the size-only getCache(name) overload.
            Map.entry("nonce-replay-protection", 100_000L));

    private static final long DEFAULT_MAX_SIZE = 1_000L;

    /**
     * Peak sustained rate of signed A2A envelopes the nonce cache is sized to
     * survive, in requests per second.
     */
    public static final int NONCE_PEAK_SIGNED_RPS = 300;

    /**
     * Head-room multiplier applied on top of the steady-state occupancy of a
     * rate-sized cache.
     * <p>
     * Sizing at exactly {@code rps * ttl} is not enough, because Caffeine's
     * W-TinyLFU admission is <em>frequency</em>-based rather than LRU. A nonce is
     * written once via {@code putIfAbsent} and never read, so every entry ties at
     * the same estimated frequency and, once the cache is full, the admission
     * filter rejects the <em>candidate</em> — the newly inserted nonce is dropped
     * while older ones stay. Retention then degrades non-uniformly and precisely
     * for the most recent nonces, which are the replayable ones. Keeping the cap
     * out of reach for the whole window is the only way to avoid that.
     */
    static final double RATE_SIZED_EVICTION_HEADROOM = 2.0;

    /**
     * Caches whose capacity is a function of the TTL they are requested with,
     * mapped to the peak write rate (entries per second) they must absorb.
     * <p>
     * Replay protection is only as strong as the cache is deep: forgetting a nonce
     * while a replay carrying it would still pass the freshness and clock-skew
     * checks re-opens the replay window. Deriving the size from the requested TTL
     * means a future change to {@code NonceCacheService}'s TTL cannot silently
     * outgrow a hard-coded capacity.
     */
    private static final Map<String, Integer> RATE_SIZED_CACHES = Map.of("nonce-replay-protection", NONCE_PEAK_SIGNED_RPS);

    /**
     * Maximum entry count for {@code cacheName} when built with {@code ttl}
     * ({@code null} for the size-only overload).
     * <p>
     * For a rate-sized cache this is
     * {@code peakRps * ttlSeconds * RATE_SIZED_EVICTION_HEADROOM}, never below the
     * cache's configured floor. For every other cache it is the configured size.
     */
    public static long maximumSizeFor(String cacheName, Duration ttl) {
        long configured = CACHE_SIZES.getOrDefault(cacheName, DEFAULT_MAX_SIZE);
        Integer peakRps = RATE_SIZED_CACHES.get(cacheName);
        if (peakRps == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return configured;
        }

        double ttlSeconds = ttl.toMillis() / 1000.0;
        long required = (long) Math.ceil(peakRps * ttlSeconds * RATE_SIZED_EVICTION_HEADROOM);
        return Math.max(configured, required);
    }

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
                        .maximumSize(maximumSizeFor(n, null))
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
                        .maximumSize(maximumSizeFor(name, ttl))
                        .expireAfter(WriteExpiry.<Object, Object>of(ttl))
                        .recordStats()
                        .build());
        return new CacheImpl<>(name, cache);
    }
}
