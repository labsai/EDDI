/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Rate limits of the public owner report endpoint, per node: token buckets that
 * refill continuously.
 * <ul>
 * <li><b>Per link</b>: {@value #PER_LINK_PER_MINUTE} reads a minute — an owner
 * reloading a page is far below it; a script hammering one link is not.</li>
 * <li><b>All reports</b>: {@value #GLOBAL_PER_MINUTE} a minute, so the endpoint
 * cannot be used to load the health stores.</li>
 * <li><b>Unknown tokens</b>: {@value #MISSES_PER_MINUTE} a minute across all
 * callers. Guessing a 256-bit token is hopeless anyway; this keeps a scan from
 * costing database lookups at line rate.</li>
 * </ul>
 * Keyed by the link's token hash, never by client address: behind a proxy the
 * address is the proxy's, and a forwarded header is the client's to forge.
 */
@ApplicationScoped
public class HealthReportRateLimiter {

    static final int PER_LINK_PER_MINUTE = 30;
    static final int GLOBAL_PER_MINUTE = 300;
    static final int MISSES_PER_MINUTE = 60;
    private static final double MINUTE_NANOS = 60_000_000_000.0;

    /** A token bucket refilled at {@code limit} per minute. */
    static final class Bucket {
        private final int limit;
        private double tokens;
        private long last;

        Bucket(int limit, long now) {
            this.limit = limit;
            this.tokens = limit;
            this.last = now;
        }

        synchronized boolean tryAcquire(long now) {
            long elapsed = now - last;
            if (elapsed > 0) {
                tokens = Math.min(limit, tokens + (double) elapsed * limit / MINUTE_NANOS);
                last = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private final LongSupplier nanos;
    private final Bucket global;
    private final Bucket misses;
    private final Cache<String, Bucket> links;

    public HealthReportRateLimiter() {
        this(System::nanoTime);
    }

    HealthReportRateLimiter(LongSupplier nanos) {
        this.nanos = nanos;
        long now = nanos.getAsLong();
        this.global = new Bucket(GLOBAL_PER_MINUTE, now);
        this.misses = new Bucket(MISSES_PER_MINUTE, now);
        this.links = Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(2)).build();
    }

    /** Whether one more report request may be served at all. */
    public boolean tryRequest() {
        return global.tryAcquire(nanos.getAsLong());
    }

    /** Whether one more request with an unknown token may be looked up. */
    public boolean tryMiss() {
        return misses.tryAcquire(nanos.getAsLong());
    }

    /** Whether the link with this token hash may be read once more. */
    public boolean tryLink(String tokenHash) {
        long now = nanos.getAsLong();
        return links.get(tokenHash, k -> new Bucket(PER_LINK_PER_MINUTE, now)).tryAcquire(now);
    }
}
