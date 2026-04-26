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
 */
@ApplicationScoped
public class ToolCacheService {
    private static final Logger LOGGER = Logger.getLogger(ToolCacheService.class);

    private static final String CACHE_NAME = "tool-results";

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
     * Get cached result if available Uses smart TTL based on tool type
     */
    public String get(String toolName, String arguments) {
        return cacheGetTimer.record(() -> {
            String key = buildKey(toolName, arguments);
            CachedResult cached = cache.get(key);

            ToolCacheStats stats = perToolStats.computeIfAbsent(toolName, k -> new ToolCacheStats());

            if (cached == null) {
                misses.incrementAndGet();
                stats.misses.incrementAndGet();
                cacheMissCounter.increment();

                // Record miss by tool name
                meterRegistry.counter("eddi.tool.cache.misses.by_tool", "tool", toolName).increment();

                LOGGER.debug("Cache miss for " + toolName);
                return null;
            }

            hits.incrementAndGet();
            stats.hits.incrementAndGet();
            cacheHitCounter.increment();

            // Record hit by tool name
            meterRegistry.counter("eddi.tool.cache.hits.by_tool", "tool", toolName).increment();

            LOGGER.debug(String.format("Cache hit for %s (age: %dms)", sanitize(toolName), System.currentTimeMillis() - cached.cachedAt));
            return cached.result;
        });
    }

    /**
     * Put result in cache with smart TTL based on tool type
     */
    public void put(String toolName, String arguments, String result) {
        long ttlSeconds = getSmartTTL(toolName);
        put(toolName, arguments, result, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * Put result in cache with custom TTL
     */
    public void put(String toolName, String arguments, String result, long ttl, TimeUnit unit) {
        cachePutTimer.record(() -> {
            String key = buildKey(toolName, arguments);
            CachedResult cached = new CachedResult(result, toolName);

            cache.put(key, cached, ttl, unit);

            // Record put by tool name
            meterRegistry.counter("eddi.tool.cache.puts.by_tool", "tool", toolName).increment();

            LOGGER.debug(String.format("Cached result for %s (TTL: %d %s)", sanitize(toolName), ttl, unit.toString().toLowerCase()));
        });
    }

    /**
     * Get smart TTL for a tool based on its data freshness requirements
     */
    private long getSmartTTL(String toolName) {
        // Check for exact match
        Long ttl = TOOL_TTL_SECONDS.get(toolName.toLowerCase());
        if (ttl != null) {
            return ttl;
        }

        // Check for partial match (e.g., "WebSearchTool" contains "websearch")
        String lowerToolName = toolName.toLowerCase();
        for (Map.Entry<String, Long> entry : TOOL_TTL_SECONDS.entrySet()) {
            if (lowerToolName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Default TTL for unknown tools
        LOGGER.debug("Using default TTL for unknown tool: " + sanitize(toolName));
        return DEFAULT_TTL_SECONDS;
    }

    /**
     * Invalidate cache entry
     */
    public void invalidate(String toolName, String arguments) {
        String key = buildKey(toolName, arguments);
        cache.remove(key);
        LOGGER.debug("Invalidated cache for " + toolName);
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
     * Build cache key from tool name and arguments. For short arguments (≤2048
     * chars), the key is readable: "toolName:arguments". For longer arguments, a
     * SHA-256 digest is used to avoid collisions.
     */
    private String buildKey(String toolName, String arguments) {
        if (arguments.length() > 2048) {
            return toolName + ":" + sha256(arguments);
        }
        return toolName + ":" + arguments;
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
