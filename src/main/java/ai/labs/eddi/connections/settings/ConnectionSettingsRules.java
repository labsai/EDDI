/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import io.quarkus.runtime.LaunchMode;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The shape rules for connection settings, shared by the three places a value
 * can come from: a pinned property checked at boot, a write through
 * {@code PUT /connectionstore/settings}, and a stored value read back at
 * request time.
 * <p>
 * One implementation, because two copies of "what counts as a bare https
 * origin" drift, and the drift surfaces as a redirect URI the provider refuses
 * to match — a user-facing OAuth error with no config problem named anywhere.
 */
public final class ConnectionSettingsRules {

    private ConnectionSettingsRules() {
    }

    /**
     * Returns the trimmed base URL, or refuses one a provider would not match.
     * <p>
     * Parsed rather than prefix-matched: {@code startsWith("https://")} accepts a
     * path, a query, a fragment, userinfo and a malformed authority, each of which
     * produces a redirect URI the provider will not match. Plain http is accepted
     * only for a loopback host while developing or testing.
     *
     * @param label
     *            names the setting in the error, so it says what to fix
     * @throws IllegalArgumentException
     *             naming {@code label} and quoting the value
     */
    public static String requireBarePublicOrigin(String value, String label, boolean devOrTest) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " is empty. It becomes the OAuth redirect_uri, which the provider matches exactly, "
                    + "so it cannot be inferred from an inbound request.");
        }
        URI base;
        try {
            base = new URI(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " is not a valid URL: " + trimmed, e);
        }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        boolean bareOrigin = base.getUserInfo() == null && base.getQuery() == null && base.getFragment() == null
                && (base.getPath() == null || base.getPath().isEmpty() || "/".equals(base.getPath())) && base.getHost() != null;
        boolean loopbackHttpWhileDeveloping = devOrTest && "http".equals(scheme) && base.getHost() != null
                && ConnectionConfiguration.isLoopbackHost(base.getHost());
        if (!bareOrigin || !("https".equals(scheme) || loopbackHttpWhileDeveloping)) {
            throw new IllegalArgumentException(label + " must be a bare https origin (scheme://host[:port])"
                    + (devOrTest ? ", or http://localhost[:port] / http://127.0.0.1[:port] while developing or testing" : "") + " — got: "
                    + trimmed);
        }
        return trimmed;
    }

    /**
     * Canonicalises and de-duplicates origins, skipping blank entries.
     *
     * @throws IllegalArgumentException
     *             for the first entry that is not a bare origin
     */
    public static List<String> canonicalOrigins(Collection<String> origins, String label) {
        var canonical = new LinkedHashSet<String>();
        if (origins != null) {
            for (String origin : origins) {
                if (origin != null && !origin.isBlank()) {
                    canonical.add(ConnectionConfiguration.requireCanonicalOrigin(origin.trim(), label));
                }
            }
        }
        return List.copyOf(canonical);
    }

    /** Whether the relaxed dev/test rules apply. */
    public static boolean isDevOrTest() {
        LaunchMode mode = LaunchMode.current();
        return mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST;
    }
}
