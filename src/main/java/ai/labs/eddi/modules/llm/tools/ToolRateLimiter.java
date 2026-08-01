/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for tool execution.
 *
 * <p>
 * <b>Buckets are per conversation, per tool.</b> A single global bucket per
 * tool name — which is what this class used to keep — makes
 * {@code defaultRateLimit: 100} a deployment-wide allowance, so one busy (or
 * misbehaving) conversation starves that tool for every other user of the
 * instance. Scoping the bucket to the conversation makes the configured limit
 * mean what an agent designer reads it to mean: calls per minute <em>for this
 * conversation</em>.
 * </p>
 *
 * <p>
 * A second, opt-in global bucket can be layered on top for provider-quota
 * protection ({@code eddi.tools.ratelimit.global.enabled}, off by default, with
 * {@code eddi.tools.ratelimit.global.limit} calls/minute). Both must admit the
 * call; a call rejected by the global bucket returns the token it already took
 * from its conversation bucket.
 * </p>
 *
 * <p>
 * The algorithm is a genuine token bucket: tokens refill continuously at
 * {@code limit} per {@link #WINDOW_MS}, so an exhausted caller recovers
 * gradually. The previous implementation was a fixed window despite this
 * javadoc, which let a caller spend a full allowance at the end of one window
 * and another immediately at the start of the next — 2x the configured rate
 * across the boundary.
 * </p>
 */
@ApplicationScoped
public class ToolRateLimiter {
    private static final Logger LOGGER = Logger.getLogger(ToolRateLimiter.class);

    private static final int DEFAULT_RATE_LIMIT = 100; // calls per minute
    static final long WINDOW_MS = 60_000; // 1 minute
    private static final double WINDOW_NANOS = WINDOW_MS * 1_000_000.0;

    /**
     * Bucket scope for the optional deployment-wide layer, and for callers that
     * have no conversation to attribute the call to. Kept distinct so an unscoped
     * call cannot charge the same bucket twice.
     */
    private static final String SCOPE_GLOBAL = "*global*";
    private static final String SCOPE_UNSCOPED = "*unscoped*";

    /**
     * Bucket identity. A typed key rather than a concatenated string: string keys
     * would need a separator guaranteed absent from both halves, and a lookup by
     * tool would degrade into suffix matching.
     */
    private record BucketKey(String scope, String toolName) {
    }

    /**
     * Index from tool name to the keys of that tool's live buckets.
     * <p>
     * Every per-tool read ({@link #getRemaining}, {@link #getInfo},
     * {@link #getResetTimeMs}) needs the tightest bucket for ONE tool, and the
     * Micrometer gauge calls {@link #getRemaining} on every scrape. Deriving that
     * by iterating the whole 100,000-entry store made metrics scraping O(tools ×
     * live conversations) and took every bucket's monitor on the way, contending
     * with the live {@link #tryAcquire} path. The index keeps the scan proportional
     * to the tool's own buckets.
     * <p>
     * Declared before {@link #buckets} on purpose — the cache's removal listener
     * writes to it, so it must already be initialised.
     * <p>
     * Entries are never removed, only emptied: the key set is the set of tool
     * names, which is bounded by the deployment's configured tools, whereas
     * removing an emptied set would race with a concurrent {@code bucketFor} adding
     * to the very set being dropped.
     */
    private final Map<String, Set<BucketKey>> bucketKeysByTool = new ConcurrentHashMap<>();

    /**
     * Buckets are now keyed per conversation, so the store has to be bounded.
     * <p>
     * Idle expiry is safe rather than merely convenient: a bucket refills
     * completely within one window, so a bucket untouched for two windows is
     * indistinguishable from a bucket that does not exist. Size eviction is the
     * crude fallback for the pathological case and can hand an in-flight
     * conversation a fresh allowance — which is why the ceiling is far above any
     * plausible concurrent conversation count.
     */
    private final Cache<BucketKey, RateLimitBucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMillis(2 * WINDOW_MS))
            .removalListener((BucketKey key, RateLimitBucket value, RemovalCause cause) -> {
                if (key != null) {
                    Set<BucketKey> keys = bucketKeysByTool.get(key.toolName());
                    if (keys != null) {
                        keys.remove(key);
                    }
                }
            })
            .build();

    /** Tool names whose {@code remaining} gauge has already been registered. */
    private final Set<String> gaugedTools = ConcurrentHashMap.newKeySet();

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Whether to layer a deployment-wide bucket on top of the per-conversation one.
     * Off by default: the per-conversation limit is the one an agent designer
     * configures, and a global ceiling is an operator's provider-quota decision.
     */
    @Inject
    @ConfigProperty(name = "eddi.tools.ratelimit.global.enabled", defaultValue = "false")
    boolean globalLimitEnabled;

    /** Calls per minute allowed across the whole deployment, per tool. */
    @Inject
    @ConfigProperty(name = "eddi.tools.ratelimit.global.limit", defaultValue = "1000")
    int globalLimit = 1000;

    @PostConstruct
    public void init() {
        LOGGER.info("Tool rate limiter initialized with metrics");
    }

    /**
     * Token bucket for one (scope, tool) pair.
     *
     * <p>
     * All state mutation is under the instance monitor; {@code refill()} must only
     * be called while holding it.
     * </p>
     */
    private static class RateLimitBucket {
        private int limit;
        private double tokens;
        private long lastRefillNanos;

        RateLimitBucket(int limit) {
            this.limit = Math.max(0, limit);
            this.tokens = this.limit;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * Re-point this bucket at a new configured limit, preserving how much of the
         * old allowance had been consumed. Resetting {@code tokens} to the new limit
         * instead would let a config edit (or a second task using the same tool with a
         * different {@code rateLimit}) wipe an exhausted caller's debt.
         */
        synchronized void updateLimit(int newLimit) {
            int sanitised = Math.max(0, newLimit);
            if (sanitised == limit) {
                return;
            }
            refill();
            double consumed = limit - tokens;
            limit = sanitised;
            tokens = Math.max(0.0, Math.min(limit, limit - consumed));
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            lastRefillNanos = now;
            tokens = Math.min(limit, tokens + elapsedNanos * limit / WINDOW_NANOS);
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        /** Give a token back — used when a later layer rejects the same call. */
        synchronized void release() {
            tokens = Math.min(limit, tokens + 1.0);
        }

        synchronized int getLimit() {
            return limit;
        }

        synchronized int getRemaining() {
            refill();
            return (int) Math.floor(tokens);
        }

        /**
         * When the full allowance is available again — the closest equivalent of the
         * old fixed window's end, and what the REST rate-limit view reports.
         */
        synchronized long getResetTimeMs() {
            refill();
            long now = System.currentTimeMillis();
            if (limit <= 0) {
                return now + WINDOW_MS;
            }
            double missing = limit - tokens;
            return now + Math.max(0L, (long) Math.ceil(missing * WINDOW_MS / limit));
        }

        /** Milliseconds until a single further call is permitted. */
        synchronized long getRetryAfterMs() {
            refill();
            if (tokens >= 1.0) {
                return 0L;
            }
            if (limit <= 0) {
                return WINDOW_MS;
            }
            return Math.max(1L, (long) Math.ceil((1.0 - tokens) * WINDOW_MS / limit));
        }
    }

    /**
     * Try to acquire permission to execute a tool, with no conversation
     * attribution.
     *
     * @param toolName
     *            Name of the tool
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String toolName) {
        return tryAcquire(null, toolName, DEFAULT_RATE_LIMIT);
    }

    /**
     * Try to acquire permission with a custom rate limit and no conversation
     * attribution. All such calls share one bucket per tool — prefer
     * {@link #tryAcquire(String, String, int)} wherever a conversation id exists.
     *
     * @param toolName
     *            Name of the tool
     * @param limit
     *            Calls per minute allowed
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String toolName, int limit) {
        return tryAcquire(null, toolName, limit);
    }

    /**
     * Try to acquire permission to execute a tool on behalf of one conversation.
     *
     * @param conversationId
     *            the conversation the call belongs to; {@code null} or blank falls
     *            back to a shared unattributed bucket
     * @param toolName
     *            Name of the tool
     * @param limit
     *            Calls per minute allowed <em>per conversation</em>
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String conversationId, String toolName, int limit) {
        String scope = (conversationId == null || conversationId.isBlank()) ? SCOPE_UNSCOPED : conversationId;
        RateLimitBucket bucket = bucketFor(scope, toolName, limit);

        boolean acquired = bucket.tryAcquire();
        RateLimitBucket denyingBucket = acquired ? null : bucket;
        String denyingScope = scope;

        if (acquired && globalLimitEnabled) {
            RateLimitBucket global = bucketFor(SCOPE_GLOBAL, toolName, globalLimit);
            if (!global.tryAcquire()) {
                // The conversation is within its own allowance; the deployment is not.
                // Hand its token back so the rejection does not also cost it quota.
                bucket.release();
                acquired = false;
                denyingBucket = global;
                denyingScope = SCOPE_GLOBAL;
            }
        }

        if (acquired) {
            meterRegistry.counter("eddi.tool.ratelimit.allowed", "tool", toolName).increment();
        } else {
            meterRegistry.counter("eddi.tool.ratelimit.denied", "tool", toolName).increment();
            LOGGER.warn(String.format("Rate limit exceeded for tool '%s' (scope '%s'). Retry in %d ms.",
                    sanitize(toolName), sanitize(denyingScope), denyingBucket.getRetryAfterMs()));
        }

        registerRemainingGauge(toolName);

        return acquired;
    }

    private RateLimitBucket bucketFor(String scope, String toolName, int limit) {
        BucketKey bucketKey = new BucketKey(scope, toolName);
        RateLimitBucket bucket = buckets.asMap().compute(bucketKey, (key, existing) -> {
            if (existing == null) {
                return new RateLimitBucket(limit);
            }
            existing.updateLimit(limit);
            return existing;
        });
        bucketKeysByTool.computeIfAbsent(toolName, tool -> ConcurrentHashMap.newKeySet()).add(bucketKey);
        return bucket;
    }

    /**
     * Register the {@code remaining} gauge for a tool, once, <em>tagged with the
     * tool name</em>.
     * <p>
     * It previously carried no tags at all, so the very first bucket to be touched
     * claimed the single un-tagged series and every later tool silently reported
     * that one bucket's headroom. It also cannot be bound to a bucket instance any
     * more — there is one per conversation — so the gauge reports the tightest
     * remaining allowance across that tool's live buckets, i.e. how close the most
     * constrained caller is to being limited.
     */
    private void registerRemainingGauge(String toolName) {
        if (gaugedTools.add(toolName)) {
            meterRegistry.gauge("eddi.tool.ratelimit.remaining", Tags.of("tool", toolName), this,
                    limiter -> limiter.getRemaining(toolName));
        }
    }

    /** The keys {@link #mostConstrained} examines — this tool's buckets only. */
    private Set<BucketKey> scanCandidates(String toolName) {
        Set<BucketKey> keys = bucketKeysByTool.get(toolName);
        return keys != null ? keys : Set.of();
    }

    /**
     * How many buckets a per-tool read will examine. The test hook that pins the
     * index: a regression to the full-store scan makes the work proportional to
     * EVERY tool's buckets again, which no return value can reveal — the results
     * are identical either way, only the cost differs.
     */
    int scanCandidateCount(String toolName) {
        return scanCandidates(toolName).size();
    }

    /**
     * The bucket for this tool with the least headroom, or {@code null} when the
     * tool has no live bucket.
     */
    private RateLimitBucket mostConstrained(String toolName) {
        Set<BucketKey> candidates = scanCandidates(toolName);
        if (candidates.isEmpty()) {
            return null;
        }
        Map<BucketKey, RateLimitBucket> live = buckets.asMap();
        RateLimitBucket tightest = null;
        int tightestRemaining = Integer.MAX_VALUE;
        for (BucketKey key : candidates) {
            RateLimitBucket bucket = live.get(key);
            if (bucket == null) {
                // Evicted or expired between the removal listener firing and now —
                // drop the stale key rather than reporting a bucket that is gone.
                candidates.remove(key);
                continue;
            }
            int remaining = bucket.getRemaining();
            if (remaining < tightestRemaining) {
                tightestRemaining = remaining;
                tightest = bucket;
            }
        }
        return tightest;
    }

    /**
     * Remaining calls for a tool — the tightest allowance across its live buckets.
     */
    public int getRemaining(String toolName) {
        RateLimitBucket bucket = mostConstrained(toolName);
        return bucket != null ? bucket.getRemaining() : DEFAULT_RATE_LIMIT;
    }

    /**
     * Reset time for the tool's most constrained bucket.
     */
    public long getResetTimeMs(String toolName) {
        RateLimitBucket bucket = mostConstrained(toolName);
        return bucket != null ? bucket.getResetTimeMs() : System.currentTimeMillis() + WINDOW_MS;
    }

    /**
     * Reset the rate limit for a specific tool, across every conversation.
     */
    public void reset(String toolName) {
        buckets.asMap().keySet().removeIf(key -> key.toolName().equals(toolName));
        // The removal listener also clears these, but it may run asynchronously —
        // reset() must be observable immediately to its caller.
        Set<BucketKey> keys = bucketKeysByTool.get(toolName);
        if (keys != null) {
            keys.clear();
        }
        LOGGER.info("Reset rate limit for tool: " + sanitize(toolName));
    }

    /**
     * Reset all rate limits
     */
    public void resetAll() {
        buckets.invalidateAll();
        buckets.cleanUp();
        bucketKeysByTool.clear();
        LOGGER.info("Reset all tool rate limits");
    }

    /**
     * Rate limit info for a tool, reported from its most constrained bucket.
     */
    public RateLimitInfo getInfo(String toolName) {
        RateLimitBucket bucket = mostConstrained(toolName);
        if (bucket == null) {
            return new RateLimitInfo(DEFAULT_RATE_LIMIT, DEFAULT_RATE_LIMIT, System.currentTimeMillis() + WINDOW_MS);
        }

        return new RateLimitInfo(bucket.getLimit(), bucket.getRemaining(), bucket.getResetTimeMs());
    }

    /** Test hook: number of live buckets across all scopes. */
    int bucketCount() {
        buckets.cleanUp();
        return buckets.asMap().size();
    }

    /**
     * Rate limit information
     */
    public static class RateLimitInfo {
        public final int limit;
        public final int remaining;
        public final long resetTimeMs;

        public RateLimitInfo(int limit, int remaining, long resetTimeMs) {
            this.limit = limit;
            this.remaining = remaining;
            this.resetTimeMs = resetTimeMs;
        }

        @Override
        public String toString() {
            long waitSec = (resetTimeMs - System.currentTimeMillis()) / 1000;
            return String.format("Rate Limit: %d/%d remaining, resets in %ds", remaining, limit, waitSec);
        }
    }
}
