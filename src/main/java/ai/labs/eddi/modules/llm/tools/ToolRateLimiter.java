/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter for tool execution to prevent abuse. Phase 4: Token bucket
 * algorithm with per-tool limits and metrics.
 */
@ApplicationScoped
public class ToolRateLimiter {
    private static final Logger LOGGER = Logger.getLogger(ToolRateLimiter.class);

    private static final int DEFAULT_RATE_LIMIT = 100; // calls per minute
    private static final long WINDOW_MS = 60_000; // 1 minute

    @Inject
    MeterRegistry meterRegistry;

    private final Map<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        LOGGER.info("Tool rate limiter initialized with metrics");
    }

    /**
     * Rate limit bucket for a specific tool
     */
    private static class RateLimitBucket {
        private int limit;
        private final AtomicInteger count;
        private long windowStart;

        RateLimitBucket(int limit) {
            this.limit = limit;
            this.count = new AtomicInteger(0);
            this.windowStart = System.currentTimeMillis();
        }

        /**
         * Update the rate limit for this bucket. Only updates if the new limit differs
         * from the default.
         */
        synchronized void updateLimit(int newLimit) {
            if (this.limit != newLimit) {
                this.limit = newLimit;
            }
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();

            // Reset window if expired
            if (now - windowStart > WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }

            // Check if under limit
            if (count.get() < limit) {
                count.incrementAndGet();
                return true;
            }

            return false;
        }

        synchronized int getRemaining() {
            long now = System.currentTimeMillis();

            // Reset if window expired
            if (now - windowStart > WINDOW_MS) {
                windowStart = now;
                count.set(0);
            }

            return Math.max(0, limit - count.get());
        }

        synchronized long getResetTimeMs() {
            return windowStart + WINDOW_MS;
        }
    }

    /**
     * Try to acquire permission to execute a tool
     *
     * @param toolName
     *            Name of the tool
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String toolName) {
        return tryAcquire(toolName, DEFAULT_RATE_LIMIT);
    }

    /**
     * Try to acquire permission with custom rate limit
     *
     * @param toolName
     *            Name of the tool
     * @param limit
     *            Calls per minute allowed
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String toolName, int limit) {
        RateLimitBucket bucket = buckets.compute(toolName, (k, existing) -> {
            if (existing == null) {
                return new RateLimitBucket(limit);
            }
            existing.updateLimit(limit);
            return existing;
        });

        boolean acquired = bucket.tryAcquire();

        if (acquired) {
            meterRegistry.counter("eddi.tool.ratelimit.allowed", "tool", toolName).increment();
        } else {
            meterRegistry.counter("eddi.tool.ratelimit.denied", "tool", toolName).increment();

            long resetTime = bucket.getResetTimeMs();
            long waitTimeMs = resetTime - System.currentTimeMillis();
            LOGGER.warn(String.format("Rate limit exceeded for tool '%s'. Reset in %d seconds.", sanitize(toolName), waitTimeMs / 1000));
        }

        // Update gauge for current usage
        meterRegistry.gauge("eddi.tool.ratelimit.remaining", bucket, b -> (double) b.getRemaining());

        return acquired;
    }

    /**
     * Get remaining calls for a tool in current window
     */
    public int getRemaining(String toolName) {
        RateLimitBucket bucket = buckets.get(toolName);
        return bucket != null ? bucket.getRemaining() : DEFAULT_RATE_LIMIT;
    }

    /**
     * Get reset time for a tool's rate limit window
     */
    public long getResetTimeMs(String toolName) {
        RateLimitBucket bucket = buckets.get(toolName);
        return bucket != null ? bucket.getResetTimeMs() : System.currentTimeMillis() + WINDOW_MS;
    }

    /**
     * Reset rate limit for a specific tool
     */
    public void reset(String toolName) {
        buckets.remove(toolName);
        LOGGER.info("Reset rate limit for tool: " + sanitize(toolName));
    }

    /**
     * Reset all rate limits
     */
    public void resetAll() {
        buckets.clear();
        LOGGER.info("Reset all tool rate limits");
    }

    /**
     * Get rate limit info for a tool
     */
    public RateLimitInfo getInfo(String toolName) {
        RateLimitBucket bucket = buckets.get(toolName);
        if (bucket == null) {
            return new RateLimitInfo(DEFAULT_RATE_LIMIT, DEFAULT_RATE_LIMIT, System.currentTimeMillis() + WINDOW_MS);
        }

        return new RateLimitInfo(bucket.limit, bucket.getRemaining(), bucket.getResetTimeMs());
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
