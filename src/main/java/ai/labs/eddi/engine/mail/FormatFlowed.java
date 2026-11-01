/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * RFC 3676 {@code format=flowed}: text whose long paragraphs are wrapped with
 * "soft" line breaks (a trailing space) that a reader joins again.
 * <p>
 * Decoding matters for the parser because the quote stripper reads lines: a
 * flowed paragraph left wrapped would show its quote markers on every physical
 * line but the first. Encoding matters for the renderer, so long agent replies
 * reflow on narrow screens instead of breaking mid-sentence at column 78.
 */
final class FormatFlowed {

    /** RFC 3676 §4.2: lines SHOULD be 78 characters or fewer. */
    static final int LINE_LENGTH = 78;

    private static final String SIGNATURE_SEPARATOR = "-- ";

    private FormatFlowed() {
    }

    /**
     * Joins soft-broken lines. Quote depth is kept as {@code "> "} per level, so
     * the result reads like ordinary quoted text.
     *
     * @param delSp
     *            the {@code DelSp=yes} parameter: the trailing space is a separator
     *            the sender added, not part of the text
     */
    static String decode(String text, boolean delSp) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder paragraph = null;
        int paragraphDepth = 0;
        for (String raw : lines) {
            int depth = 0;
            while (depth < raw.length() && raw.charAt(depth) == '>') {
                depth++;
            }
            String line = raw.substring(depth);
            if (line.startsWith(" ")) {
                // Space-stuffing (§4.4), or the space after the quote markers.
                line = line.substring(1);
            }
            if (paragraph != null && depth != paragraphDepth) {
                // A change of quote depth ends a paragraph even after a soft break.
                appendLine(out, paragraphDepth, paragraph.toString());
                paragraph = null;
            }
            boolean soft = line.endsWith(" ") && !line.equals(SIGNATURE_SEPARATOR);
            if (soft) {
                if (paragraph == null) {
                    paragraph = new StringBuilder();
                    paragraphDepth = depth;
                }
                paragraph.append(delSp ? line.substring(0, line.length() - 1) : line);
                continue;
            }
            if (paragraph != null) {
                paragraph.append(line);
                appendLine(out, paragraphDepth, paragraph.toString());
                paragraph = null;
            } else {
                appendLine(out, depth, line);
            }
        }
        if (paragraph != null) {
            appendLine(out, paragraphDepth, paragraph.toString());
        }
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, int depth, String line) {
        if (depth > 0) {
            out.append("> ".repeat(depth));
        }
        out.append(line).append('\n');
    }

    /**
     * Encodes unquoted text as flowed, lines joined with CRLF: trailing spaces of
     * hard lines removed (they would read as soft breaks), lines starting with a
     * space, {@code >} or {@code From } space-stuffed, and lines longer than
     * {@link #LINE_LENGTH} wrapped at a space with a soft break. A word longer than
     * a line is left whole rather than split.
     */
    static String encode(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.equals(SIGNATURE_SEPARATOR)) {
                line = line.stripTrailing();
            }
            encodeLine(out, line);
            if (i < lines.length - 1) {
                out.append("\r\n");
            }
        }
        return out.toString();
    }

    private static void encodeLine(StringBuilder out, String line) {
        String rest = line;
        while (true) {
            String stuffed = needsStuffing(rest) ? " " + rest : rest;
            if (stuffed.length() <= LINE_LENGTH || rest.equals(SIGNATURE_SEPARATOR)) {
                out.append(stuffed);
                return;
            }
            int limit = LINE_LENGTH - (stuffed.length() - rest.length()) - 1;
            int cut = rest.lastIndexOf(' ', limit);
            if (cut <= 0) {
                cut = rest.indexOf(' ', limit);
            }
            if (cut <= 0 || cut == rest.length() - 1) {
                out.append(stuffed);
                return;
            }
            // Keep the space at the end of this line: that is the soft break.
            out.append(needsStuffing(rest) ? " " : "").append(rest, 0, cut + 1).append("\r\n");
            rest = rest.substring(cut + 1);
        }
    }

    private static boolean needsStuffing(String line) {
        return line.startsWith(" ") || line.startsWith(">") || line.startsWith("From ");
    }
}
