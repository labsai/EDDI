/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import java.util.List;

/**
 * A table, possibly running over several pages.
 *
 * @param tableId
 *            identifies the table among the document's derived tables
 * @param rows
 *            rows of cells, top to bottom
 * @param confidence
 *            how sure the detector is that this is a table, 0 to 1
 * @param firstPage
 *            the page the table starts on
 * @param lastPage
 *            the page it ends on
 */
public record TableBlock(int page, float y, int order, String tableId, List<List<Cell>> rows, float confidence,
        int firstPage, int lastPage) implements Block {

    public TableBlock {
        rows = rows == null ? List.of() : rows.stream().map(row -> row == null ? List.<Cell>of() : List.copyOf(row)).toList();
    }
}
