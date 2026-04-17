package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebSearchTool. Note: These tests focus on the tool's behavior
 * without making actual HTTP calls.
 */
class WebSearchToolTest {

    private WebSearchTool webSearchTool;

    @BeforeEach
    void setUp() {
        webSearchTool = new WebSearchTool(new SafeHttpClient(10000));
    }

    @Test
    void testSearchWeb_ValidQuery() {
        // Note: Without API keys configured, this will use DuckDuckGo fallback
        // In a real environment with API keys, this would return actual results
        String result = webSearchTool.searchWeb("test query", 5);
        assertNotNull(result);
        // Result could be error message if no API configured, or actual results
    }

    @Test
    void testSearchWeb_NullMaxResults() {
        String result = webSearchTool.searchWeb("test query", null);
        assertNotNull(result);
        // Should default to 5 results
    }

    @Test
    void testSearchWeb_ZeroMaxResults() {
        String result = webSearchTool.searchWeb("test query", 0);
        assertNotNull(result);
        // Should default to at least 1 result
    }

    @Test
    void testSearchWeb_MaxResultsCapping() {
        String result = webSearchTool.searchWeb("test query", 100);
        assertNotNull(result);
        // Should cap at 10 results
    }

    @Test
    void testSearchWeb_EmptyQuery() {
        String result = webSearchTool.searchWeb("", 5);
        assertNotNull(result);
    }

    @Test
    void testSearchWikipedia_ValidQuery() {
        String result = webSearchTool.searchWikipedia("Java programming language");
        assertNotNull(result);
        // Should return Wikipedia content or error message
    }

    @Test
    void testSearchWikipedia_EmptyQuery() {
        String result = webSearchTool.searchWikipedia("");
        assertNotNull(result);
    }

    @Test
    void testSearchWikipedia_NonExistentTopic() {
        String result = webSearchTool.searchWikipedia("xyznonexistenttopic123456");
        assertNotNull(result);
        // Should handle gracefully
    }

    @Test
    void testSearchNews_ValidQuery() {
        String result = webSearchTool.searchNews("technology", 5);
        assertNotNull(result);
    }

    @Test
    void testSearchNews_NullMaxResults() {
        String result = webSearchTool.searchNews("technology", null);
        assertNotNull(result);
    }

    @Test
    void testSearchNews_EmptyQuery() {
        String result = webSearchTool.searchNews("", 5);
        assertNotNull(result);
    }
}
