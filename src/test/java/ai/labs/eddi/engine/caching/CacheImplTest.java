/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CacheImplTest {

    private CacheImpl<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new CacheImpl<>("test-cache",
                Caffeine.newBuilder().maximumSize(100).build());
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

    // ==================== TTL-aware methods (delegate to non-TTL)
    // ====================

    @Test
    void putWithTtl_delegatesToPut() {
        cache.put("key1", "value1", 60, TimeUnit.SECONDS);
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void putIfAbsentWithTtl_delegatesToPutIfAbsent() {
        cache.putIfAbsent("key1", "value1", 60, TimeUnit.SECONDS);
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void putAllWithTtl_delegatesToPutAll() {
        cache.putAll(Map.of("a", "1"), 60, TimeUnit.SECONDS);
        assertEquals("1", cache.get("a"));
    }

    @Test
    void replaceWithTtl_delegatesToReplace() {
        cache.put("key1", "old");
        cache.replace("key1", "new", 60, TimeUnit.SECONDS);
        assertEquals("new", cache.get("key1"));
    }

    @Test
    void replaceOldNewWithTtl_delegatesToReplace() {
        cache.put("key1", "old");
        assertTrue(cache.replace("key1", "old", "new", 60, TimeUnit.SECONDS));
    }

    @Test
    void putWithLifespanAndIdle_delegatesToPut() {
        cache.put("key1", "value1", 60, TimeUnit.SECONDS, 30, TimeUnit.SECONDS);
        assertEquals("value1", cache.get("key1"));
    }

    @Test
    void putIfAbsentWithLifespanAndIdle_delegatesToPutIfAbsent() {
        cache.putIfAbsent("key1", "value1", 60, TimeUnit.SECONDS, 30, TimeUnit.SECONDS);
        assertEquals("value1", cache.get("key1"));
    }
}
