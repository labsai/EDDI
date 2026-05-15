/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("NonceCacheService Tests")
class NonceCacheServiceTest {

    private NonceCacheService nonceCacheService;
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

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString())).thenReturn((ICache) mockCache);
        when(cacheFactory.getCache(anyString(), any())).thenReturn((ICache) mockCache);

        nonceCacheService = new NonceCacheService(cacheFactory, new SimpleMeterRegistry());

        // Set config properties via reflection
        var maxAgeField = NonceCacheService.class.getDeclaredField("maxAgeMs");
        maxAgeField.setAccessible(true);
        maxAgeField.set(nonceCacheService, 300_000L); // 5 min

        var clockSkewField = NonceCacheService.class.getDeclaredField("clockSkewMs");
        clockSkewField.setAccessible(true);
        clockSkewField.set(nonceCacheService, 30_000L); // 30 sec

        // Call @PostConstruct
        nonceCacheService.init();
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
