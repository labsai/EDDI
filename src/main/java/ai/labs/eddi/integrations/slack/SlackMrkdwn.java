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
     * A text is truncated only when it cannot be split into {@code maxChunks}
     * chunks at safe boundaries at all; within that, line breaks are preferred.
     * Only the prefix that can fit is escaped, every search for a cut point stays
     * inside its own chunk, and the feasibility check costs one step per chunk, so
     * the cost is bounded by the posted output, not by the input.
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
                // Filling every chunk to its last safe boundary (the greedy cut) needs
                // the fewest chunks: with indivisible units — an entity, a surrogate
                // pair, any other character — no partition reaches further after k
                // chunks than the greedy one does. A line break is the nicer cut, so it
                // is taken only when the rest still fits the chunks that are left when
                // packed greedily; otherwise this chunk is filled to the limit. A text
                // is therefore truncated only when no safe partition into maxChunks
                // exists at all. (A plain length estimate missed that entities cannot
                // be split: "x\n" plus nine "&amp;" in 3 chunks of 24 was truncated.)
                int greedyEnd = safeCut(escaped, offset, end);
                int lineEnd = lineBreakCut(escaped, offset, end);
                int chunksLeft = maxChunks - chunks.size() - 1;
                end = lineEnd > 0 && fitsGreedily(escaped, lineEnd, maxLength, chunksLeft) ? lineEnd : greedyEnd;
            }
            chunks.add(escaped.substring(offset, end));
            offset = end;
        }
        if (rawCut || offset < escaped.length()) {
            // The last chunk can end where the raw prefix was cut — possibly between
            // the two halves of a surrogate pair — so it is re-cut at a safe boundary,
            // with room for the marker when the limit allows one.
            String last = chunks.getLast();
            boolean marker = maxLength > TRUNCATION_MARKER.length();
            int limit = marker ? Math.min(last.length(), maxLength - TRUNCATION_MARKER.length()) : last.length();
            int keep = safeBoundaryAtOrBefore(last, limit);
            chunks.set(chunks.size() - 1, last.substring(0, keep) + (marker ? TRUNCATION_MARKER : ""));
        }
        return chunks;
    }

    /**
     * The cut just after the last line break inside {@code (offset, end)}, or
     * {@code -1} when there is none. A line break is a single character, so a cut
     * after it is always a safe boundary.
     */
    private static int lineBreakCut(String escaped, int offset, int end) {
        for (int i = end - 1; i > offset; i--) {
            if (escaped.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Whether {@code escaped} from {@code from} fits into {@code chunks} chunks
     * when each is filled to its last safe boundary — exactly whether it fits at
     * all, since that packing needs the fewest chunks. Costs one step per chunk.
     */
    private static boolean fitsGreedily(String escaped, int from, int maxLength, int chunks) {
        int offset = from;
        for (int used = 0; used < chunks; used++) {
            if (escaped.length() - offset <= maxLength) {
                return true;
            }
            offset = safeCut(escaped, offset, offset + maxLength);
        }
        return offset >= escaped.length();
    }

    /**
     * The last safe boundary at or before {@code end}, which may be {@code 0}: the
     * re-cut of a final chunk for the truncation marker can land inside an entity
     * the chunk starts with, where {@link #safeCut} (which always keeps at least
     * one character) would leave half of it.
     */
    private static int safeBoundaryAtOrBefore(String text, int end) {
        for (int i = end - 1; i >= Math.max(0, end - 4); i--) {
            char c = text.charAt(i);
            if (c == ';') {
                break;
            }
            if (c == '&') {
                end = i;
                break;
            }
        }
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end;
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
