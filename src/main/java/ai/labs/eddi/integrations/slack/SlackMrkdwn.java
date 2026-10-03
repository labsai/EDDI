/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import java.util.ArrayList;
import java.util.List;

/**
 * Escaping of text that EDDI did not write — model output, a group member's
 * contribution, an artifact name, a display name — before it is posted to
 * Slack.
 * <p>
 * Slack parses {@code <...>} control sequences in message text:
 * {@code <!channel>}, {@code <!here>} and {@code <!everyone>} notify everybody,
 * {@code <@U123>} mentions a user, {@code <https://x|label>} disguises a link.
 * An LLM reply (prompt-injected or not) containing {@code <!channel>} used to
 * be posted as it was and pinged the whole channel. Slack's own rule is to
 * escape {@code &}, {@code <} and {@code >}; clients render the entities as the
 * characters, inside code spans and blocks too, so the text reads the same. A
 * {@code >} that opens a line is left alone: it is the blockquote marker, and
 * with every {@code <} escaped it can no longer close a control sequence.
 * <p>
 * Only untrusted text goes through here: EDDI's own notices keep the mentions
 * they mean ({@code "Approved by <@U123>"}).
 */
public final class SlackMrkdwn {

    private SlackMrkdwn() {
    }

    /**
     * @return {@code text} with {@code &} and {@code <} replaced by their entities,
     *         and {@code >} too except where it opens a line (a blockquote);
     *         {@code ""} for {@code null}
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        var escaped = new StringBuilder(text.length() + 16);
        boolean lineStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append(lineStart ? ">" : "&gt;");
                default -> escaped.append(c);
            }
            if (c == '\n') {
                lineStart = true;
            } else if (c != ' ' && c != '\t' && !(c == '>' && lineStart)) {
                lineStart = false;
            }
        }
        return escaped.toString();
    }

    /**
     * Longest text EDDI sends in one Slack message. Slack truncates {@code text}
     * beyond 40,000 characters and recommends staying under 4,000; escaping can
     * make a message longer than it was, so the limit applies to the escaped text.
     */
    public static final int MAX_MESSAGE_LENGTH = 3900;

    /**
     * Most messages one text is split into. Slack allows about one message per
     * second per channel, so an unbounded reply (a 2 MB tool result echoed by a
     * model) would post hundreds of messages over minutes; past this many the text
     * is cut and ends with {@link #TRUNCATION_MARKER}.
     */
    public static final int MAX_MESSAGES = 20;

    /**
     * Appended to the last message when a text was longer than
     * {@link #MAX_MESSAGES} messages.
     */
    public static final String TRUNCATION_MARKER = "\n_[message truncated]_";

    /**
     * {@link #escapeInChunks(String, int, int)} with at most {@link #MAX_MESSAGES}
     * pieces.
     */
    public static List<String> escapeInChunks(String text, int maxLength) {
        return escapeInChunks(text, maxLength, MAX_MESSAGES);
    }

    /**
     * {@link #escape(String) Escapes} {@code text} and splits the result into at
     * most {@code maxChunks} messages of at most {@code maxLength} characters —
     * preferably at a line break, never inside an entity ({@code &amp;},
     * {@code &lt;}, {@code &gt;}) or a surrogate pair. A text that needs more
     * messages is cut, and its last message ends with {@link #TRUNCATION_MARKER}.
     * <p>
     * Linear in the part of the text that is posted: only the prefix that can fit
     * is escaped, and every search for a cut point stays inside its own chunk (a
     * search over the whole prefix made a newline-free text quadratic).
     *
     * @return the escaped pieces, in order; empty for {@code null} or empty text
     */
    public static List<String> escapeInChunks(String text, int maxLength, int maxChunks) {
        if (maxLength < 8) {
            throw new IllegalArgumentException("maxLength must leave room for an entity: " + maxLength);
        }
        if (maxChunks < 1) {
            throw new IllegalArgumentException("maxChunks must be positive: " + maxChunks);
        }
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        // An escaped text is never shorter than the raw one, so nothing beyond this
        // many raw characters can be posted.
        long budget = (long) maxLength * maxChunks;
        boolean rawCut = text.length() > budget;
        String escaped = escape(rawCut ? text.substring(0, (int) budget) : text);

        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < escaped.length() && chunks.size() < maxChunks) {
            int end = Math.min(offset + maxLength, escaped.length());
            if (end < escaped.length()) {
                end = cutPoint(escaped, offset, end);
            }
            chunks.add(escaped.substring(offset, end));
            offset = end;
        }
        if ((rawCut || offset < escaped.length()) && maxLength > TRUNCATION_MARKER.length()) {
            String last = chunks.getLast();
            int keep = safeCut(last, 0, Math.min(last.length(), maxLength - TRUNCATION_MARKER.length()));
            chunks.set(chunks.size() - 1, last.substring(0, keep) + TRUNCATION_MARKER);
        }
        return chunks;
    }

    /**
     * The last line break inside {@code (offset, end)}, else a {@link #safeCut}.
     */
    private static int cutPoint(String escaped, int offset, int end) {
        for (int i = end - 1; i > offset; i--) {
            if (escaped.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return safeCut(escaped, offset, end);
    }

    /**
     * Moves a cut point back so it falls neither inside an entity nor between a
     * surrogate pair. Looks at most at the four characters before the cut — the
     * longest entity, {@code &amp;}, is five.
     */
    private static int safeCut(String escaped, int offset, int end) {
        for (int i = end - 1; i >= Math.max(offset + 1, end - 4); i--) {
            char c = escaped.charAt(i);
            if (c == ';') {
                break;
            }
            if (c == '&') {
                end = i;
                break;
            }
        }
        if (end > offset + 1 && Character.isHighSurrogate(escaped.charAt(end - 1))) {
            end--;
        }
        return end;
    }
}
