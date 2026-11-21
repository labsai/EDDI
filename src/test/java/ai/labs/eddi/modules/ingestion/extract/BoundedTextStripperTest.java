/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BoundedTextStripper")
class BoundedTextStripperTest {

    /** One reported line. */
    private record Seen(int page, String text, float x, float xEnd, float top, float fontSize) {
    }

    private static List<Seen> lines(byte[] pdf, int page) throws IOException {
        List<Seen> seen = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            var stripper = new BoundedTextStripper(Instant.now().plusSeconds(60));
            stripper.allowGlyphs(Long.MAX_VALUE);
            stripper.setLineListener((p, text, x, xEnd, top, size) -> seen.add(new Seen(p, text, x, xEnd, top, size)));
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            stripper.getText(document);
        }
        return seen;
    }

    @Nested
    @DisplayName("reports laid-out lines")
    class Positions {

        @Test
        @DisplayName("one call per line, with its words, page, left edge, top and font size")
        void lineByLine() throws IOException {
            List<Seen> seen = lines(PdfFixtures.headingsOnly(), 1);

            assertEquals("Annual Report 2026", seen.get(0).text());
            assertEquals(26f, seen.get(0).fontSize(), 0.01f);
            assertTrue(seen.get(1).text().startsWith("Body line 1: the quick brown fox"), seen.get(1).text());
            assertTrue(seen.stream().allMatch(line -> line.page() == 1));
            assertTrue(seen.stream().allMatch(line -> Math.abs(line.x() - 50f) < 0.5f), seen.toString());
            assertTrue(seen.stream().allMatch(line -> line.xEnd() > line.x()), seen.toString());
            for (int i = 1; i < seen.size(); i++) {
                assertTrue(seen.get(i).top() > seen.get(i - 1).top(), "tops grow down the page: " + seen);
            }
            assertEquals(1 + 4 + 1 + 8, seen.size(), seen.toString());
        }

        @Test
        @DisplayName("takes the font size the text matrix gives, not the one the font was set at")
        void scaledByTheTextMatrix() throws IOException {
            Seen heading = lines(PdfFixtures.headingsOnly(), 1).stream()
                    .filter(line -> line.text().equals("1 Introduction")).findFirst().orElseThrow();

            assertEquals(18f, heading.fontSize(), 0.01f);
        }

        @Test
        @DisplayName("measures a line's top above its baseline")
        void topIsAboveTheBaseline() throws IOException {
            // The fixture's first body line of page 2 has its baseline 740 pt from the
            // bottom of a 792 pt page, 52 pt from the top; its top lies above that.
            Seen first = lines(PdfFixtures.headingsOnly(), 2).getFirst();

            assertTrue(first.top() < 52f && first.top() > 52f - PdfFixtures.BODY_SIZE, String.valueOf(first.top()));
        }

        @Test
        @DisplayName("only observes: the text is the same with and without a listener")
        void observesOnly() throws IOException {
            Map<String, Supplier<byte[]>> fixtures = Map.of("headings", PdfFixtures::headingsOnly, "columns",
                    PdfFixtures::twoColumn, "plain", () -> PdfFixtures.plain(3), "outlined",
                    () -> PdfFixtures.outlined(2, 3));
            for (var fixture : fixtures.entrySet()) {
                try (PDDocument document = Loader.loadPDF(fixture.getValue().get())) {
                    var plain = new BoundedTextStripper(Long.MAX_VALUE, Duration.ofMinutes(1));
                    var observed = new BoundedTextStripper(Long.MAX_VALUE, Duration.ofMinutes(1));
                    List<String> seen = new ArrayList<>();
                    observed.setLineListener((p, text, x, xEnd, top, size) -> seen.add(text));

                    assertEquals(plain.getText(document), observed.getText(document), fixture.getKey());
                    assertFalse(seen.isEmpty(), fixture.getKey());
                }
            }
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("stops a long page program at the deadline, with the ingestion message")
        void deadlineIngestion() throws IOException {
            try (PDDocument document = Loader.loadPDF(longProgram(20_000))) {
                var stripper = new BoundedTextStripper(Instant.now().minusSeconds(1));
                stripper.allowGlyphs(Long.MAX_VALUE);

                var failure = assertThrows(UnreadableDocumentException.class, () -> stripper.getText(document));
                assertTrue(failure.getMessage().contains("too long"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("stops a long page program at the deadline, naming it, for the attachment path")
        void deadlineAttachment() throws IOException {
            try (PDDocument document = Loader.loadPDF(longProgram(20_000))) {
                var stripper = new BoundedTextStripper(Long.MAX_VALUE, Duration.ZERO);

                var failure = assertThrows(IOException.class, () -> stripper.getText(document));
                assertTrue(failure.getMessage().contains("exceeded 0 seconds"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("still refuses when the deadline passes inside a form PDFBox swallows the error of")
        void refusalSurvivesAForm() throws IOException {
            // The page is one "Do"; PDFBox logs and swallows the IOException thrown
            // inside the form, and no operator follows to throw again: only the check
            // at the end of getText is left to refuse.
            try (PDDocument document = Loader.loadPDF(formWithLongProgram(20_000))) {
                var stripper = new BoundedTextStripper(Long.MAX_VALUE, Duration.ZERO);

                assertThrows(IOException.class, () -> stripper.getText(document));
            }
        }

        @Test
        @DisplayName("cuts a page at its glyph allowance, keeps what was laid out, and says so")
        void glyphAllowancePerPage() throws IOException {
            try (PDDocument document = Loader.loadPDF(PdfFixtures.plain(2))) {
                var stripper = new BoundedTextStripper(Instant.now().plusSeconds(60));
                stripper.setStartPage(1);
                stripper.setEndPage(1);
                stripper.allowGlyphs(10);

                String text = stripper.getText(document);

                assertTrue(stripper.pageWasCut());
                assertEquals("Page 1 lin", text.strip());

                stripper.allowGlyphs(Long.MAX_VALUE);
                stripper.getText(document);
                assertFalse(stripper.pageWasCut(), "a new allowance clears the flag");
            }
        }
    }

    /** One page whose program is {@code operators} pairs of q/Q. */
    private static byte[] longProgram(int operators) throws IOException {
        try (PDDocument document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                out.write("q Q\n".repeat(operators).getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return save(document);
        }
    }

    /**
     * One page that draws one form, whose program is {@code operators} q/Q pairs.
     */
    private static byte[] formWithLongProgram(int operators) throws IOException {
        try (PDDocument document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var form = new PDFormXObject(document);
            form.setBBox(new PDRectangle(100, 100));
            try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                out.write("q Q\n".repeat(operators).getBytes(StandardCharsets.US_ASCII));
            }
            var resources = new PDResources();
            resources.put(COSName.getPDFName("Fm1"), form);
            page.setResources(resources);
            var stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write("/Fm1 Do\n".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        var out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
