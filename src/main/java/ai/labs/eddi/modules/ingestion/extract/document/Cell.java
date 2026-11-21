/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

/**
 * One cell of a {@link TableBlock}.
 *
 * @param text
 *            the cell's text, as data
 * @param rowSpan
 *            rows the cell spans, at least 1
 * @param colSpan
 *            columns the cell spans, at least 1
 */
public record Cell(String text, int rowSpan, int colSpan) {

    public Cell {
        rowSpan = Math.max(1, rowSpan);
        colSpan = Math.max(1, colSpan);
    }

    /** A cell spanning one row and one column. */
    public static Cell of(String text) {
        return new Cell(text, 1, 1);
    }
}
