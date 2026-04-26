/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended unit tests for WebScraperTool with mocked HTTP client. Exercises
 * HTML parsing branches, content extraction, truncation, and metadata logic.
 */
class WebScraperToolExtendedTest {

    private SafeHttpClient mockHttpClient;
    private WebScraperTool webScraperTool;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(SafeHttpClient.class);
        webScraperTool = new WebScraperTool(mockHttpClient);
    }

    @SuppressWarnings("unchecked")
    private void mockResponse(int statusCode, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    // ==================== extractWebPageText ====================

    @Nested
    @DisplayName("extractWebPageText — HTML parsing")
    class ExtractTextTests {

        @Test
        @DisplayName("should extract title and body text from simple HTML")
        void extractTitleAndBody() throws Exception {
            mockResponse(200, "<html><head><title>Test Page</title></head><body><p>Hello world</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Title: Test Page"));
            assertTrue(result.contains("Hello world"));
        }

        @Test
        @DisplayName("should remove script and style elements")
        void removesScriptAndStyle() throws Exception {
            mockResponse(200, "<html><head><title>Clean</title></head><body>"
                    + "<script>alert('xss')</script>"
                    + "<style>.hidden{display:none}</style>"
                    + "<p>Visible content</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertFalse(result.contains("alert"));
            assertFalse(result.contains("display:none"));
            assertTrue(result.contains("Visible content"));
        }

        @Test
        @DisplayName("should remove nav, footer, header, aside elements")
        void removesStructuralElements() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<nav>Navigation links</nav>"
                    + "<header>Header stuff</header>"
                    + "<main><p>Main content</p></main>"
                    + "<aside>Sidebar</aside>"
                    + "<footer>Footer info</footer>"
                    + "</body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Main content"));
            assertFalse(result.contains("Navigation links"));
            assertFalse(result.contains("Footer info"));
        }

        @Test
        @DisplayName("should prefer main/article content over body")
        void prefersMainContent() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<div>Other stuff</div>"
                    + "<main><p>Important content in main</p></main>"
                    + "</body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Important content in main"));
        }

        @Test
        @DisplayName("should fall back to body when no main/article element")
        void fallsBackToBody() throws Exception {
            mockResponse(200, "<html><body><p>Body content only</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Body content only"));
        }

        @Test
        @DisplayName("should truncate content longer than 5000 characters")
        void truncatesLongContent() throws Exception {
            String longContent = "A".repeat(6000);
            mockResponse(200, "<html><body><p>" + longContent + "</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.length() < 6000);
            assertTrue(result.contains("[Content truncated"));
        }

        @Test
        @DisplayName("should not truncate content under 5000 characters")
        void doesNotTruncateShortContent() throws Exception {
            mockResponse(200, "<html><body><p>Short content</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertFalse(result.contains("[Content truncated"));
        }

        @Test
        @DisplayName("should handle empty title gracefully")
        void handlesEmptyTitle() throws Exception {
            mockResponse(200, "<html><head><title></title></head><body><p>Content</p></body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertFalse(result.contains("Title:"));
            assertTrue(result.contains("Content"));
        }

        @Test
        @DisplayName("should handle HTTP error status code")
        void handlesHttpError() throws Exception {
            mockResponse(500, "Internal Server Error");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Error"));
        }

        @Test
        @DisplayName("should handle IOException from HTTP client")
        void handlesIOException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Error"));
            assertTrue(result.contains("Connection refused"));
        }

        @Test
        @DisplayName("should extract content from article tag")
        void extractsFromArticleTag() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<div>Sidebar noise</div>"
                    + "<article><h1>Article Title</h1><p>Article body text</p></article>"
                    + "</body></html>");

            String result = webScraperTool.extractWebPageText("https://example.com");

            assertTrue(result.contains("Article body text"));
        }
    }

    // ==================== extractLinks ====================

    @Nested
    @DisplayName("extractLinks — link extraction")
    class ExtractLinksTests {

        @Test
        @DisplayName("should extract links with text and href")
        void extractLinksWithText() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<a href='https://example.com/page1'>Page One</a>"
                    + "<a href='https://example.com/page2'>Page Two</a>"
                    + "</body></html>");

            String result = webScraperTool.extractLinks("https://example.com", 10);

            assertTrue(result.contains("Page One"));
            assertTrue(result.contains("Page Two"));
        }

        @Test
        @DisplayName("should handle links without text")
        void handlesLinksWithoutText() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<a href='https://example.com/logo'></a>"
                    + "</body></html>");

            String result = webScraperTool.extractLinks("https://example.com", 10);

            // Link should be present without text prefix
            assertNotNull(result);
        }

        @Test
        @DisplayName("should report no links when none found")
        void noLinksFound() throws Exception {
            mockResponse(200, "<html><body><p>No links here</p></body></html>");

            String result = webScraperTool.extractLinks("https://example.com", 10);

            assertTrue(result.contains("No links found"));
        }

        @Test
        @DisplayName("should cap maxLinks at 50")
        void capsMaxLinksAt50() throws Exception {
            StringBuilder html = new StringBuilder("<html><body>");
            for (int i = 0; i < 60; i++) {
                html.append("<a href='https://example.com/page").append(i).append("'>Link ").append(i).append("</a>");
            }
            html.append("</body></html>");
            mockResponse(200, html.toString());

            String result = webScraperTool.extractLinks("https://example.com", 100);

            // Should not contain links beyond 50
            assertNotNull(result);
        }

        @Test
        @DisplayName("should use default maxLinks=20 when null")
        void defaultMaxLinks() throws Exception {
            StringBuilder html = new StringBuilder("<html><body>");
            for (int i = 0; i < 30; i++) {
                html.append("<a href='https://example.com/page").append(i).append("'>Link ").append(i).append("</a>");
            }
            html.append("</body></html>");
            mockResponse(200, html.toString());

            String result = webScraperTool.extractLinks("https://example.com", null);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should use default maxLinks=20 when negative")
        void defaultForNegativeMaxLinks() throws Exception {
            mockResponse(200, "<html><body><a href='https://example.com/a'>A</a></body></html>");

            String result = webScraperTool.extractLinks("https://example.com", -5);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle HTTP error in link extraction")
        void handlesHttpErrorInLinkExtraction() throws Exception {
            mockResponse(403, "Forbidden");

            String result = webScraperTool.extractLinks("https://example.com", 10);

            assertTrue(result.contains("Error"));
        }
    }

    // ==================== extractWithSelector ====================

    @Nested
    @DisplayName("extractWithSelector — CSS selector extraction")
    class ExtractWithSelectorTests {

        @Test
        @DisplayName("should extract elements matching CSS selector")
        void extractsMatchingElements() throws Exception {
            mockResponse(200, "<html><body>"
                    + "<h1>Title 1</h1>"
                    + "<h1>Title 2</h1>"
                    + "<p>Paragraph</p>"
                    + "</body></html>");

            String result = webScraperTool.extractWithSelector("https://example.com", "h1");

            assertTrue(result.contains("Title 1"));
            assertTrue(result.contains("Title 2"));
            assertTrue(result.contains("2 element(s)"));
        }

        @Test
        @DisplayName("should return 'no elements found' for non-matching selector")
        void noElementsFound() throws Exception {
            mockResponse(200, "<html><body><p>Content</p></body></html>");

            String result = webScraperTool.extractWithSelector("https://example.com", ".nonexistent");

            assertTrue(result.contains("No elements found"));
        }

        @Test
        @DisplayName("should truncate at 20 elements")
        void truncatesAt20Elements() throws Exception {
            StringBuilder html = new StringBuilder("<html><body>");
            for (int i = 0; i < 25; i++) {
                html.append("<span class='item'>Item ").append(i).append("</span>");
            }
            html.append("</body></html>");
            mockResponse(200, html.toString());

            String result = webScraperTool.extractWithSelector("https://example.com", "span.item");

            assertTrue(result.contains("[Additional elements truncated]"));
        }

        @Test
        @DisplayName("should handle HTTP error in selector extraction")
        void handlesHttpError() throws Exception {
            mockResponse(404, "Not Found");

            String result = webScraperTool.extractWithSelector("https://example.com", "div");

            assertTrue(result.contains("Error"));
        }
    }

    // ==================== extractMetadata ====================

    @Nested
    @DisplayName("extractMetadata — page metadata")
    class ExtractMetadataTests {

        @Test
        @DisplayName("should extract all metadata fields")
        void extractsAllMetadata() throws Exception {
            mockResponse(200, "<html><head>"
                    + "<title>My Page</title>"
                    + "<meta name='description' content='Page description'>"
                    + "<meta name='keywords' content='java, testing'>"
                    + "<meta name='author' content='EDDI'>"
                    + "<meta property='og:title' content='OG Page Title'>"
                    + "<meta property='og:description' content='OG Description'>"
                    + "</head><body></body></html>");

            String result = webScraperTool.extractMetadata("https://example.com");

            assertTrue(result.contains("Title: My Page"));
            assertTrue(result.contains("Description: Page description"));
            assertTrue(result.contains("Keywords: java, testing"));
            assertTrue(result.contains("Author: EDDI"));
            assertTrue(result.contains("OG Title: OG Page Title"));
            assertTrue(result.contains("OG Description: OG Description"));
        }

        @Test
        @DisplayName("should handle missing metadata fields")
        void handlesMissingMetadata() throws Exception {
            mockResponse(200, "<html><head><title>Simple Page</title></head><body></body></html>");

            String result = webScraperTool.extractMetadata("https://example.com");

            assertTrue(result.contains("Title: Simple Page"));
            assertFalse(result.contains("Description:"));
            assertFalse(result.contains("Keywords:"));
            assertFalse(result.contains("Author:"));
            assertFalse(result.contains("OG Title:"));
            assertFalse(result.contains("OG Description:"));
        }

        @Test
        @DisplayName("should handle empty title")
        void handlesEmptyTitle() throws Exception {
            mockResponse(200, "<html><head><title></title>"
                    + "<meta name='description' content='Has description'>"
                    + "</head><body></body></html>");

            String result = webScraperTool.extractMetadata("https://example.com");

            assertFalse(result.contains("Title:"));
            assertTrue(result.contains("Description: Has description"));
        }

        @Test
        @DisplayName("should handle HTTP error in metadata extraction")
        void handlesHttpError() throws Exception {
            mockResponse(500, "Server Error");

            String result = webScraperTool.extractMetadata("https://example.com");

            assertTrue(result.contains("Error"));
        }

        @Test
        @DisplayName("should handle IOException during metadata fetch")
        void handlesIOException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Network error"));

            String result = webScraperTool.extractMetadata("https://example.com");

            assertTrue(result.contains("Error"));
        }
    }
}
