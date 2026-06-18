/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ComponentCacheTest {

    @Test
    void getComponentMap_returnsEmptyMapForNewType() {
        var cache = new ComponentCache();
        Map<String, Object> map = cache.getComponentMap("parser");
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void getComponentMap_returnsSameMapOnSecondCall() {
        var cache = new ComponentCache();
        Map<String, Object> map1 = cache.getComponentMap("parser");
        Map<String, Object> map2 = cache.getComponentMap("parser");
        assertSame(map1, map2);
    }

    @Test
    void put_storesComponent() {
        var cache = new ComponentCache();
        cache.put("parser", "key1", "component1");
        assertEquals("component1", cache.getComponentMap("parser").get("key1"));
    }

    @Test
    void put_multipleComponentTypes_isolated() {
        var cache = new ComponentCache();
        cache.put("parser", "key1", "p1");
        cache.put("rules", "key1", "r1");

        assertEquals("p1", cache.getComponentMap("parser").get("key1"));
        assertEquals("r1", cache.getComponentMap("rules").get("key1"));
    }

    @Test
    void put_overwritesSameKey() {
        var cache = new ComponentCache();
        cache.put("parser", "key1", "old");
        cache.put("parser", "key1", "new");
        assertEquals("new", cache.getComponentMap("parser").get("key1"));
    }

    @Test
    void concurrentAccess_noCorruption() throws Exception {
        var cache = new ComponentCache();
        int threadCount = 8;
        int opsPerThread = 500;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();

        // Half the threads write (simulating deployment), half read (simulating
        // conversation turns)
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // all threads start together
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < opsPerThread; i++) {
                    String type = "type" + (i % 5); // 5 component types
                    if (threadId % 2 == 0) {
                        // Writer thread (deployment)
                        cache.put(type, "key-" + threadId + "-" + i, "val-" + i);
                    } else {
                        // Reader thread (conversation turn)
                        Map<String, Object> map = cache.getComponentMap(type);
                        assertNotNull(map); // should never be null
                        map.getOrDefault("key-any", null); // read without NPE
                    }
                }
            }));
        }

        latch.countDown(); // go!
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Verify no exceptions were thrown
        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get(1, TimeUnit.SECONDS));
        }

        // Verify data is consistent — writer threads wrote to type0..type4
        for (int type = 0; type < 5; type++) {
            Map<String, Object> map = cache.getComponentMap("type" + type);
            assertNotNull(map);
            assertFalse(map.isEmpty());
        }
    }
}
