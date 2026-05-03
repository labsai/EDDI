/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
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
 * TOC-driven BFS web crawler for ingesting documentation from websites.
 * <p>
 * The crawler:
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
 */
@ApplicationScoped
public class WebCrawler {

    private static final Logger LOGGER = Logger.getLogger(WebCrawler.class);

    private final SafeHttpClient httpClient;

    @Inject
    public WebCrawler(SafeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Crawls a website starting from the configured URL.
     *
     * @param source
     *            the ingestion source configuration
     * @return crawl result containing crawled pages and any errors
     */
    public CrawlResult crawl(RagIngestionSource source) {
        long startTime = System.currentTimeMillis();

        String startUrl = source.getStartUrl();
        String tocSelector = source.getTocSelector();
        RagIngestionSource.Scope scope = source.getScope();
        RagIngestionSource.CrawlSettings settings = source.getCrawlSettings();

        LOGGER.infof("Starting crawl from %s with TOC selector: %s", sanitize(startUrl), sanitize(tocSelector));

        List<CrawledPage> pages = new ArrayList<>();
        List<CrawlError> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<UrlDepth> queue = new LinkedList<>();

        // Extract start URL domain and path
        String startDomain;
        String startPathPrefix;
        try {
            URI startUri = new URI(startUrl);
            startDomain = startUri.getHost();
            startPathPrefix = normalizePathPrefix(scope.getPathPrefix());
        } catch (URISyntaxException e) {
            errors.add(new CrawlError(startUrl, "Invalid start URL: " + e.getMessage()));
            return new CrawlResult(pages, errors, System.currentTimeMillis() - startTime);
        }

        // Start by fetching the start URL to extract TOC links
        try {
            CrawledPage startPage = fetchPage(startUrl, settings);
            if (startPage != null) {
                pages.add(startPage);
                visited.add(normalizeUrl(startUrl));

                // Extract TOC links from start page
                List<String> tocLinks = extractTocLinks(startPage.html(), tocSelector, startUrl);
                LOGGER.infof("Found %d TOC links on start page", tocLinks.size());

                // Add TOC links to queue at depth 1
                for (String link : tocLinks) {
                    if (shouldCrawl(link, startDomain, startPathPrefix, scope)) {
                        queue.offer(new UrlDepth(link, 1));
                    }
                }
            }
        } catch (Exception e) {
            errors.add(new CrawlError(startUrl, "Failed to fetch start URL: " + e.getMessage()));
            LOGGER.errorf(e, "Failed to fetch start URL: %s", sanitize(startUrl));
        }

        // BFS crawl
        while (!queue.isEmpty() && pages.size() < scope.getMaxPages()) {
            UrlDepth current = queue.poll();
            String url = current.url;
            int depth = current.depth;

            // Skip if already visited
            String normalizedUrl = normalizeUrl(url);
            if (visited.contains(normalizedUrl)) {
                continue;
            }

            // Check max depth
            if (depth > scope.getMaxDepth()) {
                continue;
            }

            // Mark as visited
            visited.add(normalizedUrl);

            // Apply request delay (politeness)
            if (settings.getRequestDelayMs() > 0) {
                try {
                    Thread.sleep(settings.getRequestDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Fetch the page
            try {
                CrawledPage page = fetchPage(url, settings);
                if (page != null) {
                    pages.add(page);
                    LOGGER.debugf("Crawled: %s (depth %d, %d/%d pages)", sanitize(url), depth, pages.size(), scope.getMaxPages());

                    // Extract more links for further crawling (BFS)
                    if (depth < scope.getMaxDepth()) {
                        List<String> links = extractLinks(page.html(), url);
                        for (String link : links) {
                            String normalizedLink = normalizeUrl(link);
                            if (!visited.contains(normalizedLink) && shouldCrawl(link, startDomain, startPathPrefix, scope)) {
                                queue.offer(new UrlDepth(link, depth + 1));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add(new CrawlError(url, e.getMessage()));
                LOGGER.warnf("Failed to crawl %s: %s", sanitize(url), sanitize(e.getMessage()));
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.infof("Crawl completed: %d pages, %d errors, %d ms", pages.size(), errors.size(), duration);

        return new CrawlResult(pages, errors, duration);
    }

    private CrawledPage fetchPage(String url, RagIngestionSource.CrawlSettings settings) throws Exception {
        // Validate URL for SSRF safety
        UrlValidationUtils.validateUrl(url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                .header("User-Agent", settings.getUserAgent())
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

        return new CrawledPage(url, title, response.body(), response.statusCode());
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

    private boolean shouldCrawl(String url, String startDomain, String startPathPrefix, RagIngestionSource.Scope scope) {
        try {
            URI uri = new URI(url);

            // Check scheme
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }

            // Check same domain
            if (scope.isSameDomainOnly()) {
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
            for (String pattern : scope.getExcludePatterns()) {
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
            URI uri = new URI(url);
            String normalized = uri.normalize().toString();
            // Remove trailing slash and fragment for comparison
            normalized = normalized.replaceAll("#$", "");
            normalized = normalized.replaceAll("/$", "");
            return normalized.toLowerCase();
        } catch (URISyntaxException e) {
            return url.toLowerCase();
        }
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

    // --- Records ---

    /**
     * Result of a crawl operation.
     */
    public record CrawlResult(List<CrawledPage> pages, List<CrawlError> errors, long durationMs) {
    }

    /**
     * A single crawled page.
     */
    public record CrawledPage(String url, String title, String html, int httpStatus) {
    }

    /**
     * An error that occurred during crawling.
     */
    public record CrawlError(String url, String error) {
    }

    // --- Helper classes ---

    private record UrlDepth(String url, int depth) {
    }
}
