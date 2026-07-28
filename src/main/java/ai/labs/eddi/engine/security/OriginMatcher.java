/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import java.net.URI;
import java.util.Locale;

/**
 * Origin comparison for caller-token forwarding.
 * <p>
 * Two origins match when scheme, host and effective port are equal, with the
 * default port for the scheme treated as equal to the explicit one — so
 * {@code https://eddi.example} and {@code https://eddi.example:443} are the
 * same origin. Host comparison is case-insensitive; scheme and host are the
 * only things compared, never the path.
 *
 * @author ginccc
 */
public final class OriginMatcher {

    private OriginMatcher() {
    }

    /** Build a normalized {@code scheme://host[:port]} string. */
    public static String normalize(String scheme, String host, int port) {
        if (scheme == null || host == null || host.isBlank()) {
            return null;
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        int effectivePort = effectivePort(lowerScheme, port);
        if (effectivePort == -1) {
            return null;
        }
        return lowerScheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + effectivePort;
    }

    /** Normalize the origin of a URI, ignoring its path and query. */
    public static String normalize(URI uri) {
        if (uri == null) {
            return null;
        }
        return normalize(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    /**
     * Whether a target URI addresses the same origin the caller came in on.
     * <p>
     * Returns {@code false} when either side is unknown: an unresolvable origin
     * must never be treated as a match, or the token could be forwarded to an
     * arbitrary host.
     */
    public static boolean sameOrigin(String callerOrigin, URI target) {
        if (callerOrigin == null) {
            return false;
        }
        String targetOrigin = normalize(target);
        return targetOrigin != null && targetOrigin.equals(callerOrigin);
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }
}
