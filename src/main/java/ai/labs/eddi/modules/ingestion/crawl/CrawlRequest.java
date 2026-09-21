/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.time.Duration;
import java.util.List;

/**
 * What to crawl, how far, and under what limits.
 *
 * <p>
 * Every bound has a default, and the defaults are the ones an operator who
 * thinks about none of this should get: a crawl that finishes, stays on the
 * site it was pointed at, and does not hammer it.
 *
 * @param seedUrl
 *            where the crawl starts
 */
public record CrawlRequest(String seedUrl, Scope scope, Limits limits, Politeness politeness) {

    public CrawlRequest {
        if (seedUrl == null || seedUrl.isBlank()) {
            throw new IllegalArgumentException("seedUrl must not be null or blank");
        }
        scope = scope == null ? Scope.defaults() : scope;
        limits = limits == null ? Limits.defaults() : limits;
        politeness = politeness == null ? Politeness.defaults() : politeness;
    }

    /** Convenience for a crawl with every default. */
    public static CrawlRequest of(String seedUrl) {
        return new CrawlRequest(seedUrl, Scope.defaults(), Limits.defaults(), Politeness.defaults());
    }

    /**
     * Where the crawler may go.
     *
     * @param includeSubdomains
     *            off by default — a crawl seeded at a docs site should not wander
     *            into every subdomain the company owns
     * @param excludePatterns
     *            globs matched against the URL <em>path</em>; see
     *            {@link UrlPattern}
     */
    public record Scope(
            boolean sameSiteOnly,
            boolean includeSubdomains,
            String pathPrefix,
            int maxDepth,
            List<String> excludePatterns) {

        public Scope {
            pathPrefix = CrawlUrls.normalizePathPrefix(pathPrefix);
            maxDepth = maxDepth > 0 ? maxDepth : 3;
            excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
        }

        public static Scope defaults() {
            return new Scope(true, false, "/", 3, List.of());
        }
    }

    /**
     * What stops the crawl.
     *
     * @param maxPages
     *            pages handed to the sink
     * @param maxFetchAttempts
     *            requests made, successful or not. Separate from {@code maxPages}
     *            because a page-only cap bounds nothing: a documentation site
     *            linking 5,000 assets would fetch all 5,000 while emitting almost
     *            none, which is exactly what the draft did.
     * @param maxBytesPerPage
     *            cap on a single response body, so one oversized file cannot
     *            exhaust the heap
     * @param maxTotalBytes
     *            cap on the whole crawl's download
     * @param timeBudget
     *            wall-clock ceiling. A scheduled run has a lease; a crawl that
     *            outlives it is cancelled mid-flight and its partial results are
     *            worse than a clean stop.
     */
    public record Limits(
            int maxPages,
            int maxFetchAttempts,
            long maxBytesPerPage,
            long maxTotalBytes,
            Duration timeBudget,
            Duration requestTimeout) {

        public Limits {
            maxPages = maxPages > 0 ? maxPages : 200;
            maxFetchAttempts = maxFetchAttempts > 0 ? maxFetchAttempts : Math.max(maxPages * 3, maxPages);
            maxBytesPerPage = maxBytesPerPage > 0 ? maxBytesPerPage : 5L * 1024 * 1024;
            maxTotalBytes = maxTotalBytes > 0 ? maxTotalBytes : 512L * 1024 * 1024;
            timeBudget = timeBudget == null || timeBudget.isZero() || timeBudget.isNegative()
                    ? Duration.ofMinutes(10)
                    : timeBudget;
            requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                    ? Duration.ofSeconds(15)
                    : requestTimeout;
        }

        public static Limits defaults() {
            return new Limits(200, 600, 5L * 1024 * 1024, 512L * 1024 * 1024,
                    Duration.ofMinutes(10), Duration.ofSeconds(15));
        }
    }

    /**
     * How the crawler behaves towards the site.
     *
     * @param respectRobots
     *            on by default. EDDI installations crawl sites their operators do
     *            not own, on a schedule; ignoring robots.txt gets the installation
     *            blocked and its operator a complaint. Turning it off is for a site
     *            you own.
     */
    public record Politeness(Duration requestDelay, String userAgent, boolean respectRobots) {

        public static final String DEFAULT_USER_AGENT = "EDDI-Crawler/1.0 (+https://eddi.labs.ai)";

        public Politeness {
            requestDelay = requestDelay == null || requestDelay.isNegative() ? Duration.ofMillis(500) : requestDelay;
            userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
        }

        public static Politeness defaults() {
            return new Politeness(Duration.ofMillis(500), DEFAULT_USER_AGENT, true);
        }
    }
}
