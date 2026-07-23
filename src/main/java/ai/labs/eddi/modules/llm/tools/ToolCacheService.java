/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caffeine-backed cache service for tool results with smart TTL management.
 * Phase 4: Intelligent caching with tool-specific time-to-live values and
 * metrics.
 *
 * <p>
 * <strong>Every entry is partitioned by a scope tag.</strong> The cache used to
 * key purely on {@code toolName + arguments}, which made it a single global
 * namespace: one authenticated user's tool result was served verbatim to the
 * next caller that happened to ask the same tool the same question. The scope
 * tag — see {@link #resolveScopeTag} and {@link ToolCacheScope} — is now the
 * first segment of every key, so the identity that produced an entry decides
 * who may read it back.
 * </p>
 *
 * <p>
 * A {@code null} scope tag means no usable identity could be derived. It is
 * never substituted with a placeholder such as {@code ""} or {@code "unknown"},
 * because that would recreate exactly one shared partition for every anonymous
 * request. Instead the cache is bypassed: {@link #get} returns {@code null}
 * without a lookup and {@link #put} stores nothing.
 * </p>
 */
@ApplicationScoped
public class ToolCacheService {
    private static final Logger LOGGER = Logger.getLogger(ToolCacheService.class);

    private static final String CACHE_NAME = "tool-results";

    /** Scope tag for {@link ToolCacheScope#GLOBAL} — one partition for everyone. */
    private static final String GLOBAL_SCOPE_TAG = "g";

    /**
     * Prefix for the per-user partition; the userId is hashed, never stored raw.
     */
    private static final String USER_SCOPE_PREFIX = "u:";

    /** Prefix for the per-conversation partition. */
    private static final String CONVERSATION_SCOPE_PREFIX = "c:";

    /** Hex characters of the userId digest retained in a USER scope tag. */
    private static final int USER_HASH_LENGTH = 32;

    /** Separates the scope tag from the tool/arguments part of a cache key. */
    private static final String SCOPE_SEPARATOR = "|";

    /** Arguments longer than this are hashed into the key instead of inlined. */
    private static final int MAX_INLINE_ARGUMENT_LENGTH = 2048;

    // Smart TTL values based on data freshness requirements
    private static final Map<String, Long> TOOL_TTL_SECONDS = Map.ofEntries(
            // Real-time data - short TTL
            Map.entry("weather", 300L), // 5 minutes - weather changes frequently
            Map.entry("websearch", 1800L), // 30 minutes - web results change
            Map.entry("news", 600L), // 10 minutes - news updates quickly

            // Semi-static data - medium TTL
            Map.entry("webscraper", 3600L), // 1 hour - page content semi-static
            Map.entry("pdfreader", 86400L), // 24 hours - PDF content rarely changes

            // Static computations - long TTL
            Map.entry("calculator", 604800L), // 7 days - math results never change
            Map.entry("datetime", 60L), // 1 minute - current time changes
            Map.entry("dataformatter", 86400L), // 24 hours - format conversions are static
            Map.entry("textsummarizer", 86400L) // 24 hours - summaries are deterministic
    );

    private static final long DEFAULT_TTL_SECONDS = 300L; // 5 minutes default

    @Inject
    ICacheFactory cacheFactory;

    @Inject
    MeterRegistry meterRegistry;

    private ICache<String, CachedResult> cache;

    // Metrics
    private io.micrometer.core.instrument.Counter cacheHitCounter;
    private io.micrometer.core.instrument.Counter cacheMissCounter;
    private io.micrometer.core.instrument.Timer cacheGetTimer;
    private io.micrometer.core.instrument.Timer cachePutTimer;

    private final AtomicInteger hits = new AtomicInteger(0);
    private final AtomicInteger misses = new AtomicInteger(0);
    private final Map<String, ToolCacheStats> perToolStats = new ConcurrentHashMap<>();

    /**
     * Cached result wrapper with metadata
     */
    private static class CachedResult {
        final String result;
        final long cachedAt;

        CachedResult(String result, String toolName) {
            this.result = result;
            this.cachedAt = System.currentTimeMillis();
        }
    }

    /**
     * Per-tool cache statistics
     */
    private static class ToolCacheStats {
        final AtomicInteger hits = new AtomicInteger(0);
        final AtomicInteger misses = new AtomicInteger(0);

        double getHitRate() {
            int total = hits.get() + misses.get();
            return total > 0 ? (double) hits.get() / total * 100 : 0;
        }
    }

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.getCache(CACHE_NAME);

        // Initialize metrics
        this.cacheHitCounter = meterRegistry.counter("eddi.tool.cache.hits");
        this.cacheMissCounter = meterRegistry.counter("eddi.tool.cache.misses");
        this.cacheGetTimer = meterRegistry.timer("eddi.tool.cache.get.duration");
        this.cachePutTimer = meterRegistry.timer("eddi.tool.cache.put.duration");

        // Register gauge for cache size
        meterRegistry.gauge("eddi.tool.cache.size", cache, ICache::size);

        LOGGER.info("Tool cache service initialized with Caffeine cache: " + CACHE_NAME);
    }

    /**
     * Resolve the cache scope tag for one tool call.
     *
     * <p>
     * Returns the first segment of every cache key this call may touch, or
     * {@code null} when no usable identity is available — in which case the caller
     * MUST bypass the cache entirely rather than fall back to a shared partition.
     * </p>
     *
     * <p>
     * A {@link ToolCacheScope#USER} scope without a usable {@code userId} degrades
     * to the conversation partition rather than widening to a shared one; if the
     * conversation id is missing too, there is nothing left to partition on and the
     * result is {@code null}.
     * </p>
     *
     * <p>
     * Both tool names are required: {@code toolCacheScopes} is keyed the same way
     * as {@code toolRateLimits} and {@code toolPricing} — dispatch name or
     * configuration slug, dispatch name first. See
     * {@link ToolCacheScope#resolve(Map, String, String, String)}.
     * </p>
     *
     * @param dispatchName
     *            the tool name the model invoked
     * @param canonicalName
     *            the tool's configuration slug (the dispatch name itself when the
     *            tool has no slug)
     * @param toolCacheScopes
     *            per-tool scope overrides from the task config, may be null
     * @param defaultToolCacheScope
     *            task-level default scope token, may be null
     * @param userId
     *            authenticated user id for this conversation, may be null/blank
     * @param conversationId
     *            current conversation id, may be null/blank
     * @return the scope tag, or {@code null} when the cache must be bypassed
     */
    public static String resolveScopeTag(String dispatchName, String canonicalName, Map<String, String> toolCacheScopes,
                                         String defaultToolCacheScope, String userId, String conversationId) {
        ToolCacheScope scope = ToolCacheScope.resolve(toolCacheScopes, defaultToolCacheScope, dispatchName, canonicalName);
        return scopeTagFor(scope, userId, conversationId);
    }

    /**
     * Maps an already-resolved scope plus the available identities onto a scope
     * tag. Exercised through {@link #resolveScopeTag}, which is the only entry
     * point callers should use.
     */
    private static String scopeTagFor(ToolCacheScope scope, String userId, String conversationId) {
        if (scope == ToolCacheScope.GLOBAL) {
            return GLOBAL_SCOPE_TAG;
        }

        if (scope == ToolCacheScope.USER && !isBlank(userId)) {
            return USER_SCOPE_PREFIX + sha256(userId).substring(0, USER_HASH_LENGTH);
        }

        // CONVERSATION scope lands here directly; USER scope lands here only when it
        // has no usable userId and must fail closed onto the narrower partition.
        if (!isBlank(conversationId)) {
            return CONVERSATION_SCOPE_PREFIX + conversationId;
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Get cached result if available Uses smart TTL based on tool type
     *
     * @param scopeTag
     *            partition tag from {@link #resolveScopeTag}; {@code null} bypasses
     *            the cache
     */
    public String get(String scopeTag, String toolName, String arguments) {
        if (scopeTag == null) {
            logBypass(toolName);
            return null;
        }

        return cacheGetTimer.record(() -> {
            String key = buildKey(scopeTag, toolName, arguments);
            CachedResult cached = cache.get(key);

            ToolCacheStats stats = perToolStats.computeIfAbsent(toolName, k -> new ToolCacheStats());

            if (cached == null) {
                misses.incrementAndGet();
                stats.misses.incrementAndGet();
                cacheMissCounter.increment();

                // Record miss by tool name
                meterRegistry.counter("eddi.tool.cache.misses.by_tool", "tool", toolName).increment();

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Cache miss for %s", sanitize(toolName));
                }
                return null;
            }

            hits.incrementAndGet();
            stats.hits.incrementAndGet();
            cacheHitCounter.increment();

            // Record hit by tool name
            meterRegistry.counter("eddi.tool.cache.hits.by_tool", "tool", toolName).increment();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("Cache hit for %s (age: %dms)", sanitize(toolName), System.currentTimeMillis() - cached.cachedAt);
            }
            return cached.result;
        });
    }

    /**
     * Put result in cache with smart TTL based on tool type
     *
     * @param scopeTag
     *            partition tag from {@link #resolveScopeTag}; {@code null} stores
     *            nothing
     */
    public void put(String scopeTag, String toolName, String arguments, String result) {
        put(scopeTag, ToolInvocation.of(toolName), arguments, result);
    }

    /**
     * Put result in cache with the invocation's smart TTL, keyed on its
     * <em>dispatch</em> name.
     *
     * <p>
     * The TTL is resolved from the dispatch name first and only then from the
     * canonical slug — the same precedence
     * {@code AgentOrchestrator.resolveRateLimit} uses, and for the same reason: an
     * entry that describes a single operation is more specific than one describing
     * the whole tool. Slug-only resolution made the {@code news} bucket
     * unreachable, because {@code searchNews} canonicalises to {@code websearch}
     * and matched its 30-minute entry before the 10-minute news entry could ever be
     * considered. The slug fallback stays, because it is the only way a built-in
     * whose dispatch name resembles nothing in the table ({@code calculate} →
     * {@code calculator}) matches at all.
     * </p>
     *
     * <p>
     * The KEY, however, must stay on the dispatch name: {@code searchWeb},
     * {@code searchNews} and {@code searchWikipedia} all canonicalise to
     * {@code websearch}, and keying on the slug would make them share one entry and
     * serve each other's results for identical arguments.
     * </p>
     *
     * @param scopeTag
     *            partition tag from {@link #resolveScopeTag}; {@code null} stores
     *            nothing
     */
    public void put(String scopeTag, ToolInvocation invocation, String arguments, String result) {
        long ttlSeconds = resolveSmartTTL(invocation.dispatchName(), invocation.canonicalName());
        put(scopeTag, invocation.dispatchName(), arguments, result, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * Put result in cache with custom TTL
     *
     * @param scopeTag
     *            partition tag from {@link #resolveScopeTag}; {@code null} stores
     *            nothing
     */
    public void put(String scopeTag, String toolName, String arguments, String result, long ttl, TimeUnit unit) {
        if (scopeTag == null) {
            logBypass(toolName);
            return;
        }

        cachePutTimer.record(() -> {
            String key = buildKey(scopeTag, toolName, arguments);
            CachedResult cached = new CachedResult(result, toolName);

            cache.put(key, cached, ttl, unit);

            // Record put by tool name
            meterRegistry.counter("eddi.tool.cache.puts.by_tool", "tool", toolName).increment();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("Cached result for %s (TTL: %d %s)", sanitize(toolName), ttl, unit.toString().toLowerCase());
            }
        });
    }

    /**
     * Get smart TTL for a single tool name based on its data freshness
     * requirements, falling back to {@link #DEFAULT_TTL_SECONDS}.
     */
    private long getSmartTTL(String toolName) {
        Long ttl = lookupTTL(toolName);
        if (ttl != null) {
            return ttl;
        }

        // Default TTL for unknown tools
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Using default TTL for unknown tool: %s", sanitize(toolName));
        }
        return DEFAULT_TTL_SECONDS;
    }

    /**
     * Get the smart TTL for one invocation: the dispatch name is consulted first,
     * the canonical slug second. See
     * {@link #put(String, ToolInvocation, String, String)}.
     */
    private long resolveSmartTTL(String dispatchName, String canonicalName) {
        Long ttl = lookupTTL(dispatchName);
        if (ttl == null) {
            ttl = lookupTTL(canonicalName);
        }
        if (ttl != null) {
            return ttl;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Using default TTL for unknown tool: %s", sanitize(dispatchName));
        }
        return DEFAULT_TTL_SECONDS;
    }

    /**
     * Match one tool name against the TTL table, exact first then by substring.
     *
     * @return the configured TTL, or {@code null} when nothing matches — the
     *         distinction the caller needs in order to try a second name before
     *         settling for the default.
     */
    private static Long lookupTTL(String toolName) {
        if (toolName == null) {
            return null;
        }

        String lowerToolName = toolName.toLowerCase();

        // Check for exact match
        Long ttl = TOOL_TTL_SECONDS.get(lowerToolName);
        if (ttl != null) {
            return ttl;
        }

        // Check for partial match (e.g., "WebSearchTool" contains "websearch")
        for (Map.Entry<String, Long> entry : TOOL_TTL_SECONDS.entrySet()) {
            if (lowerToolName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Invalidate cache entry
     *
     * @param scopeTag
     *            partition tag from {@link #resolveScopeTag}; {@code null} removes
     *            nothing
     */
    public void invalidate(String scopeTag, String toolName, String arguments) {
        if (scopeTag == null) {
            logBypass(toolName);
            return;
        }

        String key = buildKey(scopeTag, toolName, arguments);
        cache.remove(key);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Invalidated cache for %s", sanitize(toolName));
        }
    }

    private static void logBypass(String toolName) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debugf("Cache bypassed for %s: no usable identity to scope the entry to", sanitize(toolName));
        }
    }

    /**
     * Clear all cache
     */
    public void clear() {
        cache.clear();
        hits.set(0);
        misses.set(0);
        perToolStats.clear();
        LOGGER.info("Tool cache cleared");
    }

    /**
     * Get cache statistics
     */
    public CacheStats getStats() {
        int totalRequests = hits.get() + misses.get();
        double hitRate = totalRequests > 0 ? (double) hits.get() / totalRequests * 100 : 0;

        return new CacheStats(cache.size(), hits.get(), misses.get(), hitRate, perToolStats);
    }

    /**
     * Get statistics for a specific tool
     */
    public ToolCacheStats getToolStats(String toolName) {
        return perToolStats.get(toolName);
    }

    /**
     * Build cache key from the scope tag, tool name and arguments:
     * {@code scopeTag|toolName:arguments}. For short arguments (≤2048 chars) the
     * arguments part stays readable; for longer arguments a SHA-256 digest is used
     * to keep keys bounded while avoiding collisions.
     * <p>
     * The scope tag comes first so that entries belonging to different identities
     * can never collide, whatever the tool name and arguments are.
     */
    private static String buildKey(String scopeTag, String toolName, String arguments) {
        String argumentPart = arguments.length() > MAX_INLINE_ARGUMENT_LENGTH ? sha256(arguments) : arguments;
        return scopeTag + SCOPE_SEPARATOR + toolName + ":" + argumentPart;
    }

    /**
     * Compute SHA-256 hex digest of the given input.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in every JVM
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Get the configured TTL for a tool (for informational purposes)
     */
    public long getConfiguredTTL(String toolName) {
        return getSmartTTL(toolName);
    }

    /**
     * Cache statistics
     */
    public static class CacheStats {
        public final int size;
        public final int hits;
        public final int misses;
        public final double hitRate;
        public final Map<String, ToolCacheStats> perToolStats;

        public CacheStats(int size, int hits, int misses, double hitRate, Map<String, ToolCacheStats> perToolStats) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.perToolStats = new ConcurrentHashMap<>(perToolStats);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Cache Stats: size=%d, hits=%d, misses=%d, hit rate=%.1f%%\n", size, hits, misses, hitRate));

            if (!perToolStats.isEmpty()) {
                sb.append("Per-Tool Stats:\n");
                perToolStats.forEach((tool, stats) -> {
                    sb.append(String.format("  %s: %.1f%% hit rate (%d hits, %d misses)\n", tool, stats.getHitRate(), stats.hits.get(),
                            stats.misses.get()));
                });
            }

            return sb.toString();
        }
    }
}
