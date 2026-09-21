/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an MCP server's {@code WWW-Authenticate} challenge, and decides whether
 * anything in it may be acted on.
 * <p>
 * A 401 from an MCP server is an <em>auth challenge</em>, not a discovery
 * failure. Treating the two alike is why an authenticated server currently
 * trips the circuit breaker after three attempts and then reports "server down"
 * — an operator debugging that has no signal pointing at credentials at all.
 *
 * <h3>Everything here is attacker-influenced input</h3> The
 * {@code resource_metadata} URL arrives in a header from the very server we are
 * failing to authenticate against, and the document it points at names an
 * authorization server. So:
 * <ul>
 * <li>the metadata URL must share an origin with the MCP server that returned
 * it — a server may not redirect discovery to a host of its choosing;</li>
 * <li>the fetch itself goes through {@code SafeHttpClient} (the caller's job,
 * documented at the call site);</li>
 * <li>any authorization server it names must already be on the operator's
 * credential-endpoint allowlist. Discovery may <em>select</em> among
 * pre-approved servers; it may never <em>introduce</em> one.</li>
 * </ul>
 * Dynamic client registration (RFC 7591) is deliberately out of scope: an admin
 * registers the client once and stores the id and secret in a connection.
 */
public final class McpAuthChallengeParser {

    /**
     * {@code Bearer resource_metadata="https://…"} — RFC 9728 §5.1. Quoted and
     * unquoted forms both occur in the wild.
     */
    private static final Pattern RESOURCE_METADATA = Pattern.compile("(?i)resource_metadata\\s*=\\s*\"?([^\",\\s]+)\"?");

    private McpAuthChallengeParser() {
    }

    /**
     * Whether a failure looks like an authentication challenge rather than an
     * outage.
     */
    public static boolean isAuthChallenge(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    /**
     * Extracts the {@code resource_metadata} URL, but only when it shares an origin
     * with the server that issued the challenge.
     *
     * @param wwwAuthenticate
     *            the raw header value, or null
     * @param mcpServerUrl
     *            the server the challenge came from
     * @return a URL safe to fetch, or empty
     */
    public static Optional<String> resourceMetadataUrl(String wwwAuthenticate, String mcpServerUrl) {
        if (wwwAuthenticate == null || wwwAuthenticate.isBlank() || mcpServerUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = RESOURCE_METADATA.matcher(wwwAuthenticate);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String candidate = matcher.group(1);
        try {
            URI metadata = new URI(candidate);
            URI server = new URI(mcpServerUrl);
            if (!sameOrigin(metadata, server)) {
                // A server pointing discovery at another host is either misconfigured
                // or trying to make EDDI fetch a document of its choosing. Neither is
                // worth following.
                return Optional.empty();
            }
            return Optional.of(metadata.toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * RFC 9728 §3.3 — the metadata's {@code resource} must be the MCP server being
     * accessed.
     * <p>
     * Without this a compromised server could serve metadata describing somebody
     * else's resource and steer a token request at it. Compared by origin rather
     * than by exact string, since a server legitimately advertises its base URL
     * while the client addressed a path beneath it.
     */
    public static boolean describesResource(String advertisedResource, String mcpServerUrl) {
        if (advertisedResource == null || mcpServerUrl == null) {
            return false;
        }
        try {
            return sameOrigin(new URI(advertisedResource), new URI(mcpServerUrl));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        if (first.getScheme() == null || second.getScheme() == null || first.getHost() == null || second.getHost() == null) {
            return false;
        }
        return first.getScheme().toLowerCase(Locale.ROOT).equals(second.getScheme().toLowerCase(Locale.ROOT))
                && first.getHost().toLowerCase(Locale.ROOT).equals(second.getHost().toLowerCase(Locale.ROOT))
                && effectivePort(first) == effectivePort(second);
    }

    /**
     * The port a URI actually addresses, so {@code https://host} and
     * {@code https://host:443} are one origin rather than two.
     */
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
