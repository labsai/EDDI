/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("NonceCacheService Tests")
class NonceCacheServiceTest {

    private static final long MAX_AGE_MS = 300_000L;
    private static final long CLOCK_SKEW_MS = 30_000L;

    private NonceCacheService nonceCacheService;
    private ICacheFactory cacheFactory;
    private final ConcurrentHashMap<String, Boolean> cacheMap = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        cacheMap.clear();

        // Create a mock ICache backed by ConcurrentHashMap
        ICache<String, Boolean> mockCache = mock(ICache.class);
        when(mockCache.get(any())).thenAnswer(inv -> cacheMap.get(inv.getArgument(0)));
        doAnswer(inv -> {
            cacheMap.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(mockCache).put(anyString(), any(Boolean.class));
        // putIfAbsent: return null on first insert (success), existing value on
        // duplicate
        when(mockCache.putIfAbsent(anyString(), any(Boolean.class)))
                .thenAnswer(inv -> cacheMap.putIfAbsent(inv.getArgument(0), inv.getArgument(1)));

        cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString())).thenReturn((ICache) mockCache);
        when(cacheFactory.getCache(anyString(), any())).thenReturn((ICache) mockCache);

        nonceCacheService = new NonceCacheService(cacheFactory, new SimpleMeterRegistry());

        // Set config properties via reflection
        var maxAgeField = NonceCacheService.class.getDeclaredField("maxAgeMs");
        maxAgeField.setAccessible(true);
        maxAgeField.set(nonceCacheService, MAX_AGE_MS); // 5 min

        var clockSkewField = NonceCacheService.class.getDeclaredField("clockSkewMs");
        clockSkewField.setAccessible(true);
        clockSkewField.set(nonceCacheService, CLOCK_SKEW_MS); // 30 sec

        // Call @PostConstruct
        nonceCacheService.init();
    }

    /**
     * A nonce that is forgotten while its timestamp would still pass the freshness
     * and clock-skew checks is a nonce that can be replayed. The cache must
     * therefore be asked for with an explicit TTL that covers the whole window —
     * asking for the size-only cache leaves the lifetime entirely to eviction.
     */
    @Nested
    @DisplayName("Replay window coverage")
    class CacheConfigurationTests {

        @Test
        @DisplayName("the nonce cache is requested WITH a TTL, not from the size-only overload")
        void usesTtlCache() {
            verify(cacheFactory).getCache(eq("nonce-replay-protection"), any(Duration.class));
            verify(cacheFactory, never()).getCache(anyString());
        }

        @Test
        @DisplayName("the requested TTL covers maxAge + clockSkew with head-room")
        void ttlCoversTheReplayWindow() {
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(cacheFactory).getCache(eq("nonce-replay-protection"), ttl.capture());

            assertTrue(ttl.getValue().toMillis() > MAX_AGE_MS + CLOCK_SKEW_MS,
                    "a TTL of " + ttl.getValue() + " would forget a nonce that is still replayable "
                            + "(maxAge " + MAX_AGE_MS + "ms + clockSkew " + CLOCK_SKEW_MS + "ms)");
        }

        /**
         * A TTL that covers the replay window is worthless if the cache cannot hold the
         * nonces written during it — size eviction forgets them just as effectively as
         * expiry would, and Caffeine's frequency-based admission drops the newest (most
         * replayable) ones first. This joins the TTL this service actually asks for to
         * the capacity {@link CacheFactory} actually builds, so changing either one
         * alone fails here.
         */
        @Test
        @DisplayName("the real CacheFactory sizes the nonce cache for the TTL this service requests")
        void cacheCapacityCoversTheRequestedTtl() {
            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(cacheFactory).getCache(eq("nonce-replay-protection"), ttl.capture());

            long requiredEntries = (long) CacheFactory.NONCE_PEAK_SIGNED_RPS * ttl.getValue().toSeconds();
            long capacity = CacheFactory.maximumSizeFor("nonce-replay-protection", ttl.getValue());

            assertTrue(capacity >= requiredEntries,
                    "a " + ttl.getValue().toSeconds() + "s replay window at "
                            + CacheFactory.NONCE_PEAK_SIGNED_RPS + " signed requests/s holds " + requiredEntries
                            + " nonces, but the cache is capped at " + capacity
                            + " — the overflow is replayable inside its own freshness window");
        }
    }

    @Nested
    @DisplayName("Valid Nonces")
    class ValidTests {

        @Test
        @DisplayName("Should accept fresh nonce with current timestamp")
        void testValidNonce() {
            var result = nonceCacheService.validate("nonce-1", Instant.now().toEpochMilli());
            assertEquals(NonceCacheService.NonceValidation.VALID, result);
        }

        @Test
        @DisplayName("Should accept nonce within max age")
        void testNonceWithinAge() {
            long fourMinutesAgo = Instant.now().toEpochMilli() - 240_000;
            var result = nonceCacheService.validate("nonce-2", fourMinutesAgo);
            assertEquals(NonceCacheService.NonceValidation.VALID, result);
        }
    }

    @Nested
    @DisplayName("Freshness Rejection")
    class FreshnessTests {

        @Test
        @DisplayName("Should reject nonce that is too old")
        void testTooOld() {
            long sixMinutesAgo = Instant.now().toEpochMilli() - 360_000;
            var result = nonceCacheService.validate("nonce-old", sixMinutesAgo);
            assertEquals(NonceCacheService.NonceValidation.TOO_OLD, result);
        }
    }

    @Nested
    @DisplayName("Clock Skew Rejection")
    class ClockSkewTests {

        @Test
        @DisplayName("Should reject nonce too far in the future")
        void testClockSkew() {
            long oneMinuteAhead = Instant.now().toEpochMilli() + 60_000;
            var result = nonceCacheService.validate("nonce-future", oneMinuteAhead);
            assertEquals(NonceCacheService.NonceValidation.CLOCK_SKEW, result);
        }

        @Test
        @DisplayName("Should accept nonce slightly in the future (within skew)")
        void testWithinSkew() {
            long slightlyAhead = Instant.now().toEpochMilli() + 10_000;
            var result = nonceCacheService.validate("nonce-ok", slightlyAhead);
            assertEquals(NonceCacheService.NonceValidation.VALID, result);
        }
    }

    @Nested
    @DisplayName("Replay Detection")
    class ReplayTests {

        @Test
        @DisplayName("Should reject duplicate nonce")
        void testReplay() {
            long now = Instant.now().toEpochMilli();

            var first = nonceCacheService.validate("nonce-dup", now);
            assertEquals(NonceCacheService.NonceValidation.VALID, first);

            var second = nonceCacheService.validate("nonce-dup", now);
            assertEquals(NonceCacheService.NonceValidation.REPLAY, second);
        }

        @Test
        @DisplayName("Should accept different nonces")
        void testDifferentNonces() {
            long now = Instant.now().toEpochMilli();

            assertEquals(NonceCacheService.NonceValidation.VALID,
                    nonceCacheService.validate("nonce-a", now));
            assertEquals(NonceCacheService.NonceValidation.VALID,
                    nonceCacheService.validate("nonce-b", now));
        }
    }
}
