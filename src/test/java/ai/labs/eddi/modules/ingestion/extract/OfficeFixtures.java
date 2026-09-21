/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Office and PDF files built in memory, for any test that needs a real one.
 *
 * <p>
 * Built rather than checked in as binaries. A committed .docx is opaque: when a
 * test fails, nobody can see what is in the file it failed on, and nobody can
 * make a variant of it — which is exactly what testing a parser needs, since
 * the interesting cases are the malformed ones. Here the XML is right next to
 * the assertion.
 */
public final class OfficeFixtures {

    private OfficeFixtures() {
    }

    /** A .docx whose body is the given paragraphs, each {@code style|text}. */
    public static byte[] docx(String... styledParagraphs) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : styledParagraphs) {
            int separator = paragraph.indexOf('|');
            String style = separator < 0 ? "" : paragraph.substring(0, separator);
            String text = separator < 0 ? paragraph : paragraph.substring(separator + 1);
            body.append("<w:p>");
            if (!style.isEmpty()) {
                body.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
            }
            body.append("<w:r><w:t>").append(escape(text)).append("</w:t></w:r></w:p>");
        }
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s</w:body>
                </w:document>
                """.formatted(body);
        return zip(Map.of("word/document.xml", document));
    }

    /** A .docx whose paragraph is a numbered list item. */
    static byte[] docxWithListItem(String text) {
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr>
                      <w:r><w:t>%s</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """.formatted(escape(text));
        return zip(Map.of("word/document.xml", document));
    }

    /** A .pptx with one slide per argument, each slide one line of text. */
    public static byte[] pptx(String... slideTexts) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (int i = 0; i < slideTexts.length; i++) {
            parts.put("ppt/slides/slide" + (i + 1) + ".xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                           xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                      <p:cSld><p:spTree><p:sp><p:txBody>
                        <a:p><a:r><a:t>%s</a:t></a:r></a:p>
                      </p:txBody></p:sp></p:spTree></p:cSld>
                    </p:sld>
                    """.formatted(escape(slideTexts[i])));
        }
        return zip(parts);
    }

    /**
     * A .pptx whose slides are written to the archive in the wrong order, so a
     * reader that trusts entry order gets them backwards.
     */
    static byte[] pptxInReverseArchiveOrder(String first, String second) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("ppt/slides/slide2.xml", slideXml(second));
        parts.put("ppt/slides/slide1.xml", slideXml(first));
        return zip(parts);
    }

    private static String slideXml(String text) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody>
                    <a:p><a:r><a:t>%s</a:t></a:r></a:p>
                  </p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """.formatted(escape(text));
    }

    /**
     * An .xlsx with one sheet.
     *
     * @param rows
     *            each row's cells, given as {@code A1}-style references mapped to
     *            shared-string indexes — so a gap in the references is a gap in the
     *            sheet
     */
    public static byte[] xlsx(String sheetName, List<String> sharedStrings, List<Map<String, Integer>> rows) {
        StringBuilder sheetData = new StringBuilder();
        for (int rowNumber = 1; rowNumber <= rows.size(); rowNumber++) {
            sheetData.append("<row r=\"").append(rowNumber).append("\">");
            for (Map.Entry<String, Integer> cell : rows.get(rowNumber - 1).entrySet()) {
                sheetData.append("<c r=\"").append(cell.getKey()).append("\" t=\"s\"><v>")
                        .append(cell.getValue()).append("</v></c>");
            }
            sheetData.append("</row>");
        }

        StringBuilder strings = new StringBuilder();
        for (String value : sharedStrings) {
            strings.append("<si><t>").append(escape(value)).append("</t></si>");
        }

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """.formatted(escape(sheetName)));
        parts.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1"
                      Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                      Target="worksheets/sheet1.xml"/>
                </Relationships>
                """);
        parts.put("xl/sharedStrings.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">%s</sst>
                """.formatted(strings));
        parts.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>%s</sheetData>
                </worksheet>
                """.formatted(sheetData));
        return zip(parts);
    }

    /** A PDF with one page per argument. */
    public static byte[] pdf(String... pageTexts) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF nobody can open without the password. */
    static byte[] encryptedPdf(String text) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            var permissions = new AccessPermission();
            permissions.setCanExtractContent(false);
            document.protect(new StandardProtectionPolicy("owner-secret", "user-secret", permissions));
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A deck whose slide index puts {@code slide2.xml} first — what reordering a
     * deck in PowerPoint produces, since it rewrites the index and leaves the part
     * names where they were.
     */
    static byte[] pptxWithIndex(String firstInDeck, String secondInDeck) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("ppt/slides/slide1.xml", slideXml(secondInDeck));
        parts.put("ppt/slides/slide2.xml", slideXml(firstInDeck));
        parts.put("ppt/presentation.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst>
                    <p:sldId id="256" r:id="rId2"/>
                    <p:sldId id="257" r:id="rId1"/>
                  </p:sldIdLst>
                </p:presentation>
                """);
        parts.put("ppt/_rels/presentation.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="slides/slide1.xml"
                      Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"/>
                  <Relationship Id="rId2" Target="slides/slide2.xml"
                      Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"/>
                </Relationships>
                """);
        return zip(parts);
    }

    /**
     * An archive whose oversized entry is one no extractor wants.
     *
     * <p>
     * Moving to the next ZIP entry decompresses the rest of the current one, so an
     * entry nobody asked for is the cheapest place to hide a bomb: skipping it
     * costs the same CPU as reading it, with nothing counting the cost.
     */
    static byte[] bombInAnIgnoredPart(String wantedPart, int uncompressedBytes) {
        Map<String, String> parts = new LinkedHashMap<>();
        // Outside every prefix the format sniff recognises, so it is walked past
        // rather than matched — which is what makes it a place to hide.
        parts.put("docProps/thumbnail.bin", "a".repeat(uncompressedBytes));
        parts.put(wantedPart, "<w:document xmlns:w=\"x\"><w:body/></w:document>");
        return zip(parts);
    }

    /**
     * An .xlsx whose cell carries its text inline instead of in the shared table.
     */
    static byte[] xlsxWithInlineString(String sheetName, String text) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """.formatted(escape(sheetName)));
        parts.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml"
                      Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>
                </Relationships>
                """);
        parts.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>%s</t></is></c></row>
                  </sheetData>
                </worksheet>
                """.formatted(escape(text)));
        return zip(parts);
    }

    /** A PDF page with no text at all — what a scan without OCR looks like. */
    static byte[] pdfWithNoText() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An archive whose one part decompresses to far more than it occupies — a zip
     * bomb, in miniature.
     */
    static byte[] zipBomb(String partName, int uncompressedBytes) {
        return zip(Map.of(partName, "a".repeat(uncompressedBytes)));
    }

    /**
     * An archive that names the same part twice, with different contents.
     *
     * <p>
     * Built by writing the second entry under a decoy name of the same length and
     * then rewriting that name in the bytes, because {@link ZipOutputStream}
     * refuses to produce a duplicate. Which is the point: a file like this is not
     * something a normal writer emits, and the only way to get one is the way an
     * attacker would.
     */
    static byte[] withDuplicatePart(String partName, String first, String second) {
        String decoy = partName.substring(0, partName.length() - 1) + " ";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(partName));
            zip.write(first.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(decoy));
            zip.write(second.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The name appears in the local header and again in the central directory,
        // and nothing checksums it — so a same-length substitution is enough.
        byte[] archive = out.toByteArray();
        byte[] from = decoy.getBytes(StandardCharsets.UTF_8);
        byte[] to = partName.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= archive.length; i++) {
            if (matches(archive, i, from)) {
                System.arraycopy(to, 0, archive, i, to.length);
            }
        }
        return archive;
    }

    private static boolean matches(byte[] haystack, int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    static byte[] zip(Map<String, String> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
