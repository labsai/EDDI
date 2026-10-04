/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import ai.labs.eddi.engine.cluster.SharedBucket;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class CacheFactory implements ICacheFactory, ClusterInvalidatingCache.Announcer {
    private final ConcurrentHashMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<>();

    // ---- cluster mode (eddi.messaging.type=nats) ----
    //
    // Field-injected so the many tests that build this factory with `new` keep the
    // node-local behaviour: with clusterConfig null — or in-memory mode — every
    // cache is exactly what it always was.

    /**
     * Caches that hold state rather than copies, and are therefore shared by every
     * node through a KV bucket (name → bucket): replay nonces, A2A task mappings,
     * paginated tool responses, Slack event de-duplication, channel thread locks.
     */
    static final Map<String, String> SHARED_BUCKETS = Map.of(
            "nonce-replay-protection", "NONCES",
            "a2aTaskMapping", "A2A_TASKS",
            "a2aTaskMapping:context", "A2A_CONTEXTS",
            "a2aTaskMapping:state", "A2A_STATES",
            "paginated-tool-responses", "TOOL_PAGES",
            "slack-event-dedup", "DEDUP",
            "channel-thread-locks", "CHANNEL");

    /**
     * Node-local copies of database state that the other nodes must drop when this
     * node changes them (eviction events), with a TTL backstop for the two that had
     * none.
     */
    static final Set<String> INVALIDATED = Set.of("agentTriggers", "userConversations", "conversationState",
            "gdprProcessingRestrictions");

    @Inject
    ClusterConfig clusterConfig;

    @Inject
    Instance<NatsSharedStateFactory> natsSharedState;

    @Inject
    Instance<IClusterEventBus> clusterEvents;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    private boolean clustered() {
        return clusterConfig != null && clusterConfig.isNats();
    }

    private <K, V> ICache<K, V> clusterAware(String name, Duration ttl, ICache<K, V> local) {
        if (!clustered()) {
            return local;
        }
        String bucket = SHARED_BUCKETS.get(name);
        if (bucket != null) {
            Duration bucketTtl = ttl != null ? ttl : defaultSharedTtl(name);
            boolean failClosed = "nonce-replay-protection".equals(name)
                    && ClusterConfig.REJECT.equals(clusterConfig.degradedNonces());
            return new SharedKvCache<>(local, natsSharedState.get().bucket(new SharedBucket(bucket, bucketTtl, -1)), failClosed);
        }
        if (INVALIDATED.contains(name)) {
            subscribeOnce();
            return new ClusterInvalidatingCache<>(local, this);
        }
        return local;
    }

    private Duration defaultSharedTtl(String name) {
        if (name.startsWith("a2aTaskMapping")) {
            return clusterConfig.a2aTaskTtl();
        }
        if ("paginated-tool-responses".equals(name)) {
            return Duration.ofMinutes(15);
        }
        return Duration.ofHours(1);
    }

    private void subscribeOnce() {
        if (!subscribed.compareAndSet(false, true)) {
            return;
        }
        IClusterEventBus events = clusterEvents.get();
        events.subscribe(ClusterEvent.CACHE_EVICT, e -> evictLocal(e.getString("cache"), e.getString("key")));
        events.subscribe(ClusterEvent.CACHE_CLEAR, e -> clearLocal(e.getString("cache")));
        events.onResync(() -> INVALIDATED.forEach(this::clearLocal));
    }

    @Override
    public void evicted(String cacheName, Object key) {
        clusterEvents.get().publish(ClusterEvent.CACHE_EVICT, Map.of("cache", cacheName, "key", KvKeys.hashed(String.valueOf(key))));
    }

    @Override
    public void cleared(String cacheName) {
        clusterEvents.get().publish(ClusterEvent.CACHE_CLEAR, Map.of("cache", cacheName));
    }

    /** Drops a key another node changed — matched by hash, never sent in clear. */
    void evictLocal(String cacheName, String keyHash) {
        if (cacheName == null || keyHash == null) {
            return;
        }
        caches.forEach((registered, cache) -> {
            if (registered.equals(cacheName) || registered.startsWith(cacheName + ":ttl=")) {
                cache.asMap().keySet().removeIf(k -> KvKeys.hashed(String.valueOf(k)).equals(keyHash));
            }
        });
    }

    void clearLocal(String cacheName) {
        if (cacheName == null) {
            return;
        }
        caches.forEach((registered, cache) -> {
            if (registered.equals(cacheName) || registered.startsWith(cacheName + ":ttl=")) {
                cache.invalidateAll();
            }
        });
    }

    // Cache size configs (previously in infinispan-embedded.xml).
    //
    // Keyed by string on purpose — this is a registry, and importing the service
    // classes that request each cache would point the dependency the wrong way.
    // CacheFactoryTest binds the entries that have a named constant
    // (GdprComplianceService.RESTRICTION_CACHE_NAME,
    // TenantQuotaService.QUOTA_CACHE_NAME) back to it, so renaming one without
    // updating this map fails there rather than silently reverting that cache to
    // DEFAULT_MAX_SIZE.
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

            // Keyed by userId like "userConversations", so the keyspace is the number
            // of ACTIVE users rather than a fixed set: at the 1_000 default a
            // deployment with a few thousand concurrent users thrashes precisely under
            // load, and Caffeine's W-TinyLFU then rejects the newly inserted key
            // rather than an old one (see RATE_SIZED_EVICTION_HEADROOM). A miss is not
            // an error, so nothing would log it — the per-turn store round trip on the
            // hottest path in the system would simply come back. One Boolean per user.
            Map.entry("gdprProcessingRestrictions", 10_000L),

            // Keyed by "type:channelId:targetName", so occupancy is channels times
            // observers — a live population, not a fixed set. At the 1_000 default
            // an eviction is not a miss that simply reloads: a dropped window
            // restarts the daily allowance AND clears the cooldown, because a window
            // rebuilt from nothing has lastResponseEpochSeconds == 0 and ObserveGate
            // skips the cooldown check entirely for that. Evicting under load is
            // therefore the one moment an observer would be least rate-limited.
            Map.entry("channel-observe-windows", 10_000L),

            // Keyed by tenantId, and tenants are few even in multi-tenant deployments.
            // Listed rather than left to the default so the sizing sits on record next
            // to the per-user caches above.
            Map.entry("tenantQuotas", 1_000L),

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

    /**
     * Whether {@code cacheName} has its own entry in {@link #CACHE_SIZES}, as
     * opposed to inheriting {@link #DEFAULT_MAX_SIZE}.
     * <p>
     * Exists because {@link #maximumSizeFor} cannot tell the two apart: a cache
     * deliberately listed at 1,000 and a cache nobody sized both answer 1,000, so a
     * test asserting the number alone stays green when the entry is deleted — which
     * is precisely the regression the entries were added to prevent, and a cache
     * that silently reverts to the default logs nothing.
     */
    static boolean hasExplicitSize(String cacheName) {
        return CACHE_SIZES.containsKey(cacheName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> getCache(String cacheName) {
        String name = cacheName != null ? cacheName : "local";
        if (clustered() && INVALIDATED.contains(name)) {
            // In a cluster a lost eviction must not pin a stale entry forever.
            return getCache(name, clusterConfig.localCacheTtl());
        }
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
        return clusterAware(name, null, new CacheImpl<>(name, cache));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ICache<K, V> getCache(String cacheName, Duration ttl) {
        if (ttl == null) {
            throw new IllegalArgumentException("TTL must not be null; use getCache(cacheName) for caches without expiry");
        }
        String name = cacheName != null ? cacheName : "local";
        // Use a distinct key to prevent collision with size-only caches.
        //
        // The TTL is rendered with Duration.toString() rather than toSeconds():
        // toSeconds() TRUNCATES, so every sub-second TTL collapsed onto ":ttl=0" and
        // any two durations sharing a whole-second part collapsed together. Two
        // callers asking the same cache name for different TTLs then shared ONE
        // instance whose expiry policy was whichever of them built it first — the
        // later TTL was accepted and silently ignored. Duration.toString() is
        // injective over distinct Durations and identical for equal ones (so
        // ofSeconds(60) and ofMinutes(1) still share, as they should), and it is
        // strictly finer-grained than the toMillis() maximumSizeFor() below derives
        // the capacity from, so a key can never span two different capacities.
        String cacheKey = name + ":ttl=" + ttl.toString();
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
        return clusterAware(name, ttl, new CacheImpl<>(name, cache));
    }
}
