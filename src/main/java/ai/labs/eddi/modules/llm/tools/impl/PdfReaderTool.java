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
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final SafeHttpClient httpClient;
    private final AttachmentTextExtractor textExtractor;

    @Inject
    public PdfReaderTool(SafeHttpClient httpClient, AttachmentTextExtractor textExtractor) {
        this.httpClient = httpClient;
        this.textExtractor = textExtractor;
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
        Path tempFile = null;
        try {
            tempFile = downloadPdf(url);
            return Files.readAllBytes(tempFile);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temp file: " + tempFile);
                }
            }
        }
    }

    private Path downloadPdf(String url) throws IOException, InterruptedException {
        LOGGER.debug("Downloading PDF from URL: " + url);

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)").GET().build();

        Path tempFile = Files.createTempFile("eddi-pdf-", ".pdf");

        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("HTTP " + response.statusCode() + " when downloading PDF");
        }

        LOGGER.debug("PDF downloaded to temp file: " + tempFile);
        return tempFile;
    }
}
