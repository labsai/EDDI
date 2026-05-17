/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
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
            String responseId = store.store("testTool", response, 100);

            assertNotNull(responseId);
            verify(cache).put(eq(responseId), any(), eq(900L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should return null for null response")
        void testStoreNullResponse() {
            assertNull(store.store("testTool", null, 100));
        }

        @Test
        @DisplayName("Should return null for empty response")
        void testStoreEmptyResponse() {
            assertNull(store.store("testTool", "", 100));
        }

        @Test
        @DisplayName("Should return null for zero page size")
        void testStoreZeroPageSize() {
            assertNull(store.store("testTool", "content", 0));
        }
    }

    @Nested
    @DisplayName("GetPage Tests")
    class GetPageTests {

        @Test
        @DisplayName("Should return null for unknown responseId")
        void testGetPageUnknownId() {
            when(cache.get("unknown")).thenReturn(null);
            assertNull(store.getPage("unknown", 1));
        }

        @Test
        @DisplayName("Should return error for out-of-range page number")
        void testGetPageOutOfRange() {
            // Create a real store and retrieve
            var realStore = createStoreWithRealCache();
            String response = "Hello World!";
            String responseId = realStore.store("testTool", response, 5);

            PaginatedResponseStore.PageResult result = realStore.getPage(responseId, 999);
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("Should return correct page content")
        void testGetPageCorrectContent() {
            var realStore = createStoreWithRealCache();
            String response = "ABCDEFGHIJ"; // 10 chars
            String responseId = realStore.store("testTool", response, 4);

            // Page 1: "ABCD"
            PaginatedResponseStore.PageResult page1 = realStore.getPage(responseId, 1);
            assertNotNull(page1);
            assertTrue(page1.isSuccess());
            assertEquals("ABCD", page1.content());
            assertEquals(3, page1.totalPages());

            // Page 2: "EFGH"
            PaginatedResponseStore.PageResult page2 = realStore.getPage(responseId, 2);
            assertTrue(page2.isSuccess());
            assertEquals("EFGH", page2.content());

            // Page 3: "IJ"
            PaginatedResponseStore.PageResult page3 = realStore.getPage(responseId, 3);
            assertTrue(page3.isSuccess());
            assertEquals("IJ", page3.content());
        }

        @Test
        @DisplayName("Should report correct total characters")
        void testGetPageTotalChars() {
            var realStore = createStoreWithRealCache();
            String response = "A".repeat(250);
            String responseId = realStore.store("testTool", response, 100);

            PaginatedResponseStore.PageResult page = realStore.getPage(responseId, 1);
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
            when(cache.get("unknown")).thenReturn(null);
            assertEquals(0, store.getPageCount("unknown"));
        }

        @Test
        @DisplayName("Should return correct count for stored response")
        void testPageCountCorrect() {
            var realStore = createStoreWithRealCache();
            String responseId = realStore.store("testTool", "A".repeat(250), 100);
            assertEquals(3, realStore.getPageCount(responseId));
        }
    }

    /**
     * Creates a store backed by a simple in-memory HashMap for integration-style
     * tests.
     */
    @SuppressWarnings("unchecked")
    private PaginatedResponseStore createStoreWithRealCache() {
        var realCache = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        ICache<String, Object> mapCache = mock(ICache.class);
        doAnswer(inv -> {
            realCache.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(mapCache).put(anyString(), any(), anyLong(), any(TimeUnit.class));
        when(mapCache.get(anyString())).thenAnswer(inv -> realCache.get(inv.getArgument(0)));

        ICacheFactory factory = mock(ICacheFactory.class);
        doReturn(mapCache).when(factory).getCache("paginated-tool-responses");

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
