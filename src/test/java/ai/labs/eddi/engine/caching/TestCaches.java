/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test seam for building caches whose clock a test controls.
 * <p>
 * {@link #expiring} produces exactly what {@code CacheFactory.getCache(name)}
 * produces in production — a size-bounded Caffeine with a
 * {@link WriteExpiry#never()} default and therefore a usable variable-expiry
 * view — except that time is driven by a {@link FakeTicker} instead of
 * {@code System.nanoTime()}. Tests that need to prove an entry expired can then
 * do so deterministically rather than by sleeping.
 * <p>
 * Lives in the production package so it can reach the package-private
 * {@link WriteExpiry}; it is used from tests in other packages
 * ({@code ToolCacheServiceTest}, {@code PaginatedResponseStoreTest}).
 */
public final class TestCaches {

    private TestCaches() {
        // static helpers only
    }

    /**
     * A cache equivalent to {@code CacheFactory.getCache(name)} but ticked by the
     * supplied {@link FakeTicker}.
     */
    public static <K, V> ICache<K, V> expiring(String name, FakeTicker ticker) {
        return new CacheImpl<>(name, Caffeine.newBuilder()
                .maximumSize(10_000)
                .ticker(ticker)
                .expireAfter(WriteExpiry.<Object, Object>never())
                .build());
    }

    /**
     * A Caffeine {@link Ticker} a test can wind forward at will.
     */
    public static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        public void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        public void advanceSeconds(long seconds) {
            advance(Duration.ofSeconds(seconds));
        }
    }
}
