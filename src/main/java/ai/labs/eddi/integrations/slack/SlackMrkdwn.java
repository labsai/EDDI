/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

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
}
