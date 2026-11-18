/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel (.xlsx): each sheet as a Markdown table, headed by the sheet's name.
 *
 * <p>
 * A table is worth the trouble over a flat dump of values because retrieval
 * returns a passage without its surroundings: "Berlin | 412" carries its
 * meaning only while the header row is still attached to it.
 *
 * <p>
 * Dates and currency are stored as plain numbers with a display format applied
 * elsewhere in the file; this reads the stored number. A date therefore appears
 * as its serial (45000), which is honest about what the cell holds.
 */
@ApplicationScoped
public class ExcelTextExtractor implements DocumentTextExtractor {

    private static final String MIME = OpenXmlPackage.EXCEL_MIME;
    private static final String WORKBOOK = "xl/workbook.xml";
    private static final String WORKBOOK_RELS = "xl/_rels/workbook.xml.rels";
    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final Pattern SHEET_PART = Pattern.compile("xl/worksheets/sheet\\d{1,9}\\.xml");
    private static final Pattern CELL_REFERENCE = Pattern.compile("([A-Z]{1,3})\\d{1,9}");

    @Override
    public boolean supports(String mimeType) {
        return MIME.equalsIgnoreCase(mimeType);
    }

    @Override
    public String extract(byte[] content, ExtractionLimits limits) {
        OpenXmlPackage archive = OpenXmlPackage.read(content, ExcelTextExtractor::isWanted, limits);
        if (!archive.has(WORKBOOK)) {
            throw new UnreadableDocumentException("This file is not an Excel workbook (no xl/workbook.xml).");
        }

        List<String> sharedStrings = archive.has(SHARED_STRINGS)
                ? readSharedStrings(archive.part(SHARED_STRINGS))
                : List.of();
        Map<String, String> sheets = sheetsInWorkbookOrder(archive, limits.visibleTextOnly());

        StringBuilder markdown = new StringBuilder();
        int rendered = 0;
        for (Map.Entry<String, String> sheet : sheets.entrySet()) {
            if (rendered >= limits.maxParts() || markdown.length() >= limits.maxCharacters()) {
                break;
            }
            List<List<String>> rows = readSheet(archive.part(sheet.getValue()), sharedStrings, limits);
            rendered++;
            if (rows.isEmpty()) {
                continue;
            }
            markdown.append("## ").append(Extraction.normalize(sheet.getKey())).append("\n\n");
            MarkdownTable.append(markdown, rows, limits);
            markdown.append('\n');
        }
        return Extraction.capped(markdown.toString(), limits.maxCharacters());
    }

    private static boolean isWanted(String name) {
        return WORKBOOK.equals(name) || WORKBOOK_RELS.equals(name) || SHARED_STRINGS.equals(name)
                || SHEET_PART.matcher(name).matches();
    }

