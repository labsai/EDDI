/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pre-compiled regex filter for redacting secrets from log messages. Applied in
 * the log capture workflow to prevent API keys and tokens from appearing in
 * logs, ring buffer, and database.
 */
public final class SecretRedactionFilter {

    private static final String REDACTED = "<REDACTED>";

    /**
     * Ordered list of redaction patterns. Each pattern has a compiled regex and a
     * replacement strategy.
     */
    // Quantifiers below are POSSESSIVE (++, *+, {n,}+) to eliminate ReDoS
    // backtracking. This is behavior-preserving here: every quantified class is
    // followed by a literal that is NOT a member of the class (a '.', '}', or the
    // string end), so a correct match never needs to give characters back — the
    // possessive form yields identical results while running in linear time on
    // adversarial inputs (e.g. long repetitions of "${vault:").
    private static final List<RedactionRule> RULES = List.of(
            // Anthropic API keys: sk-ant-... — underscores INCLUDED. Real keys carry
            // them, and a class without '_' stopped matching at the first one: a full
            // key inside a gated tool call's arguments sailed through "redacted" and
            // rendered clear-text on the approval card. Ordered before the generic
            // sk- rule so the replacement keeps the sk-ant- prefix.
            new RedactionRule(Pattern.compile("sk-ant-[a-zA-Z0-9_\\-]{20,}+"), "sk-ant-" + REDACTED),

            // OpenAI API keys: sk-... (at least 20 chars). Underscores and hyphens
            // included — sk-proj-... keys carry both.
            new RedactionRule(Pattern.compile("sk-[a-zA-Z0-9_\\-]{20,}+"), "sk-" + REDACTED),

            // Bearer tokens — JWTs AND opaque tokens. A single possessive character
            // class (no mandatory '.') so 'Bearer <opaque>' is redacted too, not only
            // dotted JWTs; min length 20 avoids redacting short benign words.
            new RedactionRule(Pattern.compile("Bearer\\s++[A-Za-z0-9\\-_.+/=]{20,}+"),
                    "Bearer " + REDACTED),

            // Generic API key patterns: key=... or apikey=... in query strings AND in
            // JSON — including JSON nested as an ESCAPED string ("apiKey\": \"..."),
            // the exact shape of a tool call whose requestBody argument is itself a
            // JSON document. The optional backslash-tolerant quote groups are what
            // see through that escaping; without them the separator never matched
            // inside escaped bodies. Quantifiers stay possessive (ReDoS).
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)(?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(?:\\\\*+[\"'])?+[^'\"\\\\\\s,;}{\\]]{8,}+"),
                    "$1=" + REDACTED),

            // Vault references (should never appear in logs, but defense-in-depth)
            // Matches both ${vault:...} and legacy ${eddivault:...}
            // Note: $ must be escaped in replacement strings for Matcher.replaceAll()
            new RedactionRule(Pattern.compile("\\$\\{(?:vault|eddivault):[^}]++}"), "\\${vault:" + REDACTED + "}"));

    private SecretRedactionFilter() {
        // Utility class
    }

    /**
     * Redact potential secret values from a log message.
     *
     * @param message
     *            the raw log message
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
