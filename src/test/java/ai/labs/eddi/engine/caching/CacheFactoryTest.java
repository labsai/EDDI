/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheFactory} — covers named cache creation, TTL caches,
 * null name handling, and same-cache reuse semantics.
 * <p>
 * The factory builds its caches against {@code System.nanoTime()}, so the
 * expiry assertions here use a sub-millisecond TTL and a short sleep rather
 * than a fake ticker. They only ever wait for "at least this much time has
 * passed", never for "at most", so they are slow-machine safe. Precise expiry
 * semantics are pinned deterministically in {@code CacheImplTest}.
 */
@DisplayName("CacheFactory Tests")
class CacheFactoryTest {

    /**
     * Long enough that a 1 ms TTL has certainly elapsed, short enough to not drag.
     */
    private static final long PAST_TTL_MILLIS = 150L;

    private CacheFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CacheFactory();
    }

    /**
     * The maximum entry count Caffeine was actually built with — asserting the
     * factory's arithmetic in isolation would not catch it failing to reach
     * {@code Caffeine.maximumSize}.
     */
    private static long evictionMaximumOf(ICache<?, ?> cache) {
        return ((CacheImpl<?, ?>) cache).backingCache().policy().eviction()
                .orElseThrow(() -> new AssertionError("cache was built without a size bound")).getMaximum();
    }

    @Nested
    @DisplayName("getCache(name)")
    class GetCache {

        @Test
        @DisplayName("should return a non-null cache")
        void returnsCache() {
            ICache<String, String> cache = factory.getCache("testCache");
            assertNotNull(cache);
        }

        @Test
        @DisplayName("should handle null name as 'local'")
        void nullNameFallback() {
            ICache<String, String> cache = factory.getCache(null);
            assertNotNull(cache);
        }

        @Test
        @DisplayName("should use known cache sizes for predefined names")
        void predefinedCacheSizes() {
            // These use the CACHE_SIZES map — exercises the computeIfAbsent path
            ICache<String, Object> c1 = factory.getCache("userConversations");
            ICache<String, Object> c2 = factory.getCache("agentTriggers");
            ICache<String, Object> c3 = factory.getCache("conversationState");
            ICache<String, Object> c4 = factory.getCache("parser");
            assertNotNull(c1);
            assertNotNull(c2);
            assertNotNull(c3);
            assertNotNull(c4);
        }

        @Test
        @DisplayName("cache should support put and get operations")
        void putAndGet() {
            ICache<String, String> cache = factory.getCache("opsTest");
            cache.put("key1", "value1");
            assertEquals("value1", cache.get("key1"));
        }

        @Test
        @DisplayName("a size-only cache must still honour a PER-ENTRY TTL")
        void perEntryTtlIsHonoured() throws InterruptedException {
            // This is the D5b wiring: ToolCacheService and PaginatedResponseStore both
            // take a cache from getCache(name) and then pass their own TTL to put().
            // Without expireAfter(...) on the builder there is no variable-expiry view
            // for CacheImpl to write through and the TTL is silently dropped.
            ICache<String, String> cache = factory.getCache("perEntryTtl");
            cache.put("expiring", "value", 1, TimeUnit.MILLISECONDS);
            cache.put("permanent", "value");

            Thread.sleep(PAST_TTL_MILLIS);

            assertNull(cache.get("expiring"), "an entry written with a 1ms lifespan must be gone");
            assertEquals("value", cache.get("permanent"), "an entry written without a lifespan must survive");
        }

        @Test
        @DisplayName("a NEGATIVE per-entry lifespan means unlimited, it does not throw")
        void negativeLifespanIsUnlimited() {
            ICache<String, String> cache = factory.getCache("negativeTtl");

            assertDoesNotThrow(() -> cache.put("key1", "value1", -1, TimeUnit.SECONDS));
            assertEquals("value1", cache.get("key1"));
        }
    }

    @Nested
    @DisplayName("getCache(name, ttl)")
    class GetCacheWithTtl {

        @Test
        @DisplayName("should return a non-null cache with TTL")
        void returnsTtlCache() {
            ICache<String, String> cache = factory.getCache("ttlTest", Duration.ofMinutes(5));
            assertNotNull(cache);
        }

        @Test
        @DisplayName("should handle null name as 'local' with TTL")
        void nullNameWithTtl() {
            ICache<String, String> cache = factory.getCache(null, Duration.ofMinutes(5));
            assertNotNull(cache);
        }

        @Test
        @DisplayName("should throw on null TTL")
        void nullTtlThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> factory.getCache("test", null));
        }

        @Test
        @DisplayName("TTL cache should support put and get")
        void ttlPutAndGet() {
            ICache<String, String> cache = factory.getCache("ttlOps", Duration.ofSeconds(60));
            cache.put("key1", "ttlValue");
            assertEquals("ttlValue", cache.get("key1"));
        }

        /**
         * The one expiry path that already worked before D5b — Slack event dedup, the
         * HITL approval-notified cache and the channel thread locks all rely on it.
         * Switching the builder from {@code expireAfterWrite(ttl)} to
         * {@code expireAfter(WriteExpiry.of(ttl))} must not have quietly dropped it.
         */
        @Test
        @DisplayName("the cache-wide TTL still expires entries written without their own lifespan")
        void cacheWideTtlStillExpires() throws InterruptedException {
            ICache<String, String> cache = factory.getCache("cacheWideTtl", Duration.ofMillis(1));
            cache.put("key1", "value1");
            assertEquals("value1", cache.get("key1"));

            Thread.sleep(PAST_TTL_MILLIS);

            assertNull(cache.get("key1"), "the cache-wide TTL must still remove the entry");
        }

        @Test
        @DisplayName("a per-entry lifespan overrides the cache-wide TTL")
        void perEntryLifespanOverridesCacheTtl() throws InterruptedException {
            ICache<String, String> cache = factory.getCache("ttlOverride", Duration.ofHours(1));
            cache.put("short", "value", 1, TimeUnit.MILLISECONDS);
            cache.put("default", "value");

            Thread.sleep(PAST_TTL_MILLIS);

            assertNull(cache.get("short"), "the 1ms per-entry lifespan must win over the 1h cache TTL");
            assertEquals("value", cache.get("default"), "the 1h cache TTL must still apply to untimed entries");
        }

        /**
         * D2: replay protection is only as strong as the cache is deep. The nonce cache
         * must be able to hold every nonce written during the TTL it was asked for — a
         * fixed capacity below {@code peakRps * ttl} forgets nonces that are still
         * replayable, and because Caffeine's admission filter is frequency-based rather
         * than LRU it forgets the <em>newest</em> ones.
         */
        @Test
        @DisplayName("the nonce cache's capacity covers the TTL it is built with")
        void nonceCacheCapacityCoversItsTtl() {
            Duration ttl = Duration.ofSeconds(390);

            long capacity = evictionMaximumOf(factory.getCache("nonce-replay-protection", ttl));

            long steadyStateOccupancy = (long) CacheFactory.NONCE_PEAK_SIGNED_RPS * ttl.toSeconds();
            assertTrue(capacity >= steadyStateOccupancy,
                    "a capacity of " + capacity + " cannot hold the " + steadyStateOccupancy
                            + " nonces written during a " + ttl.toSeconds() + "s replay window");
            assertEquals((long) Math.ceil(steadyStateOccupancy * CacheFactory.RATE_SIZED_EVICTION_HEADROOM), capacity,
                    "the capacity must carry head-room, because eviction is not LRU and drops the newest nonce");
        }

        /**
         * The whole point of deriving the size: raising the TTL must raise the capacity
         * by itself, so a future change to the replay window cannot silently outgrow a
         * hard-coded number.
         */
        @Test
        @DisplayName("a longer nonce TTL yields a proportionally larger cache")
        void nonceCacheCapacityTracksTheTtl() {
            long shortTtl = evictionMaximumOf(factory.getCache("nonce-replay-protection", Duration.ofSeconds(390)));
            long longTtl = evictionMaximumOf(factory.getCache("nonce-replay-protection", Duration.ofSeconds(780)));

            assertEquals(2 * shortTtl, longTtl, "doubling the replay window must double the capacity");
        }

        @Test
        @DisplayName("caches that are not rate-sized keep their configured capacity")
        void nonRateSizedCachesKeepConfiguredSize() {
            assertEquals(10_000L, evictionMaximumOf(factory.getCache("tool-results", Duration.ofMinutes(5))));
            assertEquals(1_000L, evictionMaximumOf(factory.getCache("slack-event-dedup", Duration.ofHours(24))),
                    "an unlisted cache must stay on the 1_000 default however long its TTL is");
        }

        @Test
        @DisplayName("building a TTL cache must not stack expireAfterWrite with expireAfter")
        void ttlCacheBuildsWithoutPolicyConflict() {
            // Caffeine throws IllegalStateException at build() time if both expiry
            // policies are configured. Every @PostConstruct that asks for a TTL cache
            // would fail on startup.
            assertDoesNotThrow(() -> factory.getCache("slack-event-dedup", Duration.ofMinutes(10)));
            assertDoesNotThrow(() -> factory.getCache("channel-thread-locks", Duration.ofHours(24)));
            assertDoesNotThrow(() -> factory.getCache("nonce-replay-protection", Duration.ofMillis(390_000)));
        }
    }
}
