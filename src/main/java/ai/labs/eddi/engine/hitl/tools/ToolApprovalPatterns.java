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
    /**
     * Also permits {@code / { }} so a pattern can name an endpoint path template
     * such as {@code http.post:/agentstore/agents/{id}}. Braces are safe in the
     * compiled regex because {@link #compile} quotes every non-wildcard segment.
     */
    private static final Pattern LEGAL_CHARS = Pattern.compile("[A-Za-z0-9_\\-.:*/{}]+");

    /**
     * The only source whose tools carry an endpoint, so the only one qualifiable.
     */
    private static final String HTTP_SOURCE = "http";

    /** HTTP methods that may qualify the http source, as {@code http.post:…}. */
    private static final List<String> KNOWN_METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");
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
            if (!prefix.contains("*") && !KNOWN_SOURCES.contains(prefix) && !isMethodQualifiedSource(prefix)) {
                return Optional.of("unknown tool source prefix '" + prefix + ":' in pattern '" + pattern + "'"
                        + suggestionFor(prefix) + " — known sources: " + String.join(", ", KNOWN_SOURCES)
                        + "; only the http source may be qualified by method, e.g. 'http.post:'");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a prefix is {@code http.<httpMethod>}, e.g. {@code http.post}.
     * <p>
     * Deliberately restricted to {@code http}: it is the only source whose tools
     * carry an endpoint (see {@code AgentOrchestrator} — the mcp and a2a branches
     * register a source and no endpoint). Accepting {@code mcp.post:} would let a
     * pattern save cleanly and then match nothing at runtime, and since the gate
     * allows an unmatched call, that is an ungated write — the very failure this
     * validation exists to prevent. Widen this only together with whatever
     * populates endpoints for the other source.
     * <p>
     * A typo like {@code http.pots:} is reported for the same reason.
     */
    private static boolean isMethodQualifiedSource(String prefix) {
        int dot = prefix.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        return HTTP_SOURCE.equals(prefix.substring(0, dot)) && KNOWN_METHODS.contains(prefix.substring(dot + 1));
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
