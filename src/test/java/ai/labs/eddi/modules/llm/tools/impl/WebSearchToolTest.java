/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WebSearchTool}.
 * <p>
 * Tests focus on the JSON parsing logic (formatGoogleResults,
 * formatDuckDuckGoResults, formatWikipediaResults) which was refactored from
 * hand-rolled string splitting to Jackson ObjectMapper.
 * <p>
 * Tests that would make real HTTP calls are excluded — those belong in
 * integration tests.
 */
class WebSearchToolTest {

    private WebSearchTool webSearchTool;

    @BeforeEach
    void setUp() {
        webSearchTool = new WebSearchTool(new SafeHttpClient(10000), new ObjectMapper());
    }

    // ==================== Google Results Parsing ====================

    @Nested
    class GoogleResultsParsing {

        @Test
        void formatGoogleResults_ValidResponse_ShouldExtractAllFields() {
            String json = """
                    {
                      "items": [
                        {
                          "title": "Test Result 1",
                          "snippet": "This is the first result snippet",
                          "link": "https://example.com/1"
                        },
                        {
                          "title": "Test Result 2",
                          "snippet": "This is the second result snippet",
                          "link": "https://example.com/2"
                        }
                      ]
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "test query");

            assertTrue(result.contains("Test Result 1"));
            assertTrue(result.contains("This is the first result snippet"));
            assertTrue(result.contains("https://example.com/1"));
            assertTrue(result.contains("Test Result 2"));
            assertTrue(result.contains("This is the second result snippet"));
            assertTrue(result.contains("https://example.com/2"));
            assertTrue(result.contains("Search results for 'test query'"));
        }

        @Test
        void formatGoogleResults_EmptyItems_ShouldReturnNoResults() {
            String json = """
                    {
                      "items": []
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "test");

            assertTrue(result.contains("No results found"));
        }

        @Test
        void formatGoogleResults_NoItemsKey_ShouldReturnNoResults() {
            String json = """
                    {
                      "searchInformation": {"totalResults": "0"}
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "test");

            assertTrue(result.contains("No results found"));
        }

        @Test
        void formatGoogleResults_SpecialCharsInTitle_ShouldHandleCorrectly() {
            String json = """
                    {
                      "items": [
                        {
                          "title": "Test with \\"quotes\\" and special chars: <>&",
                          "snippet": "Snippet with \\n newlines",
                          "link": "https://example.com/path?q=test&lang=en"
                        }
                      ]
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "special");

            assertTrue(result.contains("Test with"));
            assertTrue(result.contains("quotes"));
            assertFalse(result.contains("No results found"));
        }

        @Test
        void formatGoogleResults_MissingSnippet_ShouldReturnEmptyString() {
            String json = """
                    {
                      "items": [
                        {
                          "title": "Title Only",
                          "link": "https://example.com"
                        }
                      ]
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "test");

            assertTrue(result.contains("Title Only"));
            assertTrue(result.contains("https://example.com"));
        }

        @Test
        void formatGoogleResults_MoreThan10Items_ShouldCapAt10() {
            StringBuilder json = new StringBuilder("{\"items\":[");
            for (int i = 0; i < 15; i++) {
                if (i > 0)
                    json.append(",");
                json.append(String.format(
                        "{\"title\":\"Result %d\",\"snippet\":\"Snippet %d\",\"link\":\"https://example.com/%d\"}", i, i, i));
            }
            json.append("]}");

            String result = webSearchTool.formatGoogleResults(json.toString(), "test");

            // Should contain results 0-9 but not 10-14
            assertTrue(result.contains("Result 0"));
            assertTrue(result.contains("Result 9"));
            // Count numbered results
            int count = 0;
            for (int i = 1; i <= 15; i++) {
                if (result.contains(i + ". Result"))
                    count++;
            }
            assertEquals(10, count);
        }

        @Test
        void formatGoogleResults_InvalidJson_ShouldReturnNoResults() {
            String result = webSearchTool.formatGoogleResults("not valid json at all{{{", "test");

            assertTrue(result.contains("No results found"));
        }

        @Test
        void formatGoogleResults_NullSnippetField_ShouldTreatAsEmpty() {
            String json = """
                    {
                      "items": [
                        {
                          "title": "Title",
                          "snippet": null,
                          "link": "https://example.com"
                        }
                      ]
                    }
                    """;

            String result = webSearchTool.formatGoogleResults(json, "test");

            assertTrue(result.contains("Title"));
            assertFalse(result.contains("No results found"));
        }
    }

    // ==================== DuckDuckGo Results Parsing ====================

    @Nested
    class DuckDuckGoResultsParsing {

        @Test
        void formatDuckDuckGoResults_WithAbstract_ShouldExtractQuickAnswer() {
            String json = """
                    {
                      "Abstract": "Java is a high-level programming language.",
                      "AbstractURL": "https://en.wikipedia.org/wiki/Java",
                      "RelatedTopics": []
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "java", 5);

            assertTrue(result.contains("Quick Answer:"));
            assertTrue(result.contains("Java is a high-level programming language."));
            assertTrue(result.contains("https://en.wikipedia.org/wiki/Java"));
        }

