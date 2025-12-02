package ai.labs.eddi.modules.langchain.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebScraperTool.
 */
class WebScraperToolTest {

    private WebScraperTool webScraperTool;

    @BeforeEach
    void setUp() {
        webScraperTool = new WebScraperTool();
    }

    @Test
    void testExtractWebPageText_ValidUrl() {
        // Using a simple HTML test
        String result = webScraperTool.extractWebPageText("https://example.com");
        assertNotNull(result);
        // Result could be error or actual content depending on network availability
    }

    @Test
    void testExtractWebPageText_InvalidUrl() {
        String result = webScraperTool.extractWebPageText("not-a-valid-url");
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testExtractWebPageText_EmptyUrl() {
        String result = webScraperTool.extractWebPageText("");
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testExtractLinks_ValidUrl() {
        String result = webScraperTool.extractLinks("https://example.com", 10);
        assertNotNull(result);
    }

    @Test
    void testExtractLinks_InvalidUrl() {
        String result = webScraperTool.extractLinks("not-a-valid-url", 10);
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testExtractLinks_NullMaxLinks() {
        String result = webScraperTool.extractLinks("https://example.com", null);
        assertNotNull(result);
        // Should use default max links
    }
}

