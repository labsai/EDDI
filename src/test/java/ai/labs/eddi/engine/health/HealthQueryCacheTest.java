/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthQueryCacheTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void concurrentCallersOfAColdKeyRunTheLoaderOnce() throws Exception {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        int callers = 8;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    allStarted.countDown();
                    return cache.get("key", () -> {
                        loads.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "value";
                    });
                }));
            }
            assertTrue(allStarted.await(5, TimeUnit.SECONDS));
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
            // Give the other callers time to reach the cache while the load is in flight.
            Thread.sleep(500);
            release.countDown();
            for (Future<String> result : results) {
                assertEquals("value", result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get());
        assertEquals("value", cache.get("key", () -> "other"));
    }

    @Test
    void concurrentBatchReadsShareOneQueryAndKeepNothing() throws Exception {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        int callers = 4;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(callers);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    allStarted.countDown();
                    return cache.shared("batch", () -> {
                        loads.incrementAndGet();
                        loaderEntered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "rows";
                    });
                }));
            }
            assertTrue(allStarted.await(5, TimeUnit.SECONDS));
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(500);
            release.countDown();
            for (Future<String> result : results) {
                assertEquals("rows", result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loads.get());
        // Nothing kept: the next batch read runs the query again.
        assertEquals("again", cache.shared("batch", () -> "again"));
        assertNull(cache.peek("batch"));
    }

    @Test
    void aFailedLoadIsNotCached() {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        IllegalStateException failure = new IllegalStateException("store down");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> cache.get("key", () -> {
            throw failure;
        }));
        assertSame(failure, thrown);
        assertNull(cache.peek("key"));
        assertEquals("value", cache.get("key", () -> "value"));
    }

    @Test
    void waitersOfAFailedLoadSeeThatFailureOnlyAndTheNextCallerRetries() throws Exception {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> cache.get("key", () -> {
                loaderEntered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("store down");
            }));
            assertTrue(loaderEntered.await(5, TimeUnit.SECONDS));
            AtomicInteger waiterLoads = new AtomicInteger();
            Future<String> waiter = pool.submit(() -> cache.get("key", () -> {
                waiterLoads.incrementAndGet();
                return "waiter";
            }));
            Thread.sleep(500);
            release.countDown();
            assertThrows(Exception.class, () -> first.get(5, TimeUnit.SECONDS));
            // The waiter reached the cache while the load was in flight, so it shares
            // that attempt's failure rather than running its own query.
            Exception shared = assertThrows(Exception.class, () -> waiter.get(5, TimeUnit.SECONDS));
            assertTrue(shared.getCause() instanceof IllegalStateException);
            assertEquals(0, waiterLoads.get());
        } finally {
            pool.shutdownNow();
        }
        // The failure was not cached: the next caller runs the query afresh.
        assertEquals("fresh", cache.get("key", () -> "fresh"));
    }

    @Test
    void aNullResultIsNotCached() {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        assertNull(cache.get("key", () -> null));
        assertEquals("value", cache.get("key", () -> "value"));
    }

    @Test
    void aZeroTtlSwitchesTheCacheOff() {
        HealthQueryCache cache = new HealthQueryCache(Duration.ZERO, CLOCK);
        AtomicInteger loads = new AtomicInteger();
        cache.get("key", loads::incrementAndGet);
        cache.get("key", loads::incrementAndGet);
        assertEquals(2, loads.get());
        cache.put("key", "value");
        assertNull(cache.peek("key"));
    }

    @Test
    void putAndPeek() {
        HealthQueryCache cache = new HealthQueryCache(Duration.ofSeconds(60), CLOCK);
        assertNull(cache.peek("key"));
        cache.put("key", "value");
        assertEquals("value", cache.peek("key"));
        assertEquals("value", cache.get("key", () -> "other"));
    }
}
