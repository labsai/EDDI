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

class BoundedPdfLoaderTest {

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
