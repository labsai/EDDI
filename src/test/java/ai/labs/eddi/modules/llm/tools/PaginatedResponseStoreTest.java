/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.TestCaches;
import ai.labs.eddi.engine.caching.TestCaches.FakeTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("PaginatedResponseStore Tests")
class PaginatedResponseStoreTest {

    private PaginatedResponseStore store;
    private static final String CONV = "68f1c0ffee0000000000c0de";

    @Mock
    private ICacheFactory cacheFactory;

    @SuppressWarnings("unchecked")
    private ICache<String, Object> cache = mock(ICache.class);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);
        doReturn(cache).when(cacheFactory).getCache("paginated-tool-responses");

        store = new PaginatedResponseStore();
        // Use reflection to inject since @Inject fields
        try {
            var cacheFactoryField = PaginatedResponseStore.class.getDeclaredField("cacheFactory");
            cacheFactoryField.setAccessible(true);
            cacheFactoryField.set(store, cacheFactory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        store.init();
    }

    @Nested
    @DisplayName("Store Tests")
    class StoreTests {

        @Test
        @DisplayName("Should split response into correct number of pages")
        void testStoreCreatesPages() {
            String response = "A".repeat(300);
            String responseId = store.store(CONV, "testTool", response, 100);

            assertNotNull(responseId);
            verify(cache).put(eq(CONV + "/" + responseId), any(), eq(900L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should return null for null response")
        void testStoreNullResponse() {
            assertNull(store.store(CONV, "testTool", null, 100));
        }

        @Test
        @DisplayName("Should return null for empty response")
        void testStoreEmptyResponse() {
            assertNull(store.store(CONV, "testTool", "", 100));
        }

        @Test
        @DisplayName("Should return null for zero page size")
        void testStoreZeroPageSize() {
            assertNull(store.store(CONV, "testTool", "content", 0));
        }

        @Test
        @DisplayName("L13: without a conversation nothing is stored — pages that cannot be scoped are not kept")
        void testStoreNeedsAConversation() {
            assertNull(store.store(null, "testTool", "content", 100));
            assertNull(store.store(" ", "testTool", "content", 100));
            verify(cache, never()).put(any(), any(), anyLong(), any());
        }
    }

    /**
     * Security review 2026-10-15, L13: the store is one process-wide cache, and a
     * responseId used to be enough to read any conversation's pages.
     */
    @Nested
    @DisplayName("Conversation scoping")
    class ScopingTests {

        @Test
        @DisplayName("another conversation cannot read the pages, by responseId or by count")
        void otherConversationIsNotFound() {
            var realStore = createStoreWithRealCache();
            String responseId = realStore.store(CONV, "testTool", "A".repeat(250), 100);

            assertNotNull(realStore.getPage(CONV, responseId, 1), "the owning conversation reads its page");
            assertNull(realStore.getPage("ffffffffffffffffffffffff", responseId, 1), "a different conversation gets nothing");
            assertEquals(0, realStore.getPageCount("ffffffffffffffffffffffff", responseId));
            assertNull(realStore.getPage(null, responseId, 1));
            assertNull(realStore.getPage(CONV, null, 1));
        }

        @Test
        @DisplayName("the key cannot be forged by a responseId that carries another conversation's prefix")
        void keyIsNotForgeable() {
            var realStore = createStoreWithRealCache();
            String responseId = realStore.store(CONV, "testTool", "A".repeat(250), 100);

            assertNull(realStore.getPage("x", CONV + "/" + responseId, 1));
            assertEquals(CONV + "/" + responseId, PaginatedResponseStore.key(CONV, responseId));
        }
    }

    @Nested
    @DisplayName("GetPage Tests")
    class GetPageTests {

        @Test
        @DisplayName("Should return null for unknown responseId")
        void testGetPageUnknownId() {
            when(cache.get(CONV + "/unknown")).thenReturn(null);
            assertNull(store.getPage(CONV, "unknown", 1));
        }

        @Test
        @DisplayName("Should return error for out-of-range page number")
        void testGetPageOutOfRange() {
            // Create a real store and retrieve
            var realStore = createStoreWithRealCache();
            String response = "Hello World!";
            String responseId = realStore.store(CONV, "testTool", response, 5);

            PaginatedResponseStore.PageResult result = realStore.getPage(CONV, responseId, 999);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("Should return correct page content")
        void testGetPageCorrectContent() {
            var realStore = createStoreWithRealCache();
            String response = "ABCDEFGHIJ"; // 10 chars
            String responseId = realStore.store(CONV, "testTool", response, 4);

            // Page 1: "ABCD"
            PaginatedResponseStore.PageResult page1 = realStore.getPage(CONV, responseId, 1);
            assertNotNull(page1);
            assertTrue(page1.isSuccess());
            assertEquals("ABCD", page1.content());
            assertEquals(3, page1.totalPages());

            // Page 2: "EFGH"
            PaginatedResponseStore.PageResult page2 = realStore.getPage(CONV, responseId, 2);
            assertTrue(page2.isSuccess());
            assertEquals("EFGH", page2.content());

            // Page 3: "IJ"
            PaginatedResponseStore.PageResult page3 = realStore.getPage(CONV, responseId, 3);
            assertTrue(page3.isSuccess());
            assertEquals("IJ", page3.content());
        }

        @Test
        @DisplayName("Should report correct total characters")
        void testGetPageTotalChars() {
            var realStore = createStoreWithRealCache();
            String response = "A".repeat(250);
            String responseId = realStore.store(CONV, "testTool", response, 100);

            PaginatedResponseStore.PageResult page = realStore.getPage(CONV, responseId, 1);
            assertTrue(page.isSuccess());
            assertEquals(250, page.totalCharacters());
            assertEquals("testTool", page.toolName());
        }
    }

    @Nested
    @DisplayName("GetPageCount Tests")
    class GetPageCountTests {

        @Test
        @DisplayName("Should return 0 for unknown responseId")
        void testPageCountUnknown() {
            when(cache.get(CONV + "/unknown")).thenReturn(null);
            assertEquals(0, store.getPageCount(CONV, "unknown"));
        }

        @Test
        @DisplayName("Should return correct count for stored response")
        void testPageCountCorrect() {
            var realStore = createStoreWithRealCache();
            String responseId = realStore.store(CONV, "testTool", "A".repeat(250), 100);
            assertEquals(3, realStore.getPageCount(CONV, responseId));
        }
    }

    /**
     * The 15-minute TTL this store has always documented was, until D5b, thrown
     * away by {@code CacheImpl}: pages lived until the cache filled up. These tests
     * run against a real ticker-driven cache and wind the clock past the boundary.
     */
    @Nested
    @DisplayName("Page expiry")
    class ExpiryTests {

        @Test
        @DisplayName("Pages are gone once the 15-minute TTL has elapsed")
        void pagesExpireAfterTtl() {
            var ticker = new FakeTicker();
            var realStore = createStoreWithRealCache(ticker);
            String responseId = realStore.store(CONV, "testTool", "A".repeat(250), 100);

            ticker.advanceSeconds(901);

            assertNull(realStore.getPage(CONV, responseId, 1), "a 15-minute-old responseId must no longer resolve");
            assertEquals(0, realStore.getPageCount(CONV, responseId));
        }

        @Test
        @DisplayName("Pages survive a whole tool-calling loop — still resolvable just before the TTL")
        void pagesSurviveUntilTtl() {
            var ticker = new FakeTicker();
            var realStore = createStoreWithRealCache(ticker);
            String responseId = realStore.store(CONV, "testTool", "A".repeat(250), 100);

            ticker.advanceSeconds(899);

            PaginatedResponseStore.PageResult page = realStore.getPage(CONV, responseId, 2);
            assertNotNull(page);
            assertTrue(page.isSuccess());
        }

        @Test
        @DisplayName("Each stored response expires on its own clock, not the first one's")
        void expiryIsPerResponse() {
            var ticker = new FakeTicker();
            var realStore = createStoreWithRealCache(ticker);

            String first = realStore.store(CONV, "testTool", "A".repeat(250), 100);
            ticker.advanceSeconds(600);
            String second = realStore.store(CONV, "testTool", "B".repeat(250), 100);

            ticker.advanceSeconds(301); // 901s for the first, 301s for the second

            assertNull(realStore.getPage(CONV, first, 1));
            assertNotNull(realStore.getPage(CONV, second, 1));
        }
    }

    /**
     * Creates a store backed by the same cache shape production uses — a real
     * {@code CacheImpl} over Caffeine — but ticked by the caller so expiry can be
     * asserted deterministically.
     */
    private PaginatedResponseStore createStoreWithRealCache() {
        return createStoreWithRealCache(new FakeTicker());
    }

    private PaginatedResponseStore createStoreWithRealCache(FakeTicker ticker) {
        ICache<String, Object> realCache = TestCaches.expiring("paginated-tool-responses", ticker);

        ICacheFactory factory = mock(ICacheFactory.class);
        doReturn(realCache).when(factory).getCache("paginated-tool-responses");

        PaginatedResponseStore s = new PaginatedResponseStore();
        try {
            var f = PaginatedResponseStore.class.getDeclaredField("cacheFactory");
            f.setAccessible(true);
            f.set(s, factory);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        s.init();
        return s;
    }
}
