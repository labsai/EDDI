/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.Locale;
import java.util.Set;

/**
 * The one answer to "does this configuration parameter name an endpoint?".
 * <p>
 * Three places ask it, and each used to keep its own list: the LLM registry
 * (which checks a vault secret's {@code allowedHosts} against every endpoint
 * the configuration sets), the embedding parameters (which refuse an endpoint
 * the provider would ignore), and the export merge (which keeps a hidden
 * credential out of a config whose destination changed). The lists had already
 * drifted — Vertex's {@code apiEndpoint} was an endpoint to the registry and
 * not to the merge, {@code apiBase} only to the embedding check — and a key one
 * of them misses is a key through which a credential can follow an
 * editor-chosen host. A builder that starts reading a new endpoint key adds it
 * here, and all three learn it.
 * <p>
 * Compared case-insensitively ({@code baseURL} is {@code baseUrl}). Any key
 * ending in {@code url} counts, which covers {@code baseUrl},
 * {@code serverUrl}, {@code base_url} and {@code targetServerUrl} alike.
 */
public final class EndpointParameters {

    /** Endpoint keys that do not end in {@code url}, lower-cased. */
    private static final Set<String> NAMES = Set.of("uri", "endpoint", "apiendpoint", "host", "hostname", "apibase", "api_base");

    private EndpointParameters() {
    }

    /** Whether a parameter of this name says where requests are sent. */
    public static boolean isEndpointKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.endsWith("url") || NAMES.contains(normalized);
    }
}