    /**
     * Sheet name to part name, in the order the workbook lists them — which is the
     * order the tabs appear in, and is unrelated to ZIP entry order or to the
     * numbers in the part names.
     */
    private static Map<String, String> sheetsInWorkbookOrder(OpenXmlPackage archive, boolean visibleOnly) {
        Map<String, String> byRelationshipId = archive.has(WORKBOOK_RELS)
                ? readRelationships(archive.part(WORKBOOK_RELS))
                : Map.of();

        Map<String, String> sheets = new LinkedHashMap<>();
        boolean skippedHidden = false;
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(archive.part(WORKBOOK))) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(xml.getLocalName())) {
                    String name = OpenXmlPackage.attribute(xml, "name");
                    String part = byRelationshipId.get(OpenXmlPackage.relationshipId(xml));
                    // "hidden" is a sheet the author tucked away; "veryHidden" cannot even be
                    // unhidden from the Excel menu. Neither is something a reader sees.
                    String state = OpenXmlPackage.attribute(xml, "state");
                    boolean hiddenSheet = "hidden".equalsIgnoreCase(state) || "veryHidden".equalsIgnoreCase(state);
                    if (visibleOnly && hiddenSheet) {
                        skippedHidden = true;
                        continue;
                    }
                    if (name != null && part != null && archive.has(part)) {
                        sheets.putIfAbsent(name, part);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("This workbook's sheet index could not be read.", e);
        }

        if (sheets.isEmpty() && skippedHidden) {
            throw new UnreadableDocumentException("This workbook has only hidden sheets, so there is no visible content to ingest. "
                    + "Unhide a sheet, or set visibleTextOnly to false on the ingestion source to read hidden ones too.");
        }
        if (sheets.isEmpty()) {
            // Some generators write no relationships. Part order is then the only
            // ordering available, and the numbers in the names are better than nothing.
            archive.parts().keySet().stream()
                    .filter(name -> SHEET_PART.matcher(name).matches())
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> sheets.put(name.substring("xl/worksheets/".length()), name));
        }
        return sheets;
    }

    private static Map<String, String> readRelationships(byte[] relsXml) {
        Map<String, String> byId = new LinkedHashMap<>();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(relsXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(xml.getLocalName())) {
                    String id = OpenXmlPackage.attribute(xml, "Id");
                    String target = OpenXmlPackage.attribute(xml, "Target");
                    if (id != null && target != null) {
                        byId.put(id, resolveTarget(target));
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("This workbook's sheet index could not be read.", e);
        }
        return byId;
    }

    /** Targets are relative to {@code xl/}, or absolute from the package root. */
    private static String resolveTarget(String target) {
        String trimmed = target.strip();
        return trimmed.startsWith("/") ? trimmed.substring(1) : "xl/" + trimmed;
    }

    private static List<String> readSharedStrings(byte[] sharedStringsXml) {
        List<String> strings = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(sharedStringsXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT && "t".equals(xml.getLocalName())) {
                    current.append(xml.getElementText());
                } else if (event == XMLStreamConstants.END_ELEMENT && "si".equals(xml.getLocalName())) {
                    strings.add(current.toString());
                    current.setLength(0);
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("This workbook's text could not be read.", e);
        }
        return strings;
    }

    private static List<List<String>> readSheet(byte[] sheetXml, List<String> sharedStrings, ExtractionLimits limits) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        String cellType = null;
        int column = -1;
        StringBuilder cell = new StringBuilder();
        boolean visibleOnly = limits.visibleTextOnly();
        Set<Integer> hiddenColumns = new HashSet<>();
        // A row hidden by hand is dropped; one hidden by an autofilter or collapsed by
        // grouping is not (the file marks all of them hidden="1"; a filter's rows are
        // recognised by the sheet's autoFilter element, which follows the data, and a
        // group's by outlineLevel). So hidden rows are kept until the sheet is read.
        boolean rowHidden = false;
        int rowNumber = -1;
        // The rows an autoFilter covers (1-based, inclusive), once its element is read.
        int filterFrom = -1;
        int filterTo = -1;
        // Per kept row: its row number when it is hidden by hand (a candidate to drop),
        // else -1.
        List<Integer> handHiddenRows = new ArrayList<>();

        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(sheetXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext() && rows.size() < limits.maxRowsPerSheet()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "col" -> {
                            if (visibleOnly && isOn(OpenXmlPackage.attribute(xml, "hidden")) && !isGrouped(xml)) {
                                addColumnRange(hiddenColumns, OpenXmlPackage.attribute(xml, "min"), OpenXmlPackage.attribute(xml, "max"),
                                        limits.maxColumnsPerSheet());
                            }
                        }
                        case "row" -> {
                            rowHidden = visibleOnly && isOn(OpenXmlPackage.attribute(xml, "hidden")) && !isGrouped(xml);
                            rowNumber = intOr(OpenXmlPackage.attribute(xml, "r"), -1);
                        }
                        case "autoFilter" -> {
                            int[] range = rowRange(OpenXmlPackage.attribute(xml, "ref"));
                            filterFrom = range[0];
                            filterTo = range[1];
                        }
                        case "c" -> {
                            cellType = OpenXmlPackage.attribute(xml, "t");
                            column = columnOf(OpenXmlPackage.attribute(xml, "r"));
                            cell.setLength(0);
                        }
                        case "v", "t" -> cell.append(xml.getElementText());
                        default -> {
                            // Styles, merges, conditional formatting.
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("c".equals(xml.getLocalName())) {
                        // A hidden column keeps its place, empty, so the others stay aligned.
                        placeCell(row, column, hiddenColumns.contains(column) ? "" : resolve(cell.toString(), cellType, sharedStrings),
                                limits);
                    } else if ("row".equals(xml.getLocalName())) {
                        if (row.stream().anyMatch(value -> !value.isEmpty())) {
                            rows.add(List.copyOf(row));
                            handHiddenRows.add(rowHidden ? rowNumber : -1);
                        }
                        row = new ArrayList<>();
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("A sheet in this workbook could not be read.", e);
        }
        // A hidden row is kept when a filter can be the reason: it lies inside the
        // autoFilter's range. A row hidden by hand outside any filter is dropped. (A
        // row
        // that is both grouped and explicitly hidden counts as grouped, and is kept.)
        for (int i = rows.size() - 1; i >= 0; i--) {
            int hiddenRow = handHiddenRows.get(i);
            boolean insideFilter = filterFrom > 0 && hiddenRow >= filterFrom && hiddenRow <= filterTo;
            if (hiddenRow >= 0 && !insideFilter) {
                rows.remove(i);
            }
        }
        return rows;
    }

    private static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * {@code A1:C10} as {@code [1, 10]}; {@code [-1, -1]} when it cannot be read —
     * then no row counts as filtered.
     */
    private static int[] rowRange(String ref) {
        if (ref == null) {
            return new int[]{-1, -1};
        }
        Matcher matcher = Pattern.compile("[A-Za-z]{1,3}(\\d{1,9})(?::[A-Za-z]{1,3}(\\d{1,9}))?").matcher(ref.trim());
        if (!matcher.matches()) {
            return new int[]{-1, -1};
        }
        int from = Integer.parseInt(matcher.group(1));
        int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
        return new int[]{Math.min(from, to), Math.max(from, to)};
    }

    /** A row or column in an outline group: collapsed, not hidden by its author. */
    private static boolean isGrouped(XMLStreamReader xml) {
        String level = OpenXmlPackage.attribute(xml, "outlineLevel");
        try {
            return level != null && Integer.parseInt(level.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isOn(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }

    /**
     * {@code min} and {@code max} are 1-based and inclusive; kept to the columns
     * that are read.
     */
    private static void addColumnRange(Set<Integer> columns, String min, String max, int maxColumns) {
        try {
            int from = Integer.parseInt(min.trim()) - 1;
            int to = Math.min(Integer.parseInt(max.trim()) - 1, maxColumns - 1);
            for (int column = Math.max(0, from); column <= to; column++) {
                columns.add(column);
            }
        } catch (NumberFormatException | NullPointerException e) {
            // A range that cannot be read hides nothing: the cells are kept.
        }
    }

    /**
     * Writes the value at its own column, padding over the cells the sheet simply
     * omits when they are empty — without this, a gap shifts every later value one
     * column left and silently rewrites the data.
     */
    private static void placeCell(List<String> row, int column, String value, ExtractionLimits limits) {
        int index = column >= 0 ? column : row.size();
        if (index >= limits.maxColumnsPerSheet()) {
            return;
        }
        while (row.size() <= index) {
            row.add("");
        }
        row.set(index, value);
    }

    private static String resolve(String raw, String cellType, List<String> sharedStrings) {
        if (!"s".equals(cellType)) {
            // Inline strings, formula results and numbers all arrive as their own text.
            return raw;
        }
        try {
            int index = Integer.parseInt(raw.strip());
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        } catch (NumberFormatException e) {
            // A shared-string cell whose index is not a number: nothing to point at.
            return "";
        }
    }

    /** {@code "BC7"} is column 55, zero-based. */
    private static int columnOf(String reference) {
        if (reference == null || !CELL_REFERENCE.matcher(reference).matches()) {
            return -1;
        }
        int column = 0;
        for (char letter : reference.toCharArray()) {
            if (letter < 'A' || letter > 'Z') {
                break;
            }
            column = column * 26 + (letter - 'A' + 1);
        }
        return column - 1;
    }

}
