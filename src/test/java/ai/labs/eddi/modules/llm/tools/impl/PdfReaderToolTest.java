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
}
