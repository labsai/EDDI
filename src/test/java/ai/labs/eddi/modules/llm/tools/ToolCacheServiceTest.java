/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.TestCaches;
import ai.labs.eddi.engine.caching.TestCaches.FakeTicker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ToolCacheService} — Caffeine-backed tool result cache with
 * smart TTL and per-identity key scoping.
 */
@DisplayName("ToolCacheService")
class ToolCacheServiceTest {

    /** A representative resolved USER scope tag. */
    private static final String SCOPE_A = "u:0123456789abcdef0123456789abcdef";

    /** A different resolved USER scope tag — MUST NOT share keys with SCOPE_A. */
    private static final String SCOPE_B = "u:fedcba9876543210fedcba9876543210";

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

            String result = service.get(SCOPE_A, "calculator", "2+2");

            assertNull(result);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put stores result under the scoped key with the smart TTL")
        void put_storesInCache() {
            service.put(SCOPE_A, "calculator", "2+2", "4");

            // calculator's smart TTL is 7 days; the key carries the scope tag
            verify(cache).put(eq(SCOPE_A + "|calculator:2+2"), any(), eq(604800L), eq(TimeUnit.SECONDS));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put with custom TTL uses provided values")
        void put_customTTL() {
            service.put(SCOPE_A, "myTool", "args", "result", 120, TimeUnit.SECONDS);

            verify(cache).put(eq(SCOPE_A + "|myTool:args"), any(), eq(120L), eq(TimeUnit.SECONDS));
        }
    }

    // ==================== Cache Key ====================

    @Nested
    @DisplayName("Cache Key Building")
    class CacheKeyTests {

