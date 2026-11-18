/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model-facing grammar for naming a file, or a part of one, of the
 * conversation's {@link ConversationListing}.
 * <p>
 * A model writes {@code report.pdf}, {@code report.pdf#u3} (unit 3),
 * {@code report.pdf#u3.1} (a sub-unit) or {@code report.pdf#p4-9} (pages 4 to
 * 9). It may also write the 1-based number of a listing entry instead of its
 * name; {@link #parse} leaves that number in {@link Parsed#name()} for the
 * resolver to try after the names.
 * <p>
 * Rules, in order:
 * <ol>
 * <li>Text equal to a known name is that name, with no part — so a file called
 * {@code a#u3.pdf}, or even {@code report#u3}, is found by its name.</li>
 * <li>Otherwise the text is split at its <em>last</em> {@code #}. The suffix
 * must be {@code u<n>(.<n>)*} or {@code p<from>-<to>}; anything else, and text
 * without a {@code #}, is a name as a whole.</li>
 * </ol>
 * Parsing is purely syntactic: whether a unit exists, or a page range is inside
 * the document, is decided by whoever resolves the part.
 *
 * @since 6.7.0
 */
public final class SelectorText {

    private static final Pattern UNIT = Pattern.compile("u[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern PAGES = Pattern.compile("p([0-9]+-[0-9]+)");

    private SelectorText() {
    }

    /**
     * A parsed name.
     *
     * @param name
     *            the file name (or a listing number), never {@code null}
     * @param unit
     *            the unit label, e.g. {@code u3.1}, or {@code null}
     * @param pages
     *            the page range, e.g. {@code 4-9}, or {@code null}
     */
    public record Parsed(String name, String unit, String pages) {

        /** Whether the text named a part of a file rather than the whole file. */
        public boolean hasPart() {
            return unit != null || pages != null;
        }
    }

    /**
     * Parses what a model wrote.
     *
     * @param text
     *            the model's text; surrounding whitespace is ignored, {@code null}
     *            is read as empty
     * @param knownNames
     *            the display names of the listing, which win over the grammar (may
     *            be {@code null})
     */
    public static Parsed parse(String text, Collection<String> knownNames) {
        String trimmed = text == null ? "" : text.strip();
        if (knownNames != null && knownNames.contains(trimmed)) {
            return new Parsed(trimmed, null, null);
        }
        int hash = trimmed.lastIndexOf('#');
        if (hash <= 0 || hash == trimmed.length() - 1) {
            return new Parsed(trimmed, null, null);
        }
        String name = trimmed.substring(0, hash);
        String suffix = trimmed.substring(hash + 1);
        if (UNIT.matcher(suffix).matches()) {
            return new Parsed(name, suffix, null);
        }
        Matcher pages = PAGES.matcher(suffix);
        if (pages.matches()) {
            return new Parsed(name, null, pages.group(1));
        }
        return new Parsed(trimmed, null, null);
    }
}
