/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.util.List;

/**
 * Rows of cells as a Markdown table.
 *
 * <p>
 * Tabular data is written as a table rather than flattened, because retrieval
 * hands back a passage on its own: {@code Berlin | 412} means something only
 * while its header row is still attached to it.
 */
final class MarkdownTable {

    private MarkdownTable() {
    }

    /** Appends the rows, treating the first as the header. */
    static void append(StringBuilder markdown, List<List<String>> rows, ExtractionLimits limits) {
        int width = Math.min(limits.maxColumnsPerSheet(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (width == 0) {
            return;
        }
        appendRow(markdown, rows.getFirst(), width);
        markdown.append('|').append(" --- |".repeat(width)).append('\n');
        for (List<String> row : rows.subList(1, rows.size())) {
            if (markdown.length() >= limits.maxCharacters()) {
                break;
            }
            appendRow(markdown, row, width);
        }
    }

    private static void appendRow(StringBuilder markdown, List<String> row, int width) {
        markdown.append('|');
        for (int i = 0; i < width; i++) {
            markdown.append(' ').append(Extraction.escapeCell(i < row.size() ? row.get(i) : "")).append(" |");
        }
        markdown.append('\n');
    }
}
