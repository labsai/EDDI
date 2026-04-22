package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ToolCacheService} — Caffeine-backed tool result cache with
 * smart TTL.
 */
@DisplayName("ToolCacheService")
class ToolCacheServiceTest {

    private ToolCacheService service;
    private ICache cache; // raw type to avoid CachedResult (private inner class) generics issues

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        cache = mock(ICache.class);
        when(cache.size()).thenReturn(0);

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(eq("tool-results"))).thenReturn(cache);

        service = new ToolCacheService();

        // Inject fields via reflection since they're CDI-injected
        setField(service, "cacheFactory", cacheFactory);
        setField(service, "meterRegistry", new SimpleMeterRegistry());

        // Trigger @PostConstruct
        service.init();
    }

    // ==================== Smart TTL ====================

    @Nested
    @DisplayName("Smart TTL")
    class SmartTTLTests {

        @Test
        @DisplayName("weather tool gets 300s TTL")
        void weatherTTL() {
            assertEquals(300L, service.getConfiguredTTL("weather"));
        }

        @Test
        @DisplayName("calculator tool gets 604800s (7 day) TTL")
        void calculatorTTL() {
            assertEquals(604800L, service.getConfiguredTTL("calculator"));
        }

        @Test
        @DisplayName("datetime tool gets 60s TTL")
        void datetimeTTL() {
            assertEquals(60L, service.getConfiguredTTL("datetime"));
        }

        @Test
        @DisplayName("websearch tool gets 1800s TTL")
        void websearchTTL() {
            assertEquals(1800L, service.getConfiguredTTL("websearch"));
        }

        @Test
        @DisplayName("pdfreader tool gets 86400s TTL")
        void pdfreaderTTL() {
            assertEquals(86400L, service.getConfiguredTTL("pdfreader"));
        }

        @Test
        @DisplayName("unknown tool gets default 300s TTL")
        void unknownToolDefaultTTL() {
            assertEquals(300L, service.getConfiguredTTL("unknownCustomTool"));
        }

        @Test
        @DisplayName("partial match: 'MyWebSearchTool' matches 'websearch' entry")
        void partialMatch() {
            assertEquals(1800L, service.getConfiguredTTL("MyWebSearchTool"));
        }

        @Test
        @DisplayName("case insensitive matching")
        void caseInsensitive() {
            assertEquals(300L, service.getConfiguredTTL("WEATHER"));
        }
    }

    // ==================== get/put ====================

    @Nested
    @DisplayName("get/put")
    class GetPutTests {

        @Test
        @DisplayName("get returns null on cache miss")
        void get_cacheMiss_returnsNull() {
            when(cache.get(anyString())).thenReturn(null);

            String result = service.get("calculator", "2+2");

            assertNull(result);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put stores result in cache")
        void put_storesInCache() {
            service.put("calculator", "2+2", "4");

            verify(cache).put(eq("calculator:2+2"), any(), anyLong(), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put with custom TTL uses provided values")
        void put_customTTL() {
            service.put("myTool", "args", "result", 120, java.util.concurrent.TimeUnit.SECONDS);

            verify(cache).put(eq("myTool:args"), any(), eq(120L), any());
        }
    }

    // ==================== Cache Key ====================

    @Nested
    @DisplayName("Cache Key Building")
    class CacheKeyTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("short arguments use readable key format")
        void shortArgs_readableKey() {
            service.put("calculator", "2+2", "4");

            verify(cache).put(eq("calculator:2+2"), any(), anyLong(), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("long arguments (>2048 chars) use SHA-256 hash key")
        void longArgs_sha256Key() {
            String longArgs = "x".repeat(3000);
            service.put("calculator", longArgs, "result");

            verify(cache).put(argThat(key -> {
                String k = (String) key;
                return k.startsWith("calculator:") && k.length() < 200;
            }), any(), anyLong(), any());
        }
    }

    // ==================== Statistics ====================

    @Nested
    @DisplayName("Statistics")
    class StatsTests {

        @Test
        @DisplayName("initial stats are all zeros")
        void initialStats() {
            var stats = service.getStats();

            assertEquals(0, stats.hits);
            assertEquals(0, stats.misses);
            assertEquals(0.0, stats.hitRate);
        }

        @Test
        @DisplayName("cache misses are tracked")
        void cacheMissTracked() {
            when(cache.get(anyString())).thenReturn(null);

            service.get("tool1", "args");

            var stats = service.getStats();
            assertEquals(1, stats.misses);
        }

        @Test
        @DisplayName("getToolStats returns null for unknown tool")
        void unknownToolStats() {
            assertNull(service.getToolStats("nonexistent"));
        }

        @Test
        @DisplayName("CacheStats toString includes per-tool stats")
        void cacheStatsToString() {
            when(cache.get(anyString())).thenReturn(null);
            service.get("myTool", "args");

            var stats = service.getStats();
            String str = stats.toString();

            assertTrue(str.contains("Cache Stats"));
            assertTrue(str.contains("myTool"));
        }
    }

    // ==================== Invalidate/Clear ====================

    @Nested
    @DisplayName("Invalidate/Clear")
    class InvalidateTests {

        @Test
        @DisplayName("invalidate removes specific key from cache")
        void invalidate_removesKey() {
            service.invalidate("calculator", "2+2");

            verify(cache).remove("calculator:2+2");
        }

        @Test
        @DisplayName("clear resets cache and stats")
        void clear_resetsAll() {
            when(cache.get(anyString())).thenReturn(null);
            service.get("tool", "args");

            service.clear();

            verify(cache).clear();
            var stats = service.getStats();
            assertEquals(0, stats.hits);
            assertEquals(0, stats.misses);
        }
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
