/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A site's {@code robots.txt}, as it applies to one crawler.
 *
 * <p>
 * The draft this replaces did not fetch robots.txt at all. That is not a
 * missing nicety: EDDI installations crawl sites their operators do not own, on
 * a schedule, and a crawler that ignores robots gets the installation's traffic
 * blocked and its operator a complaint. Honouring it is also the only way
 * {@code Crawl-delay} — the rate a site actually asks for — ever reaches the
 * crawler.
 *
 * <h2>Parsing</h2>
 * <p>
 * Blank lines are ignored rather than ending a group — real files are full of
 * them, and orphaning a group's rules silently allows everything the site meant
 * to block. A group ends at the next {@code User-agent} line.
 *
 * <p>
 * Groups are matched by {@code User-agent}, most specific first: an exact
 * (case-insensitive) match on the configured agent token wins over {@code *}.
 * Within the chosen group, the longest matching {@code Allow} or
 * {@code Disallow} path wins, which is the rule every major crawler follows and
 * the one site owners write for. {@code Sitemap} directives are collected
 * regardless of group, as the specification says they are global.
 *
 * <p>
 * A site with no robots.txt, or one that cannot be fetched, yields
 * {@link #allowAll()} — absence means permission, and a 500 from a robots
 * endpoint must not silently halt an operator's ingestion.
 */
public final class RobotsPolicy {

    /** Guards against a pathological robots.txt; real ones are a few KB. */
    private static final int MAX_RULES = 2_000;

    private final List<Rule> rules;
    private final Duration crawlDelay;
    private final List<String> sitemaps;

    private RobotsPolicy(List<Rule> rules, Duration crawlDelay, List<String> sitemaps) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
        this.sitemaps = sitemaps;
    }

    /** The policy for a site that publishes no rules. */
    public static RobotsPolicy allowAll() {
        return new RobotsPolicy(List.of(), null, List.of());
    }

    /**
     * Parses robots.txt for one user agent.
     *
     * @param content
     *            the file's text; null or blank yields {@link #allowAll()}
     * @param userAgent
     *            the crawler's token, matched case-insensitively against
     *            {@code User-agent} lines
     */
    public static RobotsPolicy parse(String content, String userAgent) {
        if (content == null || content.isBlank()) {
            return allowAll();
        }
        String agent = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);

        List<String> sitemaps = new ArrayList<>();
        List<Rule> specificRules = new ArrayList<>();
        List<Rule> wildcardRules = new ArrayList<>();
        Duration specificDelay = null;
        Duration wildcardDelay = null;

        boolean inSpecificGroup = false;
        boolean inWildcardGroup = false;
        boolean sawSpecificGroup = false;
        // A blank line ends a group; consecutive User-agent lines share one body.
        boolean lastLineWasUserAgent = false;

        for (String rawLine : content.split("\\r?\\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                // Deliberately NOT treated as ending the group. Real robots.txt files
                // are full of stray blank lines between a User-agent and its rules,
                // and orphaning those rules silently allows everything the site meant
                // to block. A group ends at the next User-agent line instead.
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (field) {
                case "user-agent" -> {
                    // Consecutive User-agent lines share one rule body; a User-agent
                    // line after a rule line starts a new group.
                    if (!lastLineWasUserAgent) {
                        inSpecificGroup = false;
                        inWildcardGroup = false;
                    }
                    String declared = value.toLowerCase(Locale.ROOT);
                    if (!declared.isEmpty() && !agent.isEmpty() && matchesAgent(agent, declared)) {
                        inSpecificGroup = true;
                        sawSpecificGroup = true;
                    } else if ("*".equals(declared)) {
                        inWildcardGroup = true;
                    }
                    lastLineWasUserAgent = true;
                }
                case "disallow", "allow" -> {
                    lastLineWasUserAgent = false;
                    boolean allow = "allow".equals(field);
                    // "Disallow:" with an empty value means "nothing is disallowed".
                    if (!allow && value.isEmpty()) {
                        continue;
                    }
                    Rule rule = new Rule(value, allow);
                    if (inSpecificGroup && specificRules.size() < MAX_RULES) {
                        specificRules.add(rule);
                    } else if (inWildcardGroup && wildcardRules.size() < MAX_RULES) {
                        wildcardRules.add(rule);
                    }
                }
                case "crawl-delay" -> {
                    lastLineWasUserAgent = false;
                    Duration parsed = parseDelay(value);
                    if (parsed != null) {
                        if (inSpecificGroup) {
                            specificDelay = parsed;
                        } else if (inWildcardGroup) {
                            wildcardDelay = parsed;
                        }
                    }
                }
                case "sitemap" -> {
                    lastLineWasUserAgent = false;
                    if (!value.isEmpty()) {
                        sitemaps.add(value);
                    }
                }
                default -> lastLineWasUserAgent = false;
            }
        }

        // A group naming this crawler replaces the wildcard group entirely — it does
        // not merge with it. Whether one exists is tracked by its declaration, not
        // derived from its rules: "Disallow:" with nothing after it is how a site
        // grants one crawler full access, and that group has no rules at all.
        boolean hasSpecific = sawSpecificGroup;
        return new RobotsPolicy(
                hasSpecific ? specificRules : wildcardRules,
                hasSpecific ? specificDelay : wildcardDelay,
                List.copyOf(sitemaps));
    }

    /**
     * Whether the crawler may fetch a path. Longest matching rule wins; an
     * {@code Allow} beats a {@code Disallow} of the same length, which is how a
     * site carves an exception out of a broad block.
     */
    public boolean isAllowed(String path) {
        String candidate = path == null || path.isEmpty() ? "/" : path;

        Rule best = null;
        for (Rule rule : rules) {
            if (rule.matches(candidate)
                    && (best == null || rule.specificity() > best.specificity()
                            || (rule.specificity() == best.specificity() && rule.allow()))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** The rate the site asks for, if it asks. */
    public Optional<Duration> crawlDelay() {
        return Optional.ofNullable(crawlDelay);
    }

    /** Sitemap URLs the site advertises — the cheapest way to discover pages. */
    public List<String> sitemaps() {
        return sitemaps;
    }

    /**
     * Whether a {@code User-agent} line names this crawler.
     *
     * <p>
     * Matched against the product token — the part before any {@code /} or space —
     * rather than by substring. Substring matching meant a robots.txt containing
     * {@code User-agent: a} captured every crawler whose name contains an "a",
     * which is all of them.
     */
    private static boolean matchesAgent(String ourAgent, String declared) {
        String ourToken = productToken(ourAgent);
        String declaredToken = productToken(declared);
        return ourToken.equals(declaredToken) || ourAgent.startsWith(declaredToken + "/");
    }

    private static String productToken(String userAgent) {
        int slash = userAgent.indexOf('/');
        int space = userAgent.indexOf(' ');
        int end = slash < 0 ? space : space < 0 ? slash : Math.min(slash, space);
        return (end < 0 ? userAgent : userAgent.substring(0, end)).trim();
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static Duration parseDelay(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (seconds < 0 || seconds > 300) {
                // A delay beyond a few minutes would stall a scheduled run past any
                // sane budget; treat it as unusable rather than obeying it blindly.
                return null;
            }
            return Duration.ofMillis((long) (seconds * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One {@code Allow}/{@code Disallow} line. Supports the {@code *} and {@code $}
     * extensions that every major crawler honours.
     */
    private record Rule(String pathPattern, boolean allow) {

        boolean matches(String path) {
            String pattern = pathPattern;
            boolean anchoredEnd = pattern.endsWith("$");
            if (anchoredEnd) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.indexOf('*') < 0) {
                return anchoredEnd ? path.equals(pattern) : path.startsWith(pattern);
            }
            return wildcardMatches(path, pattern, anchoredEnd);
        }

        /**
         * Greedy segment walk rather than a compiled regex: robots patterns come from a
         * third party, and building a regex per rule per URL is both slower and a
         * backtracking risk.
         */
        private static boolean wildcardMatches(String path, String pattern, boolean anchoredEnd) {
            String[] parts = pattern.split("\\*", -1);
            int cursor = 0;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) {
                    continue;
                }
                if (i == 0) {
                    if (!path.startsWith(part)) {
                        return false;
                    }
                    cursor = part.length();
                    continue;
                }
                int found = path.indexOf(part, cursor);
                if (found < 0) {
                    return false;
                }
                cursor = found + part.length();
            }
            if (anchoredEnd) {
                String last = parts[parts.length - 1];
                return last.isEmpty() ? true : path.endsWith(last);
            }
            return true;
        }

        /** Longer rules are more specific, per the de-facto standard. */
        int specificity() {
            return pathPattern.length();
        }
    }
}
