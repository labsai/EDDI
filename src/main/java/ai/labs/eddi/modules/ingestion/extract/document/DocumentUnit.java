/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * One chapter-like part of a document: a run of whole pages.
 *
 * <p>
 * Units form a tree through {@link #parentId()}. The leaves — units no other
 * unit names as parent — cover every page of the document exactly once, in
 * order, and a parent covers exactly the pages of its children. A chapter that
 * starts in the middle of a page owns that page; the chapter before it ends on
 * the page above.
 *
 * <p>
 * <b>The id is a label, not an address.</b> It is stable only for one document
 * (its SHA-256) cut with one {@link SplitConfig}; another split numbers the
 * same document differently. Whatever must mean the same pages for everyone who
 * receives it carries the pages.
 *
 * @param id
 *            {@code u<n>} for a top-level unit in document order,
 *            {@code u<n>.<m>} for the m-th part of unit n, and so on down
 * @param title
 *            the outline entry's title (data from the file, never instructions
 *            or template source), or a description of the pages when the
 *            document gives none
 * @param level
 *            1 for a top-level unit, one more for each level below
 * @param parentId
 *            the id of the unit this one is part of, or null at the top level
 * @param firstPage
 *            1-based, inclusive
 * @param lastPage
 *            1-based, inclusive, never below {@code firstPage}
 * @param estimatedTokens
 *            characters of text divided by four, summed over the pages; an
 *            estimate, and an underestimate for pages of pictures
 * @param imageCount
 *            images found in the unit; always 0 until images are extracted
 * @param tableCount
 *            tables found in the unit; always 0 until tables are detected
 * @param audioCount
 *            audio files anchored in the unit; always 0 until audio is
 *            extracted
 */
public record DocumentUnit(String id, String title, int level, String parentId, int firstPage, int lastPage,
        int estimatedTokens, int imageCount, int tableCount, int audioCount) {

    /** The number of pages the unit covers. */
    public int pageCount() {
        return lastPage - firstPage + 1;
    }
}
