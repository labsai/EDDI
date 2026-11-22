/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.documents.SelectorException.Reason;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The selector rule, as pure functions: how a requested part of a file becomes
 * a selector whose {@code pages} mean the same thing to every agent.
 * <ol>
 * <li>Nothing requested: the whole file.</li>
 * <li><strong>Pages requested: the pages win.</strong> They are checked against
 * the document's page count and kept; a {@code unit} and {@code split} sent
 * with them stay as labels and are <em>never</em> re-resolved — a receiver
 * whose own split numbers the chapters differently still gets the sender's
 * pages.</li>
 * <li>Only a unit requested: it is looked up in the manifest of the
 * <em>receiving</em> split and becomes pages, with the unit and that split's
 * hash kept as labels. If a {@code split} hash came with it and differs from
 * the receiving split's, the label belongs to another numbering: that is an
 * error, never a guess.</li>
 * </ol>
 * An unknown unit or a page range outside the document is an error — never a
 * silent fallback to the whole file. The only caller is
 * {@link DocumentStructureService#resolve}: no other code maps a unit id to
 * pages.
 *
 * @since 6.7.0
 */
public final class SelectorResolver {

    /** A unit label: {@code u3}, {@code u3.1}. */
    static final Pattern UNIT = Pattern.compile("u[0-9]{1,6}(?:\\.[0-9]{1,6}){0,7}");

    /** A split hash ({@code SplitConfig.hash()}): 16 lower-case hex characters. */
    static final Pattern SPLIT = Pattern.compile("[0-9a-f]{16}");

    private static final Pattern PAGES = Pattern.compile("([0-9]{1,7})\\s*-\\s*([0-9]{1,7})");

    private SelectorResolver() {
    }

    /** What the resolver needs to know of the document, asked only when needed. */
    public interface DocumentFacts {
        /** The document's page count. */
        int pageCount();

        /** The document's manifest under the receiving split. */
        DocumentManifest manifest();
    }

    /**
     * A 1-based inclusive page range.
     *
     * @param from
     *            first page
     * @param to
     *            last page, {@code >= from}
     */
    public record PageRange(int from, int to) {

        /** The canonical text form, {@code "<from>-<to>"}. */
        public String text() {
            return from + "-" + to;
        }
    }

    /**
     * Parses a page range {@code "<from>-<to>"} (blanks around the hyphen allowed).
     *
     * @throws SelectorException
     *             {@link Reason#BAD_PAGES} when the text is not such a range, a
     *             page is below 1, or {@code to} is below {@code from}
     */
    public static PageRange parsePages(String pages) {
        Matcher m = pages == null ? null : PAGES.matcher(pages.strip());
        if (m == null || !m.matches()) {
            throw new SelectorException(Reason.BAD_PAGES,
                    "the page range must be written as first-last, for example 4-9");
        }
        int from = Integer.parseInt(m.group(1));
        int to = Integer.parseInt(m.group(2));
        if (from < 1 || to < from) {
            throw new SelectorException(Reason.BAD_PAGES,
                    "the page range must start at page 1 or later and must not end before it starts");
        }
        return new PageRange(from, to);
    }

    /**
     * Applies the selector rule.
     *
     * @param storageRef
     *            the root the selector is for
     * @param unit
     *            the requested unit label, or {@code null}
     * @param pages
     *            the requested page range, or {@code null}
     * @param carriedSplit
     *            the split hash that came with {@code unit}, or {@code null}
     * @param receivingSplit
     *            the hash of the split the manifest of {@code facts} was cut with
     * @param facts
     *            the document; consulted only when a part is requested
     * @return a whole-file selector, or one with {@code pages} set — and, when a
     *         unit was resolved, {@code unit} and {@code split} as well
     * @throws SelectorException
     *             with a model-safe message, never naming the reference
     */
    public static AttachmentSelector resolve(String storageRef, String unit, String pages, String carriedSplit,
                                             String receivingSplit, DocumentFacts facts) {
        String u = blankToNull(unit);
        String p = blankToNull(pages);
        String s = blankToNull(carriedSplit);
        if (u == null && p == null) {
            return AttachmentSelector.wholeFile(storageRef);
        }
        if (u != null && !UNIT.matcher(u).matches()) {
            throw new SelectorException(Reason.UNKNOWN_UNIT, "a chapter id looks like u3 or u3.1");
        }
        if (s != null && !SPLIT.matcher(s).matches()) {
            throw new SelectorException(Reason.FOREIGN_SPLIT, "the split this chapter id belongs to is not recognized; send pages");
        }
        if (p != null) {
            // The pages are authoritative: checked, never re-derived from the label.
            PageRange range = parsePages(p);
            requireWithin(range, facts.pageCount());
            return new AttachmentSelector(storageRef, range.text(), u, u != null ? s : null);
        }
        if (s != null && !s.equals(receivingSplit)) {
            throw new SelectorException(Reason.FOREIGN_SPLIT,
                    "this chapter id belongs to a different split of the file; send pages");
        }
        DocumentManifest manifest = facts.manifest();
        DocumentUnit found = manifest.units().stream().filter(x -> u.equals(x.id())).findFirst()
                .orElseThrow(() -> new SelectorException(Reason.UNKNOWN_UNIT, "this file has no chapter " + u));
        PageRange range = new PageRange(found.firstPage(), found.lastPage());
        requireWithin(range, manifest.pageCount());
        return new AttachmentSelector(storageRef, range.text(), u, receivingSplit);
    }

    /**
     * @throws SelectorException
     *             {@link Reason#PAGES_OUT_OF_RANGE} when {@code range} ends past
     *             {@code pageCount}
     */
    static void requireWithin(PageRange range, int pageCount) {
        if (range.to() > pageCount) {
            throw new SelectorException(Reason.PAGES_OUT_OF_RANGE, "pages " + range.text() + " are outside this file, which has "
                    + pageCount + (pageCount == 1 ? " page" : " pages"));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
