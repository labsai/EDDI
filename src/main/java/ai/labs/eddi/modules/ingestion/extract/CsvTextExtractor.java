/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CSV and tab-separated files, rendered as a Markdown table.
 *
 * <p>
 * The same reasoning as for a spreadsheet applies: a row retrieved without its
 * header is a list of values nobody can interpret. The parser is RFC 4180 —
 * quoted fields, doubled quotes inside them, and newlines within a quoted field
 * — because splitting on commas silently mangles exactly the files that most
 * need to be right (addresses, descriptions, anything with a comma in it).
 */
@ApplicationScoped
public class CsvTextExtractor implements DocumentTextExtractor {

    private static final Set<String> MIMES = Set.of("text/csv", "text/tab-separated-values");

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && MIMES.contains(mimeType.toLowerCase());
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        String text = PlainTextExtractor.decode(content);
        List<List<String>> rows = parse(text, delimiterOf(text), limits);
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder markdown = new StringBuilder();
        MarkdownTable.append(markdown, rows, limits);
        return Extraction.capped(markdown.toString(), limits.maxCharacters());
    }

    /**
     * Whichever of comma, semicolon and tab appears most on the first line. A
     * German Excel export is semicolon-separated and would otherwise parse as one
     * very wide column.
     */
    private static char delimiterOf(String text) {
        int end = text.indexOf('\n');
        String firstLine = end < 0 ? text : text.substring(0, end);
        char best = ',';
        int bestCount = 0;
        for (char candidate : new char[]{',', ';', '\t'}) {
            int count = (int) firstLine.chars().filter(character -> character == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<List<String>> parse(String text, char delimiter, ExtractionLimits limits) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length() && rows.size() < limits.maxRowsPerSheet(); i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == delimiter) {
                addField(row, field, limits);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                addField(row, field, limits);
                addRow(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        addField(row, field, limits);
        addRow(rows, row);
        return rows;
    }

    private static void addField(List<String> row, StringBuilder field, ExtractionLimits limits) {
        if (row.size() < limits.maxColumnsPerSheet()) {
            row.add(field.toString());
        }
        field.setLength(0);
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(value -> !value.isBlank())) {
            rows.add(List.copyOf(row));
        }
    }
}
