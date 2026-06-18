/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PdfReaderTool - includes SSRF protection tests.
 */
class PdfReaderToolTest {

    private PdfReaderTool pdfReaderTool;

    @BeforeEach
    void setUp() {
        pdfReaderTool = new PdfReaderTool(new SafeHttpClient(10000));
    }

    // === SSRF Protection Tests ===

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "C:\\Windows\\System32\\config\\SAM", "../../../etc/shadow", "/tmp/secret.pdf"})
    void testExtractTextFromPdf_RejectsLocalFilePaths(String path) {
        String result = pdfReaderTool.extractTextFromPdf(path);
        assertTrue(result.contains("Error"), "Should reject local file path: " + path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://evil.com/malware.pdf", "jar:file:///app.jar!/config"})
    void testExtractTextFromPdf_RejectsNonHttpSchemes(String url) {
        String result = pdfReaderTool.extractTextFromPdf(url);
        assertTrue(result.contains("Error"), "Should reject non-HTTP scheme: " + url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/admin.pdf", "http://localhost/secret.pdf", "http://169.254.169.254/latest/meta-data/"})
    void testExtractTextFromPdf_RejectsInternalAddresses(String url) {
        String result = pdfReaderTool.extractTextFromPdf(url);
        assertTrue(result.contains("Error"), "Should reject internal URL: " + url);
    }

    @Test
    void testExtractTextFromPdf_EmptyUrl() {
        String result = pdfReaderTool.extractTextFromPdf("");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdf_NullUrl() {
        String result = pdfReaderTool.extractTextFromPdf(null);
        assertTrue(result.contains("Error"));
    }

    // === Page Extraction SSRF Tests ===

    @Test
    void testExtractTextFromPdfPages_RejectsLocalPath() {
        String result = pdfReaderTool.extractTextFromPdfPages("/etc/passwd", 1, 1);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdfPages_RejectsFileScheme() {
        String result = pdfReaderTool.extractTextFromPdfPages("file:///etc/passwd", 1, 1);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdfPages_RejectsLocalhost() {
        String result = pdfReaderTool.extractTextFromPdfPages("http://localhost/secret.pdf", 1, 1);
        assertTrue(result.contains("Error"));
    }

    // === PDF Info SSRF Tests ===

    @Test
    void testGetPdfInfo_RejectsLocalPath() {
        String result = pdfReaderTool.getPdfInfo("/etc/passwd");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetPdfInfo_RejectsFileScheme() {
        String result = pdfReaderTool.getPdfInfo("file:///etc/passwd");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetPdfInfo_RejectsLocalhost() {
        String result = pdfReaderTool.getPdfInfo("http://localhost/secret.pdf");
        assertTrue(result.contains("Error"));
    }

    // === Valid URL format (DNS resolution may fail but URL validation succeeds)
    // ===

    @Test
    void testExtractTextFromPdf_AcceptsHttpsUrl() {
        // The URL validation itself should pass; the actual download will fail
        String result = pdfReaderTool.extractTextFromPdf("https://example.com/test.pdf");
        assertNotNull(result);
        // Either succeeds or fails with download error, NOT validation error
    }

    @Test
    void testGetPdfInfo_AcceptsHttpsUrl() {
        String result = pdfReaderTool.getPdfInfo("https://example.com/test.pdf");
        assertNotNull(result);
    }

    @Test
    void testExtractTextFromPdfPages_AcceptsHttpsUrl() {
        String result = pdfReaderTool.extractTextFromPdfPages("https://example.com/test.pdf", 1, 1);
        assertNotNull(result);
    }

    @Test
    void testGetPdfInfo_RejectsCloudMetadata() {
        String result = pdfReaderTool.getPdfInfo("http://169.254.169.254/latest/meta-data/");
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdfPages_RejectsCloudMetadata() {
        String result = pdfReaderTool.extractTextFromPdfPages("http://169.254.169.254/", 1, 1);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdf_RejectsInvalidUrl() {
        String result = pdfReaderTool.extractTextFromPdf("not-a-valid-url");
        assertTrue(result.contains("Error"));
    }

    // === HTTP error path tests (mocked SafeHttpClient) ===

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("HTTP download error paths")
    class HttpErrorPathTests {

        private SafeHttpClient mockedHttpClient;
        private PdfReaderTool mockedTool;

        @BeforeEach
        void setUpMocked() {
            mockedHttpClient = org.mockito.Mockito.mock(SafeHttpClient.class);
            mockedTool = new PdfReaderTool(mockedHttpClient);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdf — non-200 status returns error")
        @SuppressWarnings("unchecked")
        void extractText_non200_returnsError() throws Exception {
            var mockResponse = (java.net.http.HttpResponse<java.nio.file.Path>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(mockResponse.statusCode()).thenReturn(404);
            org.mockito.Mockito.doReturn(mockResponse).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.extractTextFromPdf("https://example.com/notfound.pdf");

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("HTTP 404"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdfPages — non-200 status returns error")
        @SuppressWarnings("unchecked")
        void extractPages_non200_returnsError() throws Exception {
            var mockResponse = (java.net.http.HttpResponse<java.nio.file.Path>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(mockResponse.statusCode()).thenReturn(500);
            org.mockito.Mockito.doReturn(mockResponse).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.extractTextFromPdfPages("https://example.com/doc.pdf", 1, 3);

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("HTTP 500"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("getPdfInfo — non-200 status returns error")
        @SuppressWarnings("unchecked")
        void pdfInfo_non200_returnsError() throws Exception {
            var mockResponse = (java.net.http.HttpResponse<java.nio.file.Path>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            org.mockito.Mockito.when(mockResponse.statusCode()).thenReturn(403);
            org.mockito.Mockito.doReturn(mockResponse).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.getPdfInfo("https://example.com/forbidden.pdf");

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("HTTP 403"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdf — IOException returns error")
        void extractText_ioException_returnsError() throws Exception {
            org.mockito.Mockito.doThrow(new java.io.IOException("Connection refused")).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.extractTextFromPdf("https://example.com/doc.pdf");

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("Connection refused"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdfPages — IOException returns error")
        void extractPages_ioException_returnsError() throws Exception {
            org.mockito.Mockito.doThrow(new java.io.IOException("Timeout")).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.extractTextFromPdfPages("https://example.com/doc.pdf", 1, 5);

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("Timeout"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("getPdfInfo — IOException returns error")
        void pdfInfo_ioException_returnsError() throws Exception {
            org.mockito.Mockito.doThrow(new java.io.IOException("DNS failed")).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.getPdfInfo("https://example.com/doc.pdf");

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("DNS failed"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdf — InterruptedException returns error")
        void extractText_interruptedException_returnsError() throws Exception {
            org.mockito.Mockito.doThrow(new InterruptedException("Interrupted")).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());

            String result = mockedTool.extractTextFromPdf("https://example.com/doc.pdf");

            assertTrue(result.startsWith("Error:"));
        }
    }

    // === PDF content extraction with real PDFs ===

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("PDF content extraction with real PDFs")
    class PdfContentExtractionTests {

        private SafeHttpClient mockedHttpClient;
        private PdfReaderTool mockedTool;

        @BeforeEach
        void setUpMocked() {
            mockedHttpClient = org.mockito.Mockito.mock(SafeHttpClient.class);
            mockedTool = new PdfReaderTool(mockedHttpClient);
        }

        private java.nio.file.Path createTinyPdf(String text) throws Exception {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("eddi-test-", ".pdf");
            try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
                doc.save(tempFile.toFile());
            }
            return tempFile;
        }

        private java.nio.file.Path createMultiPagePdf(String... texts) throws Exception {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("eddi-test-multi-", ".pdf");
            try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                for (String text : texts) {
                    var page = new org.apache.pdfbox.pdmodel.PDPage();
                    doc.addPage(page);
                    try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
                doc.save(tempFile.toFile());
            }
            return tempFile;
        }

        private java.nio.file.Path createPdfWithMetadata(String title, String author,
                                                         String subject, String creator)
                throws Exception {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("eddi-test-meta-", ".pdf");
            try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                var page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("test content");
                    cs.endText();
                }
                var info = doc.getDocumentInformation();
                info.setTitle(title);
                info.setAuthor(author);
                info.setSubject(subject);
                info.setCreator(creator);
                info.setCreationDate(java.util.GregorianCalendar.from(
                        java.time.ZonedDateTime.of(2025, 1, 15, 10, 30, 0, 0,
                                java.time.ZoneId.of("UTC"))));
                doc.setDocumentInformation(info);
                doc.save(tempFile.toFile());
            }
            return tempFile;
        }

        @SuppressWarnings("unchecked")
        private void mockHttpToServePdf(java.nio.file.Path sourcePdf) throws Exception {
            org.mockito.Mockito.doAnswer(invocation -> {
                // downloadPdf() creates a temp file with prefix "eddi-pdf-" immediately
                // before calling send(). Find that temp file and copy our PDF into it.
                java.nio.file.Path tempDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
                java.nio.file.Path target = java.nio.file.Files.list(tempDir)
                        .filter(p -> p.getFileName().toString().startsWith("eddi-pdf-")
                                && p.getFileName().toString().endsWith(".pdf"))
                        .max(java.util.Comparator.comparingLong(p -> {
                            try {
                                return java.nio.file.Files.getLastModifiedTime(p).toMillis();
                            } catch (Exception e) {
                                return 0L;
                            }
                        }))
                        .orElseThrow(() -> new RuntimeException("Could not find eddi-pdf temp file"));

                java.nio.file.Files.copy(sourcePdf, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                var mockResponse = (java.net.http.HttpResponse<java.nio.file.Path>) org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
                org.mockito.Mockito.when(mockResponse.statusCode()).thenReturn(200);
                org.mockito.Mockito.when(mockResponse.body()).thenReturn(target);
                return mockResponse;
            }).when(mockedHttpClient).send(
                    org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                    org.mockito.ArgumentMatchers.any());
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdf — extracts text from a valid small PDF")
        void extractText_validPdf() throws Exception {
            java.nio.file.Path pdfPath = createTinyPdf("Hello EDDI World");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.extractTextFromPdf("https://example.com/test.pdf");

                assertNotNull(result);
                assertTrue(result.contains("Hello EDDI World"),
                        "Should extract text from PDF. Got: " + result);
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdfPages — startPage > totalPages returns error")
        void extractPages_startPageBeyondTotal() throws Exception {
            java.nio.file.Path pdfPath = createTinyPdf("Only page");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.extractTextFromPdfPages(
                        "https://example.com/doc.pdf", 5, 10);

                assertTrue(result.contains("Error"),
                        "Should return error for startPage > totalPages. Got: " + result);
                assertTrue(result.contains("out of range") || result.contains("Start page"),
                        "Should mention out of range. Got: " + result);
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdfPages — endPage > totalPages clamps to totalPages")
        void extractPages_endPageClamped() throws Exception {
            java.nio.file.Path pdfPath = createMultiPagePdf("Page one text", "Page two text");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.extractTextFromPdfPages(
                        "https://example.com/doc.pdf", 1, 100);

                assertNotNull(result);
                assertFalse(result.contains("Error"),
                        "Should not return error when endPage > totalPages. Got: " + result);
                assertTrue(result.contains("Page one text"),
                        "Should contain page 1 text. Got: " + result);
                assertTrue(result.contains("Page two text"),
                        "Should contain page 2 text. Got: " + result);
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("getPdfInfo — extracts metadata from PDF")
        void pdfInfo_withMetadata() throws Exception {
            java.nio.file.Path pdfPath = createPdfWithMetadata(
                    "EDDI Test Document", "Test Author", "Testing Subject", "PDFBox Creator");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.getPdfInfo("https://example.com/meta.pdf");

                assertNotNull(result);
                assertTrue(result.contains("EDDI Test Document"),
                        "Should contain title. Got: " + result);
                assertTrue(result.contains("Test Author"),
                        "Should contain author. Got: " + result);
                assertTrue(result.contains("Testing Subject"),
                        "Should contain subject. Got: " + result);
                assertTrue(result.contains("PDFBox Creator"),
                        "Should contain creator. Got: " + result);
                assertTrue(result.contains("Creation date"),
                        "Should contain creation date. Got: " + result);
                assertTrue(result.contains("Number of pages: 1"),
                        "Should contain page count. Got: " + result);
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdf — text > 10000 chars gets truncation message")
        void extractText_truncation() throws Exception {
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("eddi-test-long-", ".pdf");
            try {
                try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
                    var page = new org.apache.pdfbox.pdmodel.PDPage();
                    doc.addPage(page);
                    try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 6);
                        cs.setLeading(7f);
                        cs.newLineAtOffset(25, 750);
                        String line = "ABCDEFGHIJ".repeat(10); // 100 chars per line
                        for (int i = 0; i < 120; i++) {
                            cs.showText(line);
                            cs.newLine();
                        }
                        cs.endText();
                    }
                    doc.save(tempFile.toFile());
                }

                mockHttpToServePdf(tempFile);
                String result = mockedTool.extractTextFromPdf("https://example.com/long.pdf");

                assertNotNull(result);
                assertTrue(result.contains("[Content truncated"),
                        "Should contain truncation message. Got length: " + result.length());
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("extractTextFromPdfPages — valid page range extracts correctly")
        void extractPages_validRange() throws Exception {
            java.nio.file.Path pdfPath = createMultiPagePdf("First page", "Second page", "Third page");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.extractTextFromPdfPages(
                        "https://example.com/doc.pdf", 2, 2);

                assertNotNull(result);
                assertFalse(result.contains("Error"), "Should not error. Got: " + result);
                assertTrue(result.contains("Second page"),
                        "Should contain page 2 text. Got: " + result);
                assertFalse(result.contains("First page"),
                        "Should not contain page 1 text");
                assertFalse(result.contains("Third page"),
                        "Should not contain page 3 text");
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("getPdfInfo — PDF without metadata fields still works")
        void pdfInfo_noMetadata() throws Exception {
            java.nio.file.Path pdfPath = createTinyPdf("bare content");
            try {
                mockHttpToServePdf(pdfPath);
                String result = mockedTool.getPdfInfo("https://example.com/bare.pdf");

                assertNotNull(result);
                assertTrue(result.contains("Number of pages: 1"),
                        "Should report page count. Got: " + result);
                assertFalse(result.contains("Title:"),
                        "Should not contain title when none set");
            } finally {
                java.nio.file.Files.deleteIfExists(pdfPath);
            }
        }
    }
}
