/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Glob compilation + save-time validation for tool approval patterns.
 * <p>
 * The pattern language is deliberately minimal: {@code *} is the only wildcard
 * (matches any run of characters, including empty); every other character is a
 * quoted literal, so compilation is ReDoS-safe. Patterns may be qualified by a
 * known source prefix (e.g. {@code mcp:read_*}) or match the bare tool name.
 */
public final class ToolApprovalPatterns {
    public static final List<String> KNOWN_SOURCES = List.of("builtin", "http", "mcp", "a2a", "dynamic", "memory", "recall");
    private static final Pattern LEGAL_CHARS = Pattern.compile("[A-Za-z0-9_\\-.:*]+");
    private static final int MAX_LENGTH = 256;

    private ToolApprovalPatterns() {
    }

    /**
     * '*' is the only wildcard; every other char is a quoted literal (ReDoS-safe).
     */
    public static Pattern compile(String glob) {
        String[] parts = glob.split("\\*", -1);
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(sb.append("$").toString());
    }

    /** Returns an actionable error message, or empty if the pattern is valid. */
    public static Optional<String> validate(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return Optional.of("pattern must not be blank");
        }
        if (pattern.length() > MAX_LENGTH) {
            return Optional.of("pattern exceeds " + MAX_LENGTH + " characters");
        }
        if (!LEGAL_CHARS.matcher(pattern).matches()) {
            return Optional.of("pattern '" + pattern + "' contains illegal characters — allowed: A-Za-z0-9_-.:* (tool names never contain spaces)");
        }
        if (pattern.startsWith(":") || pattern.endsWith(":")) {
            return Optional.of("pattern '" + pattern
                    + "' must not start or end with a colon — the colon separates a source prefix (e.g. 'mcp:read_*') from the tool name");
        }
        int colon = pattern.indexOf(':');
        if (colon > 0) {
            String prefix = pattern.substring(0, colon);
            if (!prefix.contains("*") && !KNOWN_SOURCES.contains(prefix)) {
                return Optional.of("unknown tool source prefix '" + prefix + ":' in pattern '" + pattern + "'"
                        + suggestionFor(prefix) + " — known sources: " + String.join(", ", KNOWN_SOURCES));
            }
        }
        return Optional.empty();
    }

    private static String suggestionFor(String prefix) {
        for (String known : KNOWN_SOURCES) {
            if (levenshtein(prefix, known) <= 2) {
                return " — did you mean '" + known + ":'?";
            }
        }
        return "";
    }

    /**
     * Iterative Levenshtein distance (two-row). Public for reuse by
     * ReservedActionLint (Task 15).
     */
    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
