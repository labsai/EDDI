/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.governance;

import java.util.regex.Pattern;

/**
 * Bounds and de-fangs text authored by a remote party before it becomes prompt
 * content.
 * <p>
 * Tool descriptions, agent-card and skill descriptions, and MCP resource
 * metadata all land verbatim in the model's context, and all of them are
 * written by whoever runs the server on the other end. Whitelisting operates on
 * tool NAMES, so an approved tool whose description later turns into an
 * instruction is otherwise ungoverned.
 * <p>
 * Extracted so the rule has one definition. It was previously private to the
 * MCP manager, which is why the A2A manager — reading descriptions out of a
 * remote agent card, an identical threat over an identical channel — had no
 * governance at all: adding it there meant either duplicating a regex that will
 * be amended over time, or reaching into another manager's internals.
 */
public final class RemoteTextGovernor {

    /** What a directive-shaped span is replaced with. */
    public static final String REDACTED = "[redacted]";

    /** Marker appended when text was cut to fit. */
    public static final String TRUNCATED = " […truncated]";

    /**
     * Directive-shaped content a remote party must not be able to inject into the
     * model's context (finding F16). Matched case-insensitively; the tool stays
     * usable, the instruction does not survive.
     */
    public static final Pattern DIRECTIVE_PATTERN = Pattern.compile("(?i)(ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?"
            + "|disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?" + "|you\\s+are\\s+now\\s+"
            + "|system\\s*(prompt|message)\\s*[:=]" + "|</?(system|assistant|user)>" + "|\\[/?(INST|SYSTEM)\\]" + "|<\\|im_(start|end)\\|>)");

    private RemoteTextGovernor() {
    }

    /** Whether the text carries directive-shaped content. */
    public static boolean containsDirective(String text) {
        return text != null && DIRECTIVE_PATTERN.matcher(text).find();
    }

    /**
     * Redacts directive-shaped spans and bounds the result.
     *
     * @param text
     *            remote-authored text; {@code null} or blank yields {@code ""}
     * @param maxChars
     *            ceiling for the returned text, before the truncation marker
     */
    public static String govern(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = DIRECTIVE_PATTERN.matcher(text).replaceAll(REDACTED);
        if (sanitized.length() > maxChars) {
            sanitized = sanitized.substring(0, maxChars) + TRUNCATED;
        }
        return sanitized;
    }
}
