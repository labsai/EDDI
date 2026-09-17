/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * URL canonicalization for crawling.
 *
 * <p>
 * The identity of a page decides two things: whether the crawler has already
 * visited it, and which knowledge-base document its content updates. Both go
 * wrong quietly when normalization is careless, and in opposite directions —
 * normalize too little and one page is ingested three times under
 * {@code ?utm_source} variants; too much and two genuinely different pages
 * collapse into one and the second is never crawled.
 *
 * <p>
 * The specific trap here: <b>paths are case-sensitive, hosts are not.</b> The
 * draft this replaces lowercased the entire URL, so {@code /Docs/Guide} and
 * {@code /docs/guide} became one entry and whichever came second was silently
 * skipped.
 */
public final class CrawlUrls {

    /**
     * Tracking parameters that never change what a page says. Stripped so a page
     * linked from a campaign is not ingested a second time under a different query
     * string.
     */
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "ref", "referrer", "_ga");

    /** Index documents that address the same resource as their directory. */
    private static final Set<String> INDEX_FILENAMES = Set.of("index.html", "index.htm", "index.php", "default.html");

    private CrawlUrls() {
    }

    /**
     * Canonical form used for visited-set membership and as a document id.
     *
     * <p>
     * Lowercases only scheme and host, drops the fragment (never sent to a server)
     * and any default port, strips tracking parameters, folds an index filename
     * into its directory, and removes a trailing slash. Returns the input trimmed
     * if it cannot be parsed, so an odd URL is still comparable with itself.
     */
    public static String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = stripFragment(url.trim());
        try {
            URI uri = new URI(trimmed).normalize();
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null) {
                return trimmed;
            }

            int port = uri.getPort();
            if (isDefaultPort(scheme, port)) {
                port = -1;
            }

            String path = foldIndexFile(uri.getRawPath() == null ? "" : uri.getRawPath());
            // The root is stripped too, so "https://host/" and "https://host" are one
            // document rather than two copies of a site's front page.
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            String query = stripTrackingParameters(uri.getRawQuery());

            StringBuilder canonical = new StringBuilder(scheme).append("://").append(host);
            if (port != -1) {
                canonical.append(':').append(port);
            }
            canonical.append(path);
            if (query != null && !query.isEmpty()) {
                canonical.append('?').append(query);
            }
            return canonical.toString();
        } catch (URISyntaxException e) {
            return trimmed;
        }
    }

    /** Everything before the {@code #}; fragments are client-side only. */
    public static String stripFragment(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    /** Lowercase host of a URL, or empty when it has none. */
    public static Optional<String> host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? Optional.empty() : Optional.of(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Path of a URL, defaulting to {@code /}. */
    public static String path(String url) {
        try {
            URI uri = new URI(url);
            // Without a host there is no site-relative path to reason about; a bare
            // string would otherwise be read as a relative path and pass scope checks.
            if (uri.getHost() == null) {
                return "/";
            }
            String path = uri.getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (URISyntaxException | NullPointerException e) {
            return "/";
        }
    }

    /** Whether the scheme is one the crawler will fetch. */
    public static boolean isHttpScheme(String url) {
        try {
            String scheme = new URI(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException | NullPointerException e) {
            return false;
        }
    }

    /**
     * Whether {@code candidate} belongs to {@code seed}'s site.
     *
     * @param includeSubdomains
     *            when true, {@code docs.example.com} counts as part of
     *            {@code example.com}. Off by default: a crawl seeded at a docs site
     *            should not wander into every subdomain a company owns.
     */
    public static boolean isSameSite(String candidateHost, String seedHost, boolean includeSubdomains) {
        if (candidateHost == null || seedHost == null) {
            return false;
        }
        String candidate = candidateHost.toLowerCase(Locale.ROOT);
        String seed = seedHost.toLowerCase(Locale.ROOT);
        if (candidate.equals(seed)) {
            return true;
        }
        if (!includeSubdomains) {
            // A very common real-world case that exact matching gets wrong, so it is
            // handled explicitly rather than left to surprise the operator.
            return candidate.equals(stripWww(seed)) || stripWww(candidate).equals(seed);
        }
        return candidate.endsWith("." + stripWww(seed)) || stripWww(candidate).equals(stripWww(seed));
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** Normalizes a path prefix to a leading and trailing slash. */
    public static String normalizePathPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "/";
        }
        String normalized = prefix.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    /**
     * Whether a path lies under a prefix. The prefix {@code /docs/} matches
     * {@code /docs} itself as well as everything beneath it — an operator who
     * scopes a crawl to a section means the section's own landing page too.
     */
    public static boolean isUnderPathPrefix(String path, String normalizedPrefix) {
        if ("/".equals(normalizedPrefix)) {
            return true;
        }
        String candidate = path == null || path.isEmpty() ? "/" : path;
        if (candidate.startsWith(normalizedPrefix)) {
            return true;
        }
        return (candidate + "/").equals(normalizedPrefix);
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    private static String foldIndexFile(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return path;
        }
        String filename = path.substring(lastSlash + 1).toLowerCase(Locale.ROOT);
        return INDEX_FILENAMES.contains(filename) ? path.substring(0, lastSlash + 1) : path;
    }

    /**
     * Drops tracking parameters and sorts the rest, so two links to the same page
     * that differ only in parameter order are one document rather than two.
     */
    private static String stripTrackingParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (!TRACKING_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                kept.add(pair);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }
}
