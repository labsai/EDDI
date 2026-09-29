/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.PdfInfo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * PDF reader tool for extracting text from PDF documents fetched by URL.
 * <p>
 * Handles the download, SSRF validation and user-facing formatting; the actual
 * PDFBox extraction is delegated to the shared {@link AttachmentTextExtractor}
 * so the multimodal forwarder and {@code readAttachment} recall tool share one
 * implementation.
 */
@ApplicationScoped
public class PdfReaderTool {
    private static final Logger LOGGER = Logger.getLogger(PdfReaderTool.class);

    /**
     * Backstop PDF-download cap used when no configuration is supplied (25 MiB).
     */
    static final long DEFAULT_MAX_PDF_BYTES = 25L * 1024 * 1024;

    private final SafeHttpClient httpClient;
    private final AttachmentTextExtractor textExtractor;
    private final long maxPdfBytes;

    @Inject
    public PdfReaderTool(SafeHttpClient httpClient, AttachmentTextExtractor textExtractor,
            @ConfigProperty(name = "eddi.tools.pdf-reader.max-download-bytes",
                            defaultValue = "26214400") long maxPdfBytes) {
        this.httpClient = httpClient;
        this.textExtractor = textExtractor;
        this.maxPdfBytes = maxPdfBytes > 0 ? maxPdfBytes : DEFAULT_MAX_PDF_BYTES;
    }

    /** Convenience constructor for tests and callers with no configured cap. */
    public PdfReaderTool(SafeHttpClient httpClient, AttachmentTextExtractor textExtractor) {
        this(httpClient, textExtractor, DEFAULT_MAX_PDF_BYTES);
    }

    @Tool("Extracts all text content from a PDF file. Provide the URL to the PDF document.")
    public String extractTextFromPdf(@P("pdfLocation") String pdfLocation) {

        try {
            LOGGER.info("Extracting text from PDF: " + pdfLocation);
            validateUrl(pdfLocation);

            byte[] pdfBytes = downloadPdfBytes(pdfLocation);
            return textExtractor.extractPdfText(pdfBytes);

        } catch (Exception e) {
            LOGGER.error("PDF extraction error for " + pdfLocation + ": " + e.getMessage());
            return "Error: Could not extract text from PDF - " + e.getMessage();
        }
    }

    @Tool("Extracts text from specific pages of a PDF file")
    public String extractTextFromPdfPages(@P("pdfLocation") String pdfLocation, @P("startPage") int startPage, @P("endPage") int endPage) {

        try {
            LOGGER.info("Extracting text from PDF pages " + startPage + "-" + endPage + ": " + pdfLocation);
            validateUrl(pdfLocation);

            byte[] pdfBytes = downloadPdfBytes(pdfLocation);
            return textExtractor.extractPdfText(pdfBytes, startPage, endPage, textExtractor.getDefaultMaxChars());

        } catch (Exception e) {
            LOGGER.error("PDF page extraction error: " + e.getMessage());
            return "Error: Could not extract text from PDF pages - " + e.getMessage();
        }
    }

    @Tool("Gets metadata and information about a PDF file (number of pages, title, author, etc.)")
    public String getPdfInfo(@P("pdfLocation") String pdfLocation) {

        try {
            LOGGER.info("Getting PDF info for: " + pdfLocation);
            validateUrl(pdfLocation);

            byte[] pdfBytes = downloadPdfBytes(pdfLocation);
            PdfInfo info = textExtractor.extractPdfInfo(pdfBytes);

            StringBuilder sb = new StringBuilder();
            sb.append("PDF Information:\n\n");
            sb.append("Number of pages: ").append(info.numberOfPages()).append("\n");
            if (info.title() != null) {
                sb.append("Title: ").append(info.title()).append("\n");
            }
            if (info.author() != null) {
                sb.append("Author: ").append(info.author()).append("\n");
            }
            if (info.subject() != null) {
                sb.append("Subject: ").append(info.subject()).append("\n");
            }
            if (info.creator() != null) {
                sb.append("Creator: ").append(info.creator()).append("\n");
            }
            if (info.creationDate() != null) {
                sb.append("Creation date: ").append(info.creationDate().getTime()).append("\n");
            }

            LOGGER.debug("PDF info extracted for " + pdfLocation);
            return sb.toString();

        } catch (Exception e) {
            LOGGER.error("PDF info extraction error: " + e.getMessage());
            return "Error: Could not get PDF information - " + e.getMessage();
        }
    }

    private byte[] downloadPdfBytes(String url) throws IOException, InterruptedException {
        LOGGER.debug("Downloading PDF from URL: " + url);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)").GET().build();

        // Bounded read in place of ofFile()+readAllBytes(): an LLM-chosen URL could
        // otherwise stream an unbounded file onto disk and then into the heap. The
        // body is capped as it arrives; a body that hits the cap is rejected rather
        // than truncated, because a partial PDF is not something to hand PDFBox.
        SafeHttpClient.BoundedResponse response = httpClient.sendBounded(request, maxPdfBytes);

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " when downloading PDF");
        }
        if (response.truncated()) {
            throw new IOException("PDF exceeds the maximum download size of " + maxPdfBytes + " bytes");
        }

        return response.body();
    }
}
