/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds {@code @handle} mentions in the sender's <em>own</em> text of a mail
 * (email v2 plan §7.3) — never in quoted history, a forwarded message, a
 * signature or an inline-quoted line.
 * <p>
 * <b>Strict on purpose.</b> A missed mention costs the sender a re-send; a
 * mention read out of someone else's quoted words would route their mail to an
 * agent they never asked. So the extractor reads only the <em>head region</em>
 * — from the top down to the first line that looks like the start of history, a
 * header block or a signature, and at most {@value #MAX_HEAD_LINES} non-blank
 * lines or {@value #MAX_HEAD_CHARACTERS} characters — and when in doubt it
 * stops early. It reads the text the parser produced (quote-stripped or not:
 * stripping only removes trailing parts, so a top-down scan means the same on
 * both) and never changes it; it is separate from {@link QuoteStripper}, which
 * removes history, while this only refuses to look at it.
 * <p>
 * Nothing here knows about channels: the caller names the handles it knows and
 * what each one stands for.
 */
public final class MentionExtractor {

    /** Non-blank lines of the head region at most. */
    public static final int MAX_HEAD_LINES = 20;

    /** Characters of the head region at most, line breaks included. */
    public static final int MAX_HEAD_CHARACTERS = 2_000;

    /** Longest handle. */
    public static final int MAX_HANDLE_LENGTH = 40;

    /** A configured handle: {@code [a-z0-9][a-z0-9_-]{0,39}}. */
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9][a-z0-9_-]{0," + (MAX_HANDLE_LENGTH - 1) + "}");

    /**
     * A header label in any language followed by an address or a time/date:
     * {@code From: Alice <alice@example.com>}, {@code Enviado: 12/11/2026 10:15},
     * {@code Van: …}. Broader than the stripper's Outlook labels so that Spanish,
     * Italian or Dutch Outlook blocks stop the scan too. The address part needs a
     * local-part character before the {@code @}, so a mention after a label
     * ({@code FYI: @finance}) is not an address. Possessive where it can be; it
     * only ever runs on one line of the bounded head region.
     */
    private static final Pattern HEADER_LABEL = Pattern.compile(
            "^\\s*+\\**+\\p{L}[\\p{L} .'-]{0,30}+\\**+\\s*+:.*?(?:[\\p{L}\\p{N}._%+-]@|\\d{1,2}[:./]\\d{1,2})",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** A signature delimiter: {@code -- } (RFC 3676) or a lone em dash. */
    private static final Pattern SIGNATURE = Pattern.compile("^(?:-- ?|\\s*\\x{2014}\\s*)$");

    /**
     * One mention found.
     *
     * @param handle
     *            the handle, lower-cased
     * @param target
     *            what the caller said the handle stands for
     */
    public record MentionHit(String handle, String target) {
    }

    private MentionExtractor() {
    }

    /**
     * {@code value} as a stored handle — lower-cased, no {@code @} — or null when
     * it is not one.
     */
    public static String normalizeHandle(String value) {
        if (value == null) {
            return null;
        }
        String handle = value.trim().toLowerCase(Locale.ROOT);
        return HANDLE.matcher(handle).matches() ? handle : null;
    }

    /**
     * The known mentions in the head region of {@code text}, in order of first
     * appearance, each handle once. Handles not in {@code known} are ignored — they
     * may be people. None at all when the text and HTML alternatives of the mail
     * say different things ({@link MailAnomaly#ALTERNATIVES_DIVERGE}): which one
     * the sender meant is unknown.
     *
     * @param known
     *            lower-case handle to what it stands for
     */
    public static List<MentionHit> extract(String text, Set<MailAnomaly> anomalies, Map<String, String> known) {
        if (text == null || text.isEmpty() || known == null || known.isEmpty()
                || (anomalies != null && anomalies.contains(MailAnomaly.ALTERNATIVES_DIVERGE))) {
            return List.of();
        }
        Map<String, MentionHit> hits = new LinkedHashMap<>();
        for (String line : headRegion(text)) {
            scan(line, known, hits);
        }
        return List.copyOf(hits.values());
    }

    /**
     * The lines of the head region: from the top, stopping before the first quoted
     * line, attribution, header block, history separator or signature delimiter,
     * and within {@value #MAX_HEAD_LINES} non-blank lines and
     * {@value #MAX_HEAD_CHARACTERS} characters — a line that would cross the
     * character limit is not read at all.
     */
    static List<String> headRegion(String text) {
        // Never more than the limit (and one character to know whether the last line
        // read is complete) is looked at: the rest of a long mail is not scanned.
        boolean whole = text.length() <= MAX_HEAD_CHARACTERS;
        String prefix = whole ? text : text.substring(0, MAX_HEAD_CHARACTERS + 1);
        String[] raw = prefix.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        // Without the whole text, the last piece may be a cut line: never read it.
        int usable = whole ? raw.length : raw.length - 1;
        List<String> head = new ArrayList<>();
        int characters = 0;
        int nonBlank = 0;
        for (int i = 0; i < usable; i++) {
            String line = raw[i];
            characters += line.length() + 1;
            if (characters > MAX_HEAD_CHARACTERS + 1) {
                break;
            }
            if (line.isBlank()) {
                head.add(line);
                continue;
            }
            if (stopsAt(line, i + 1 < usable ? raw[i + 1] : null)) {
                break;
            }
            if (++nonBlank > MAX_HEAD_LINES) {
                break;
            }
            head.add(line);
        }
        return head;
    }

    /** Whether the head region ends before {@code line}. */
    private static boolean stopsAt(String line, String next) {
        String stripped = line.strip();
        if (stripped.startsWith(">") || SIGNATURE.matcher(line).matches() || SIGNATURE.matcher(stripped).matches()) {
            return true;
        }
        if (QuoteStripper.isAttribution(stripped)
                || (next != null && !next.isBlank() && QuoteStripper.isAttribution((stripped + " " + next.strip()).strip()))) {
            return true;
        }
        return QuoteStripper.opensHistory(stripped) || HEADER_LABEL.matcher(line).find();
    }

    /** Adds the known mentions of one line to {@code hits}. */
    private static void scan(String line, Map<String, String> known, Map<String, MentionHit> hits) {
        int at = line.indexOf('@');
        while (at >= 0) {
            int next = line.indexOf('@', at + 1);
            if (at == 0 || opensMention(line.charAt(at - 1))) {
                int end = at + 1;
                while (end < line.length() && isHandleChar(line.charAt(end))) {
                    end++;
                }
                int length = end - at - 1;
                if (length > 0 && length <= MAX_HANDLE_LENGTH && isAsciiLetterOrDigit(line.charAt(at + 1)) && endsMention(line, end)) {
                    String handle = line.substring(at + 1, end).toLowerCase(Locale.ROOT);
                    String target = known.get(handle);
                    if (target != null) {
                        hits.putIfAbsent(handle, new MentionHit(handle, target));
                    }
                }
            }
            at = next;
        }
    }

    /**
     * What may stand right before the {@code @}: whitespace, or the bracket an
     * HTML-only mail's converted mention starts with ({@code [@Name](mailto:…)}).
     */
    private static boolean opensMention(char c) {
        return Character.isWhitespace(c) || c == '(' || c == '[';
    }

    /**
     * Whether the handle ending at {@code end} is a whole mention: not part of an
     * address ({@code @finance@x}), a domain ({@code @finance.com}) or a longer
     * word in another script.
     */
    private static boolean endsMention(String line, int end) {
        if (end >= line.length()) {
            return true;
        }
        char c = line.charAt(end);
        if (c == '@' || Character.isLetterOrDigit(c)) {
            return false;
        }
        return !(c == '.' && end + 1 < line.length() && Character.isLetterOrDigit(line.charAt(end + 1)));
    }

    private static boolean isHandleChar(char c) {
        return isAsciiLetterOrDigit(c) || c == '_' || c == '-';
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
