/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Web search tool that integrates with search APIs. Supports Google Custom
 * Search, DuckDuckGo, and other search providers.
 * <p>
 * Uses Jackson {@link ObjectMapper} for safe, correct JSON parsing of all
 * search API responses.
 */
@ApplicationScoped
public class WebSearchTool {
    private static final Logger LOGGER = Logger.getLogger(WebSearchTool.class);
    private final SafeHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @ConfigProperty(name = "eddi.tools.websearch.google.api-key")
    Optional<String> googleApiKey;

    @ConfigProperty(name = "eddi.tools.websearch.google.cx")
    Optional<String> googleCx;

    @ConfigProperty(name = "eddi.tools.websearch.provider", defaultValue = "duckduckgo")
    String searchProvider;

    @Inject
    public WebSearchTool(SafeHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Tool("Searches the web for current information on any topic. Returns relevant search results with titles and snippets.")
    public String searchWeb(@P("query") String query, @P("maxResults") Integer maxResults) {

        if (maxResults == null || maxResults < 1) {
            maxResults = 5;
        }
        if (maxResults > 10) {
            maxResults = 10;
        }

        try {
            LOGGER.info("Searching web for: " + query);

            String results;
            if ("google".equalsIgnoreCase(searchProvider) && googleApiKey.isPresent() && googleCx.isPresent()) {
                results = searchWithGoogle(query, maxResults);
            } else {
                // Fallback to DuckDuckGo HTML scraping (no API key required)
                results = searchWithDuckDuckGo(query, maxResults);
            }

            LOGGER.debug("Search completed for: " + query);
            return results;

        } catch (Exception e) {
            LOGGER.error("Web search error for query '" + query + "': " + e.getMessage(), e);
            return "Error: Could not perform web search - " + e.getMessage();
        }
    }

    private String searchWithGoogle(String query, int maxResults) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = String.format("https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=%d", googleApiKey.get(), googleCx.get(),
                encodedQuery, maxResults);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Google search returned status: " + response.statusCode());
        }

        return formatGoogleResults(response.body(), query);
    }

    private String searchWithDuckDuckGo(String query, int maxResults) throws IOException, InterruptedException {
        // Use DuckDuckGo's instant answer API (free, no API key required)
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1";

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "EDDI-Agent/1.0")
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("DuckDuckGo search returned status: " + response.statusCode());
        }

        return formatDuckDuckGoResults(response.body(), query, maxResults);
    }

    /**
     * Parses Google Custom Search API JSON response using Jackson.
     */
    String formatGoogleResults(String jsonResponse, String query) {
        StringBuilder results = new StringBuilder();
        results.append("Search results for '").append(query).append("':\n\n");

        boolean hasResults = false;
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode items = root.get("items");

            if (items != null && items.isArray()) {
                int count = 0;
                for (JsonNode item : items) {
                    if (count >= 10)
                        break;

                    String title = getTextOrEmpty(item, "title");
                    String snippet = getTextOrEmpty(item, "snippet");
                    String link = getTextOrEmpty(item, "link");

                    results.append(++count).append(". ").append(title).append("\n");
                    results.append("   ").append(snippet).append("\n");
                    results.append("   ").append(link).append("\n\n");
                    hasResults = true;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not parse Google search results: " + e.getMessage());
        }

        if (!hasResults) {
            results.append("No results found for '").append(query).append("'.");
        }

        return results.toString();
    }

    /**
     * Parses DuckDuckGo Instant Answer API JSON response using Jackson.
     */
    String formatDuckDuckGoResults(String jsonResponse, String query, int maxResults) {
        StringBuilder results = new StringBuilder();
        results.append("Search results for '").append(query).append("':\n\n");

        boolean hasResults = false;
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            // Parse DuckDuckGo instant answer abstract
            String abstractText = getTextOrEmpty(root, "Abstract");
            String abstractUrl = getTextOrEmpty(root, "AbstractURL");

            if (!abstractText.isEmpty()) {
                results.append("Quick Answer:\n");
                results.append(abstractText).append("\n");
                if (!abstractUrl.isEmpty()) {
                    results.append("Source: ").append(abstractUrl).append("\n");
                }
                results.append("\n");
                hasResults = true;
            }

            // Parse related topics
            JsonNode relatedTopics = root.get("RelatedTopics");
            if (relatedTopics != null && relatedTopics.isArray()) {
                int count = 0;
                for (JsonNode topic : relatedTopics) {
                    if (count >= maxResults)
                        break;

                    String text = getTextOrEmpty(topic, "Text");
                    if (!text.isEmpty()) {
                        results.append(++count).append(". ").append(text).append("\n");
                        hasResults = true;
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error parsing DuckDuckGo results", e);
            return "Search completed but could not parse results. Query: " + query;
        }

        if (!hasResults) {
            results.append("No instant results found. Try refining your search query.");
        }

        return results.toString();
    }

    @Tool("Searches for news articles on a specific topic")
    public String searchNews(@P("query") String query, @P("maxResults") Integer maxResults) {

        // Add "news" keyword to regular search for better results
        return searchWeb(query + " news", maxResults);
    }

    @Tool("Searches Wikipedia for information on a topic")
    public String searchWikipedia(@P("query") String query) {

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encodedQuery + "&format=json&srlimit=3";

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "EDDI-Agent/1.0")
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Wikipedia search returned status: " + response.statusCode());
            }

            return formatWikipediaResults(response.body(), query);

        } catch (Exception e) {
            LOGGER.error("Wikipedia search error: " + e.getMessage(), e);
            return "Error: Could not search Wikipedia - " + e.getMessage();
        }
    }

    /**
     * Parses Wikipedia API JSON response using Jackson.
     */
    String formatWikipediaResults(String jsonResponse, String query) {
        StringBuilder results = new StringBuilder();
        results.append("Wikipedia results for '").append(query).append("':\n\n");

        boolean hasResults = false;
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode queryNode = root.get("query");
            JsonNode searchResults = queryNode != null ? queryNode.get("search") : null;

            if (searchResults != null && searchResults.isArray()) {
                int count = 0;
                for (JsonNode item : searchResults) {
                    if (count >= 3)
                        break;

                    String title = getTextOrEmpty(item, "title");
                    String snippet = getTextOrEmpty(item, "snippet")
                            .replaceAll("<[^>]*>", ""); // Strip HTML tags

                    String wikiUrl = "https://en.wikipedia.org/wiki/"
                            + URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8);

                    results.append(++count).append(". ").append(title).append("\n");
                    results.append("   ").append(snippet).append("\n");
                    results.append("   ").append(wikiUrl).append("\n\n");
                    hasResults = true;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error parsing Wikipedia results", e);
            return "Wikipedia search completed but could not parse results.";
        }

        if (!hasResults) {
            results.append("No Wikipedia articles found for '").append(query).append("'.");
        }

        return results.toString();
    }

    /**
     * Safely extracts a text value from a JsonNode field, returning empty string if
     * the field is missing or null.
     */
    private static String getTextOrEmpty(JsonNode node, String fieldName) {
        if (node == null)
            return "";
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : "";
    }
}
