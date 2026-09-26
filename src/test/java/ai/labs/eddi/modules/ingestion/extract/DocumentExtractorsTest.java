/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading text out of the file formats an operator uploads.
 *
 * <p>
 * The interesting half of these cases is the hostile one. An uploaded file is
 * not the operator's own data in any meaningful sense — it is whatever somebody
 * dragged into a browser — so the parser has to survive an archive that
 * decompresses to gigabytes, an XML document that expands entities into the
 * same, a spreadsheet whose cells skip columns, and a file whose name says one
 * thing while its bytes say another.
 */
@DisplayName("Document extraction")
class DocumentExtractorsTest {

    private final ExtractionLimits limits = ExtractionLimits.defaults();
    private final DocumentExtractors extractors = new DocumentExtractors(List.of(
            new PdfTextExtractor(), new WordTextExtractor(), new ExcelTextExtractor(),
            new PowerPointTextExtractor(), new PlainTextExtractor(), new CsvTextExtractor(),
            new HtmlDocumentExtractor(new HtmlToMarkdownConverter())));

    @Nested
    @DisplayName("Word")
    class Word {

        private final WordTextExtractor extractor = new WordTextExtractor();

        @Test
        @DisplayName("keeps headings as headings")
        void keepsHeadings() {
            String markdown = extractor.extract(
                    OfficeFixtures.docx("Heading1|Leave policy", "|You get 30 days."), limits);

            // Not cosmetic: the chunker splits on headings, and a retrieved passage
            // that has lost its section title cannot be placed by whoever reads it.
            assertTrue(markdown.startsWith("# Leave policy"), markdown);
            assertTrue(markdown.contains("You get 30 days."), markdown);
        }

        @Test
        @DisplayName("understands the styles Word actually writes")
        void understandsWordsOwnStyleNames() {
            assertTrue(extractor.extract(OfficeFixtures.docx("heading 2|Sub"), limits).startsWith("## Sub"));
            assertTrue(extractor.extract(OfficeFixtures.docx("Title|Top"), limits).startsWith("# Top"));
            assertFalse(extractor.extract(OfficeFixtures.docx("BodyText|Plain"), limits).startsWith("#"));
        }

        @Test
        @DisplayName("writes list items as list items")
        void writesListItems() {
            assertTrue(extractor.extract(OfficeFixtures.docxWithListItem("First point"), limits)
                    .contains("- First point"));
        }

        @Test
        @DisplayName("refuses a file that is not a Word document")
        void refusesSomethingElse() {
            byte[] spreadsheet = OfficeFixtures.xlsx("Sheet1", List.of("x"), List.of(Map.of("A1", 0)));
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(spreadsheet, limits));
            assertTrue(failure.getMessage().contains("not a Word document"), failure.getMessage());
        }

