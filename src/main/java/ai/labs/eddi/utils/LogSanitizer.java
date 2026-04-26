package ai.labs.eddi.utils;

/**
 * Sanitizes user-controlled values before logging to prevent log injection
 * (CWE-117). Strips newlines and control characters that could forge log
 * entries.
 *
 * @author ginccc
 * @since 6.0.2
 */
public final class LogSanitizer {
    private LogSanitizer() {
    }

    /**
     * Remove newlines and control characters from a value before logging. Newlines
     * and tabs are replaced with underscores (preserving readability); other
     * control characters (U+0000-U+001F, U+007F) are stripped entirely.
     *
     * @param value
     *            the value to sanitize (may be null)
     * @return sanitized string safe for log output, or {@code "null"} if input is
     *         null
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        return value.replaceAll("[\\r\\n\\t]", "_")
                .replaceAll("[\\x00-\\x1F\\x7F]", "");
    }
}
