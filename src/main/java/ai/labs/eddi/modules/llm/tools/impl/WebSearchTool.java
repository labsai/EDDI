/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.ToolFailureException;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.Expressions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Web search tool that integrates with search APIs. The provider is chosen by
 * {@code eddi.tools.websearch.provider}:
 * <ul>
 * <li>{@code duckduckgo} (default) — DuckDuckGo's Instant Answer API: no key,
 * no price, but instant answers rather than a ranked results page.</li>
 * <li>{@code brave} — the Brave Search API web endpoint, keyed by
 * {@code eddi.tools.websearch.brave.api-key} (plain, or a {@code ${vault:...}}
 * reference), sent as a header rather than in the URL.</li>
 * <li>{@code google} — Google's Custom Search JSON API. <b>Deprecated:</b>
 * Google closed it to new customers and shuts it down on
 * {@link #GOOGLE_CSE_SHUTDOWN}; a startup WARN says so.</li>
 * </ul>
 * A paid provider whose key is missing falls back to DuckDuckGo, and a startup
 * WARN names the missing property.
 * <p>
 * Uses Jackson {@link ObjectMapper} for safe, correct JSON parsing of all
 * search API responses.
 */
@ApplicationScoped
public class WebSearchTool {
    private static final Logger LOGGER = Logger.getLogger(WebSearchTool.class);

    /**
     * Ceiling on a search provider's answer. A results page is tens of kilobytes;
     * the limit only has to stop a misbehaving endpoint from filling the heap.
     */
    static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;

    static final String PROVIDER_DUCKDUCKGO = "duckduckgo";
    static final String PROVIDER_BRAVE = "brave";
    static final String PROVIDER_GOOGLE = "google";

    /**
     * The day Google's Custom Search JSON API stops answering
     * (https://developers.google.com/custom-search/v1/overview).
     */
    static final LocalDate GOOGLE_CSE_SHUTDOWN = LocalDate.of(2027, 1, 1);

    static final String BRAVE_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    /** Brave rejects a {@code q} longer than 400 characters. */
    static final int BRAVE_MAX_QUERY_LENGTH = 400;

    private final OutboundHttp http;
    private final ObjectMapper objectMapper;
    private final SecretResolver secretResolver;

    @ConfigProperty(name = "eddi.tools.websearch.google.api-key")
    Optional<String> googleApiKey;

    @ConfigProperty(name = "eddi.tools.websearch.google.cx")
    Optional<String> googleCx;

    @ConfigProperty(name = "eddi.tools.websearch.brave.api-key")
    Optional<String> braveApiKey;

    @ConfigProperty(name = "eddi.tools.websearch.provider", defaultValue = "duckduckgo")
    String searchProvider;

    @Inject
    public WebSearchTool(OutboundHttp http, ObjectMapper objectMapper, SecretResolver secretResolver) {
        this.http = http;
        this.objectMapper = objectMapper;
        this.secretResolver = secretResolver;
        // Defaults for construction outside CDI; CDI injects over them.
        this.googleApiKey = Optional.empty();
        this.googleCx = Optional.empty();
        this.braveApiKey = Optional.empty();
        this.searchProvider = PROVIDER_DUCKDUCKGO;
    }

    /**
     * Outside CDI (tests): no vault, so a {@code ${vault:...}} key is refused.
     */
    public WebSearchTool(OutboundHttp http, ObjectMapper objectMapper) {
        this(http, objectMapper, null);
    }

    /**
     * The provider a search actually goes to: the configured one when its
     * credentials are present, else DuckDuckGo.
     */
    String effectiveProvider() {
        return switch (configuredProvider()) {
            case PROVIDER_GOOGLE -> isSet(googleApiKey) && isSet(googleCx) ? PROVIDER_GOOGLE : PROVIDER_DUCKDUCKGO;
            case PROVIDER_BRAVE -> isSet(braveApiKey) ? PROVIDER_BRAVE : PROVIDER_DUCKDUCKGO;
            default -> PROVIDER_DUCKDUCKGO;
        };
    }

    private String configuredProvider() {
        return searchProvider == null ? "" : searchProvider.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSet(Optional<String> value) {
        return value != null && value.isPresent() && !value.get().isBlank();
    }

    void logConfigurationWarnings(@Observes StartupEvent event) {
        for (String warning : configurationWarnings(LocalDate.now())) {
            LOGGER.warn(warning);
        }
    }

    /**
     * What is wrong, or about to break, in the web-search configuration: one line
     * per problem, for the startup log.
     */
    List<String> configurationWarnings(LocalDate today) {
        List<String> warnings = new ArrayList<>();
        switch (configuredProvider()) {
            case PROVIDER_DUCKDUCKGO -> {
                // nothing to configure
            }
            case PROVIDER_GOOGLE -> {
                warnings.add(String.format("eddi.tools.websearch.provider=google uses Google's Custom Search JSON API, which is closed to "
                        + "new customers and %s on %s (https://developers.google.com/custom-search/v1/overview). Switch to "
                        + "eddi.tools.websearch.provider=brave with eddi.tools.websearch.brave.api-key.",
                        today.isBefore(GOOGLE_CSE_SHUTDOWN) ? "shuts down" : "was shut down", GOOGLE_CSE_SHUTDOWN));
                if (!isSet(googleApiKey) || !isSet(googleCx)) {
                    warnings.add("eddi.tools.websearch.provider=google but eddi.tools.websearch.google.api-key or "
                            + "eddi.tools.websearch.google.cx is not set; web searches go to DuckDuckGo instead.");
                }
            }
            case PROVIDER_BRAVE -> {
                if (!isSet(braveApiKey)) {
                    warnings.add("eddi.tools.websearch.provider=brave but eddi.tools.websearch.brave.api-key is not set; "
                            + "web searches go to DuckDuckGo instead.");
                }
            }
            default -> warnings.add(String.format("eddi.tools.websearch.provider=%s is not a known provider (duckduckgo, brave, google); "
                    + "web searches go to DuckDuckGo.", sanitize(searchProvider)));
        }
        return warnings;
    }

    @Tool("Searches the web for current information on any topic. Returns relevant search results with titles and snippets.")
    public String searchWeb(@P("Search terms") String query,
                            @P(value = "Maximum number of results, 1-10 (default 5)", required = false) Integer maxResults) {

        if (maxResults == null || maxResults < 1) {
            maxResults = 5;
        }
        if (maxResults > 10) {
            maxResults = 10;
        }

        try {
            LOGGER.debugf("Searching web for: %s", sanitize(query));

            String results = switch (effectiveProvider()) {
                case PROVIDER_BRAVE -> searchWithBrave(query, maxResults);
                case PROVIDER_GOOGLE -> searchWithGoogle(query, maxResults);
                // DuckDuckGo's Instant Answer API, no API key required
                default -> searchWithDuckDuckGo(query, maxResults);
            };

            LOGGER.debugf("Search completed for: %s", sanitize(query));
            return results;

        } catch (Exception e) {
            // The message can name the request URI, and Google's carries the API key.
            String error = redactApiKey(String.valueOf(e.getMessage()));
            LOGGER.warnf("Web search failed for query '%s': %s", sanitize(query), sanitize(error));
            throw new ToolFailureException("Error: Could not perform web search - " + error);
        }
    }

    /**
     * Replaces the value of every {@code key=} query parameter in {@code message}.
     */
    static String redactApiKey(String message) {
        return message.replaceAll("(?i)(key=)[^&\\s]+", "$1[REDACTED]");
    }

    private String searchWithGoogle(String query, int maxResults) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = String.format("https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=%d",
                URLEncoder.encode(googleApiKey.get(), StandardCharsets.UTF_8), URLEncoder.encode(googleCx.get(), StandardCharsets.UTF_8),
                encodedQuery, maxResults);

        String body = fetch(get(url).build(), "Google search");

        return formatGoogleResults(body, query);
    }

    private String searchWithBrave(String query, int maxResults) throws IOException, InterruptedException {
        String key = resolveBraveKey();
        String q = query.length() > BRAVE_MAX_QUERY_LENGTH ? query.substring(0, BRAVE_MAX_QUERY_LENGTH) : query;
        String url = BRAVE_ENDPOINT + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&count=" + maxResults;

        // The key travels in X-Subscription-Token, one of OutboundHttp's
        // SENSITIVE_HEADERS, so a cross-origin redirect never carries it on.
        String body = fetch(get(url).header("Accept", "application/json").header("X-Subscription-Token", key).build(), "Brave search");

        return formatBraveResults(body, query, maxResults);
    }

    /**
     * The Brave key with a {@code ${vault:...}} reference resolved. A reference the
     * vault cannot resolve is refused rather than sent as the credential.
     */
    private String resolveBraveKey() throws IOException {
        String key = rawBraveKey().filter(SecretReference::isVaultReference).orElse(braveApiKey.orElseThrow()).trim();
        if (SecretReference.isVaultReference(key)) {
            if (secretResolver != null) {
                key = secretResolver.resolveValue(key);
            }
            if (key == null || SecretReference.isVaultReference(key)) {
                throw new IOException("eddi.tools.websearch.brave.api-key names a vault secret that could not be resolved");
            }
        }
        return key;
    }

    /**
     * The key exactly as configured, before SmallRye expands it. Config reads
     * {@code ${vault:brave-search}} as an expression — property {@code vault},
     * default {@code brave-search} — so the injected {@link #braveApiKey} holds the
     * bare default and the reference is lost. Read unexpanded, it is still a
     * reference the vault can resolve.
     */
    Optional<String> rawBraveKey() {
        try {
            return Expressions.withoutExpansion(
                    () -> ConfigProvider.getConfig().getOptionalValue("eddi.tools.websearch.brave.api-key", String.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String searchWithDuckDuckGo(String query, int maxResults) throws IOException, InterruptedException {
        // Use DuckDuckGo's instant answer API (free, no API key required)
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1";

        String body = fetch(get(url).header("User-Agent", "EDDI-Agent/1.0").build(), "DuckDuckGo search");

        return formatDuckDuckGoResults(body, query, maxResults);
    }

    /**
     * A GET of a search provider's fixed host: the URL is trusted, every redirect
     * hop is SSRF-checked.
     */
    private static OutboundRequest.Builder get(String url) {
        return OutboundRequest.get(Profile.DEFAULT, URI.create(url))
                .ssrf(SsrfPolicy.HOPS_ONLY)
                .timeout(Duration.ofSeconds(15));
    }

    /**
     * Sends {@code request} with the body capped at {@link #MAX_RESPONSE_BYTES} and
     * returns the body of a 200 answer. A body cut short at the cap or the read
     * deadline is refused rather than parsed as a fragment.
     */
    private String fetch(OutboundRequest request, String source) throws IOException, InterruptedException {
        OutboundResponse<byte[]> response = http.send(request, BodyMode.bytes(MAX_RESPONSE_BYTES, BodyMode.OnOverflow.TRUNCATE));
        if (response.status() != 200) {
            throw new IOException(source + " returned status: " + response.status());
        }
        if (response.truncated()) {
            throw new IOException(source + " response exceeded " + MAX_RESPONSE_BYTES + " bytes or its read deadline");
        }
        return new String(response.body(), StandardCharsets.UTF_8);
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
     * Parses a Brave Search API web response ({@code web.results[]} with
     * {@code title}, {@code url}, {@code description}) into the shape
     * {@link #formatGoogleResults} produces. Brave marks matches with
     * {@code <strong>} tags, which are stripped.
     */
    String formatBraveResults(String jsonResponse, String query, int maxResults) {
        StringBuilder results = new StringBuilder();
        results.append("Search results for '").append(query).append("':\n\n");

        boolean hasResults = false;
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode web = root == null ? null : root.get("web");
            JsonNode items = web == null ? null : web.get("results");

            if (items != null && items.isArray()) {
                int limit = Math.min(maxResults, 10);
                int count = 0;
                for (JsonNode item : items) {
                    if (count >= limit)
                        break;

                    String title = plainText(getTextOrEmpty(item, "title"));
                    String snippet = plainText(getTextOrEmpty(item, "description"));
                    String link = getTextOrEmpty(item, "url");

                    results.append(++count).append(". ").append(title).append("\n");
                    results.append("   ").append(snippet).append("\n");
                    results.append("   ").append(link).append("\n\n");
                    hasResults = true;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not parse Brave search results: " + e.getMessage());
        }

        if (!hasResults) {
            results.append("No results found for '").append(query).append("'.");
        }

        return results.toString();
    }

    /**
     * Drops HTML tags and decodes the few entities search snippets carry.
     * {@code &amp;} goes last so {@code &amp;lt;} stays the text {@code &lt;}.
     */
    static String plainText(String html) {
        return html.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
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
    public String searchNews(@P("News topic to search for") String query,
                             @P(value = "Maximum number of results, 1-10 (default 5)", required = false) Integer maxResults) {

        // Add "news" keyword to regular search for better results
        return searchWeb(query + " news", maxResults);
    }

    @Tool("Searches Wikipedia for information on a topic")
    public String searchWikipedia(@P("Topic to look up on Wikipedia") String query) {

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + encodedQuery + "&format=json&srlimit=3";

            String body = fetch(get(url).header("User-Agent", "EDDI-Agent/1.0").build(), "Wikipedia search");

            return formatWikipediaResults(body, query);

        } catch (Exception e) {
            LOGGER.warnf(e, "Wikipedia search failed for query '%s'", sanitize(query));
            throw new ToolFailureException("Error: Could not search Wikipedia - " + e.getMessage());
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