        @Test
        @DisplayName("refuses malformed XML instead of returning half a document")
        void refusesMalformedXml() {
            byte[] broken = OfficeFixtures.zip(Map.of("word/document.xml",
                    "<w:document><w:body><w:p><w:r><w:t>unclosed"));
            assertThrows(UnreadableDocumentException.class, () -> extractor.extract(broken, limits));
        }
    }

    @Nested
    @DisplayName("PowerPoint")
    class PowerPoint {

        private final PowerPointTextExtractor extractor = new PowerPointTextExtractor();

        @Test
        @DisplayName("keeps slides in the deck's order, not the archive's")
        void ordersSlidesByNumber() {
            String markdown = extractor.extract(
                    OfficeFixtures.pptxInReverseArchiveOrder("Agenda", "Results"), limits);

            // A deck whose slides come back shuffled reads as a different deck, and
            // ZIP entry order is not slide order.
            assertTrue(markdown.indexOf("Agenda") < markdown.indexOf("Results"), markdown);
            assertTrue(markdown.contains("## Slide 1"), markdown);
        }

        @Test
        @DisplayName("names the slide each passage came from")
        void numbersSlides() {
            String markdown = extractor.extract(OfficeFixtures.pptx("One", "Two"), limits);
            assertTrue(markdown.contains("## Slide 2"), markdown);
        }

        @Test
        @DisplayName("follows the deck's own index, not the part numbers")
        void followsTheDeckIndex() {
            // Reordering a deck in PowerPoint rewrites the index and leaves the part
            // names alone, so slide1.xml is routinely not the first slide. Reading
            // the numbers gives the order the slides were created in — wrong in a
            // way nobody notices until a passage cites the wrong slide.
            String markdown = extractor.extract(
                    OfficeFixtures.pptxWithIndex("Opening remarks", "Closing remarks"), limits);

            assertTrue(markdown.indexOf("Opening remarks") < markdown.indexOf("Closing remarks"), markdown);
            assertTrue(markdown.indexOf("## Slide 1") < markdown.indexOf("Closing remarks"), markdown);
        }

        @Test
        @DisplayName("stops at the slide limit")
        void stopsAtTheSlideLimit() {
            String[] slides = new String[10];
            for (int i = 0; i < slides.length; i++) {
                slides[i] = "Slide text " + i;
            }
            String markdown = extractor.extract(OfficeFixtures.pptx(slides),
                    new ExtractionLimits(200_000, 3, 5_000, 64, 64L * 1024 * 1024));

            assertTrue(markdown.contains("## Slide 3"), markdown);
            assertFalse(markdown.contains("## Slide 4"), markdown);
        }

        @Test
        @DisplayName("refuses a presentation with no slides")
        void refusesAnEmptyDeck() {
            assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(OfficeFixtures.zip(Map.of("ppt/presentation.xml", "<p/>")), limits));
        }
    }

    @Nested
    @DisplayName("Excel")
    class Excel {

        private final ExcelTextExtractor extractor = new ExcelTextExtractor();

        @Test
        @DisplayName("renders a sheet as a table under its own name")
        void rendersATable() {
            byte[] workbook = OfficeFixtures.xlsx("Headcount",
                    List.of("City", "People", "Berlin", "412"),
                    List.of(Map.of("A1", 0, "B1", 1), Map.of("A2", 2, "B2", 3)));

            String markdown = extractor.extract(workbook, limits);

            assertTrue(markdown.contains("## Headcount"), markdown);
            assertTrue(markdown.contains("| City | People |"), markdown);
            assertTrue(markdown.contains("| Berlin | 412 |"), markdown);
        }

        @Test
        @DisplayName("keeps a value under its own header when a cell is missing")
        void keepsColumnsAlignedAcrossGaps() {
            // B2 is absent. A reader that appends cells in document order would put
            // "Remote" under "People" and quietly rewrite the data.
            byte[] workbook = OfficeFixtures.xlsx("Headcount",
                    List.of("City", "People", "Note", "Berlin", "Remote"),
                    List.of(Map.of("A1", 0, "B1", 1, "C1", 2), Map.of("A2", 3, "C2", 4)));

            String markdown = extractor.extract(workbook, limits);

            assertTrue(markdown.contains("| Berlin |  | Remote |"), markdown);
        }

        @Test
        @DisplayName("escapes a cell that contains a pipe")
        void escapesPipes() {
            byte[] workbook = OfficeFixtures.xlsx("S",
                    List.of("A", "x|y"),
                    List.of(Map.of("A1", 0), Map.of("A2", 1)));

            // Unescaped, this would end the cell early and shift the whole row.
            assertTrue(extractor.extract(workbook, limits).contains("x\\|y"));
        }

        @Test
        @DisplayName("reads a cell that carries its text inline")
        void readsInlineStrings() {
            // Not every writer uses the shared-string table; a cell can carry its own
            // text. Reading only the table leaves those cells empty.
            assertTrue(extractor.extract(OfficeFixtures.xlsxWithInlineString("S", "Typed here"), limits)
                    .contains("Typed here"));
        }

        @Test
        @DisplayName("refuses a file with no workbook")
        void refusesSomethingElse() {
            assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(OfficeFixtures.docx("|hello"), limits));
        }
    }

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private final PdfTextExtractor extractor = new PdfTextExtractor();

        @Test
        @DisplayName("reads every page")
        void readsPages() {
            String markdown = extractor.extract(OfficeFixtures.pdf("First page", "Second page"), limits);
            assertTrue(markdown.contains("First page"), markdown);
            assertTrue(markdown.contains("Second page"), markdown);
        }

        @Test
        @DisplayName("gives nothing for a scan with no text layer")
        void handlesAScan() {
            // Not an error: a page of pixels simply has no text, and the caller
            // reports an empty document rather than a failure.
            assertEquals("", extractor.extract(OfficeFixtures.pdfWithNoText(), limits));
        }

        @Test
        @DisplayName("refuses an encrypted PDF instead of embedding an empty document")
        void refusesAnEncryptedPdf() {
            // PDFBox opens some encrypted files with an empty password and then
            // yields nothing useful. An empty document that reports success is worse
            // than a refusal: the operator sees a file in the list and no answers.
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(OfficeFixtures.encryptedPdf("Confidential"), limits));
            assertTrue(failure.getMessage().contains("password"), failure.getMessage());
        }

        @Test
        @DisplayName("refuses something that is not a PDF")
        void refusesNonPdf() {
            assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract("not a pdf".getBytes(StandardCharsets.UTF_8), limits));
        }

        @Test
        @DisplayName("stops at the character cap")
        void stopsAtTheCharacterCap() {
            String text = extractor.extract(OfficeFixtures.pdf("abcdefghij"),
                    limits.withMaxCharacters(4));
            assertTrue(text.length() <= 4, text);
        }

        /** A limit small enough that a test can exceed it without allocating much. */
        private final ExtractionLimits oneMegabyte = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);

        @Test
        @DisplayName("refuses a stream that inflates past the budget, before PDFBox decodes it")
        void refusesADeflateBomb() {
            // PDFBox decodes a stream whole into memory, with no limit of its own. A
            // few kilobytes of deflate expand to gigabytes, and the first read of such
            // a stream was an OutOfMemoryError in the ingestion worker.
            byte[] bomb = pdfWithStream(zlib(new byte[4 * 1024 * 1024]));

            var failure = assertThrows(UnreadableDocumentException.class, () -> extractor.extract(bomb, oneMegabyte));

            assertTrue(failure.getMessage().contains("expands"), failure.getMessage());
        }

        @Test
        @DisplayName("measures a stream the way PDFBox reads it, whatever its first two bytes say")
        void refusesABombBehindABogusHeader() {
            // PDFBox skips the zlib header without checking it. A scan that looked for
            // a valid header first would pass straight over this one.
            byte[] rawDeflate = deflate(new byte[4 * 1024 * 1024], true);
            byte[] disguised = new byte[rawDeflate.length + 2];
            disguised[0] = 'Q';
            disguised[1] = 'Q';
            System.arraycopy(rawDeflate, 0, disguised, 2, rawDeflate.length);

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(pdfWithStream(disguised), oneMegabyte));

            // Not merely refused — the fixture is no loadable PDF, so PDFBox would
            // refuse it too. Refused by the budget, before PDFBox saw it.
            assertTrue(failure.getMessage().contains("expands"), failure.getMessage());
        }

        @Test
        @DisplayName("measures a stream compressed twice at the level that expands most")
        void refusesANestedBomb() {
            byte[] twice = zlib(zlib(new byte[4 * 1024 * 1024]));
            assertTrue(zlib(new byte[4 * 1024 * 1024]).length < 1024 * 1024,
                    "the outer level alone must be within the budget, or this proves nothing");

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(pdfWithStream(twice, "[/FlateDecode /FlateDecode]"), oneMegabyte));

            assertTrue(failure.getMessage().contains("expands"), failure.getMessage());
        }

        // --- The review's bypasses, as loadable PDFs. Each is refused with the
        // pre-filter on, and — the part that matters — with it off, by the budget
        // on what PDFBox itself decodes. A 1 MB per-stream bound means a 2 MB
        // document budget here.

        @Test
        @DisplayName("a stream whose keyword is followed by spaces is still found and still bounded")
        void whitespaceAfterTheStreamKeyword() {
            // PDFBox skips spaces before the line break; the scan accepted only a line
            // break, so "stream \n" hid a stream PDFBox then decoded in full.
            byte[] pdf = loadablePdf(zlib(content(8 * 1024 * 1024)), "/FlateDecode", " \n", 1);

            assertRefusedBoth(pdf);
        }

        @Test
        @DisplayName("RunLength, which the scan could not measure, is bounded")
        void runLength() {
            var runs = new ByteArrayOutputStream();
            for (int i = 0; i < 40_000; i++) {
                runs.write(129);
                runs.write('q');
            }
            runs.write(128);

            assertRefusedBoth(loadablePdf(runs.toByteArray(), "/RunLengthDecode", "\n", 1));
        }

        @Test
        @DisplayName("ASCIIHex in front of Flate is bounded")
        void asciiHexThenFlate() {
            byte[] compressed = zlib(content(8 * 1024 * 1024));
            var hex = new StringBuilder();
            for (byte b : compressed) {
                hex.append(String.format("%02x", b));
            }
            hex.append('>');

            assertRefusedBoth(loadablePdf(hex.toString().getBytes(StandardCharsets.US_ASCII),
                    "[/ASCIIHexDecode /FlateDecode]", "\n", 1));
        }

        @Test
        @DisplayName("one stream referenced many times from a page is counted every time it is decoded")
        void repeatedReferenceToOneStream() {
            // Each decode is within the per-stream bound; PDFBox decodes every
            // element of a /Contents array at once, so twenty references to one
            // 60 MB stream were an OutOfMemoryError from a 61 KB file.
            byte[] pdf = loadablePdf(zlib(content(900 * 1024)), "/FlateDecode", "\n", 5);

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(pdf, oneMegabyte));
            assertTrue(failure.getMessage().contains("while it is read"), failure.getMessage());
        }

        @Test
        @DisplayName("an ordinary loadable PDF under the budget is read")
        void aSmallLoadablePdfIsRead() {
            byte[] page = zlib("BT /F1 12 Tf 72 700 Td (hello there) Tj ET\n".getBytes(StandardCharsets.US_ASCII));

            assertTrue(extractor.extract(loadablePdf(page, "/FlateDecode", "\n", 3), oneMegabyte, false)
                    .contains("hello there"));
        }

        @Test
        @DisplayName("the budget is not spent outside an extraction")
        void theBudgetOnlyCountsWhereItWasOpened() {
            // The filters are replaced process-wide, so every other use of PDFBox
            // passes through them; with no budget open they must count nothing.
            extractor.extract(OfficeFixtures.pdf("first"), oneMegabyte);

            assertTrue(extractor.extract(OfficeFixtures.pdf("second"), oneMegabyte).contains("second"));
            assertTrue(OfficeFixtures.pdf("x".repeat(10)).length > 0, "writing a PDF still works");
        }

        @Test
        @DisplayName("an image stream larger than the per-stream bound is not refused by the pre-filter")
        void largeImagesAreNotRefused() {
            // Text extraction never decodes images, so a large Flate image — an A2
            // scan, a CAD drawing — says nothing about what reading the text costs.
            byte[] image = zlib(content(4 * 1024 * 1024));
            var out = new ByteArrayOutputStream();
            out.writeBytes(("%PDF-1.4\n5 0 obj\n<< /Type /XObject /Subtype /Image /Width 2048 /Height 2048 "
                    + "/BitsPerComponent 8 /ColorSpace /DeviceGray /Length " + image.length
                    + " /Filter /FlateDecode >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(image);
            out.writeBytes("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            PdfStreamBudget.requireWithin(out.toByteArray(), 1024 * 1024, Instant.now().plusSeconds(30));
        }

        private void assertRefusedBoth(byte[] pdf) {
            var withScan = assertThrows(UnreadableDocumentException.class, () -> extractor.extract(pdf, oneMegabyte));
            assertTrue(withScan.getMessage().contains("compressed stream"), withScan.getMessage());
            var budgetOnly = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(pdf, oneMegabyte, false));
            assertTrue(budgetOnly.getMessage().contains("while it is read"), budgetOnly.getMessage());
        }

        /** Page content, a no-op operator repeated to the size wanted. */
        private byte[] content(int bytes) {
            return "q Q\n".repeat(bytes / 4).getBytes(StandardCharsets.US_ASCII);
        }

        /**
         * A real, loadable one-page PDF whose content stream is {@code data},
         * referenced {@code refs} times.
         */
        private byte[] loadablePdf(byte[] data, String filter, String afterKeyword, int refs) {
            var out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
            offsets.add(out.size());
            out.writeBytes("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n".getBytes(StandardCharsets.US_ASCII));
            offsets.add(out.size());
            out.writeBytes("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n".getBytes(StandardCharsets.US_ASCII));
            offsets.add(out.size());
            out.writeBytes(("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents ["
                    + "4 0 R ".repeat(refs) + "]>>endobj\n").getBytes(StandardCharsets.US_ASCII));
            offsets.add(out.size());
            out.writeBytes(("4 0 obj<</Length " + data.length + "/Filter " + filter + ">>stream" + afterKeyword)
                    .getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(data);
            out.writeBytes("\nendstream endobj\n".getBytes(StandardCharsets.US_ASCII));
            int xref = out.size();
            var trailer = new StringBuilder("xref\n0 5\n0000000000 65535 f \n");
            for (int offset : offsets) {
                trailer.append(String.format("%010d 00000 n \n", offset));
            }
            trailer.append("trailer<</Size 5/Root 1 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
            out.writeBytes(trailer.toString().getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        }

        @Test
        @DisplayName("a stream within the budget is not refused")
        void acceptsAStreamWithinTheBudget() {
            // The same fixture shape as the bombs, below the budget: what refuses it
            // is PDFBox, because it is no real PDF — not the budget.
            byte[] fine = pdfWithStream(zlib(new byte[512 * 1024]));

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(fine, oneMegabyte));

            assertFalse(failure.getMessage().contains("expands"), failure.getMessage());
        }

        @Test
        @DisplayName("gives up with a message once the time budget is spent")
        void honoursItsTimeBudget() {
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractor.extract(OfficeFixtures.pdf("Some text"), limits.withMaxDuration(Duration.ofNanos(1))));

            assertTrue(failure.getMessage().contains("too long"), failure.getMessage());
        }

        @Test
        @DisplayName("stops a page that lays out far more glyphs than could ever be kept")
        void stopsAPageThatClaimsTooManyGlyphs() {
            // A page's glyphs are held until the page is done, so one page claiming a
            // million of them was a million objects before the character cap applied.
            String huge = "x".repeat(25_000);

            String text = extractor.extract(OfficeFixtures.pdf("Intro", huge), limits.withMaxCharacters(100));

            assertEquals("Intro", text);
        }

        /**
         * Not a loadable PDF — it does not need to be: the budget is checked on the raw
         * bytes before PDFBox is given them.
         */
        private byte[] pdfWithStream(byte[] data) {
            return pdfWithStream(data, "/FlateDecode");
        }

        private byte[] pdfWithStream(byte[] data, String filter) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(("%PDF-1.4\n1 0 obj\n<< /Length " + data.length + " /Filter " + filter + " >>\nstream\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(data);
            out.writeBytes("\nendstream\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        }

        private byte[] zlib(byte[] data) {
            return deflate(data, false);
        }

        private byte[] deflate(byte[] data, boolean raw) {
            var deflater = new Deflater(Deflater.BEST_COMPRESSION, raw);
            try {
                deflater.setInput(data);
                deflater.finish();
                var out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
                return out.toByteArray();
            } finally {
                deflater.end();
            }
        }
    }

    @Nested
    @DisplayName("Probing an upload for text")
    class ProbingForText {

        @Test
        @DisplayName("refuses a scan with no text layer, and says why")
        void refusesAScan() {
            // It used to be stored, listed as waiting to be indexed, and skipped as
            // blank by every run, with nothing anywhere saying why.
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractors.requireText(OfficeFixtures.pdfWithNoText(), "application/pdf", limits));

            assertTrue(failure.getMessage().contains("no text layer"), failure.getMessage());
        }

        @Test
        @DisplayName("refuses a text file with nothing in it")
        void refusesBlankText() {
            assertThrows(UnreadableDocumentException.class, () -> extractors.requireText(
                    "  \n\t ".getBytes(StandardCharsets.UTF_8), "text/plain", limits));
        }

        @Test
        @DisplayName("accepts a file with text")
        void acceptsText() {
            extractors.requireText(OfficeFixtures.pdf("", "Second page has words"), "application/pdf", limits);
        }
    }

    @Nested
    @DisplayName("Text formats")
    class TextFormats {

        @Test
        @DisplayName("decodes UTF-8 with a byte-order mark")
        void decodesUtf8Bom() {
            byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
            assertEquals("hi", new PlainTextExtractor().extract(withBom, limits));
        }

        @Test
        @DisplayName("falls back rather than producing replacement characters")
        void fallsBackForNonUtf8() {
            // 0xE9 alone is "é" in Windows-1252 and invalid UTF-8. Decoding it as
            // UTF-8 anyway gives a document full of U+FFFD that embeds as nonsense.
            byte[] latin = new byte[]{'c', 'a', 'f', (byte) 0xE9};
            assertEquals("café", new PlainTextExtractor().extract(latin, limits));
        }

        @Test
        @DisplayName("reads a CSV as a table, quotes and commas included")
        void readsCsv() {
            String csv = "city,note\nBerlin,\"big, and grey\"\n";
            String markdown = new CsvTextExtractor().extract(csv.getBytes(StandardCharsets.UTF_8), limits);

            assertTrue(markdown.contains("| city | note |"), markdown);
            // Split on commas, this would become two cells and lose the header
            // alignment for the rest of the row.
            assertTrue(markdown.contains("| Berlin | big, and grey |"), markdown);
        }

        @Test
        @DisplayName("reads a semicolon-separated export")
        void readsSemicolonCsv() {
            String csv = "city;people\nBerlin;412\n";
            assertTrue(new CsvTextExtractor().extract(csv.getBytes(StandardCharsets.UTF_8), limits)
                    .contains("| Berlin | 412 |"));
        }

        @Test
        @DisplayName("converts an uploaded HTML file the way the crawler would")
        void convertsHtml() {
            String html = "<html><body><h1>Title</h1><p>Body text.</p></body></html>";
            String markdown = new HtmlDocumentExtractor(new HtmlToMarkdownConverter())
                    .extract(html.getBytes(StandardCharsets.UTF_8), limits);

            assertTrue(markdown.contains("# Title"), markdown);
            assertTrue(markdown.contains("Body text."), markdown);
        }
    }

    @Nested
    @DisplayName("Hostile input")
    class HostileInput {

        @Test
        @DisplayName("refuses an archive that decompresses past its budget")
        void refusesAZipBomb() {
            byte[] bomb = OfficeFixtures.zipBomb("word/document.xml", 4 * 1024 * 1024);
            var tightLimits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> new WordTextExtractor().extract(bomb, tightLimits));
            assertTrue(failure.getMessage().contains("expands to more than"), failure.getMessage());
        }

        @Test
        @DisplayName("charges an entry it does not want for what that entry decompresses to")
        void countsSkippedEntries() {
            // Moving to the next ZIP entry decompresses the rest of the current one,
            // so an entry nobody wants is the cheapest place to hide a bomb: the
            // work happens either way and nothing counts it.
            byte[] bomb = OfficeFixtures.bombInAnIgnoredPart("word/document.xml", 4 * 1024 * 1024);
            var tightLimits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> new WordTextExtractor().extract(bomb, tightLimits));
            assertTrue(failure.getMessage().contains("expands to more than"), failure.getMessage());
        }

        @Test
        @DisplayName("charges the same entry when only sniffing the format")
        void countsSkippedEntriesWhileSniffing() {
            // Format detection runs inside the upload request, which is where an
            // unbounded inflate hurts most.
            byte[] bomb = OfficeFixtures.bombInAnIgnoredPart("word/document.xml", 4 * 1024 * 1024);
            var tightLimits = new ExtractionLimits(200_000, 500, 5_000, 64, 1024 * 1024);

            assertThrows(UnreadableDocumentException.class,
                    () -> extractors.resolveMimeType("big.docx", bomb, tightLimits));
        }

        @Test
        @DisplayName("refuses an archive that names the same part twice")
        void refusesDuplicateParts() {
            // Two readers can disagree about which copy is the document, which is how
            // a file passes review saying one thing and ingests another.
            byte[] ambiguous = OfficeFixtures.withDuplicatePart("word/document.xml",
                    "<w:document/>", "<w:document/>");
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> new WordTextExtractor().extract(ambiguous, limits));
            assertTrue(failure.getMessage().contains("same part twice"), failure.getMessage());
        }

        @Test
        @DisplayName("does not expand XML entities")
        void doesNotExpandEntities() {
            // The billion-laughs attack. With entity expansion on, this one small
            // part becomes gigabytes of "lol" before anything else can intervene.
            String billionLaughs = """
                    <?xml version="1.0"?>
                    <!DOCTYPE w:document [
                      <!ENTITY a "lol">
                      <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                      <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                    ]>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>&c;</w:t></w:r></w:p></w:body>
                    </w:document>
                    """;
            byte[] file = OfficeFixtures.zip(Map.of("word/document.xml", billionLaughs));

            // DTDs are refused outright, so this fails rather than expanding. Either
            // outcome is acceptable — what must not happen is a successful parse
            // carrying the expansion.
            // The DTD is refused outright, so this never reaches the expansion. A
            // parser that merely declined to expand would leave the entity
            // unresolved and still be safe, but this build refuses the document, and
            // asserting the weaker property would pass even if DTDs were re-enabled.
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> new WordTextExtractor().extract(file, limits));
            assertNotNull(failure.getMessage());
        }
    }

    @Nested
    @DisplayName("Deciding what a file is")
    class DecidingWhatAFileIs {

        @Test
        @DisplayName("reads the bytes, not the name")
        void readsTheBytesNotTheName() {
            // A spreadsheet saved with a .docx name. Trusting the name would hand it
            // to the Word extractor, which would refuse it as corrupt — and the
            // operator would be told their working file is broken.
            byte[] workbook = OfficeFixtures.xlsx("S", List.of("a"), List.of(Map.of("A1", 0)));

            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    extractors.resolveMimeType("quarterly.docx", workbook));
        }

        @Test
        @DisplayName("identifies each Office format from its own parts")
        void identifiesEachOfficeFormat() {
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    extractors.resolveMimeType("a.docx", OfficeFixtures.docx("|hi")));
            assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    extractors.resolveMimeType("a.pptx", OfficeFixtures.pptx("hi")));
            assertEquals("application/pdf", extractors.resolveMimeType("a.pdf", OfficeFixtures.pdf("hi")));
        }

        @Test
        @DisplayName("uses the extension only where the bytes carry no signature")
        void usesTheExtensionForTextOnly() {
            byte[] text = "# Notes".getBytes(StandardCharsets.UTF_8);
            assertEquals("text/markdown", extractors.resolveMimeType("notes.md", text));
            assertEquals("text/csv", extractors.resolveMimeType("rows.csv", text));
            // An unknown extension on readable text is still text.
            assertEquals("text/plain", extractors.resolveMimeType("notes.rst", text));
        }

        @Test
        @DisplayName("tells an operator how to fix a legacy Office file")
        void namesTheFixForLegacyOfficeFiles() {
            byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1, 0, 0};

            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractors.resolveMimeType("old.doc", ole2));
            // "Not a document EDDI can read" would be true and useless; the fix is
            // one Save As away and the message says so.
            assertTrue(failure.getMessage().contains(".docx"), failure.getMessage());
        }

        @Test
        @DisplayName("refuses a plain ZIP rather than storing an archive nobody can read")
        void refusesAPlainZip() {
            byte[] archive = OfficeFixtures.zip(Map.of("notes.txt", "hello"));
            var failure = assertThrows(UnreadableDocumentException.class,
                    () -> extractors.resolveMimeType("bundle.zip", archive));
            assertTrue(failure.getMessage().contains("ZIP archive"), failure.getMessage());
        }

        @Test
        @DisplayName("text under a text name stays text, whatever its first two bytes are")
        void readableTextBeatsAWeakSignature() {
            // Magic-byte detection is a prefix match on short signatures: "BM" is a
            // bitmap, "ID3" is an MP3, and "%PDF-" anywhere in the first kilobyte is
            // a PDF. A Markdown file may legitimately begin with any of them.
            byte[] looksLikeABitmap = "BMW service intervals\n".getBytes(StandardCharsets.UTF_8);
            assertEquals("text/markdown", extractors.resolveMimeType("cars.md", looksLikeABitmap));

            byte[] mentionsPdf = "See the %PDF- header for details".getBytes(StandardCharsets.UTF_8);
            assertEquals("text/plain", extractors.resolveMimeType("notes.txt", mentionsPdf));
        }

        @Test
        @DisplayName("accepts a UTF-16 text file rather than calling its NUL bytes binary")
        void acceptsUtf16() {
            // UTF-16 puts a NUL in every other byte of ASCII, which is exactly the
            // test for "this is binary" — while the decoder handles it perfectly
            // well. Refusing it would contradict what the documentation promises.
            byte[] utf16 = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
            assertEquals("text/plain", extractors.resolveMimeType("notes.txt", utf16));
            assertEquals("hi", new PlainTextExtractor().extract(utf16, limits));
        }

        @Test
        @DisplayName("refuses an image")
        void refusesAnImage() {
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
            assertThrows(UnreadableDocumentException.class,
                    () -> extractors.resolveMimeType("chart.png", png));
        }

        @Test
        @DisplayName("refuses text under a name that claims a format with a signature")
        void refusesTextNamedAsABinaryFormat() {
            // The name's word used to be taken, so a text file renamed .pdf or .docx
            // was stored as one — and failed on every run as a corrupt document.
            byte[] text = "just some notes".getBytes(StandardCharsets.UTF_8);

            for (String name : List.of("notes.pdf", "notes.docx", "notes.xlsx", "notes.pptx")) {
                var failure = assertThrows(UnreadableDocumentException.class,
                        () -> extractors.resolveMimeType(name, text), name);
                assertTrue(failure.getMessage().contains("not a"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("refuses an empty file")
        void refusesAnEmptyFile() {
            assertThrows(UnreadableDocumentException.class,
                    () -> extractors.resolveMimeType("empty.txt", new byte[0]));
        }

        @Test
        @DisplayName("has an extractor for every type it will name")
        void hasAnExtractorForEveryTypeItNames() {
            // The listing shown to an operator and the set of files that can actually
            // be ingested are the same set, or the Manager offers a format that fails
            // on every run.
            for (String extension : DocumentExtractors.supportedExtensions()) {
                byte[] probe = switch (extension) {
                    case ".pdf" -> OfficeFixtures.pdf("x");
                    case ".docx" -> OfficeFixtures.docx("|x");
                    case ".xlsx" -> OfficeFixtures.xlsx("S", List.of("x"), List.of(Map.of("A1", 0)));
                    case ".pptx" -> OfficeFixtures.pptx("x");
                    default -> "text".getBytes(StandardCharsets.UTF_8);
                };
                String mimeType = extractors.resolveMimeType("probe" + extension, probe);
                assertTrue(extractors.extractorFor(mimeType).isPresent(),
                        extension + " resolves to " + mimeType + ", which nothing can extract");
            }
        }
    }
}