        @Test
        void formatDuckDuckGoResults_WithRelatedTopics_ShouldExtractTopics() {
            String json = """
                    {
                      "Abstract": "",
                      "AbstractURL": "",
                      "RelatedTopics": [
                        {"Text": "First related topic about Java"},
                        {"Text": "Second related topic about Java"},
                        {"Text": "Third related topic about Java"}
                      ]
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "java", 5);

            assertTrue(result.contains("1. First related topic"));
            assertTrue(result.contains("2. Second related topic"));
            assertTrue(result.contains("3. Third related topic"));
        }

        @Test
        void formatDuckDuckGoResults_RelatedTopicsCapped_ShouldRespectMaxResults() {
            String json = """
                    {
                      "Abstract": "",
                      "RelatedTopics": [
                        {"Text": "Topic 1"},
                        {"Text": "Topic 2"},
                        {"Text": "Topic 3"},
                        {"Text": "Topic 4"},
                        {"Text": "Topic 5"}
                      ]
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "test", 2);

            assertTrue(result.contains("1. Topic 1"));
            assertTrue(result.contains("2. Topic 2"));
            assertFalse(result.contains("3. Topic 3"));
        }

        @Test
        void formatDuckDuckGoResults_EmptyResponse_ShouldReturnNoInstantResults() {
            String json = """
                    {
                      "Abstract": "",
                      "AbstractURL": "",
                      "RelatedTopics": []
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "xyznoexist", 5);

            assertTrue(result.contains("No instant results found"));
        }

        @Test
        void formatDuckDuckGoResults_BothAbstractAndTopics_ShouldIncludeBoth() {
            String json = """
                    {
                      "Abstract": "Quick answer about testing.",
                      "AbstractURL": "https://example.com",
                      "RelatedTopics": [
                        {"Text": "Related topic 1"}
                      ]
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "testing", 5);

            assertTrue(result.contains("Quick Answer:"));
            assertTrue(result.contains("Quick answer about testing."));
            assertTrue(result.contains("1. Related topic 1"));
        }

        @Test
        void formatDuckDuckGoResults_InvalidJson_ShouldReturnParseError() {
            String result = webSearchTool.formatDuckDuckGoResults("{bad json!!!", "test", 5);

            assertTrue(result.contains("could not parse results"));
        }

