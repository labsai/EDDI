/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * <p>
 * <b>Single-flight per key</b>: when an entry is missing or has expired, the
 * first caller runs the query and every concurrent caller of the same key waits
 * for that one result instead of running the query again — the Manager polls,
 * and several tabs or users asking at once would otherwise all hit the store
 * the moment an entry expires. The in-flight query is a future in a Caffeine
 * {@link AsyncCache}, run on the first caller's thread; the map is only touched
 * to insert the future, so a slow query never holds a lock other keys need. A
 * query that fails, or answers {@code null}, is removed when it completes: its
 * waiters see that one outcome, and the next caller runs the query afresh.
 */
final class HealthQueryCache {

    static final long MAX_ENTRIES = 5_000;

    private final AsyncCache<Object, Object> cache;
    /** The batch reads in flight ({@link #shared}); empty when none runs. */
    private final ConcurrentMap<Object, CompletableFuture<Object>> batches = new ConcurrentHashMap<>();

    HealthQueryCache(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            this.cache = null;
        } else {
            Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
            this.cache = Caffeine.newBuilder().maximumSize(MAX_ENTRIES).expireAfterWrite(ttl).ticker(ticker).buildAsync();
        }
    }

    <T> T get(Object key, Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> inFlight = cache.asMap().putIfAbsent(key, mine);
        if (inFlight == null) {
            try {
                T value = loader.get();
                if (value == null) {
                    // A null is never cached.
                    cache.asMap().remove(key, mine);
                }
                mine.complete(value);
                return value;
            } catch (RuntimeException | Error e) {
                // Only the callers already waiting see this failure; the next one retries.
                cache.asMap().remove(key, mine);
                mine.completeExceptionally(e);
                throw e;
            }
        }
        return join(inFlight);
    }

    /**
     * Runs {@code loader} once for all concurrent callers of {@code key} and keeps
     * nothing afterwards: for a batch read whose parts the caller caches itself
     * (the portfolio's reads over many agents), so identical requests arriving
     * together share one query. With the cache off it simply runs the loader.
     */
    <T> T shared(Object key, Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> inFlight = batches.putIfAbsent(key, mine);
        if (inFlight != null) {
            return join(inFlight);
        }
        try {
            T value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            batches.remove(key, mine);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T join(CompletableFuture<Object> inFlight) {
        try {
            return (T) inFlight.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** A cached value, or null — never waits for a query still running. */
    @SuppressWarnings("unchecked")
    <T> T peek(Object key) {
        if (cache == null) {
            return null;
        }
        CompletableFuture<Object> entry = cache.getIfPresent(key);
        return entry != null && entry.isDone() && !entry.isCompletedExceptionally() ? (T) entry.join() : null;
    }

    void put(Object key, Object value) {
        if (cache != null && value != null) {
            cache.put(key, CompletableFuture.completedFuture(value));
        }
    }
}
