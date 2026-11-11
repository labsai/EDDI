/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A short-lived cache of what the health views read from the stores — the turn
 * row aggregations above all, which scan up to 90 days of rows per call.
 * <p>
 * Keyed by the full query (agent, range, version, origins, grouping), never by
 * caller: every entry is one agent's numbers, and who may see them is decided
 * before the cache is asked — VIEW on the agent, the ratings rule for ratings,
 * the viewable agents of a portfolio. Entries live
 * {@code eddi.health.cache-ttl} (60 s by default; 0 switches the cache off),
 * well within the rollup interval the numbers lag by anyway. At most
 * {@value #MAX_ENTRIES} entries. A {@code null} result is never cached.
 */
final class HealthQueryCache {

    static final long MAX_ENTRIES = 5_000;

    private final Cache<Object, Object> cache;

    HealthQueryCache(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            this.cache = null;
        } else {
            Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
            this.cache = Caffeine.newBuilder().maximumSize(MAX_ENTRIES).expireAfterWrite(ttl).ticker(ticker).build();
        }
    }

    @SuppressWarnings("unchecked")
    <T> T get(Object key, Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        Object hit = cache.getIfPresent(key);
        if (hit != null) {
            return (T) hit;
        }
        T value = loader.get();
        if (value != null) {
            cache.put(key, value);
        }
        return value;
    }

    /** A cached value, or null. */
    @SuppressWarnings("unchecked")
    <T> T peek(Object key) {
        return cache == null ? null : (T) cache.getIfPresent(key);
    }

    void put(Object key, Object value) {
        if (cache != null && value != null) {
            cache.put(key, value);
        }
    }
}
