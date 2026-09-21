/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.AttachmentExtractionException;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.PdfInfo;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachmentTextExtractor}.
 */
class AttachmentTextExtractorTest {

    private final AttachmentTextExtractor extractor = new AttachmentTextExtractor(10_000);

    // ==================== canExtractText ====================

    @Nested
    class CanExtractText {

        @ParameterizedTest
        @ValueSource(strings = {
                "application/pdf", "text/plain", "text/csv", "text/markdown",
                "application/json", "application/xml", "application/csv",
                "application/yaml", "application/x-yaml", "application/x-ndjson",
                "application/ld+json", "image/svg+xml", "text/plain; charset=utf-8"})
        void shouldSupportTextLikeAndPdf(String mime) {
            assertTrue(extractor.canExtractText(mime), "should extract text for " + mime);
        }

        @ParameterizedTest
        @ValueSource(strings = {"image/png", "image/jpeg", "audio/mpeg", "video/mp4",
                "application/octet-stream", "application/zip"})
        void shouldNotSupportBinaryTypes(String mime) {
            assertFalse(extractor.canExtractText(mime), "should not extract text for " + mime);
        }

        @Test
        void shouldHandleNullMime() {
            assertFalse(extractor.canExtractText(null));
        }
    }

    // ==================== Text-like extraction ====================

    @Nested
    class TextExtraction {

        @Test
        void shouldDecodePlainText() throws Exception {
            byte[] bytes = "Hello, EDDI!".getBytes(StandardCharsets.UTF_8);
            assertEquals("Hello, EDDI!", extractor.extractText(bytes, "text/plain"));
        }

        @Test
        void shouldDecodeJson() throws Exception {
            byte[] bytes = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            assertEquals("{\"a\":1}", extractor.extractText(bytes, "application/json"));
        }

        @Test
        void shouldDecodeTextWithCharsetParam() throws Exception {
            byte[] bytes = "csv,data\n1,2".getBytes(StandardCharsets.UTF_8);
            assertEquals("csv,data\n1,2", extractor.extractText(bytes, "text/csv; charset=utf-8"));
        }

        @Test
        void shouldReturnEmptyForNullBytes() throws Exception {
            assertEquals("", extractor.extractText(null, "text/plain"));
        }

        @Test
        void shouldReturnEmptyForEmptyBytes() throws Exception {
            assertEquals("", extractor.extractText(new byte[0], "text/plain"));
        }

        @Test
        void shouldThrowForUnsupportedType() {
            byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
            var ex = assertThrows(AttachmentExtractionException.class,
                    () -> extractor.extractText(bytes, "image/png"));
            assertTrue(ex.getMessage().contains("image/png"));
        }

        @Test
        void shouldCapTextToMaxChars() throws Exception {
            byte[] bytes = "ABCDEFGHIJ".repeat(50).getBytes(StandardCharsets.UTF_8); // 500 chars
            String result = extractor.extractText(bytes, "text/plain", 100);
            assertTrue(result.startsWith("ABCDEFGHIJ"));
            assertTrue(result.contains("[Content truncated - showing first 100 characters]"));
        }

        @Test
        void shouldUseDefaultCapWhenMaxCharsNonPositive() throws Exception {
            var smallExtractor = new AttachmentTextExtractor(20);
            byte[] bytes = "ABCDEFGHIJ".repeat(10).getBytes(StandardCharsets.UTF_8); // 100 chars
            String result = smallExtractor.extractText(bytes, "text/plain", 0);
            assertTrue(result.contains("[Content truncated - showing first 20 characters]"));
        }
    }

    // ==================== Config cap ====================

    @Test
    void nonPositiveConfiguredCapFallsBackToDefault() {
        var ex = new AttachmentTextExtractor(0);
        assertEquals(AttachmentTextExtractor.DEFAULT_MAX_CHARS, ex.getDefaultMaxChars());
    }

    @Test
    void positiveConfiguredCapIsHonored() {
        var ex = new AttachmentTextExtractor(500);
        assertEquals(500, ex.getDefaultMaxChars());
    }

    // ==================== PDF extraction ====================

