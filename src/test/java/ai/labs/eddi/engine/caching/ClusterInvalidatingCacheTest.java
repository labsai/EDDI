/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B3: a read-through fill is not a change, so it must not make every other node
 * evict its copy; a mutation still must.
 */
@DisplayName("ClusterInvalidatingCache")
class ClusterInvalidatingCacheTest {

    private final List<Object> evicted = new ArrayList<>();
    private final ClusterInvalidatingCache.Announcer announcer = new ClusterInvalidatingCache.Announcer() {
        @Override
        public void evicted(String cacheName, Object key) {
            evicted.add(key);
        }

        @Override
        public void cleared(String cacheName) {
            evicted.add("*");
        }
    };
    private final ICache<String, String> cache = new ClusterInvalidatingCache<>(
            TestCaches.expiring("agentTriggers", new TestCaches.FakeTicker()), announcer);

    @Test
    @DisplayName("fills are stored locally without announcing an eviction")
    void fillsAreSilent() {
        cache.fill("a", "1");
        cache.fillIfAbsent("b", "2");
        cache.fillIfAbsent("b", "3");

        assertEquals("1", cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals(List.of(), evicted);
    }

    @Test
    @DisplayName("mutations are still announced")
    void mutationsAreAnnounced() {
        cache.put("a", "1");
        cache.remove("a");
        cache.putIfAbsent("b", "2");

        assertEquals(List.of("a", "a", "b"), evicted);
    }

    /**
     * Every change is announced so the other nodes drop their copy. A change made
     * through a view went straight to the node-local cache and was never announced,
     * so the views refuse to change anything, whatever the cache underneath allows.
     */
    @Test
    @DisplayName("keySet, values and entrySet refuse to change the cache, so no change goes unannounced")
    void viewsAreReadOnly() {
        ICache<String, String> overMutableViews = new ClusterInvalidatingCache<>(SharedKvCacheTest.mutableViewCache(), announcer);
        overMutableViews.put("k", "v");

        assertThrows(UnsupportedOperationException.class, () -> overMutableViews.keySet().remove("k"));
        assertThrows(UnsupportedOperationException.class, () -> overMutableViews.values().removeIf(v -> true));
        assertThrows(UnsupportedOperationException.class, () -> overMutableViews.entrySet().iterator().next().setValue("x"));
        assertEquals("v", overMutableViews.get("k"));
        assertEquals(List.of("k"), evicted, "only the put changed the cache, and it was announced");
    }
}
