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

    /** Extra raw characters read past twice the remaining room. */
    private static final int CELL_SLACK = 64;

    private MarkdownTable() {
    }

    /** Appends the rows, treating the first as the header. */
    static void append(StringBuilder markdown, List<List<String>> rows, ExtractionLimits limits) {
        int width = Math.min(limits.maxColumnsPerSheet(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (width == 0) {
            return;
        }
        int limit = limits.maxCharacters();
        appendRow(markdown, rows.getFirst(), width, limit);
        markdown.append('|').append(" --- |".repeat(width)).append('\n');
        for (List<String> row : rows.subList(1, rows.size())) {
            if (markdown.length() >= limits.maxCharacters()) {
                break;
            }
            appendRow(markdown, row, width, limit);
        }
    }

    /**
     * One row, never written past the character limit.
     *
     * <p>
     * The limit used to be checked only between rows, and each cell was copied in
     * full. A workbook's shared string is stored once and may be referenced by
     * every cell, so one 10 MB string behind a 64-column header row rendered as 640
     * MB before the first check — from a file that compresses to almost nothing.
     * Each cell is now cut to what can still fit before it is normalised and
     * escaped, and a row stops once the limit is reached.
     */
    private static void appendRow(StringBuilder markdown, List<String> row, int width, int limit) {
        markdown.append('|');
        for (int i = 0; i < width; i++) {
            int room = limit - markdown.length();
            if (room <= 0) {
                break;
            }
            String cell = i < row.size() ? row.get(i) : "";
            markdown.append(' ').append(Extraction.escapeCell(fitting(cell, room))).append(" |");
        }
        markdown.append('\n');
    }

    /**
     * The start of a cell, long enough to fill {@code room} characters of output.
     * Twice the room, plus a little: normalising collapses whitespace runs, so the
     * raw text needed to fill the room can be longer than the room itself.
     */
    static String fitting(String cell, int room) {
        if (cell == null) {
            return "";
        }
        long allowance = 2L * room + CELL_SLACK;
        if (cell.length() <= allowance) {
            return cell;
        }
        int end = (int) allowance;
        if (Character.isHighSurrogate(cell.charAt(end - 1))) {
            end--;
        }
        return cell.substring(0, end);
    }
}
