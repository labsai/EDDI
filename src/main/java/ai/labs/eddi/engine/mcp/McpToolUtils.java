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
     * Parse an environment string to the corresponding enum value. Defaults to
     * {@link Environment#production} if null, blank, or unrecognized.
     */
    static Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return Environment.production;
        }
        try {
            return Environment.valueOf(environment.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Environment.production;
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
