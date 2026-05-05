/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.model.SourceConfig;
import ai.labs.eddi.configs.ingestion.model.WebSourceConfig;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Web content fetcher implementing {@link ContentFetcher}.
 * <p>
 * TOC-driven BFS web crawler for ingesting documentation from websites. The
 * crawler:
 * <ol>
 * <li>Fetches the starting URL</li>
 * <li>Extracts table-of-contents links using a CSS selector</li>
 * <li>BFS-crawls each discovered page within scope constraints</li>
 * <li>Returns raw HTML content and metadata for each page</li>
 * </ol>
 * <p>
 * Scope constraints: same domain, path prefix, max depth, max pages, exclude
 * patterns. Uses {@link SafeHttpClient} for SSRF-safe HTTP fetching.
 *
 * @since 6.0.3
 * @see ContentFetcher
 */
@ApplicationScoped
public class WebContentFetcher implements ContentFetcher {

    private static final Logger LOGGER = Logger.getLogger(WebContentFetcher.class);

    private final SafeHttpClient httpClient;

    @Inject
    public WebContentFetcher(SafeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getType() {
        return "web";
    }

    @Override
    public FetchResult fetch(String sourceId, SourceConfig config) {
        if (!(config instanceof WebSourceConfig webConfig)) {
            throw new IllegalArgumentException(
                    "Expected WebSourceConfig but got: " + (config != null ? config.getClass().getName() : "null"));
        }

        long startTime = System.currentTimeMillis();

        String startUrl = webConfig.startUrl();
        String tocSelector = webConfig.tocSelector();
        WebSourceConfig.Scope scope = webConfig.scope();
        WebSourceConfig.CrawlSettings settings = webConfig.crawlSettings();

        LOGGER.infof("[web] Starting crawl for source '%s' from %s with TOC selector: %s",
                sanitize(sourceId), sanitize(startUrl), sanitize(tocSelector));

        List<FetchedDocument> documents = new ArrayList<>();
        List<FetchError> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<UrlDepth> queue = new LinkedList<>();

        // Extract start URL domain and path
        String startDomain;
        String startPathPrefix;
        try {
            URI startUri = new URI(startUrl);
            startDomain = startUri.getHost();
            startPathPrefix = normalizePathPrefix(scope.pathPrefix());
        } catch (URISyntaxException e) {
            errors.add(new FetchError(startUrl, "Invalid start URL: " + e.getMessage()));
            return new FetchResult(documents, errors, System.currentTimeMillis() - startTime);
        }

        // Start by fetching the start URL to extract TOC links
        try {
            FetchedDocument startDoc = fetchDocument(startUrl, settings);
            if (startDoc != null) {
                documents.add(startDoc);
                visited.add(normalizeUrl(startUrl));

                // Extract TOC links from start page
                List<String> tocLinks = extractTocLinks(startDoc.content(), tocSelector, startUrl);
                LOGGER.infof("[web] Found %d TOC links on start page for source '%s'", tocLinks.size(), sanitize(sourceId));

                // Add TOC links to queue at depth 1
                for (String link : tocLinks) {
                    link = stripFragment(link);
                    if (shouldCrawl(link, startDomain, startPathPrefix, scope)) {
                        queue.offer(new UrlDepth(link, 1));
                    }
                }
            }
        } catch (Exception e) {
            errors.add(new FetchError(startUrl, "Failed to fetch start URL: " + e.getMessage()));
            LOGGER.errorf(e, "[web] Failed to fetch start URL for source '%s': %s", sanitize(sourceId), sanitize(startUrl));
        }

        // BFS crawl
        while (!queue.isEmpty() && documents.size() < scope.maxPages()) {
            UrlDepth current = queue.poll();
            String url = current.url;
            int depth = current.depth;

            // Skip if already visited
            String normalizedUrl = normalizeUrl(url);
            if (visited.contains(normalizedUrl)) {
                continue;
            }

            // Check max depth
            if (depth > scope.maxDepth()) {
                continue;
            }

            // Mark as visited
            visited.add(normalizedUrl);

            // Apply request delay (politeness)
            if (settings.requestDelayMs() > 0) {
                try {
                    Thread.sleep(settings.requestDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Fetch the page
            try {
                FetchedDocument doc = fetchDocument(url, settings);
                if (doc != null) {
                    documents.add(doc);
                    LOGGER.debugf("[web] Crawled for source '%s': %s (depth %d, %d/%d pages)",
                            sanitize(sourceId), sanitize(url), depth, documents.size(), scope.maxPages());

                    // Extract more links for further crawling (BFS)
                    if (depth < scope.maxDepth()) {
                        List<String> links = extractLinks(doc.content(), url);
                        for (String link : links) {
                            link = stripFragment(link);
                            String normalizedLink = normalizeUrl(link);
                            if (!visited.contains(normalizedLink) && shouldCrawl(link, startDomain, startPathPrefix, scope)) {
                                queue.offer(new UrlDepth(link, depth + 1));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add(new FetchError(url, e.getMessage()));
                LOGGER.warnf("[web] Failed to crawl for source '%s': %s - %s",
                        sanitize(sourceId), sanitize(url), sanitize(e.getMessage()));
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.infof("[web] Crawl completed for source '%s': %d pages, %d errors, %d ms",
                sanitize(sourceId), documents.size(), errors.size(), duration);

        return new FetchResult(documents, errors, duration);
    }

    private FetchedDocument fetchDocument(String url, WebSourceConfig.CrawlSettings settings) throws Exception {
        // Strip fragment — fragments are client-side only and never sent in HTTP
        // requests
        url = stripFragment(url);

        // Validate URL for SSRF safety
        UrlValidationUtils.validateUrl(url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("User-Agent", settings.userAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase().contains("html")) {
            // Skip non-HTML content
            return null;
        }

        // Extract title from HTML
        Document doc = Jsoup.parse(response.body());
        String title = doc.title();

        // Build metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("url", url);
        metadata.put("httpStatus", String.valueOf(response.statusCode()));
        metadata.put("contentType", contentType.isEmpty() ? "text/html" : contentType.split(";")[0].trim());

        return new FetchedDocument(url, title, response.body(), "text/html", metadata);
    }

    private List<String> extractTocLinks(String html, String tocSelector, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        Elements links = doc.select(tocSelector);

        List<String> result = new ArrayList<>();
        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href != null && !href.isBlank()) {
                result.add(href);
            }
        }

        return result;
    }

    private List<String> extractLinks(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        Elements links = doc.select("a[href]");

        List<String> result = new ArrayList<>();
        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href != null && !href.isBlank()) {
                result.add(href);
            }
        }

        return result;
    }

    private boolean shouldCrawl(String url, String startDomain, String startPathPrefix, WebSourceConfig.Scope scope) {
        try {
            URI uri = new URI(url);

            // Check scheme
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }

            // Check same domain
            if (scope.sameDomainOnly()) {
                String host = uri.getHost();
                if (host == null || !host.equalsIgnoreCase(startDomain)) {
                    return false;
                }
            }

            // Check path prefix
            String path = uri.getPath();
            if (path == null) {
                path = "/";
            }
            if (!path.startsWith(startPathPrefix)) {
                return false;
            }

            // Check exclude patterns
            String normalizedUrl = url.toLowerCase();
            for (String pattern : scope.excludePatterns()) {
                if (matchesGlob(normalizedUrl, pattern.toLowerCase())) {
                    return false;
                }
            }

            // Check fragment-only links (same page anchors)
            String pathAndQuery = uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
            if (pathAndQuery.isEmpty() || pathAndQuery.equals("/")) {
                // This is likely just a fragment link to the same page
                return false;
            }

            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String normalizePathPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/";
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        if (!prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        return prefix;
    }

    private String normalizeUrl(String url) {
        try {
            url = stripFragment(url);
            URI uri = new URI(url);
            String normalized = uri.normalize().toString();
            // Remove trailing slash for comparison
            if (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized.toLowerCase();
        } catch (URISyntaxException e) {
            return url.toLowerCase();
        }
    }

    private static String stripFragment(String url) {
        if (url == null)
            return null;
        int idx = url.indexOf('#');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    private boolean matchesGlob(String text, String pattern) {
        // Simple glob matching: * matches any sequence, ? matches single char
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", ".");
        return text.matches(regex);
    }

    // --- Helper classes ---

    private record UrlDepth(String url, int depth) {
    }
}