        /**
         * The D5 regression test. Before scoping, the key was
         * {@code toolName + ":" + arguments} — identical for every caller — so one
         * authenticated user's tool result was served verbatim to the next user that
         * asked the same question. Two different identities must now land on two
         * different keys.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("two different users produce two DISTINCT keys for the same tool and arguments")
        void differentUsers_produceDifferentKeys() {
            service.put(SCOPE_A, "getAccountBalance", "{\"account\":\"main\"}", "alice-balance");
            service.put(SCOPE_B, "getAccountBalance", "{\"account\":\"main\"}", "bob-balance");

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(cache, times(2)).put(keys.capture(), any(), anyLong(), any());

            String aliceKey = keys.getAllValues().get(0);
            String bobKey = keys.getAllValues().get(1);

            assertNotEquals(aliceKey, bobKey,
                    "same tool + same arguments from two different users must NOT share a cache key — "
                            + "a shared key is how one user's tool result gets served to another");
            assertTrue(aliceKey.startsWith(SCOPE_A + "|"), "expected the scope tag as the first key segment, got: " + aliceKey);
            assertTrue(bobKey.startsWith(SCOPE_B + "|"), "expected the scope tag as the first key segment, got: " + bobKey);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("the SAME identity does reuse one key (scoping must not disable caching)")
        void sameUser_sharesOneKey() {
            service.put(SCOPE_A, "getAccountBalance", "{\"account\":\"main\"}", "first");
            service.put(SCOPE_A, "getAccountBalance", "{\"account\":\"main\"}", "second");

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(cache, times(2)).put(keys.capture(), any(), anyLong(), any());

            assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("short arguments are inlined verbatim: scopeTag|toolName:arguments")
        void shortArgs_readableKey() {
            String args = "{\"expression\":\"2+2\"}";

            service.put(SCOPE_A, "calculate", args, "4");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(cache).put(key.capture(), any(), anyLong(), any());

            // Full key asserted, not just a prefix: the arguments are present verbatim
            // (not digested) and the scope tag is separated by a single '|'.
            assertEquals(SCOPE_A + "|calculate:" + args, key.getValue());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("long arguments (>2048 chars) are replaced by the FULL SHA-256 hex of those arguments")
        void longArgs_sha256Key() throws Exception {
            String longArgs = "x".repeat(3000);

            service.put(SCOPE_A, "calculate", longArgs, "result");

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(cache).put(key.capture(), any(), anyLong(), any());

            // Independent oracle: anything other than the complete digest (a truncation,
            // a different hash, a prefix of the arguments) fails this equality.
            assertEquals(SCOPE_A + "|calculate:" + sha256Hex(longArgs), key.getValue());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("two different over-threshold argument strings still produce different keys")
        void longArgs_distinctArgumentsStayDistinct() {
            service.put(SCOPE_A, "calculate", "x".repeat(3000) + "A", "a");
            service.put(SCOPE_A, "calculate", "x".repeat(3000) + "B", "b");

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(cache, times(2)).put(keys.capture(), any(), anyLong(), any());

            assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                    "hashing must not collapse distinct long arguments onto one key");
        }
    }

    // ==================== Scope resolution ====================

    @Nested
    @DisplayName("Scope resolution")
    class ScopeResolutionTests {

        @Test
        @DisplayName("no config at all resolves to USER")
        void defaultsToUser() {
            assertEquals(ToolCacheScope.USER, ToolCacheScope.resolve(null, null, "calculate"));
        }

        @Test
        @DisplayName("task-level default applies to tools without an override")
        void taskDefaultApplies() {
            assertEquals(ToolCacheScope.CONVERSATION, ToolCacheScope.resolve(Map.of(), "conversation", "calculate"));
        }

        @Test
        @DisplayName("per-tool entry wins over the task-level default")
        void perToolWinsOverDefault() {
            assertEquals(ToolCacheScope.GLOBAL,
                    ToolCacheScope.resolve(Map.of("calculate", "global"), "conversation", "calculate"));
        }

        @Test
        @DisplayName("a per-tool entry for a DIFFERENT tool does not leak across")
        void perToolIsPerTool() {
            assertEquals(ToolCacheScope.USER,
                    ToolCacheScope.resolve(Map.of("calculate", "global"), null, "getCurrentWeather"));
        }

        @Test
        @DisplayName("tokens are case-insensitive and trimmed")
        void tokensAreLenient() {
            assertEquals(ToolCacheScope.GLOBAL, ToolCacheScope.resolve(Map.of("calculate", "  GLOBAL "), null, "calculate"));
        }

        @Test
        @DisplayName("an unrecognized token falls back to USER, never to a wider scope")
        void unknownTokenFailsClosed() {
            assertEquals(ToolCacheScope.USER, ToolCacheScope.resolve(Map.of("calculate", "globl"), null, "calculate"));
            assertEquals(ToolCacheScope.USER, ToolCacheScope.resolve(null, "everyone", "calculate"));
            assertNull(ToolCacheScope.fromConfig("nonsense"));
            assertNull(ToolCacheScope.fromConfig(null));
        }

        @Test
        @DisplayName("USER scope tag is 'u:' + the first 32 hex chars of the userId digest")
        void userScopeTagShape() throws Exception {
            String tag = ToolCacheService.resolveScopeTag("calculate", null, null, "alice", "conv-1");

            assertEquals("u:" + sha256Hex("alice").substring(0, 32), tag);
            assertFalse(tag.contains("alice"), "the raw userId must not appear in the cache key");
        }

        @Test
        @DisplayName("two different userIds resolve to two different USER tags")
        void differentUserIdsDifferentTags() {
            String alice = ToolCacheService.resolveScopeTag("calculate", null, null, "alice", "conv-1");
            String bob = ToolCacheService.resolveScopeTag("calculate", null, null, "bob", "conv-1");

            assertNotEquals(alice, bob);
        }

        @Test
        @DisplayName("CONVERSATION scope tag is 'c:' + conversationId, ignoring the userId")
        void conversationScopeTag() {
            String tag = ToolCacheService.resolveScopeTag("calculate", Map.of("calculate", "conversation"), null, "alice", "conv-1");

            assertEquals("c:conv-1", tag);
        }

        @Test
        @DisplayName("GLOBAL scope tag is 'g' even without any identity")
        void globalScopeTag() {
            assertEquals("g", ToolCacheService.resolveScopeTag("calculate", Map.of("calculate", "global"), null, null, null));
        }

        @Test
        @DisplayName("USER scope with a blank userId degrades to the conversation partition, not a shared one")
        void userScopeWithoutUserIdDegradesToConversation() {
            assertEquals("c:conv-1", ToolCacheService.resolveScopeTag("calculate", null, null, null, "conv-1"));
            assertEquals("c:conv-1", ToolCacheService.resolveScopeTag("calculate", null, null, "   ", "conv-1"));
        }

        @Test
        @DisplayName("no usable identity at all resolves to null (cache must be bypassed)")
        void noIdentityResolvesToNull() {
            assertNull(ToolCacheService.resolveScopeTag("calculate", null, null, null, null));
            assertNull(ToolCacheService.resolveScopeTag("calculate", null, null, "  ", "  "));
            assertNull(ToolCacheService.resolveScopeTag("calculate", Map.of("calculate", "conversation"), null, "alice", null),
                    "CONVERSATION scope without a conversationId has nothing to partition on");
        }
    }

    // ==================== Null-scope bypass ====================

    @Nested
    @DisplayName("Null scope tag bypasses the cache entirely")
    class NullScopeBypassTests {

        @Test
        @DisplayName("get with a null scope tag never reads the cache and returns null")
        void get_nullScope_noLookup() {
            assertNull(service.get(null, "calculate", "2+2"));

            verify(cache, never()).get(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put with a null scope tag stores nothing")
        void put_nullScope_noStore() {
            service.put(null, "calculate", "2+2", "4");

            verify(cache, never()).put(any(), any(), anyLong(), any());
            verify(cache, never()).put(any(), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("put with an explicit TTL and a null scope tag stores nothing")
        void putWithTtl_nullScope_noStore() {
            service.put(null, "calculate", "2+2", "4", 60, TimeUnit.SECONDS);

            verify(cache, never()).put(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("invalidate with a null scope tag removes nothing")
        void invalidate_nullScope_noRemove() {
            service.invalidate(null, "calculate", "2+2");

            verify(cache, never()).remove(any());
        }

        @Test
        @DisplayName("a bypassed get is not counted as a cache miss")
        void bypassIsNotAMiss() {
            service.get(null, "calculate", "2+2");

            assertEquals(0, service.getStats().misses);
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

            service.get(SCOPE_A, "tool1", "args");

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
            service.get(SCOPE_A, "myTool", "args");

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
        @DisplayName("invalidate removes the scoped key from cache")
        void invalidate_removesKey() {
            service.invalidate(SCOPE_A, "calculator", "2+2");

            verify(cache).remove(SCOPE_A + "|calculator:2+2");
        }

        @Test
        @DisplayName("invalidate for one user does not remove another user's entry")
        void invalidate_isScoped() {
            service.invalidate(SCOPE_A, "calculator", "2+2");

            verify(cache, never()).remove(SCOPE_B + "|calculator:2+2");
        }

        @Test
        @DisplayName("clear resets cache and stats")
        void clear_resetsAll() {
            when(cache.get(anyString())).thenReturn(null);
            service.get(SCOPE_A, "tool", "args");

            service.clear();

            verify(cache).clear();
            var stats = service.getStats();
            assertEquals(0, stats.hits);
            assertEquals(0, stats.misses);
        }
    }

    // ==================== Smart TTL is load-bearing ====================

    /**
     * The tests above run against a mocked {@link ICache} because they are about
     * the <em>key</em> — its shape, its partitioning, and when the cache is
     * bypassed. A mock can say nothing about expiry, and for a long time nothing
     * else did either: {@code CacheImpl} discarded every lifespan it was handed
     * (D5b), so {@code TOOL_TTL_SECONDS} was a lookup table whose values reached
     * Caffeine and were thrown away. {@code getConfiguredTTL} assertions passed
     * throughout.
     * <p>
     * These tests therefore wire a second service to a <strong>real</strong>
     * {@code CacheImpl} over a ticker-driven Caffeine cache — the same shape
     * {@code CacheFactory.getCache("tool-results")} hands out in production — and
     * assert that two tools with two different table entries actually die at two
     * different times.
     */
    @Nested
    @DisplayName("Smart TTL is load-bearing")
    class SmartTtlEnforcementTests {

