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
public class RagIngestionSource {

    /** Display name for this ingestion source */
    private String name;

    /** Human-readable description */
    private String description;

    /**
     * Entry point URL for the crawler. The crawler fetches this page first, then
     * extracts TOC links from it using the {@code tocSelector} CSS selector.
     */
    private String startUrl;

    /**
     * CSS selector for table-of-contents links on the start page. Example:
     * {@code "nav.sidebar a[href]"} or {@code "#toc a"}.
     */
    private String tocSelector;

    /** Crawl scope constraints */
    private Scope scope = new Scope();

    /** Crawl behavior settings */
    private CrawlSettings crawlSettings = new CrawlSettings();

    /**
     * URI pointing to the {@link ai.labs.eddi.configs.rag.model.RagConfiguration}
     * that defines the embedding model and vector store for this source.
     * <p>
     * Format: {@code eddi://ai.labs.rag/ragstore/rags/{id}?version=1}
     */
    private String ragConfigUri;

    /** Ingestion pipeline settings */
    private IngestionSettings ingestionSettings = new IngestionSettings();

    /** Schedule configuration */
    private Schedule schedule = new Schedule();

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStartUrl() {
        return startUrl;
    }

    public void setStartUrl(String startUrl) {
        this.startUrl = startUrl;
    }

    public String getTocSelector() {
        return tocSelector;
    }

    public void setTocSelector(String tocSelector) {
        this.tocSelector = tocSelector;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public CrawlSettings getCrawlSettings() {
        return crawlSettings;
    }

    public void setCrawlSettings(CrawlSettings crawlSettings) {
        this.crawlSettings = crawlSettings;
    }

    public String getRagConfigUri() {
        return ragConfigUri;
    }

    public void setRagConfigUri(String ragConfigUri) {
        this.ragConfigUri = ragConfigUri;
    }

    public IngestionSettings getIngestionSettings() {
        return ingestionSettings;
    }

    public void setIngestionSettings(IngestionSettings ingestionSettings) {
        this.ingestionSettings = ingestionSettings;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    // --- Inner classes ---

    /**
     * Scope constraints for the web crawler. Controls how far and where the crawler
     * will follow links.
     */
    public static class Scope {

        /** Only follow links on the same domain as startUrl (default: true) */
        private boolean sameDomainOnly = true;

        /** Only follow links under this path prefix (default: "/") */
        private String pathPrefix = "/";

        /** Maximum BFS depth from startUrl (default: 3) */
        private int maxDepth = 3;

        /** Stop crawling after this many pages (default: 200) */
        private int maxPages = 200;

        /**
         * Glob patterns for URLs to skip. Example: [star]/api/[star], [star]/changelog.
         */
        private List<String> excludePatterns = new ArrayList<>();

        public boolean isSameDomainOnly() {
            return sameDomainOnly;
        }

        public void setSameDomainOnly(boolean sameDomainOnly) {
            this.sameDomainOnly = sameDomainOnly;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public int getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> excludePatterns) {
            this.excludePatterns = excludePatterns;
        }
    }

    /**
     * Crawl behavior settings — request timing, timeouts, and user agent.
     */
    public static class CrawlSettings {

        /** Delay between HTTP requests in milliseconds (politeness, default: 500) */
        private int requestDelayMs = 500;

        /** HTTP request timeout in seconds (default: 15) */
        private int timeoutSeconds = 15;

        /** User-Agent header value (default: "EDDI-Crawler/1.0") */
        private String userAgent = "EDDI-Crawler/1.0";

        public int getRequestDelayMs() {
            return requestDelayMs;
        }

        public void setRequestDelayMs(int requestDelayMs) {
            this.requestDelayMs = requestDelayMs;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    /**
     * Ingestion pipeline settings — chunking, dedup, and content limits.
     */
    public static class IngestionSettings {

        /** Chunking strategy: "recursive" (default), "paragraph", "sentence" */
        private String chunkStrategy = "recursive";

        /** Chunk size in characters (default: 512) */
        private int chunkSize = 512;

        /** Overlap characters between chunks (default: 64) */
        private int chunkOverlap = 64;

        /** Whether to use content-hash deduplication (default: true) */
        private boolean contentHashDedup = true;

        /** Max content length in characters per page (default: 100000) */
        private int maxContentLength = 100_000;

        public String getChunkStrategy() {
            return chunkStrategy;
        }

        public void setChunkStrategy(String chunkStrategy) {
            this.chunkStrategy = chunkStrategy;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public boolean isContentHashDedup() {
            return contentHashDedup;
        }

        public void setContentHashDedup(boolean contentHashDedup) {
            this.contentHashDedup = contentHashDedup;
        }

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }
    }

    /**
     * Schedule configuration for periodic ingestion.
     */
    public static class Schedule {

        /** Cron expression (default: daily at 2 AM) */
        private String cronExpression = "0 2 * * *";

        /** Whether the schedule is active (default: true) */
        private boolean enabled = true;

        public String getCronExpression() {
            return cronExpression;
        }

        public void setCronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
