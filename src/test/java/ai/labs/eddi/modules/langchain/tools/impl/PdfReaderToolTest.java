package ai.labs.eddi.modules.langchain.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PdfReaderTool.
 */
class PdfReaderToolTest {

    private PdfReaderTool pdfReaderTool;

    @BeforeEach
    void setUp() {
        pdfReaderTool = new PdfReaderTool();
    }

    @Test
    void testExtractTextFromPdf_InvalidUrl() {
        String result = pdfReaderTool.extractTextFromPdf("not-a-valid-url");
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdf_EmptyUrl() {
        String result = pdfReaderTool.extractTextFromPdf("");
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testExtractTextFromPdf_NonPdfUrl() {
        String result = pdfReaderTool.extractTextFromPdf("https://example.com/not-a-pdf.txt");
        assertNotNull(result);
        // Should handle non-PDF files gracefully
    }
}

