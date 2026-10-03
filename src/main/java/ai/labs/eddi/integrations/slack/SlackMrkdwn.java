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
     * {@link #escape(String) Escapes} {@code text} and splits the result into
     * messages of at most {@code maxLength} characters — preferably at a line
     * break, never inside an entity ({@code &amp;}, {@code &lt;}, {@code &gt;}) or
     * a surrogate pair.
     *
     * @return the escaped pieces, in order; empty for {@code null} or empty text
     */
    public static List<String> escapeInChunks(String text, int maxLength) {
        if (maxLength < 8) {
            throw new IllegalArgumentException("maxLength must leave room for an entity: " + maxLength);
        }
        String escaped = escape(text);
        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < escaped.length()) {
            int end = Math.min(offset + maxLength, escaped.length());
            if (end < escaped.length()) {
                int lastNewline = escaped.lastIndexOf('\n', end - 1);
                if (lastNewline > offset) {
                    end = lastNewline + 1;
                } else {
                    end = safeCut(escaped, offset, end);
                }
            }
            chunks.add(escaped.substring(offset, end));
            offset = end;
        }
        return chunks;
    }

    /**
     * Moves a cut point back so it falls neither inside an entity nor between a
     * surrogate pair.
     */
    private static int safeCut(String escaped, int offset, int end) {
        int ampersand = escaped.lastIndexOf('&', end - 1);
        if (ampersand > offset && ampersand >= end - 4) {
            int semicolon = escaped.indexOf(';', ampersand);
            if (semicolon >= end) {
                end = ampersand;
            }
        }
        if (end > offset + 1 && Character.isHighSurrogate(escaped.charAt(end - 1))) {
            end--;
        }
        return end;
    }
}
