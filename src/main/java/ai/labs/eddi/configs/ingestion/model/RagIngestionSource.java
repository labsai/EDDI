/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a RAG ingestion source — defines a starting URL, a CSS
 * selector for table-of-contents links, scope constraints for the web crawler,
 * and references an existing
 * {@link ai.labs.eddi.configs.rag.model.RagConfiguration} for embedding +
 * storage.
 *
 * <p>
 * Stored as a versioned MongoDB document (like {@code RagConfiguration}).
 * Managed via REST API at {@code /ragstore/ingestion-sources/}.
 * </p>
 */
public record RagIngestionSource(
        /** Display name for this ingestion source */
        String name,

        /** Human-readable description */
        String description,

        /**
         * Entry point URL for the crawler. The crawler fetches this page first, then
         * extracts TOC links from it using the {@code tocSelector} CSS selector.
         */
        String startUrl,

        /**
         * CSS selector for table-of-contents links on the start page. Example:
         * {@code "nav.sidebar a[href]"} or {@code "#toc a"}.
         */
        String tocSelector,

        /** Crawl scope constraints */
        Scope scope,

        /** Crawl behavior settings */
        CrawlSettings crawlSettings,

        /**
         * URI pointing to the {@link ai.labs.eddi.configs.rag.model.RagConfiguration}
         * that defines the embedding model and vector store for this source.
         * <p>
         * Format: {@code eddi://ai.labs.rag/ragstore/rags/{id}?version=1}
         */
        String ragConfigUri,

        /** Ingestion pipeline settings */
        IngestionSettings ingestionSettings,

        /** Schedule configuration */
        Schedule schedule) {

    /**
     * Compact constructor to provide default values for nullable fields.
     */
    public RagIngestionSource {
        if (scope == null) {
            scope = new Scope();
        }
        if (crawlSettings == null) {
            crawlSettings = new CrawlSettings();
        }
        if (ingestionSettings == null) {
            ingestionSettings = new IngestionSettings();
        }
        if (schedule == null) {
            schedule = new Schedule();
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
             * Glob patterns for URLs to skip. Example: [star]/api/[star], [star]/changelog.
             */
            List<String> excludePatterns) {

        /**
         * Default constructor providing standard defaults.
         */
        public Scope {
            // Empty body - used to normalize nulls to defaults
        }

        /**
         * No-arg constructor for Jackson deserialization.
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
         * No-arg constructor for Jackson deserialization.
         */
        public CrawlSettings() {
            this(500, 15, "EDDI-Crawler/1.0");
        }
    }

    /**
     * Ingestion pipeline settings — chunking, dedup, and content limits.
     */
    public record IngestionSettings(
            /** Chunking strategy: "recursive" (default), "paragraph", "sentence" */
            String chunkStrategy,

            /** Chunk size in characters (default: 512) */
            int chunkSize,

            /** Overlap characters between chunks (default: 64) */
            int chunkOverlap,

            /** Whether to use content-hash deduplication (default: true) */
            boolean contentHashDedup,

            /** Max content length in characters per page (default: 100000) */
            int maxContentLength) {

        /**
         * No-arg constructor for Jackson deserialization.
         */
        public IngestionSettings() {
            this("recursive", 512, 64, true, 100_000);
        }
    }

    /**
     * Schedule configuration for periodic ingestion.
     */
    public record Schedule(
            /** Cron expression (default: daily at 2 AM) */
            String cronExpression,

            /** Whether the schedule is active (default: true) */
            boolean enabled) {

        /**
         * No-arg constructor for Jackson deserialization.
         */
        public Schedule() {
            this("0 2 * * *", true);
        }
    }
}
