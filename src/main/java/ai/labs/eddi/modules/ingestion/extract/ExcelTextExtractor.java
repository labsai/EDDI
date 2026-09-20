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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, String> sheets = sheetsInWorkbookOrder(archive);

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
    private static Map<String, String> sheetsInWorkbookOrder(OpenXmlPackage archive) {
        Map<String, String> byRelationshipId = archive.has(WORKBOOK_RELS)
                ? readRelationships(archive.part(WORKBOOK_RELS))
                : Map.of();

        Map<String, String> sheets = new LinkedHashMap<>();
        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(archive.part(WORKBOOK))) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(xml.getLocalName())) {
                    String name = OpenXmlPackage.attribute(xml, "name");
                    String part = byRelationshipId.get(OpenXmlPackage.attribute(xml, "id"));
                    if (name != null && part != null && archive.has(part)) {
                        sheets.putIfAbsent(name, part);
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("This workbook's sheet index could not be read.", e);
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

        try (OpenXmlPackage.Part reader = OpenXmlPackage.readerFor(sheetXml)) {
            XMLStreamReader xml = reader.reader();
            while (xml.hasNext() && rows.size() < limits.maxRowsPerSheet()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
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
                        placeCell(row, column, resolve(cell.toString(), cellType, sharedStrings), limits);
                    } else if ("row".equals(xml.getLocalName())) {
                        if (row.stream().anyMatch(value -> !value.isEmpty())) {
                            rows.add(List.copyOf(row));
                        }
                        row = new ArrayList<>();
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new UnreadableDocumentException("A sheet in this workbook could not be read.", e);
        }
        return rows;
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