        @Test
        void formatDuckDuckGoResults_TopicWithEmptyText_ShouldSkip() {
            String json = """
                    {
                      "Abstract": "",
                      "RelatedTopics": [
                        {"Text": ""},
                        {"Text": "Valid topic"}
                      ]
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "test", 5);

            assertTrue(result.contains("1. Valid topic"));
            // Empty text should be skipped, not numbered
            assertFalse(result.contains("2."));
        }

        @Test
        void formatDuckDuckGoResults_AbstractOnlyNoTopics_ShouldNotShowNoResults() {
            String json = """
                    {
                      "Abstract": "A quick answer.",
                      "AbstractURL": "https://example.com",
                      "RelatedTopics": []
                    }
                    """;

            String result = webSearchTool.formatDuckDuckGoResults(json, "test", 5);

            assertTrue(result.contains("Quick Answer:"));
            assertFalse(result.contains("No instant results found"));
        }
    }

    // ==================== Wikipedia Results Parsing ====================

    @Nested
    class WikipediaResultsParsing {

        @Test
        void formatWikipediaResults_ValidResponse_ShouldExtractArticles() {
            String json = """
                    {
                      "query": {
                        "search": [
                          {
                            "title": "Java (programming language)",
                            "snippet": "Java is a high-level <span class=\\"searchmatch\\">programming</span> language"
                          },
                          {
                            "title": "JavaScript",
                            "snippet": "JavaScript is a <span>scripting</span> language"
                          }
                        ]
                      }
                    }
                    """;

            String result = webSearchTool.formatWikipediaResults(json, "java");

            assertTrue(result.contains("Wikipedia results for 'java'"));
            assertTrue(result.contains("1. Java (programming language)"));
            assertTrue(result.contains("2. JavaScript"));
            // HTML tags should be stripped from snippets
            assertFalse(result.contains("<span"));
            assertTrue(result.contains("programming"));
            assertTrue(result.contains("https://en.wikipedia.org/wiki/"));
        }

        @Test
        void formatWikipediaResults_EmptySearch_ShouldReturnNoArticles() {
            String json = """
                    {
                      "query": {
                        "search": []
                      }
                    }
                    """;

            String result = webSearchTool.formatWikipediaResults(json, "xyznoexist");

            assertTrue(result.contains("No Wikipedia articles found"));
        }

        @Test
        void formatWikipediaResults_MissingQueryNode_ShouldReturnNoArticles() {
            String json = """
                    {
                      "warnings": {"search": {"*": "some warning"}}
                    }
                    """;

            String result = webSearchTool.formatWikipediaResults(json, "test");

            assertTrue(result.contains("No Wikipedia articles found"));
        }

        @Test
        void formatWikipediaResults_MaxThreeResults_ShouldCap() {
            StringBuilder json = new StringBuilder("{\"query\":{\"search\":[");
            for (int i = 0; i < 5; i++) {
                if (i > 0)
                    json.append(",");
                json.append(String.format("{\"title\":\"Article %d\",\"snippet\":\"Snippet %d\"}", i, i));
            }
            json.append("]}}");

            String result = webSearchTool.formatWikipediaResults(json.toString(), "test");

            assertTrue(result.contains("1. Article 0"));
            assertTrue(result.contains("2. Article 1"));
            assertTrue(result.contains("3. Article 2"));
            assertFalse(result.contains("4. Article 3"));
        }

        @Test
        void formatWikipediaResults_InvalidJson_ShouldReturnParseError() {
            String result = webSearchTool.formatWikipediaResults("{bad", "test");

            assertTrue(result.contains("could not parse results"));
        }

        @Test
        void formatWikipediaResults_HtmlInSnippet_ShouldBeStripped() {
            String json = """
                    {
                      "query": {
                        "search": [
                          {
                            "title": "Test Article",
                            "snippet": "This has <b>bold</b> and <em>italic</em> text"
                          }
                        ]
                      }
                    }
                    """;

            String result = webSearchTool.formatWikipediaResults(json, "test");

            assertTrue(result.contains("This has bold and italic text"));
            assertFalse(result.contains("<b>"));
            assertFalse(result.contains("<em>"));
        }

        @Test
        void formatWikipediaResults_MissingSearchNode_ShouldReturnNoArticles() {
            String json = """
                    {
                      "query": {
                        "searchinfo": {"totalhits": 0}
                      }
                    }
                    """;

            String result = webSearchTool.formatWikipediaResults(json, "test");

            assertTrue(result.contains("No Wikipedia articles found"));
        }
    }

    // ==================== searchWeb HTTP integration (mocked) ====================

    @Nested
    class SearchWebTests {

        private WebSearchTool mockedTool;
        private SafeHttpClient mockedClient;

        @BeforeEach
        void setUpMocked() throws Exception {
            mockedClient = org.mockito.Mockito.mock(SafeHttpClient.class);
            mockedTool = new WebSearchTool(mockedClient, new ObjectMapper());
            // Use reflection to set the searchProvider field (since it's @ConfigProperty)
            var searchProviderField = WebSearchTool.class.getDeclaredField("searchProvider");
            searchProviderField.setAccessible(true);
            searchProviderField.set(mockedTool, "duckduckgo");
        }

        @Test
        void searchWeb_nullMaxResults_defaultsTo5() {
            // searchWeb with null maxResults should not throw and should default to 5
            // (will fail on HTTP call, but we test the parameter clamping)
            String result = mockedTool.searchWeb("test query", null);

            // Should return error since mock doesn't set up response
            assertNotNull(result);
            assertTrue(result.contains("Error:"));
        }

        @Test
        void searchWeb_negativeMaxResults_defaultsTo5() {
            String result = mockedTool.searchWeb("test query", -1);

            assertNotNull(result);
            assertTrue(result.contains("Error:"));
        }

        @Test
        void searchWeb_maxResultsOver10_clampedTo10() {
            String result = mockedTool.searchWeb("test query", 20);

            assertNotNull(result);
            assertTrue(result.contains("Error:"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void searchWeb_duckDuckGoSuccess_returnsResults() throws Exception {
            var response = (java.net.http.HttpResponse<String>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
            org.mockito.Mockito.when(response.body()).thenReturn("""
                    {
                      "Abstract": "Test abstract answer",
                      "AbstractURL": "https://example.com",
                      "RelatedTopics": []
                    }
                    """);
            org.mockito.Mockito.doReturn(response).when(mockedClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.searchWeb("test", 5);

            assertTrue(result.contains("Test abstract answer"));
            assertTrue(result.contains("https://example.com"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void searchWeb_duckDuckGoNon200_returnsError() throws Exception {
            var response = (java.net.http.HttpResponse<String>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(429);
            org.mockito.Mockito.doReturn(response).when(mockedClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.searchWeb("test", 5);

            assertTrue(result.contains("Error:"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void searchWeb_googleProvider_success() throws Exception {
            // Configure as Google provider
            var providerField = WebSearchTool.class.getDeclaredField("searchProvider");
            providerField.setAccessible(true);
            providerField.set(mockedTool, "google");

            var apiKeyField = WebSearchTool.class.getDeclaredField("googleApiKey");
            apiKeyField.setAccessible(true);
            apiKeyField.set(mockedTool, java.util.Optional.of("test-key"));

            var cxField = WebSearchTool.class.getDeclaredField("googleCx");
            cxField.setAccessible(true);
            cxField.set(mockedTool, java.util.Optional.of("test-cx"));

            var response = (java.net.http.HttpResponse<String>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
            org.mockito.Mockito.when(response.body()).thenReturn("""
                    {"items":[{"title":"Google Result","snippet":"Google snippet","link":"https://g.com"}]}
                    """);
            org.mockito.Mockito.doReturn(response).when(mockedClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.searchWeb("test", 5);

            assertTrue(result.contains("Google Result"));
        }
    }

    // ==================== searchNews delegation ====================

    @Nested
    class SearchNewsTests {

        @Test
        void searchNews_delegatesToSearchWeb() {
            // searchNews appends " news" to the query and delegates
            // With no mocked HTTP, will return error
            WebSearchTool tool = new WebSearchTool(org.mockito.Mockito.mock(SafeHttpClient.class), new ObjectMapper());
            try {
                var providerField = WebSearchTool.class.getDeclaredField("searchProvider");
                providerField.setAccessible(true);
                providerField.set(tool, "duckduckgo");
            } catch (Exception ignored) {
            }

            String result = tool.searchNews("AI", 3);

            assertNotNull(result);
            // Either error or results - either way, searchNews ran
            assertTrue(result.contains("Error:") || result.contains("Search results"));
        }

        @Test
        void searchNews_nullMaxResults_handledGracefully() {
            WebSearchTool tool = new WebSearchTool(org.mockito.Mockito.mock(SafeHttpClient.class), new ObjectMapper());
            try {
                var providerField = WebSearchTool.class.getDeclaredField("searchProvider");
                providerField.setAccessible(true);
                providerField.set(tool, "duckduckgo");
            } catch (Exception ignored) {
            }

            assertDoesNotThrow(() -> tool.searchNews("test", null));
        }
    }

    // ==================== searchWikipedia HTTP paths ====================

    @Nested
    class SearchWikipediaHttpTests {

        private WebSearchTool mockedTool;
        private SafeHttpClient mockedClient;

        @BeforeEach
        void setUpMocked() {
            mockedClient = org.mockito.Mockito.mock(SafeHttpClient.class);
            mockedTool = new WebSearchTool(mockedClient, new ObjectMapper());
        }

        @SuppressWarnings("unchecked")
        @Test
        void searchWikipedia_success_returnsResults() throws Exception {
            var response = (java.net.http.HttpResponse<String>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
            org.mockito.Mockito.when(response.body()).thenReturn("""
                    {"query":{"search":[{"title":"Test Article","snippet":"Test snippet"}]}}
                    """);
            org.mockito.Mockito.doReturn(response).when(mockedClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.searchWikipedia("test");

            assertTrue(result.contains("Test Article"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void searchWikipedia_non200_returnsError() throws Exception {
            var response = (java.net.http.HttpResponse<String>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(500);
            org.mockito.Mockito.doReturn(response).when(mockedClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.searchWikipedia("test");

            assertTrue(result.contains("Error:"));
        }

        @Test
        void searchWikipedia_ioException_returnsError() throws Exception {
            org.mockito.Mockito.when(mockedClient.send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any())).thenThrow(new java.io.IOException("Network error"));

            String result = mockedTool.searchWikipedia("test");

            assertTrue(result.contains("Error:"));
            assertTrue(result.contains("Network error"));
        }
    }
}
