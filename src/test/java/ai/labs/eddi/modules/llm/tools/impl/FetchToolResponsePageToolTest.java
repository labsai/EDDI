/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("FetchToolResponsePageTool Tests")
class FetchToolResponsePageToolTest {

    private static final String CONV = "68f1c0ffee0000000000c0de";
    private FetchToolResponsePageTool unbound;
    private FetchToolResponsePageTool tool;

    @Mock
    private PaginatedResponseStore paginatedResponseStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        unbound = new FetchToolResponsePageTool();
        // Inject mock store
        try {
            var f = FetchToolResponsePageTool.class.getDeclaredField("paginatedResponseStore");
            f.setAccessible(true);
            f.set(unbound, paginatedResponseStore);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        tool = unbound.boundTo(CONV);
    }

    @Test
    @DisplayName("L13: the unbound catalog bean fetches nothing, and binding yields a copy that reads its own conversation only")
    void unboundBeanRefuses() {
        String result = unbound.fetchPage("valid-id", 1);
        assertTrue(result.contains("error"), result);
        assertTrue(result.contains("within the conversation"), result);
        verifyNoInteractions(paginatedResponseStore);

        assertEquals(CONV, tool.conversationId());
        assertNull(unbound.conversationId());
        assertSame(unbound, unbound.boundTo(null), "nothing to bind to");
        assertSame(unbound, unbound.boundTo(" "));
    }

    @Test
    @DisplayName("Should return error for null responseId")
    void testNullResponseId() {
        String result = tool.fetchPage(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("responseId is required"));
    }

    @Test
    @DisplayName("Should return error for blank responseId")
    void testBlankResponseId() {
        String result = tool.fetchPage("   ", 1);
        assertTrue(result.contains("error"));
    }

    @Test
    @DisplayName("Should return error for page number < 1")
    void testInvalidPageNumber() {
        String result = tool.fetchPage("valid-id", 0);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("pageNumber must be >= 1"));
    }

    @Test
    @DisplayName("Should return error when response not found (expired)")
    void testExpiredResponse() {
        when(paginatedResponseStore.getPage(CONV, "expired-id", 1)).thenReturn(null);

        String result = tool.fetchPage("expired-id", 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("expired"));
    }

    @Test
    @DisplayName("Should return error when page out of range")
    void testOutOfRangePage() {
        var pageResult = new PaginatedResponseStore.PageResult(null, 3, 300, "testTool", "Page 5 out of range (1-3)");
        when(paginatedResponseStore.getPage(CONV, "valid-id", 5)).thenReturn(pageResult);

        String result = tool.fetchPage("valid-id", 5);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("out of range"));
        assertTrue(result.contains("totalPages"));
    }

    @Test
    @DisplayName("Should return page content on success")
    void testSuccessfulPageFetch() {
        var pageResult = new PaginatedResponseStore.PageResult("Page 2 content here", 3, 300, "testTool", null);
        when(paginatedResponseStore.getPage(CONV, "valid-id", 2)).thenReturn(pageResult);

        String result = tool.fetchPage("valid-id", 2);
        assertTrue(result.contains("\"page\":2") || result.contains("\"page\": 2"));
        assertTrue(result.contains("\"totalPages\":3") || result.contains("\"totalPages\": 3"));
        assertTrue(result.contains("\"toolName\":\"testTool\"") || result.contains("\"toolName\": \"testTool\""));
        assertTrue(result.contains("Page 2 content here"));
    }

    @Test
    @DisplayName("Should escape special characters in content")
    void testSpecialCharacterEscaping() {
        var pageResult = new PaginatedResponseStore.PageResult("Line 1\nLine 2\t\"quoted\"", 1, 50, "tool", null);
        when(paginatedResponseStore.getPage(CONV, "id", 1)).thenReturn(pageResult);

        String result = tool.fetchPage("id", 1);
        // Should contain escaped versions
        assertTrue(result.contains("\\n"));
        assertTrue(result.contains("\\\"quoted\\\""));
    }
}
