/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link WebSearchTool} — covers JSON formatting methods for
 * Google, DuckDuckGo, and Wikipedia search result parsing.
 */
@DisplayName("WebSearchTool Extended Tests")
class WebSearchToolExtendedTest {

    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        var httpClient = org.mockito.Mockito.mock(SafeHttpClient.class);
        tool = new WebSearchTool(httpClient, new ObjectMapper());
    }

    @Nested
    @DisplayName("formatGoogleResults")
    class GoogleResults {

        @Test
        @DisplayName("should format valid Google results with items")
        void formatsValidResults() {
            String json = """
                    {
                      "items": [
                        {"title": "Test Title", "snippet": "Test snippet text", "link": "https://example.com"},
                        {"title": "Second Result", "snippet": "Another snippet", "link": "https://example.org"}
                      ]
                    }
                    """;

            String result = tool.formatGoogleResults(json, "test query");

            assertTrue(result.contains("Test Title"));
            assertTrue(result.contains("Test snippet text"));
            assertTrue(result.contains("https://example.com"));
            assertTrue(result.contains("Second Result"));
            assertTrue(result.contains("test query"));
        }

        @Test
        @DisplayName("should handle empty items array")
        void handlesEmptyItems() {
            String json = "{\"items\": []}";
            String result = tool.formatGoogleResults(json, "nothing");
            assertTrue(result.contains("No results found"));
        }

        @Test
        @DisplayName("should handle missing items field")
        void handlesMissingItems() {
            String json = "{\"searchInformation\": {\"totalResults\": \"0\"}}";
            String result = tool.formatGoogleResults(json, "nothing");
            assertTrue(result.contains("No results found"));
        }

        @Test
        @DisplayName("should handle malformed JSON")
        void handlesMalformedJson() {
            String result = tool.formatGoogleResults("not json at all", "query");
            assertTrue(result.contains("No results found"));
        }

        @Test
        @DisplayName("should limit to 10 results max")
        void limitsTo10() {
            StringBuilder json = new StringBuilder("{\"items\": [");
            for (int i = 0; i < 15; i++) {
                if (i > 0)
                    json.append(",");
                json.append("{\"title\": \"Item ").append(i).append("\", \"snippet\": \"s").append(i).append("\", \"link\": \"http://ex.com/")
                        .append(i).append("\"}");
            }
            json.append("]}");

            String result = tool.formatGoogleResults(json.toString(), "test");
            // Count numbered items — should be at most 10
            long count = result.lines().filter(l -> l.matches("^\\d+\\..*")).count();
            assertTrue(count <= 10);
        }
    }

    @Nested
    @DisplayName("formatDuckDuckGoResults")
    class DuckDuckGoResults {

        @Test
        @DisplayName("should format abstract and related topics")
        void formatsAbstractAndTopics() {
            String json = """
                    {
                      "Abstract": "Java is a programming language.",
                      "AbstractURL": "https://en.wikipedia.org/wiki/Java",
                      "RelatedTopics": [
                        {"Text": "Java SE - standard edition"},
                        {"Text": "Java EE - enterprise edition"}
                      ]
                    }
                    """;

            String result = tool.formatDuckDuckGoResults(json, "java", 5);

            assertTrue(result.contains("Quick Answer"));
            assertTrue(result.contains("Java is a programming language"));
            assertTrue(result.contains("https://en.wikipedia.org/wiki/Java"));
            assertTrue(result.contains("Java SE"));
            assertTrue(result.contains("Java EE"));
        }

        @Test
        @DisplayName("should handle empty abstract with only topics")
        void emptyAbstractWithTopics() {
            String json = """
                    {
                      "Abstract": "",
                      "AbstractURL": "",
                      "RelatedTopics": [
                        {"Text": "Related topic 1"}
                      ]
                    }
                    """;

            String result = tool.formatDuckDuckGoResults(json, "query", 5);
            assertFalse(result.contains("Quick Answer"));
            assertTrue(result.contains("Related topic 1"));
        }

        @Test
        @DisplayName("should handle no results at all")
        void noResults() {
            String json = """
                    {
                      "Abstract": "",
                      "AbstractURL": "",
                      "RelatedTopics": []
                    }
                    """;

            String result = tool.formatDuckDuckGoResults(json, "query", 5);
            assertTrue(result.contains("No instant results found"));
        }

        @Test
        @DisplayName("should respect maxResults limit")
        void respectsMaxResults() {
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

            String result = tool.formatDuckDuckGoResults(json, "query", 2);
            assertTrue(result.contains("Topic 1"));
            assertTrue(result.contains("Topic 2"));
            assertFalse(result.contains("3. Topic 3"));
        }

        @Test
        @DisplayName("should handle malformed JSON")
        void handlesMalformedJson() {
            String result = tool.formatDuckDuckGoResults("{bad json", "query", 5);
            assertTrue(result.contains("could not parse"));
        }

        @Test
        @DisplayName("should skip topics with empty text")
        void skipsEmptyTopics() {
            String json = """
                    {
                      "Abstract": "",
                      "RelatedTopics": [
                        {"Text": ""},
                        {"Text": "Valid topic"}
                      ]
                    }
                    """;

            String result = tool.formatDuckDuckGoResults(json, "query", 5);
            assertTrue(result.contains("Valid topic"));
        }
    }

    @Nested
    @DisplayName("formatWikipediaResults")
    class WikipediaResults {

        @Test
        @DisplayName("should format valid Wikipedia results")
        void formatsValidResults() {
            String json = """
                    {
                      "query": {
                        "search": [
                          {"title": "Java (programming language)", "snippet": "Java is a high-level <b>programming</b> language."},
                          {"title": "JavaScript", "snippet": "JavaScript is a <b>scripting</b> language."}
                        ]
                      }
                    }
                    """;

            String result = tool.formatWikipediaResults(json, "java");

            assertTrue(result.contains("Java (programming language)"));
            assertTrue(result.contains("JavaScript"));
            // HTML tags should be stripped
            assertFalse(result.contains("<b>"));
            assertTrue(result.contains("wikipedia.org"));
        }

        @Test
        @DisplayName("should handle empty search results")
        void handlesEmptyResults() {
            String json = "{\"query\": {\"search\": []}}";
            String result = tool.formatWikipediaResults(json, "nothing");
            assertTrue(result.contains("No Wikipedia articles found"));
        }

        @Test
        @DisplayName("should handle missing query node")
        void handlesMissingQueryNode() {
            String json = "{\"error\": \"something\"}";
            String result = tool.formatWikipediaResults(json, "test");
            assertTrue(result.contains("No Wikipedia articles found"));
        }

        @Test
        @DisplayName("should handle malformed JSON")
        void handlesMalformedJson() {
            String result = tool.formatWikipediaResults("not json", "query");
            assertTrue(result.contains("could not parse"));
        }

        @Test
        @DisplayName("should limit to 3 results")
        void limitsTo3() {
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                if (i > 0)
                    items.append(",");
                items.append("{\"title\": \"Item ").append(i).append("\", \"snippet\": \"Snippet ").append(i).append("\"}");
            }
            String json = "{\"query\": {\"search\": [" + items + "]}}";

            String result = tool.formatWikipediaResults(json, "test");
            assertTrue(result.contains("Item 0"));
            assertTrue(result.contains("Item 2"));
            assertFalse(result.contains("4. Item 3"));
        }
    }
}