        /** 60s in the table. */
        private static final String SHORT_TTL_TOOL = "datetime";

        /** 604800s (7 days) in the table. */
        private static final String LONG_TTL_TOOL = "calculator";

        private FakeTicker ticker;
        private ToolCacheService realCacheService;

        @BeforeEach
        void setUp() throws Exception {
            ticker = new FakeTicker();
            ICache<String, Object> realCache = TestCaches.expiring("tool-results", ticker);

            ICacheFactory realCacheFactory = mock(ICacheFactory.class);
            doReturn(realCache).when(realCacheFactory).getCache("tool-results");

            realCacheService = new ToolCacheService();
            setField(realCacheService, "cacheFactory", realCacheFactory);
            setField(realCacheService, "meterRegistry", new SimpleMeterRegistry());
            realCacheService.init();
        }

        @Test
        @DisplayName("a 60s-TTL tool result is gone at 61s while a 7-day one is not")
        void shortTtlExpiresLongTtlSurvives() {
            realCacheService.put(SCOPE_A, SHORT_TTL_TOOL, "now", "12:00");
            realCacheService.put(SCOPE_A, LONG_TTL_TOOL, "2+2", "4");

            assertEquals("12:00", realCacheService.get(SCOPE_A, SHORT_TTL_TOOL, "now"));
            assertEquals("4", realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"));

            ticker.advanceSeconds(61);

            assertNull(realCacheService.get(SCOPE_A, SHORT_TTL_TOOL, "now"),
                    "datetime is 60s in TOOL_TTL_SECONDS — a stale clock reading must not be served at 61s");
            assertEquals("4", realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"),
                    "calculator is 7 days — the short TTL must not take it down too");
        }

        @Test
        @DisplayName("the 7-day tool result does expire, one second past its own TTL")
        void longTtlExpiresEventually() {
            realCacheService.put(SCOPE_A, LONG_TTL_TOOL, "2+2", "4");

            ticker.advanceSeconds(604_799);
            assertEquals("4", realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"), "still inside the 7-day window");

            ticker.advanceSeconds(2);
            assertNull(realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"));
        }

        @Test
        @DisplayName("an explicit TTL passed to put wins over the table")
        void explicitTtlIsHonoured() {
            realCacheService.put(SCOPE_A, LONG_TTL_TOOL, "2+2", "4", 30, TimeUnit.SECONDS);

            ticker.advanceSeconds(31);

            assertNull(realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"),
                    "the caller asked for 30s; the 7-day table entry must not override it");
        }

        @Test
        @DisplayName("an expired entry counts as a miss, not a hit")
        void expiredEntryCountsAsMiss() {
            realCacheService.put(SCOPE_A, SHORT_TTL_TOOL, "now", "12:00");
            realCacheService.get(SCOPE_A, SHORT_TTL_TOOL, "now"); // hit

            ticker.advanceSeconds(61);
            realCacheService.get(SCOPE_A, SHORT_TTL_TOOL, "now"); // must be a miss

            var stats = realCacheService.getStats();
            assertEquals(1, stats.hits);
            assertEquals(1, stats.misses);
        }

        @Test
        @DisplayName("expiry does not leak across scope partitions")
        void expiryIsPerEntryNotPerPartition() {
            realCacheService.put(SCOPE_A, LONG_TTL_TOOL, "2+2", "alice-4");
            ticker.advanceSeconds(300);
            realCacheService.put(SCOPE_B, SHORT_TTL_TOOL, "now", "bob-12:00");

            ticker.advanceSeconds(61);

            assertEquals("alice-4", realCacheService.get(SCOPE_A, LONG_TTL_TOOL, "2+2"));
            assertNull(realCacheService.get(SCOPE_B, SHORT_TTL_TOOL, "now"));
        }
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Independent SHA-256 hex oracle for the key-building assertions. */
    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    // ==================== Additional Smart TTL ====================

    @Nested
    @DisplayName("Smart TTL — Additional Entries")
    class SmartTTLAdditionalTests {

        @Test
        @DisplayName("news tool gets 600s TTL")
        void newsTTL() {
            assertEquals(600L, service.getConfiguredTTL("news"));
        }

        @Test
        @DisplayName("webscraper tool gets 3600s TTL")
        void webscraperTTL() {
            assertEquals(3600L, service.getConfiguredTTL("webscraper"));
        }

        @Test
        @DisplayName("dataformatter tool gets 86400s TTL")
        void dataformatterTTL() {
            assertEquals(86400L, service.getConfiguredTTL("dataformatter"));
        }

        @Test
        @DisplayName("textsummarizer tool gets 86400s TTL")
        void textsummarizerTTL() {
            assertEquals(86400L, service.getConfiguredTTL("textsummarizer"));
        }
    }

    // ==================== Cache Hit Path ====================

    @Nested
    @DisplayName("Cache Hit Path")
    class CacheHitTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("get returns result on cache hit (via captured put)")
        void get_cacheHit_returnsResult() {
            // Use ArgumentCaptor to capture what put stores, then return it for get
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

            service.put(SCOPE_A, "calculator", "2+2", "4");

            verify(cache).put(eq(SCOPE_A + "|calculator:2+2"), valueCaptor.capture(), anyLong(), any());
            Object storedValue = valueCaptor.getValue();

            // Now mock cache.get to return the captured value
            when(cache.get(SCOPE_A + "|calculator:2+2")).thenReturn(storedValue);

            String result = service.get(SCOPE_A, "calculator", "2+2");

            assertEquals("4", result);
        }

        /**
         * The read-side half of
         * {@link CacheKeyTests#differentUsers_produceDifferentKeys}: an entry written
         * under one identity must not be readable under another. The mocked cache
         * answers only the exact key that was written.
         */
        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("an entry written by one user is NOT returned to another user")
        void get_otherUser_missesEntry() {
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

            service.put(SCOPE_A, "getAccountBalance", "{}", "alice-balance");
            verify(cache).put(eq(SCOPE_A + "|getAccountBalance:{}"), valueCaptor.capture(), anyLong(), any());

            when(cache.get(SCOPE_A + "|getAccountBalance:{}")).thenReturn(valueCaptor.getValue());

            assertEquals("alice-balance", service.get(SCOPE_A, "getAccountBalance", "{}"));
            assertNull(service.get(SCOPE_B, "getAccountBalance", "{}"),
                    "the second user must miss, not inherit the first user's cached result");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("cache hit increments hit counter")
        void cacheHit_incrementsCounter() {
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

            service.put(SCOPE_A, "calc", "1+1", "2");
            verify(cache).put(eq(SCOPE_A + "|calc:1+1"), valueCaptor.capture(), anyLong(), any());

            when(cache.get(SCOPE_A + "|calc:1+1")).thenReturn(valueCaptor.getValue());
            service.get(SCOPE_A, "calc", "1+1");

            var stats = service.getStats();
            assertEquals(1, stats.hits);
        }
    }

    // ==================== Statistics — Additional ====================

    @Nested
    @DisplayName("Statistics — Additional")
    class StatsAdditionalTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("hit rate calculated correctly: 1 hit + 1 miss = 50%")
        void hitRateCalculation() {
            // First, a miss
            when(cache.get(anyString())).thenReturn(null);
            service.get(SCOPE_A, "tool1", "args1");

            // Capture a put value
            ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
            service.put(SCOPE_A, "tool1", "args2", "result");
            verify(cache).put(eq(SCOPE_A + "|tool1:args2"), valueCaptor.capture(), anyLong(), any());

            // Then a hit
            when(cache.get(SCOPE_A + "|tool1:args2")).thenReturn(valueCaptor.getValue());
            service.get(SCOPE_A, "tool1", "args2");

            var stats = service.getStats();
            assertEquals(1, stats.hits);
            assertEquals(1, stats.misses);
            assertEquals(50.0, stats.hitRate, 0.1);
        }

        @Test
        @DisplayName("getToolStats returns non-null after access")
        void getToolStatsAfterAccess() {
            when(cache.get(anyString())).thenReturn(null);
            service.get(SCOPE_A, "myCustomTool", "args");

            assertNotNull(service.getToolStats("myCustomTool"));
        }

        @Test
        @DisplayName("CacheStats toString without per-tool stats omits Per-Tool section")
        void cacheStatsToString_noPerToolStats() {
            var stats = new ToolCacheService.CacheStats(0, 0, 0, 0.0, Map.of());
            String str = stats.toString();

            assertTrue(str.contains("Cache Stats"));
            assertFalse(str.contains("Per-Tool Stats"));
        }
    }

    // ==================== Build Key — Threshold ====================

    @Nested
    @DisplayName("Build Key — Threshold")
    class BuildKeyThresholdTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("exactly 2048 chars uses readable key (not hashed)")
        void exactThreshold_readable() {
            String exactArgs = "x".repeat(2048);
            service.put(SCOPE_A, "tool", exactArgs, "result");

            // 2048 chars exactly → should NOT be hashed
            verify(cache).put(eq(SCOPE_A + "|tool:" + exactArgs), any(), anyLong(), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("2049 chars uses SHA-256 hash key")
        void overThreshold_hashed() throws Exception {
            String longArgs = "x".repeat(2049);
            service.put(SCOPE_A, "tool", longArgs, "result");

            verify(cache).put(eq(SCOPE_A + "|tool:" + sha256Hex(longArgs)), any(), anyLong(), any());
        }
    }

    // ==================== Old global-key API stays deleted ====================

    /**
     * Tripwire for D5. The pre-scoping {@code get(toolName, arguments)} /
     * {@code put(toolName, arguments, result)} / {@code invalidate(toolName,
     * arguments)} signatures were <em>deleted</em> rather than kept as overloads on
     * purpose: an overload lets a future caller silently reintroduce the global,
     * unpartitioned key and with it the cross-user leak.
     */
    @Nested
    @DisplayName("unscoped cache API stays deleted")
    class UnscopedApiRemovedTests {

        @Test
        @DisplayName("no two-argument get(toolName, arguments) survives")
        void noUnscopedGet() {
            assertThrows(NoSuchMethodException.class,
                    () -> ToolCacheService.class.getMethod("get", String.class, String.class),
                    "get(toolName, arguments) must not exist — it would build a key every user shares");
        }

        @Test
        @DisplayName("no three-argument put(toolName, arguments, result) survives")
        void noUnscopedPut() {
            assertThrows(NoSuchMethodException.class,
                    () -> ToolCacheService.class.getMethod("put", String.class, String.class, String.class),
                    "put(toolName, arguments, result) must not exist — it would store under a globally shared key");
        }

        @Test
        @DisplayName("no two-argument invalidate(toolName, arguments) survives")
        void noUnscopedInvalidate() {
            assertThrows(NoSuchMethodException.class,
                    () -> ToolCacheService.class.getMethod("invalidate", String.class, String.class));
        }
    }
}
