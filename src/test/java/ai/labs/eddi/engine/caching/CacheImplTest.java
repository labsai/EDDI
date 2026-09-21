/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.caching.TestCaches.FakeTicker;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CacheImplTest {

    private FakeTicker ticker;
    private CacheImpl<String, String> cache;

    @BeforeEach
    void setUp() {
        ticker = new FakeTicker();
        cache = new CacheImpl<>("test-cache", Caffeine.newBuilder()
                .maximumSize(100)
                .ticker(ticker)
                .expireAfter(WriteExpiry.<Object, Object>never())
                .build());
    }

    @Test
    void getCacheName() {
        assertEquals("test-cache", cache.getCacheName());
    }

    @Test
    void getCacheName_nullDefault() {
        var c = new CacheImpl<String, String>(null, Caffeine.newBuilder().build());
        assertEquals("default", c.getCacheName());
    }

    @Test
    void putAndGet() {
        cache.put("key1", "value1");
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void put_returnsPreviousValue() {
        cache.put("key1", "old");
        var prev = cache.put("key1", "new");
        assertEquals("old", prev);
        assertEquals("new", cache.get("key1"));
    }

    @Test
    void get_missing_returnsNull() {
        assertNull(cache.get("nonexistent"));
    }

    @Test
    void putIfAbsent_addsWhenMissing() {
        var result = cache.putIfAbsent("key1", "value1");
        assertNull(result); // no previous value
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void putIfAbsent_skipsWhenPresent() {
        cache.put("key1", "existing");
        var result = cache.putIfAbsent("key1", "new");
        assertEquals("existing", result);
        assertEquals("existing", cache.get("key1"));
    }

    @Test
    void remove_returnsOldValue() {
        cache.put("key1", "value1");
        var removed = cache.remove("key1");
        assertEquals("value1", removed);
        assertNull(cache.get("key1"));
    }

    @Test
    void remove_missingKey_returnsNull() {
        assertNull(cache.remove("missing"));
    }

    @Test
    void removeKeyValue_matchingPair() {
        cache.put("key1", "value1");
        assertTrue(cache.remove("key1", "value1"));
        assertNull(cache.get("key1"));
    }

    @Test
    void removeKeyValue_mismatchedPair() {
        cache.put("key1", "value1");
        assertFalse(cache.remove("key1", "wrong"));
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void replace_existing() {
        cache.put("key1", "old");
        var prev = cache.replace("key1", "new");
        assertEquals("old", prev);
        assertEquals("new", cache.get("key1"));
    }

    @Test
    void replace_missing() {
        assertNull(cache.replace("key1", "new"));
    }

    @Test
    void replaceOldNew_matching() {
        cache.put("key1", "old");
        assertTrue(cache.replace("key1", "old", "new"));
        assertEquals("new", cache.get("key1"));
    }

    @Test
    void replaceOldNew_mismatched() {
        cache.put("key1", "old");
        assertFalse(cache.replace("key1", "wrong", "new"));
        assertEquals("old", cache.get("key1"));
    }

    @Test
    void containsKey() {
        cache.put("key1", "value1");
        assertTrue(cache.containsKey("key1"));
        assertFalse(cache.containsKey("key2"));
    }

    @Test
    void containsValue() {
        cache.put("key1", "value1");
        assertTrue(cache.containsValue("value1"));
        assertFalse(cache.containsValue("other"));
    }

    @Test
    void size() {
        assertEquals(0, cache.size());
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(2, cache.size());
    }

    @Test
    void isEmpty() {
        assertTrue(cache.isEmpty());
        cache.put("a", "1");
        assertFalse(cache.isEmpty());
    }

    @Test
    void clear() {
        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void putAll() {
        cache.putAll(Map.of("a", "1", "b", "2"));
        assertEquals("1", cache.get("a"));
        assertEquals("2", cache.get("b"));
    }

    @Test
    void keySet() {
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(2, cache.keySet().size());
        assertTrue(cache.keySet().contains("a"));
    }

    @Test
    void values() {
        cache.put("a", "1");
        assertTrue(cache.values().contains("1"));
    }

    @Test
    void entrySet() {
        cache.put("a", "1");
        assertEquals(1, cache.entrySet().size());
    }

    // ==================== TTL-aware methods ====================

    /**
     * These used to be named {@code *_delegatesToPut} and asserted only that the
     * value was readable straight after the write — which was true whether or not
     * the lifespan was honoured, and so codified the defect (D5b: every TTL was
     * silently discarded, so nothing in a tool cache ever expired) as the contract.
     * Every test below now winds a {@link FakeTicker} past the lifespan and asserts
     * the entry is actually gone.
     */
    @Nested
    @DisplayName("per-entry TTL")
    class TtlTests {

        @Test
        @DisplayName("put(lifespan): the entry is gone once the lifespan has elapsed")
        void putWithTtl_entryExpires() {
            cache.put("key1", "value1", 60, TimeUnit.SECONDS);

            ticker.advanceSeconds(59);
            assertEquals("value1", cache.get("key1"), "the entry must survive its whole lifespan");

            ticker.advanceSeconds(2);
            assertNull(cache.get("key1"), "the entry must be gone once its 60s lifespan has elapsed");
        }

        @Test
        @DisplayName("put(lifespan): each entry expires on its OWN lifespan, not a cache-wide one")
        void putWithTtl_expiryIsPerEntry() {
            cache.put("short", "s", 60, TimeUnit.SECONDS);
            cache.put("long", "l", 600, TimeUnit.SECONDS);

            ticker.advanceSeconds(61);
            assertNull(cache.get("short"), "the 60s entry must be gone");
            assertEquals("l", cache.get("long"), "the 600s entry must NOT be taken down with it");

            ticker.advanceSeconds(540);
            assertNull(cache.get("long"), "the 600s entry must be gone at 601s");
        }

        @Test
        @DisplayName("put(lifespan): expiry runs from the write, not from the last read")
        void putWithTtl_expiresAfterWriteNotAccess() {
            cache.put("key1", "value1", 60, TimeUnit.SECONDS);

            ticker.advanceSeconds(50);
            assertEquals("value1", cache.get("key1"));

            // If reads extended the lifespan this entry would survive to 110s.
            ticker.advanceSeconds(11);
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("put(lifespan): re-writing an entry restarts its lifespan")
        void putWithTtl_rewriteRestartsLifespan() {
            cache.put("key1", "first", 60, TimeUnit.SECONDS);

            ticker.advanceSeconds(50);
            cache.put("key1", "second", 60, TimeUnit.SECONDS);

            ticker.advanceSeconds(50);
            assertEquals("second", cache.get("key1"), "100s after the first write but only 50s after the second");
        }

        @Test
        @DisplayName("put(lifespan): a NEGATIVE lifespan means unlimited (ICache contract)")
        void putWithTtl_negativeLifespanIsUnlimited() {
            assertDoesNotThrow(() -> cache.put("key1", "value1", -1, TimeUnit.SECONDS),
                    "Caffeine rejects negative durations; CacheImpl must translate, not forward");

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("value1", cache.get("key1"), "a negative lifespan must never expire");
        }

        @Test
        @DisplayName("put(lifespan): returns the replaced value")
        void putWithTtl_returnsPreviousValue() {
            cache.put("key1", "old", 60, TimeUnit.SECONDS);

            assertEquals("old", cache.put("key1", "new", 60, TimeUnit.SECONDS));
            assertNull(cache.put("other", "value", 60, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("putIfAbsent(lifespan): the inserted entry expires on its lifespan")
        void putIfAbsentWithTtl_entryExpires() {
            assertNull(cache.putIfAbsent("key1", "value1", 60, TimeUnit.SECONDS));
            assertEquals("value1", cache.get("key1"));

            ticker.advanceSeconds(61);
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("putIfAbsent(lifespan): an existing entry keeps its value AND its lifespan")
        void putIfAbsentWithTtl_doesNotTouchExistingEntry() {
            cache.put("key1", "existing", 60, TimeUnit.SECONDS);

            ticker.advanceSeconds(50);
            assertEquals("existing", cache.putIfAbsent("key1", "new", 600, TimeUnit.SECONDS));

            ticker.advanceSeconds(11);
            assertNull(cache.get("key1"), "putIfAbsent must not have extended the entry to 600s");
        }

        @Test
        @DisplayName("putIfAbsent(lifespan): a NEGATIVE lifespan means unlimited")
        void putIfAbsentWithTtl_negativeLifespanIsUnlimited() {
            assertDoesNotThrow(() -> cache.putIfAbsent("key1", "value1", -1, TimeUnit.SECONDS));

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("value1", cache.get("key1"));
        }

        @Test
        @DisplayName("putAll(lifespan): every entry in the map expires on the lifespan")
        void putAllWithTtl_allEntriesExpire() {
            cache.putAll(Map.of("a", "1", "b", "2"), 60, TimeUnit.SECONDS);
            assertEquals("1", cache.get("a"));
            assertEquals("2", cache.get("b"));

            ticker.advanceSeconds(61);
            assertNull(cache.get("a"));
            assertNull(cache.get("b"));
        }

        @Test
        @DisplayName("putAll(lifespan): a NEGATIVE lifespan means unlimited")
        void putAllWithTtl_negativeLifespanIsUnlimited() {
            assertDoesNotThrow(() -> cache.putAll(Map.of("a", "1"), -1, TimeUnit.SECONDS));

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("1", cache.get("a"));
        }

        @Test
        @DisplayName("replace(lifespan): the replacement carries the new lifespan")
        void replaceWithTtl_appliesLifespan() {
            cache.put("key1", "old"); // untimed: would otherwise live forever

            assertEquals("old", cache.replace("key1", "new", 60, TimeUnit.SECONDS));
            assertEquals("new", cache.get("key1"));

            ticker.advanceSeconds(61);
            assertNull(cache.get("key1"), "replace(lifespan) must bound an entry that had no lifespan");
        }

        @Test
        @DisplayName("replace(lifespan): a missing key is not created and nothing is scheduled")
        void replaceWithTtl_missingKey() {
            assertNull(cache.replace("key1", "new", 60, TimeUnit.SECONDS));
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("replace(lifespan): a NEGATIVE lifespan means unlimited")
        void replaceWithTtl_negativeLifespanIsUnlimited() {
            cache.put("key1", "old", 60, TimeUnit.SECONDS);

            assertDoesNotThrow(() -> cache.replace("key1", "new", -1, TimeUnit.SECONDS));

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("new", cache.get("key1"));
        }

        @Test
        @DisplayName("replace(old, new, lifespan): a matching pair gets the new lifespan")
        void replaceOldNewWithTtl_appliesLifespan() {
            cache.put("key1", "old");

            assertTrue(cache.replace("key1", "old", "new", 60, TimeUnit.SECONDS));

            ticker.advanceSeconds(61);
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("replace(old, new, lifespan): a mismatched pair leaves the entry AND its lifespan alone")
        void replaceOldNewWithTtl_mismatchLeavesLifespanAlone() {
            cache.put("key1", "old"); // untimed

            assertFalse(cache.replace("key1", "wrong", "new", 60, TimeUnit.SECONDS));

            ticker.advanceSeconds(61);
            assertEquals("old", cache.get("key1"), "a failed replace must not schedule an expiry");
        }

        @Test
        @DisplayName("replace(old, new, lifespan): a NEGATIVE lifespan means unlimited")
        void replaceOldNewWithTtl_negativeLifespanIsUnlimited() {
            cache.put("key1", "old", 60, TimeUnit.SECONDS);

            assertTrue(cache.replace("key1", "old", "new", -1, TimeUnit.SECONDS));

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("new", cache.get("key1"));
        }

        @Test
        @DisplayName("put(lifespan, maxIdle): the lifespan is honoured (maxIdle is unsupported)")
        void putWithLifespanAndIdle_honoursLifespan() {
            cache.put("key1", "value1", 60, TimeUnit.SECONDS, 30, TimeUnit.SECONDS);
            assertEquals("value1", cache.get("key1"));

            ticker.advanceSeconds(61);
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("putIfAbsent(lifespan, maxIdle): the lifespan is honoured (maxIdle is unsupported)")
        void putIfAbsentWithLifespanAndIdle_honoursLifespan() {
            cache.putIfAbsent("key1", "value1", 60, TimeUnit.SECONDS, 30, TimeUnit.SECONDS);
            assertEquals("value1", cache.get("key1"));

            ticker.advanceSeconds(61);
            assertNull(cache.get("key1"));
        }

        @Test
        @DisplayName("an untimed put clears a lifespan set by an earlier timed put")
        void untimedPutRemovesLifespan() {
            cache.put("key1", "first", 60, TimeUnit.SECONDS);
            cache.put("key1", "second");

            ticker.advanceSeconds(61);
            assertEquals("second", cache.get("key1"), "put(key, value) means 'no lifespan'");
        }
    }

    /**
     * The shape {@code CacheFactory.getCache(name, ttl)} hands out: a
     * {@link WriteExpiry#of(Duration)} default instead of {@code expireAfterWrite}.
     * Entries written without a lifespan must behave exactly as
     * {@code expireAfterWrite} made them behave, and a per-entry lifespan must
     * still override the cache-wide one in both directions.
     */
    @Nested
    @DisplayName("cache with a default TTL")
    class DefaultTtlTests {

        private CacheImpl<String, String> ttlCache;

        @BeforeEach
        void setUp() {
            ttlCache = new CacheImpl<>("ttl-cache", Caffeine.newBuilder()
                    .maximumSize(100)
                    .ticker(ticker)
                    .expireAfter(WriteExpiry.<Object, Object>of(Duration.ofSeconds(60)))
                    .build());
        }

        @Test
        @DisplayName("an untimed put expires after the cache-wide TTL")
        void untimedPutUsesCacheTtl() {
            ttlCache.put("key1", "value1");

            ticker.advanceSeconds(59);
            assertEquals("value1", ttlCache.get("key1"));

            ticker.advanceSeconds(2);
            assertNull(ttlCache.get("key1"));
        }

        @Test
        @DisplayName("reading does not extend the cache-wide TTL")
        void readDoesNotExtendCacheTtl() {
            ttlCache.put("key1", "value1");

            ticker.advanceSeconds(50);
            assertEquals("value1", ttlCache.get("key1"));

            // Were expireAfterRead to reset the clock, this would survive to 110s and
            // every dedup/lock cache would silently become expire-after-ACCESS.
            ticker.advanceSeconds(11);
            assertNull(ttlCache.get("key1"));
        }

        @Test
        @DisplayName("re-writing restarts the cache-wide TTL")
        void rewriteRestartsCacheTtl() {
            ttlCache.put("key1", "first");

            ticker.advanceSeconds(50);
            ttlCache.put("key1", "second");

            ticker.advanceSeconds(50);
            assertEquals("second", ttlCache.get("key1"));
        }

        @Test
        @DisplayName("a NEGATIVE cache-wide TTL means unlimited, it does not blow up the builder")
        void negativeCacheTtlIsUnlimited() {
            var negative = new CacheImpl<String, String>("negative-ttl", Caffeine.newBuilder()
                    .ticker(ticker)
                    .expireAfter(WriteExpiry.<Object, Object>of(Duration.ofSeconds(-1)))
                    .build());

            negative.put("key1", "value1");

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("value1", negative.get("key1"));
        }

        @Test
        @DisplayName("a cache-wide TTL too large for nanoseconds saturates instead of overflowing")
        void hugeCacheTtlSaturates() {
            var huge = new CacheImpl<String, String>("huge-ttl", Caffeine.newBuilder()
                    .ticker(ticker)
                    .expireAfter(WriteExpiry.<Object, Object>of(Duration.ofDays(400_000)))
                    .build());

            assertDoesNotThrow(() -> huge.put("key1", "value1"));

            ticker.advance(Duration.ofDays(365 * 10L));
            assertEquals("value1", huge.get("key1"));
        }

        @Test
        @DisplayName("a per-entry lifespan overrides the cache-wide TTL, shorter or longer")
        void perEntryLifespanOverridesCacheTtl() {
            ttlCache.put("shorter", "s", 10, TimeUnit.SECONDS);
            ttlCache.put("longer", "l", 600, TimeUnit.SECONDS);
            ttlCache.put("default", "d");

            ticker.advanceSeconds(11);
            assertNull(ttlCache.get("shorter"), "10s must beat the 60s cache TTL");
            assertEquals("d", ttlCache.get("default"));

            ticker.advanceSeconds(50);
            assertNull(ttlCache.get("default"), "the cache TTL still applies to untimed entries");
            assertEquals("l", ttlCache.get("longer"), "600s must beat the 60s cache TTL");

            ticker.advanceSeconds(540);
            assertNull(ttlCache.get("longer"));
        }
    }

    /**
     * A {@code CacheImpl} can be constructed over any Caffeine cache. One built
     * without a variable expiry policy has no per-entry expiry to offer, so the TTL
     * overloads must still store the value rather than blow up.
     */
    @Nested
    @DisplayName("cache without a variable expiry policy")
    class NoVariableExpiryTests {

        private CacheImpl<String, String> plain;

        @BeforeEach
        void setUp() {
            plain = new CacheImpl<>("plain", Caffeine.newBuilder().maximumSize(100).ticker(ticker).build());
        }

        @Test
        @DisplayName("TTL puts degrade to untimed puts instead of throwing")
        void ttlOperationsDegradeGracefully() {
            assertNull(plain.put("key1", "value1", 60, TimeUnit.SECONDS));
            assertEquals("value1", plain.get("key1"));

            assertEquals("value1", plain.putIfAbsent("key1", "other", 60, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> plain.putAll(Map.of("a", "1"), 60, TimeUnit.SECONDS));
            assertEquals("value1", plain.replace("key1", "replaced", 60, TimeUnit.SECONDS));
            assertTrue(plain.replace("key1", "replaced", "again", 60, TimeUnit.SECONDS));

            ticker.advanceSeconds(61);
            assertEquals("again", plain.get("key1"), "without variable expiry there is nothing to expire the entry");
        }
    }
}
