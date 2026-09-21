/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;

/**
 * Caffeine variable-expiry policy with expire-after-write semantics.
 * <p>
 * Building a cache with {@code Caffeine.expireAfter(Expiry)} rather than
 * {@code expireAfterWrite(Duration)} is what makes
 * {@code cache.policy().expireVariably()} available, and that is what lets
 * {@link CacheImpl} give an individual entry its own lifespan. This policy
 * supplies the fallback used for entries written without one:
 * <ul>
 * <li>{@link #never()} — the entry lives until size eviction removes it (the
 * behaviour of a plain size-bounded cache)</li>
 * <li>{@link #of(Duration)} — the entry lives for the cache-wide TTL (the
 * behaviour of {@code expireAfterWrite})</li>
 * </ul>
 * The two builder options are mutually exclusive — Caffeine throws
 * {@link IllegalStateException} if {@code expireAfterWrite} and
 * {@code expireAfter} are both set — so a cache with a default TTL expresses it
 * through {@link #of(Duration)} instead.
 * <p>
 * Expiry is always measured from the last <em>write</em>: reads return the
 * remaining duration unchanged and therefore never keep an entry alive.
 */
final class WriteExpiry<K, V> implements Expiry<K, V> {

    /**
     * Nanoseconds standing for "effectively never". Caffeine clamps any entry
     * duration to {@code Long.MAX_VALUE >> 1} (~146 years) before handing it to the
     * timer wheel, so this value is safe to pass through.
     */
    static final long NEVER_NANOS = Long.MAX_VALUE;

    private final long defaultNanos;

    private WriteExpiry(long defaultNanos) {
        this.defaultNanos = defaultNanos;
    }

    /**
     * Policy for a cache without a default TTL: entries written without an explicit
     * lifespan never expire on their own.
     */
    static <K, V> WriteExpiry<K, V> never() {
        return new WriteExpiry<>(NEVER_NANOS);
    }

    /**
     * Policy for a cache with a default TTL: entries written without an explicit
     * lifespan expire {@code defaultTtl} after their last write. A negative
     * duration is treated as unlimited, matching the {@link ICache} lifespan
     * contract.
     */
    static <K, V> WriteExpiry<K, V> of(Duration defaultTtl) {
        return new WriteExpiry<>(defaultTtl.isNegative() ? NEVER_NANOS : saturatedNanos(defaultTtl));
    }

    @Override
    public long expireAfterCreate(K key, V value, long currentTime) {
        return defaultNanos;
    }

    @Override
    public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
        // Overwriting an entry restarts its life — this is expire-after-WRITE.
        return defaultNanos;
    }

    @Override
    public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
        // Reading must not extend the lifespan; keep whatever is left.
        return currentDuration;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException durationTooLargeForNanos) {
            return NEVER_NANOS;
        }
    }
}