    @Nested
    class PdfExtraction {

        @Test
        void shouldExtractPdfTextViaGenericDispatch() throws Exception {
            byte[] pdf = createPdf("Hello EDDI World");
            String result = extractor.extractText(pdf, "application/pdf");
            assertTrue(result.contains("Hello EDDI World"), "got: " + result);
        }

        @Test
        void shouldExtractFullPdfText() throws Exception {
            byte[] pdf = createPdf("Some page content");
            String result = extractor.extractPdfText(pdf);
            assertTrue(result.contains("Some page content"));
        }

        @Test
        void shouldTruncateLongPdfText() throws Exception {
            var smallExtractor = new AttachmentTextExtractor(50);
            byte[] pdf = createPdf("ABCDEFGHIJ ".repeat(20)); // ~220 chars
            String result = smallExtractor.extractPdfText(pdf);
            assertTrue(result.contains("[Content truncated - showing first 50 characters]"), "got: " + result);
        }

        @Test
        void shouldExtractPageRange() throws Exception {
            byte[] pdf = createMultiPagePdf("First page", "Second page", "Third page");
            String result = extractor.extractPdfText(pdf, 2, 2, 10_000);
            assertTrue(result.contains("Second page"));
            assertFalse(result.contains("First page"));
            assertFalse(result.contains("Third page"));
        }

        @Test
        void shouldClampEndPageBeyondTotal() throws Exception {
            byte[] pdf = createMultiPagePdf("Page one", "Page two");
            String result = extractor.extractPdfText(pdf, 1, 99, 10_000);
            assertTrue(result.contains("Page one"));
            assertTrue(result.contains("Page two"));
        }

        @Test
        void shouldThrowWhenStartPageOutOfRange() throws Exception {
            byte[] pdf = createPdf("only page");
            var ex = assertThrows(AttachmentExtractionException.class,
                    () -> extractor.extractPdfText(pdf, 5, 10, 10_000));
            assertTrue(ex.getMessage().contains("out of range"));
        }

        @Test
        void shouldExtractPdfInfo() throws Exception {
            byte[] pdf = createPdfWithMetadata("My Title", "Jane Author", "The Subject", "The Creator");
            PdfInfo info = extractor.extractPdfInfo(pdf);
            assertEquals(1, info.numberOfPages());
            assertEquals("My Title", info.title());
            assertEquals("Jane Author", info.author());
            assertEquals("The Subject", info.subject());
            assertEquals("The Creator", info.creator());
            assertNotNull(info.creationDate());
        }

        @Test
        void shouldExtractPdfInfoWithoutMetadata() throws Exception {
            byte[] pdf = createPdf("bare");
            PdfInfo info = extractor.extractPdfInfo(pdf);
            assertEquals(1, info.numberOfPages());
            assertNull(info.title());
            assertNull(info.author());
        }

        @Test
        void shouldThrowOnCorruptPdf() {
            byte[] notAPdf = "this is not a pdf".getBytes(StandardCharsets.UTF_8);
            assertThrows(AttachmentExtractionException.class, () -> extractor.extractPdfText(notAPdf));
        }

        @Test
        void shouldThrowOnCorruptPdfInfo() {
            byte[] notAPdf = "still not a pdf".getBytes(StandardCharsets.UTF_8);
            assertThrows(AttachmentExtractionException.class, () -> extractor.extractPdfInfo(notAPdf));
        }
    }

    // ==================== Helpers ====================

    private static byte[] createPdf(String text) throws Exception {
        return createMultiPagePdf(text);
    }

    private static byte[] createMultiPagePdf(String... texts) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : texts) {
                var page = new PDPage();
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createPdfWithMetadata(String title, String author,
                                                String subject, String creator)
            throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("content");
                cs.endText();
            }
            var info = doc.getDocumentInformation();
            info.setTitle(title);
            info.setAuthor(author);
            info.setSubject(subject);
            info.setCreator(creator);
            info.setCreationDate(GregorianCalendar.from(
                    ZonedDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneId.of("UTC"))));
            doc.setDocumentInformation(info);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
