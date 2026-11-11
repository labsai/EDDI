/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether an edited configuration object sends its requests to a
 * different place than the object it replaces.
 * <p>
 * A literal credential an administrator stored was authorised for the
 * destination it sat next to. Editors cannot read it back, which is the whole
 * point of hiding it — so any path that keeps a hidden literal in place while
 * the fields around it change must ask this first, or it binds the hidden key
 * to a host the editor chose and the next turn sends it there. Two paths do:
 * <ul>
 * <li>agent upgrade and sync, which restore the target's own value into an
 * exported {@code ${vault:REDACTED}} placeholder
 * ({@code backup.impl.ScrubbedSecrets});</li>
 * <li>JSON Patch by a caller whose reads are redacted, which keeps every hidden
 * literal the patch does not touch ({@code ConfigJsonPatcher}).</li>
 * </ul>
 * One rule for both, so a field that counts as a destination on one path cannot
 * be overlooked on the other.
 */
public final class CredentialDestinations {

    private CredentialDestinations() {
    }

    /**
     * {@link #changedDestinationField} as a yes/no answer.
     */
    public static boolean changesDestination(Map<?, ?> sourceMap, Map<?, ?> targetMap, Predicate<String> boundToTarget) {
        return changedDestinationField(sourceMap, targetMap, boundToTarget) != null;
    }

    /**
     * The first field naming a destination whose value differs between the two
     * objects, or {@code null} when they send to the same place.
     * <p>
     * A destination field is one named like a URL ({@code targetServerUrl},
     * {@code baseUrl}, {@code url}, {@code uri}, {@code endpoint}, {@code host},
     * {@code hostname}), a {@code path} that is an absolute URL on either side, or
     * one that picks the vendor or the connection ({@code type}, a
     * {@code provider}, {@code port}, {@code useTls}): a key kept beside a changed
     * provider {@code type} is sent to that provider's default endpoint.
     * <p>
     * The fields of both sides are compared, not only the source's: dropping the
     * target's {@code baseUrl} sends to the provider's default endpoint instead,
     * which is a change of destination too. A URL is compared by its origin
     * ({@code scheme://host:port}), so a different path on the same host is the
     * same destination. A destination that is not a string — a number where a host
     * belongs — is the same only when it is equal on both sides.
     *
     * @param boundToTarget
     *            a source value the caller will itself replace by the target's —
     *            such a field does not count as a change. {@code v -> false} when
     *            nothing is.
     */
    public static String changedDestinationField(Map<?, ?> sourceMap, Map<?, ?> targetMap, Predicate<String> boundToTarget) {
        Set<String> keys = new LinkedHashSet<>();
        sourceMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
        targetMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
        for (String key : keys) {
            Object sourceValue = sourceMap.get(key);
            Object targetValue = targetMap.get(key);
            if (sourceValue instanceof String bound && boundToTarget.test(bound)) {
                continue;
            }
            String sourceText = sourceValue instanceof String text ? text : null;
            String targetText = targetValue instanceof String text ? text : null;
            if (!isDestinationField(key, sourceText, targetText) || (sourceValue == null && targetValue == null)) {
                continue;
            }
            if (sourceText == null || targetText == null) {
                if (!Objects.equals(sourceValue, targetValue)) {
                    return key;
                }
                continue;
            }
            if (!Objects.equals(destinationOf(sourceText), destinationOf(targetText))) {
                return key;
            }
        }
        return null;
    }

    /**
     * Whether a field of this name (holding these values on either side) decides
     * where a request goes.
     */
    public static boolean isDestinationField(String key, String sourceValue, String targetValue) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("url") || normalized.equals("uri") || normalized.equals("endpoint")
                || normalized.equals("host") || normalized.equals("hostname")) {
            return true;
        }
        // The vendor and the connection: the same key and host under another
        // provider type, port or TLS setting is a different server.
        if (normalized.equals("type") || normalized.endsWith("provider") || normalized.equals("port") || normalized.equals("usetls")) {
            return true;
        }
        // A relative path stays on the host its targetServerUrl names, which is
        // compared on its own; an absolute one is a destination in itself.
        return normalized.equals("path") && (isAbsolute(sourceValue) || isAbsolute(targetValue));
    }

    /**
     * Where a value sends a request: the origin ({@code scheme://host:port}) of an
     * absolute URL — a different path on the same origin is the same destination —
     * and otherwise the value itself.
     */
    public static String destinationOf(String value) {
        String trimmed = value.trim();
        if (isAbsolute(trimmed)) {
            try {
                URI uri = URI.create(trimmed);
                if (uri.getHost() != null) {
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + effectivePort(scheme, uri.getPort());
                }
            } catch (IllegalArgumentException unparseable) {
                // a template or otherwise not a URI: compared as written
            }
        }
        return trimmed;
    }

    /**
     * The port a request actually uses: an explicit default ({@code :443} on https)
     * and an omitted one are the same destination, and treating them as different
     * would replace a working credential with a placeholder.
     */
    private static int effectivePort(String scheme, int port) {
        if (port != -1) {
            return port;
        }
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            default -> -1;
        };
    }

    private static boolean isAbsolute(String value) {
        return value != null && value.contains("://");
    }
}
