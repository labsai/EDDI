package ai.labs.eddi.engine.lifecycle.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
