/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;

import java.util.Map;

/**
 * Shared utility methods for MCP tool implementations.
 *
 * @author ginccc
 */
final class McpToolUtils {

    private McpToolUtils() {
        // utility class
    }

    /**
     * Check that the current caller has the required role. When authorization is
     * disabled ({@code authEnabled=false}), no check is performed.
     *
     * @param identity
     *            the current security identity
     * @param authEnabled
     *            whether authorization is enabled
     * @param role
     *            the required realm role
     * @throws ForbiddenException
     *             if the caller lacks the required role
     */
    static void requireRole(SecurityIdentity identity, boolean authEnabled, String role) {
        if (!authEnabled) {
            return;
        }
        if (identity == null || identity.isAnonymous() || !identity.hasRole(role)) {
            throw new ForbiddenException("MCP operation requires role: " + role);
        }
    }

    /**
     * As {@link #requireRole}, but satisfied by <em>any</em> of the given roles.
     * <p>
     * EDDI has no role hierarchy — {@code hasRole} is a literal check, so a
     * single-role requirement of {@code eddi-viewer} would refuse an
     * {@code eddi-admin}. A tool whose REST counterpart enumerates several roles
     * (the docs endpoints, above all) needs the same enumeration here, or the two
     * surfaces guard the same content differently.
     *
     * @throws ForbiddenException
     *             if the caller holds none of the given roles
     */
    static void requireAnyRole(SecurityIdentity identity, boolean authEnabled, String... roles) {
        if (!authEnabled) {
            return;
        }
        if (identity != null && !identity.isAnonymous()) {
            for (String role : roles) {
                if (identity.hasRole(role)) {
                    return;
                }
            }
        }
        throw new ForbiddenException("MCP operation requires one of roles: " + String.join(", ", roles));
    }

    /**
     * Get a REST interface proxy via IRestInterfaceFactory. These proxies make HTTP
     * calls that go through the full JAX-RS workflow, including
     * DocumentDescriptorFilter which auto-creates descriptors.
     *
     * @param factory
     *            the REST interface factory
     * @param clazz
     *            the REST interface class to proxy
     * @return the proxy instance
     * @throws RuntimeException
     *             if the proxy cannot be created
     */
    static <T> T getRestStore(IRestInterfaceFactory factory, Class<T> clazz) {
        try {
            return factory.get(clazz);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            throw new RuntimeException("Failed to get REST proxy for " + clazz.getSimpleName(), e);
        }
    }

    /**
     * Parse an environment string to the corresponding enum value. Delegates to
     * {@link Environment#parseStrict(String)}, the single place that knows the
     * mapping: only an absent (null/blank) environment defaults to
     * {@link Environment#production} — an environment the platform does not know is
     * rejected with an {@link UnknownEnvironmentException} rather than silently
     * resolving to production. A typo such as {@code "staging"} must never deploy
     * to, undeploy from, or talk to production.
     *
     * @throws UnknownEnvironmentException
     *             if {@code environment} is neither blank nor a known environment
     */
    static Environment parseEnvironment(String environment) {
        try {
            return Environment.parseStrict(environment);
        } catch (IllegalArgumentException e) {
            throw new UnknownEnvironmentException(e.getMessage());
        }
    }

    /**
     * An MCP caller passed an environment EDDI does not know. Distinct type so a
     * tool can tell bad caller input apart from a server-side failure; the message
     * names both the rejected value and the valid ones, so an MCP client (and the
     * model driving it) can self-correct instead of retrying the same call.
     */
    static final class UnknownEnvironmentException extends IllegalArgumentException {

        UnknownEnvironmentException(String message) {
            super(message);
        }
    }

    /**
     * Parse a string to an integer, returning a default value on failure.
     */
    static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parse a string to a boolean, defaulting to false.
     */
    static boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    /**
     * Build an error JSON response with proper escaping. Uses manual construction
     * to avoid serialization dependency in error paths.
     */
    static String errorJson(String message) {
        return "{\"error\":\"" + escapeJsonString(message) + "\"}";
    }

    /**
     * Structured error JSON for tools whose callers need to branch on the failure
     * kind (e.g. NOT_FOUND vs WRONG_STATE vs FORBIDDEN vs DISABLED vs BAD_REQUEST).
     * Manual construction — like {@link #errorJson(String)} it can never throw on
     * the error path. {@code errorCode} and {@code details} may be null/blank/empty
     * (omitted when so).
     */
    static String errorJson(String message, String errorCode, Map<String, String> details) {
        var sb = new StringBuilder();
        sb.append("{\"error\":\"").append(escapeJsonString(message)).append("\"");
        if (errorCode != null && !errorCode.isBlank()) {
            sb.append(",\"errorCode\":\"").append(escapeJsonString(errorCode)).append("\"");
        }
        if (details != null && !details.isEmpty()) {
            sb.append(",\"details\":{");
            boolean first = true;
            for (var entry : details.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":\"")
                        .append(escapeJsonString(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Escape a string for safe inclusion in a JSON string value. Handles all
     * JSON-special characters per RFC 8259.
     */
    static String escapeJsonString(String input) {
        if (input == null) {
            return "";
        }
        var sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Extract the resource ID from a Location header like
     * "/store/resources/{id}?version=1".
     */
    static String extractIdFromLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : null;
    }

    /**
     * Extract the version from a Location header like
     * "/store/resources/{id}?version=1". Returns 1 if not found.
     */
    static int extractVersionFromLocation(String location) {
        if (location == null || !location.contains("version=")) {
            return 1;
        }
        try {
            int idx = location.indexOf("version=") + "version=".length();
            int end = location.indexOf('&', idx);
            String ver = end > 0 ? location.substring(idx, end) : location.substring(idx);
            return Integer.parseInt(ver.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
