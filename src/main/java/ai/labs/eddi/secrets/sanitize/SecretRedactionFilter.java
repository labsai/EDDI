package ai.labs.eddi.secrets.sanitize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-compiled regex filter for redacting secrets from log messages.
 * Applied in the log capture workflow to prevent API keys and tokens
 * from appearing in logs, ring buffer, and database.
 */
public final class SecretRedactionFilter {

    private static final String REDACTED = "<REDACTED>";

    /**
     * Ordered list of redaction patterns. Each pattern has a compiled regex
     * and a replacement strategy.
     */
    private static final List<RedactionRule> RULES = List.of(
            // OpenAI API keys: sk-... (at least 20 chars)
            new RedactionRule(Pattern.compile("sk-[a-zA-Z0-9]{20,}"), "sk-" + REDACTED),

            // Anthropic API keys: sk-ant-...
            new RedactionRule(Pattern.compile("sk-ant-[a-zA-Z0-9\\-]{20,}"), "sk-ant-" + REDACTED),

            // Bearer tokens (JWTs and opaque tokens)
            new RedactionRule(Pattern.compile(
                    "Bearer\\s+[A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+\\.?[A-Za-z0-9\\-_.+/=]*"),
                    "Bearer " + REDACTED),

            // Generic API key patterns: key=... or apikey=... in query strings
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)\\s*[=:]\\s*['\"]?[^'\"\\s,;}{\\]]{8,}"),
                    "$1=" + REDACTED),

            // Vault references (should never appear in logs, but defense-in-depth)
            // Note: $ must be escaped in replacement strings for Matcher.replaceAll()
            new RedactionRule(Pattern.compile("\\$\\{eddivault:[^}]+}"), "\\${eddivault:" + REDACTED + "}")
    );

    private SecretRedactionFilter() {
        // Utility class
    }

    /**
     * Redact potential secret values from a log message.
     *
     * @param message the raw log message
     * @return the message with secrets replaced by {@code <REDACTED>}
     */
    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String result = message;
        for (RedactionRule rule : RULES) {
            result = rule.pattern.matcher(result).replaceAll(rule.replacement);
        }
        return result;
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
