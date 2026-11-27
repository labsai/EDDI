/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BoundedPdfLoaderTest {

    /**
     * About 150 bytes: one cross-reference stream with zero-width fields declaring
     * two billion entries, which {@code startxref} points at. Unbounded, PDFBox
     * stores every one of them and exhausts a 256 MB heap in seconds.
     */
    static final byte[] XREF_STREAM_BOMB = ("%PDF-1.5\n1 0 obj\n<</Type /XRef /W [0 0 0] /Size 2000000000 /Length 1>>"
            + "stream\nX\nendstream\nendobj\nstartxref\n9\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);

    @Test
    @DisplayName("the 150-byte cross-reference stream bomb is refused quickly, not stored")
    void xrefStreamBomb() {
        var refused = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(
                UnreadableDocumentException.class,
                () -> BoundedPdfLoader.load(XREF_STREAM_BOMB, IOUtils.createMemoryOnlyStreamCache())));
        assertTrue(refused.getMessage().contains("declares more than " + BoundedPdfLoader.DEFAULT_MAX_XREF_ENTRIES
                + " objects"), refused.getMessage());
    }

    @Test
    @DisplayName("the bomb is refused through the ingestion PDF extractor with the user-facing message")
    void xrefStreamBombThroughTextExtractor() {
        var refused = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(
                UnreadableDocumentException.class,
                () -> new PdfTextExtractor().extract(XREF_STREAM_BOMB, ExtractionLimits.defaults())));
        assertTrue(refused.getMessage().contains("declares more than"), refused.getMessage());
    }

    @ParameterizedTest(name = "lenient = {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("entries are counted exactly as PDFBox stores them: five in use pass at cap 5, fail at cap 4")
    void entriesCountedExactly(boolean lenient) throws IOException {
        byte[] pdf = fiveObjectPdf();

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 5, lenient)) {
            assertEquals(1, document.getNumberOfPages());
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf), IOUtils.createMemoryOnlyStreamCache(),
                        4, lenient));
        assertTrue(refused.getMessage().contains("declares more than 4 objects"), refused.getMessage());
    }

    @Test
    @DisplayName("a table that breaks partway is rebuilt and counted once, not refused for the entries read before the break")
    void lenientRebuildCountsAgainFromZero() throws IOException {
        // Five entries are stored, then a corrupt sixth line makes PDFBox give up on
        // the table and rebuild it from the five objects it finds. Ten calls in all;
        // the rebuild holds five.
        byte[] pdf = fiveObjectPdf(true);

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 5, true)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        assertThrows(UnreadableDocumentException.class, () -> BoundedPdfLoader
                .load(new RandomAccessReadBuffer(pdf), IOUtils.createMemoryOnlyStreamCache(), 4, true));
    }

    @Test
    @DisplayName("object-stream entries a lenient rebuild adds are counted: a header listing 200 objects is refused at cap 50, loads at cap 5000")
    void objectStreamEntriesAddedByLenientRebuildAreCounted() throws IOException {
        byte[] pdf = objectStreamWithoutXrefPdf(200);

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 5000, true)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf), IOUtils.createMemoryOnlyStreamCache(),
                        50, true));
        assertTrue(refused.getMessage().contains("declares more than 50 objects"), refused.getMessage());
    }

    @Test
    @DisplayName("a rebuild refuses an object stream whose /N passes the cap before reading its header, even when the header lists one object")
    void objectStreamDeclaringMoreThanTheCapIsRefusedBeforeItsHeaderIsRead() throws IOException {
        // The header holds one entry, so the table never nears the cap: only the
        // declared /N, which sizes the map PDFBox fills from the header, can refuse it.
        byte[] pdf = objectStreamsWithoutXrefPdf(1, 200);

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 5000, true)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf), IOUtils.createMemoryOnlyStreamCache(),
                        50, true));
        assertTrue(refused.getMessage().contains("declares more than 50 objects"), refused.getMessage());
    }

    @Test
    @DisplayName("the objects object streams declare are totalled for the document: two streams of 30 pass at cap 60, fail at cap 59")
    void objectStreamDeclarationsAreTotalledPerDocument() throws IOException {
        byte[] pdf = objectStreamsWithoutXrefPdf(1, 30, 30);

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 60, true)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf), IOUtils.createMemoryOnlyStreamCache(),
                        59, true));
        assertTrue(refused.getMessage().contains("declares more than 59 objects"), refused.getMessage());
    }

    @ParameterizedTest(name = "lenient = {0}, indirect /N = {1}")
    @CsvSource({"true, false", "false, false", "true, true", "false, true"})
    @DisplayName("an ordinary file whose catalog sits in an object stream: /N 1 loads at cap 50, /N 60 is refused")
    void objectStreamReadThroughTheCrossReferenceStreamIsCounted(boolean lenient, boolean indirect)
            throws IOException {
        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(compressedCatalogPdf(1, indirect)),
                IOUtils.createMemoryOnlyStreamCache(), 50, lenient)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        byte[] pdf = compressedCatalogPdf(60, indirect);
        var refused = assertThrows(UnreadableDocumentException.class, () -> {
            // Wherever PDFBox first reads the catalog, at load or afterwards.
            try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                    IOUtils.createMemoryOnlyStreamCache(), 50, lenient)) {
                new PDFTextStripper().getText(document);
            }
        });
        assertTrue(refused.getMessage().contains("declares more than 50 objects"), refused.getMessage());
    }

    @Test
    @DisplayName("an object stream declaring a million objects is refused through the ingestion PDF extractor")
    void objectStreamBombThroughTextExtractor() {
        byte[] pdf = compressedCatalogPdf(1_000_000, false);
        var refused = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(
                UnreadableDocumentException.class,
                () -> new PdfTextExtractor().extract(pdf, ExtractionLimits.defaults())));
        assertTrue(refused.getMessage().contains("declares more than " + BoundedPdfLoader.DEFAULT_MAX_XREF_ENTRIES
                + " objects"), refused.getMessage());
        assertTrue(new PdfTextExtractor().extract(compressedCatalogPdf(1, false), ExtractionLimits.defaults())
                .contains("Hello"));
    }

    @Test
    @DisplayName("a refusal PDFBox swallows inside getSpaceWidth() is still reported once the document has been read")
    void refusalSwallowedByPdfBoxIsReportedAfterReading() throws IOException {
        byte[] pdf = spaceWidthInObjectStreamPdf(60);

        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(pdf),
                IOUtils.createMemoryOnlyStreamCache(), 50, true)) {
            // The premise: PDFBox catches the refusal and carries on with a guessed
            // width, so the stripper alone returns the text as if nothing happened.
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
            var refused = assertThrows(UnreadableDocumentException.class,
                    () -> BoundedPdfLoader.requireWithinCap(document));
            assertTrue(refused.getMessage().contains("declares more than 50 objects"), refused.getMessage());
        }
        try (PDDocument document = BoundedPdfLoader.load(new RandomAccessReadBuffer(spaceWidthInObjectStreamPdf(1)),
                IOUtils.createMemoryOnlyStreamCache(), 50, true)) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
            BoundedPdfLoader.requireWithinCap(document);
        }
    }

    @Test
    @DisplayName("the ingestion PDF extractor refuses a file whose refusal PDFBox swallowed, instead of returning its text")
    void swallowedRefusalThroughTextExtractor() {
        var refused = assertThrows(UnreadableDocumentException.class,
                () -> new PdfTextExtractor().extract(spaceWidthInObjectStreamPdf(1_000_000), ExtractionLimits.defaults()));
        assertTrue(refused.getMessage().contains("declares more than " + BoundedPdfLoader.DEFAULT_MAX_XREF_ENTRIES
                + " objects"), refused.getMessage());
        assertTrue(new PdfTextExtractor().extract(spaceWidthInObjectStreamPdf(1), ExtractionLimits.defaults())
                .contains("Hello"));
    }

    /**
     * The five objects of {@link #fiveObjectPdf()} and an object stream whose
     * header lists {@code listed} object numbers, with no cross-reference table and
     * no {@code startxref}, so PDFBox rebuilds the table by scanning the file and
     * writes the stream's entries into it without going through {@code setXRef}.
     */
    static byte[] objectStreamWithoutXrefPdf(int listed) {
        return objectStreamsWithoutXrefPdf(listed, listed);
    }

    /**
     * Like {@link #objectStreamWithoutXrefPdf(int)}, with one object stream per
     * element of {@code declared}: each lists {@code listed} object numbers of its
     * own and declares that element as its {@code /N}.
     */
    static byte[] objectStreamsWithoutXrefPdf(int listed, int... declared) {
        var out = new ByteArrayOutputStream();
        write(out, "%PDF-1.5\n");
        for (int i = 0; i < PAGE_OBJECTS.size(); i++) {
            write(out, (i + 1) + " 0 obj\n" + PAGE_OBJECTS.get(i) + "\nendobj\n");
        }
        for (int s = 0; s < declared.length; s++) {
            var header = new StringBuilder();
            for (int i = 0; i < listed; i++) {
                header.append(100 + s * listed + i).append(" 0 ");
            }
            write(out, (6 + s) + " 0 obj\n<</Type/ObjStm/N " + declared[s] + "/First " + header.length() + "/Length "
                    + header.length() + ">>stream\n" + header + "\nendstream\nendobj\n");
        }
        write(out, "trailer<</Size " + (6 + declared.length) + "/Root 1 0 R>>\n%%EOF\n");
        return out.toByteArray();
    }

    /**
     * An intact PDF 1.5 file: the catalog is the one object of object stream 6, and
     * a cross-reference stream says so, so PDFBox reads the object stream on its
     * ordinary path, with no rebuild. The stream declares {@code declared} objects
     * while its header lists only the catalog, directly or, with {@code indirectN},
     * through object 8.
     */
    static byte[] compressedCatalogPdf(int declared, boolean indirectN) {
        var out = new ByteArrayOutputStream();
        write(out, "%PDF-1.5\n");
        long[] offsets = new long[indirectN ? 9 : 8];
        for (int i = 1; i < PAGE_OBJECTS.size(); i++) {
            offsets[i + 1] = out.size();
            write(out, (i + 1) + " 0 obj\n" + PAGE_OBJECTS.get(i) + "\nendobj\n");
        }
        offsets[1] = -6;
        offsets[6] = out.size();
        writeObjectStream(out, 6, 1, PAGE_OBJECTS.get(0), indirectN ? "8 0 R" : String.valueOf(declared));
        if (indirectN) {
            offsets[8] = out.size();
            write(out, "8 0 obj\n" + declared + "\nendobj\n");
        }
        appendXrefStream(out, offsets, 7);
        return out.toByteArray();
    }

    /**
     * An intact PDF 1.5 file reading "Hello", whose font descriptor's
     * {@code /MissingWidth} is object 9, the one object of object stream 7, which
     * declares {@code declared} objects. The font's {@code /Widths} cover every
     * glyph the page shows but not the space, so the first and only read of object
     * 9 happens inside {@code PDFont.getSpaceWidth()}, which catches every
     * exception, logs it and assumes a width.
     */
    public static byte[] spaceWidthInObjectStreamPdf(int declared) {
        String widths = "500 ".repeat('o' - 'H' + 1);
        List<String> objects = List.of(
                PAGE_OBJECTS.get(0),
                PAGE_OBJECTS.get(1),
                PAGE_OBJECTS.get(2),
                PAGE_OBJECTS.get(3),
                "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/FirstChar " + (int) 'H' + "/LastChar " + (int) 'o'
                        + "/Widths[" + widths + "]/FontDescriptor 6 0 R>>",
                "<</Type/FontDescriptor/FontName/Helvetica/Flags 32/MissingWidth 9 0 R>>");
        var out = new ByteArrayOutputStream();
        write(out, "%PDF-1.5\n");
        long[] offsets = new long[10];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        offsets[7] = out.size();
        writeObjectStream(out, 7, 9, "250", String.valueOf(declared));
        offsets[9] = -7;
        appendXrefStream(out, offsets, 8);
        return out.toByteArray();
    }

    /**
     * Object stream {@code number} holding the one object {@code member},
     * {@code body}, and declaring {@code n} as its {@code /N}.
     */
    private static void writeObjectStream(ByteArrayOutputStream out, int number, int member, String body, String n) {
        String header = member + " 0 ";
        String data = header + body;
        write(out, number + " 0 obj\n<</Type/ObjStm/N " + n + "/First " + header.length() + "/Length "
                + data.length() + ">>stream\n" + data + "\nendstream\nendobj\n");
    }

    /**
     * Writes a cross-reference stream as object {@code xrefObject} and points
     * {@code startxref} at it. {@code offsets[i]} is object i's offset, or minus
     * the number of the object stream holding it as its first object; entry 0 is
     * free, and the stream's own entry is filled in here.
     */
    private static void appendXrefStream(ByteArrayOutputStream out, long[] offsets, int xrefObject) {
        offsets[xrefObject] = out.size();
        var entries = new ByteArrayOutputStream();
        entries.writeBytes(new byte[]{0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF});
        for (int i = 1; i < offsets.length; i++) {
            long value = Math.abs(offsets[i]);
            entries.writeBytes(new byte[]{(byte) (offsets[i] < 0 ? 2 : 1), (byte) (value >> 24), (byte) (value >> 16),
                    (byte) (value >> 8), (byte) value, 0, 0});
        }
        write(out, xrefObject + " 0 obj\n<</Type/XRef/Size " + offsets.length + "/W[1 4 2]/Root 1 0 R/Length "
                + entries.size() + ">>stream\n");
        out.writeBytes(entries.toByteArray());
        write(out, "\nendstream\nendobj\nstartxref\n" + offsets[xrefObject] + "\n%%EOF\n");
    }

    private static final String PAGE_CONTENT = "BT /F1 24 Tf 72 700 Td (Hello) Tj ET";

    /** Objects 1 to 5 of a one-page document reading "Hello". */
    private static final List<String> PAGE_OBJECTS = List.of(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>",
            "<</Length " + PAGE_CONTENT.length() + ">>stream\n" + PAGE_CONTENT + "\nendstream",
            "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");
    @Test
    @DisplayName("an ordinary PDF loads under the default cap and reads as before")
    void ordinaryPdfLoads() throws IOException {
        try (PDDocument document = BoundedPdfLoader.load(fiveObjectPdf(), IOUtils.createMemoryOnlyStreamCache())) {
            assertEquals("Hello", new PDFTextStripper().getText(document).strip());
        }
        String text = new PdfTextExtractor().extract(fiveObjectPdf(), ExtractionLimits.defaults());
        assertTrue(text.contains("Hello"), text);
    }

    /**
     * A hand-written PDF with a classic cross-reference table: objects 1..5 in use
     * and the free entry 0. Offsets are computed as the file is written, so the
     * table is exact and PDFBox has nothing to repair.
     */
    static byte[] fiveObjectPdf() {
        return fiveObjectPdf(false);
    }

    /**
     * @param corruptSixthEntry
     *            whether the table declares a sixth entry whose type is neither
     *            {@code n} nor {@code f}, which PDFBox reads as a broken table and,
     *            leniently, rebuilds by scanning the file
     */
    static byte[] fiveObjectPdf(boolean corruptSixthEntry) {
        String content = "BT /F1 24 Tf 72 700 Td (Hello) Tj ET";
        List<String> objects = List.of(
                "<</Type/Catalog/Pages 2 0 R>>",
                "<</Type/Pages/Kids[3 0 R]/Count 1>>",
                "<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>",
                "<</Length " + content.length() + ">>stream\n" + content + "\nendstream",
                "<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");
        var out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        long[] offsets = new long[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        int declared = objects.size() + 1 + (corruptSixthEntry ? 1 : 0);
        var table = new StringBuilder("xref\n0 ").append(declared).append("\n0000000000 65535 f \n");
        for (long offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        if (corruptSixthEntry) {
            table.append("0000000009 00000 x \n");
        }
        table.append("trailer<</Size ").append(objects.size() + 1).append("/Root 1 0 R>>\nstartxref\n").append(xref)
                .append("\n%%EOF\n");
        write(out, table.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
