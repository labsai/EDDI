package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.model.Deployment.Environment;

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
     * Parse an environment string to the corresponding enum value.
     * Defaults to {@link Environment#unrestricted} if null, blank, or unrecognized.
     */
    static Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return Environment.unrestricted;
        }
        try {
            return Environment.valueOf(environment.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Environment.unrestricted;
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
     * Build an error JSON response with proper escaping.
     * Uses manual construction to avoid serialization dependency in error paths.
     */
    static String errorJson(String message) {
        return "{\"error\":\"" + escapeJsonString(message) + "\"}";
    }

    /**
     * Escape a string for safe inclusion in a JSON string value.
     * Handles all JSON-special characters per RFC 8259.
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
}
