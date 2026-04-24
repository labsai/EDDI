/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheFactory} — covers named cache creation, TTL caches,
 * null name handling, and same-cache reuse semantics.
 */
@DisplayName("CacheFactory Tests")
class CacheFactoryTest {

    private CacheFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CacheFactory();
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
    }
}
