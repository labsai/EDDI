package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Web search tool that integrates with search APIs. Supports Google Custom
 * Search, DuckDuckGo, and other search providers.
 */
@ApplicationScoped
public class WebSearchTool {
    private static final Logger LOGGER = Logger.getLogger(WebSearchTool.class);
    private final HttpClient httpClient;

    @ConfigProperty(name = "eddi.tools.websearch.google.api-key")
    Optional<String> googleApiKey;

    @ConfigProperty(name = "eddi.tools.websearch.google.cx")
    Optional<String> googleCx;

    @ConfigProperty(name = "eddi.tools.websearch.provider", defaultValue = "duckduckgo")
    String searchProvider;

    public WebSearchTool() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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

    private String formatGoogleResults(String jsonResponse, String query) {
        // Simple parsing - in production, use proper JSON library
        StringBuilder results = new StringBuilder();
        results.append("Search results for '").append(query).append("':\n\n");

        // Extract items from JSON (simplified - in production use Jackson/Gson)
        if (jsonResponse.contains("\"items\"")) {
            String[] items = jsonResponse.split("\"title\"");
            int count = 0;

            for (int i = 1; i < items.length && count < 10; i++) {
                try {
                    // Extract title
                    int titleStart = items[i].indexOf(":") + 3;
                    int titleEnd = items[i].indexOf("\"", titleStart);
                    String title = items[i].substring(titleStart, titleEnd);

                    // Extract snippet
                    int snippetStart = items[i].indexOf("\"snippet\"") + 12;
                    int snippetEnd = items[i].indexOf("\"", snippetStart);
                    String snippet = items[i].substring(snippetStart, snippetEnd);

                    // Extract link
                    int linkStart = items[i].indexOf("\"link\"") + 9;
                    int linkEnd = items[i].indexOf("\"", linkStart);
                    String link = items[i].substring(linkStart, linkEnd);

                    results.append(++count).append(". ").append(unescapeJson(title)).append("\n");
                    results.append("   ").append(unescapeJson(snippet)).append("\n");
                    results.append("   ").append(link).append("\n\n");

                } catch (Exception e) {
                    // Skip malformed results
                    LOGGER.debug("Could not parse search result: " + e.getMessage());
                }
            }
        }

        if (results.length() == 0) {
            results.append("No results found for '").append(query).append("'.");
        }

        return results.toString();
    }

    private String formatDuckDuckGoResults(String jsonResponse, String query, int maxResults) {
        StringBuilder results = new StringBuilder();
        results.append("Search results for '").append(query).append("':\n\n");

        try {
            // Parse DuckDuckGo instant answer
            if (jsonResponse.contains("\"Abstract\"")) {
                String abstractText = extractJsonValue(jsonResponse, "Abstract");
                String abstractUrl = extractJsonValue(jsonResponse, "AbstractURL");

                if (!abstractText.isEmpty()) {
                    results.append("Quick Answer:\n");
                    results.append(unescapeJson(abstractText)).append("\n");
                    if (!abstractUrl.isEmpty()) {
                        results.append("Source: ").append(abstractUrl).append("\n");
                    }
                    results.append("\n");
                }
            }

            // Parse related topics
            if (jsonResponse.contains("\"RelatedTopics\"")) {
                results.append("Related information:\n");
                String[] topics = jsonResponse.split("\"Text\"");
                int count = 0;

                for (int i = 1; i < topics.length && count < maxResults; i++) {
                    try {
                        int textStart = topics[i].indexOf(":") + 3;
                        int textEnd = topics[i].indexOf("\"", textStart);
                        String text = topics[i].substring(textStart, textEnd);

                        if (!text.isEmpty()) {
                            results.append(++count).append(". ").append(unescapeJson(text)).append("\n");
                        }
                    } catch (Exception e) {
                        // Skip malformed results
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error parsing DuckDuckGo results", e);
            return "Search completed but could not parse results. Query: " + query;
        }

        if (results.toString().equals("Search results for '" + query + "':\n\n")) {
            results.append("No instant results found. Try refining your search query.");
        }

        return results.toString();
    }

    private String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":\"";
            int start = json.indexOf(searchKey);
            if (start == -1)
                return "";

            start += searchKey.length();
            int end = json.indexOf("\"", start);
            if (end == -1)
                return "";

            return json.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    private String unescapeJson(String text) {
        return text.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
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

    private String formatWikipediaResults(String jsonResponse, String query) {
        StringBuilder results = new StringBuilder();
        results.append("Wikipedia results for '").append(query).append("':\n\n");

        try {
            String[] searchResults = jsonResponse.split("\"title\"");
            int count = 0;

            for (int i = 1; i < searchResults.length && count < 3; i++) {
                try {
                    int titleStart = searchResults[i].indexOf(":\"") + 2;
                    int titleEnd = searchResults[i].indexOf("\"", titleStart);
                    String title = searchResults[i].substring(titleStart, titleEnd);

                    int snippetStart = searchResults[i].indexOf("\"snippet\":\"") + 11;
                    int snippetEnd = searchResults[i].indexOf("\"", snippetStart);
                    String snippet = searchResults[i].substring(snippetStart, snippetEnd);

                    String wikiUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8);

                    results.append(++count).append(". ").append(unescapeJson(title)).append("\n");
                    results.append("   ").append(unescapeJson(snippet).replaceAll("<[^>]*>", "")).append("\n");
                    results.append("   ").append(wikiUrl).append("\n\n");

                } catch (Exception e) {
                    LOGGER.debug("Could not parse Wikipedia result: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            LOGGER.error("Error parsing Wikipedia results", e);
            return "Wikipedia search completed but could not parse results.";
        }

        if (results.toString().equals("Wikipedia results for '" + query + "':\n\n")) {
            results.append("No Wikipedia articles found for '").append(query).append("'.");
        }

        return results.toString();
    }
}
