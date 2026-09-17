/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a knowledge base gets its documents from, and on what terms.
 *
 * <p>
 * A source belongs to its {@link RagConfiguration} rather than being a resource
 * of its own. That is deliberate: the vector store is keyed by the knowledge
 * base, so a source that could exist independently of one would need to name
 * its target by a string, and the draft this replaces did exactly that and
 * keyed ingestion on the <em>source's</em> name while retrieval keyed on the
 * <em>knowledge base's</em>. Crawled content went into one table and queries
 * read another, and because no test performed a retrieval after an ingest,
 * nothing failed. Owning the source removes the possibility.
 *
 * <h2>Defaults are defaults, not traps</h2>
 * <p>
 * Every unset value falls back to something sensible. The draft's config
 * records threw on a missing {@code maxPages} or {@code maxDepth} — which is
 * precisely what Jackson supplies for an omitted field — so {@code {"scope":
 * {"pathPrefix": "/docs/"}}} was rejected outright while its own comment
 * claimed it "provides safe defaults". Values that are present but nonsensical
 * are still rejected, loudly, by {@link #validate}.
 */
public class IngestionSource {

    /** Only source type implemented today. */
    public static final String TYPE_WEB = "web";

    /** Stable identity within the knowledge base; generated when absent. */
    private String id;

    /** Operator-facing label. */
    private String name;

    /** A disabled source keeps its configuration and history but does not run. */
    private boolean enabled = true;

    private String type = TYPE_WEB;

    /** Populated when {@link #type} is {@link #TYPE_WEB}. */
    private WebSource web;

    private IngestionSettings settings;

    /**
     * Quartz-style cron for scheduled runs; null means the source only runs when
     * triggered by hand.
     */
    private String cron;

    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ingestion source needs a name");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Ingestion source '" + name + "' needs a type");
        }
        if (!TYPE_WEB.equals(type)) {
            throw new IllegalArgumentException(
                    "Unsupported ingestion source type '" + type + "' on source '" + name
                            + "'. Supported: " + TYPE_WEB);
        }
        if (web == null) {
            throw new IllegalArgumentException("Web ingestion source '" + name + "' needs a 'web' block");
        }
        web.validate(name);
        settings().validate(name);
    }

    /** Never null — an absent settings block means "all defaults". */
    public IngestionSettings settings() {
        return settings == null ? new IngestionSettings() : settings;
    }

    // --- Getters and Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public WebSource getWeb() {
        return web;
    }

    public void setWeb(WebSource web) {
        this.web = web;
    }

    public IngestionSettings getSettings() {
        return settings;
    }

    public void setSettings(IngestionSettings settings) {
        this.settings = settings;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    /** Crawl configuration for a {@link #TYPE_WEB} source. */
    public static class WebSource {

        private String startUrl;

        /** Stay on the seed's site. */
        private boolean sameSiteOnly = true;

        /**
         * Treat subdomains as part of the site. Off by default: a crawl seeded at a
         * docs site should not wander into every subdomain a company owns.
         */
        private boolean includeSubdomains;

        private String pathPrefix = "/";
        private Integer maxDepth = 3;
        private Integer maxPages = 200;
        private List<String> excludePatterns = new ArrayList<>();

        private Integer requestDelayMs = 500;
        private Integer timeoutSeconds = 15;
        private String userAgent;

        /**
         * Honour {@code robots.txt}. On by default — EDDI installations crawl sites
         * their operators do not own, on a schedule. Turn it off only for a site you
         * own.
         */
        private boolean respectRobots = true;

        void validate(String sourceName) {
            if (startUrl == null || startUrl.isBlank()) {
                throw new IllegalArgumentException("Ingestion source '" + sourceName + "' needs a startUrl");
            }
            if (!startUrl.startsWith("http://") && !startUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "startUrl of ingestion source '" + sourceName + "' must be http or https, got: " + startUrl);
            }
            requirePositiveAtMost(maxDepth, 20, "maxDepth", sourceName);
            requirePositiveAtMost(maxPages, 50_000, "maxPages", sourceName);
            requirePositiveAtMost(timeoutSeconds, 300, "timeoutSeconds", sourceName);
            if (requestDelayMs != null && (requestDelayMs < 0 || requestDelayMs > 60_000)) {
                throw new IllegalArgumentException("requestDelayMs of ingestion source '" + sourceName
                        + "' must be between 0 and 60000, got: " + requestDelayMs);
            }
        }

        private static void requirePositiveAtMost(Integer value, int ceiling, String field, String sourceName) {
            // Null is "unset" and gets the default; a value the operator actually typed
            // is held to the limits.
            if (value != null && (value <= 0 || value > ceiling)) {
                throw new IllegalArgumentException(field + " of ingestion source '" + sourceName
                        + "' must be between 1 and " + ceiling + ", got: " + value);
            }
        }

        public String getStartUrl() {
            return startUrl;
        }

        public void setStartUrl(String startUrl) {
            this.startUrl = startUrl;
        }

        public boolean isSameSiteOnly() {
            return sameSiteOnly;
        }

        public void setSameSiteOnly(boolean sameSiteOnly) {
            this.sameSiteOnly = sameSiteOnly;
        }

        public boolean isIncludeSubdomains() {
            return includeSubdomains;
        }

        public void setIncludeSubdomains(boolean includeSubdomains) {
            this.includeSubdomains = includeSubdomains;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
        }

        public Integer getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(Integer maxPages) {
            this.maxPages = maxPages;
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> excludePatterns) {
            this.excludePatterns = excludePatterns == null ? new ArrayList<>() : excludePatterns;
        }

        public Integer getRequestDelayMs() {
            return requestDelayMs;
        }

        public void setRequestDelayMs(Integer requestDelayMs) {
            this.requestDelayMs = requestDelayMs;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public boolean isRespectRobots() {
            return respectRobots;
        }

        public void setRespectRobots(boolean respectRobots) {
            this.respectRobots = respectRobots;
        }
    }

    /** Limits that apply to the ingestion itself rather than to the crawl. */
    public static class IngestionSettings {

        /** Characters kept per document after conversion to Markdown. */
        private Integer maxContentLength = 100_000;

        /**
         * How many consecutive completed runs may miss a document before it is
         * considered gone and its vectors removed. Two, not one: sites go down and
         * crawls hit their caps, and a hair trigger empties a knowledge base because a
         * site was briefly unreachable.
         */
        private Integer tombstoneAfterMissedRuns = 2;

        /**
         * Hard ceiling on embedded segments per run — the cost control. Segments rather
         * than dollars because it needs no pricing table to be exact; set
         * {@link #costPerThousandSegments} to have runs report a dollar figure too.
         */
        private Integer maxSegmentsPerRun = 20_000;

        /** Optional rate used to report a run's cost. */
        private Double costPerThousandSegments;

        /** Cap on a single response body. */
        private Long maxBytesPerPage = 5L * 1024 * 1024;

        /** Wall-clock ceiling for one run. */
        private Integer timeBudgetMinutes = 10;

        void validate(String sourceName) {
            if (maxContentLength != null && maxContentLength <= 0) {
                throw new IllegalArgumentException(
                        "maxContentLength of ingestion source '" + sourceName + "' must be positive");
            }
            if (tombstoneAfterMissedRuns != null && tombstoneAfterMissedRuns < 1) {
                throw new IllegalArgumentException("tombstoneAfterMissedRuns of ingestion source '" + sourceName
                        + "' must be at least 1 — a document missing from a single run is not a deleted document");
            }
            if (maxSegmentsPerRun != null && maxSegmentsPerRun <= 0) {
                throw new IllegalArgumentException(
                        "maxSegmentsPerRun of ingestion source '" + sourceName + "' must be positive");
            }
            if (timeBudgetMinutes != null && (timeBudgetMinutes <= 0 || timeBudgetMinutes > 24 * 60)) {
                throw new IllegalArgumentException("timeBudgetMinutes of ingestion source '" + sourceName
                        + "' must be between 1 and 1440");
            }
            if (costPerThousandSegments != null && costPerThousandSegments < 0) {
                throw new IllegalArgumentException(
                        "costPerThousandSegments of ingestion source '" + sourceName + "' must not be negative");
            }
        }

        public int maxContentLengthOrDefault() {
            return maxContentLength == null ? 100_000 : maxContentLength;
        }

        public int tombstoneAfterMissedRunsOrDefault() {
            return tombstoneAfterMissedRuns == null ? 2 : tombstoneAfterMissedRuns;
        }

        public int maxSegmentsPerRunOrDefault() {
            return maxSegmentsPerRun == null ? 20_000 : maxSegmentsPerRun;
        }

        public long maxBytesPerPageOrDefault() {
            return maxBytesPerPage == null ? 5L * 1024 * 1024 : maxBytesPerPage;
        }

        public int timeBudgetMinutesOrDefault() {
            return timeBudgetMinutes == null ? 10 : timeBudgetMinutes;
        }

        public Integer getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(Integer maxContentLength) {
            this.maxContentLength = maxContentLength;
        }

        public Integer getTombstoneAfterMissedRuns() {
            return tombstoneAfterMissedRuns;
        }

        public void setTombstoneAfterMissedRuns(Integer tombstoneAfterMissedRuns) {
            this.tombstoneAfterMissedRuns = tombstoneAfterMissedRuns;
        }

        public Integer getMaxSegmentsPerRun() {
            return maxSegmentsPerRun;
        }

        public void setMaxSegmentsPerRun(Integer maxSegmentsPerRun) {
            this.maxSegmentsPerRun = maxSegmentsPerRun;
        }

        public Double getCostPerThousandSegments() {
            return costPerThousandSegments;
        }

        public void setCostPerThousandSegments(Double costPerThousandSegments) {
            this.costPerThousandSegments = costPerThousandSegments;
        }

        public Long getMaxBytesPerPage() {
            return maxBytesPerPage;
        }

        public void setMaxBytesPerPage(Long maxBytesPerPage) {
            this.maxBytesPerPage = maxBytesPerPage;
        }

        public Integer getTimeBudgetMinutes() {
            return timeBudgetMinutes;
        }

        public void setTimeBudgetMinutes(Integer timeBudgetMinutes) {
            this.timeBudgetMinutes = timeBudgetMinutes;
        }
    }
}
