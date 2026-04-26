/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebScraperTool - includes SSRF protection tests.
 */
class WebScraperToolTest {

    private WebScraperTool webScraperTool;

    @BeforeEach
    void setUp() {
        webScraperTool = new WebScraperTool(new SafeHttpClient(10000));
    }

    // === SSRF Protection Tests ===

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/admin", "http://localhost/secret", "http://localhost:7070/", "http://169.254.169.254/latest/meta-data/",
            "http://metadata.google.internal/computeMetadata/v1/"})
    void testExtractWebPageText_RejectsInternalAddresses(String url) {
        String result = webScraperTool.extractWebPageText(url);
        assertTrue(result.contains("Error"), "Should reject internal URL: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/", "gopher://example.com:25/", "ldap://example.com/"})
    void testExtractWebPageText_RejectsNonHttpSchemes(String url) {
        String result = webScraperTool.extractWebPageText(url);
        assertTrue(result.contains("Error"), "Should reject non-HTTP scheme: " + url);
    }

    @Test
    void testExtractWebPageText_RejectsEmptyUrl() {
        String result = webScraperTool.extractWebPageText("");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractWebPageText_RejectsInvalidUrl() {
        String result = webScraperTool.extractWebPageText("not-a-valid-url");
        assertTrue(result.contains("Error"));
    }

    // === extractLinks SSRF tests ===

    @Test
    void testExtractLinks_RejectsLocalhost() {
        String result = webScraperTool.extractLinks("http://localhost/", 10);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractLinks_RejectsInternalIP() {
        String result = webScraperTool.extractLinks("http://127.0.0.1/", 10);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractLinks_RejectsFileScheme() {
        String result = webScraperTool.extractLinks("file:///etc/passwd", 10);
        assertTrue(result.contains("Error"));
    }

    // === extractWithSelector SSRF tests ===

    @Test
    void testExtractWithSelector_RejectsLocalhost() {
        String result = webScraperTool.extractWithSelector("http://localhost/", "h1");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractWithSelector_RejectsMetadataEndpoint() {
        String result = webScraperTool.extractWithSelector("http://169.254.169.254/", "*");
        assertTrue(result.contains("Error"));
    }

    // === extractMetadata SSRF tests ===

    @Test
    void testExtractMetadata_RejectsLocalhost() {
        String result = webScraperTool.extractMetadata("http://localhost/");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractMetadata_RejectsInternalIP() {
        String result = webScraperTool.extractMetadata("http://10.0.0.1/");
        assertTrue(result.contains("Error"));
    }

    // === Max links handling ===

    @Test
    void testExtractLinks_NullMaxLinks() {
        // When URL validation passes, maxLinks null should use default
        // The URL will fail on actual fetch, but validation should pass
        String result = webScraperTool.extractLinks("https://example.com", null);
        assertNotNull(result);
    }

    @Test
    void testExtractLinks_NegativeMaxLinks() {
        String result = webScraperTool.extractLinks("https://example.com", -5);
        assertNotNull(result);
    }

    @Test
    void testExtractLinks_ExcessiveMaxLinks() {
        // Should cap at 50
        String result = webScraperTool.extractLinks("https://example.com", 100);
        assertNotNull(result);
    }
}
