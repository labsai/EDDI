/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for web-based ingestion sources.
 * <p>
 * Defines how to crawl a website: starting URL, TOC extraction selector, crawl
 * scope constraints, and HTTP request behavior.
 *
 * @param startUrl
 *            Entry point URL for the crawler
 * @param tocSelector
 *            CSS selector for TOC links (e.g., "nav.sidebar a[href]")
 * @param scope
 *            Crawl scope constraints (domain, depth, page limits)
 * @param crawlSettings
 *            HTTP request behavior (delay, timeout, user agent)
 *
 * @since 6.0.3
 */
public record WebSourceConfig(
        String startUrl,
        String tocSelector,
        Scope scope,
        CrawlSettings crawlSettings) implements SourceConfig {

    /**
     * Compact constructor to provide default values for nullable nested configs.
     */
    public WebSourceConfig {
        if (scope == null) {
            scope = new Scope();
        }
        if (crawlSettings == null) {
            crawlSettings = new CrawlSettings();
        }
    }

    /**
     * Scope constraints for the web crawler. Controls how far and where the crawler
     * will follow links.
     */
    public record Scope(
            /** Only follow links on the same domain as startUrl (default: true) */
            boolean sameDomainOnly,

            /** Only follow links under this path prefix (default: "/") */
            String pathPrefix,

            /** Maximum BFS depth from startUrl (default: 3) */
            int maxDepth,

            /** Stop crawling after this many pages (default: 200) */
            int maxPages,

            /**
             * Glob patterns for URLs to skip. Example: **&#47;api&#47;**, **&#47;changelog.
             */
            List<String> excludePatterns) {

        /**
         * No-arg constructor for Jackson deserialization with defaults.
         */
        public Scope() {
            this(true, "/", 3, 200, new ArrayList<>());
        }
    }

    /**
     * Crawl behavior settings — request timing, timeouts, and user agent.
     */
    public record CrawlSettings(
            /** Delay between HTTP requests in milliseconds (politeness, default: 500) */
            int requestDelayMs,

            /** HTTP request timeout in seconds (default: 15) */
            int timeoutSeconds,

            /** User-Agent header value (default: "EDDI-Crawler/1.0") */
            String userAgent) {

        /**
         * No-arg constructor for Jackson deserialization with defaults.
         */
        public CrawlSettings() {
            this(500, 15, "EDDI-Crawler/1.0");
        }
    }
}
