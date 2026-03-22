package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * PDF reader tool for extracting text from PDF documents.
 * Supports agenth local files and URLs.
 */
@ApplicationScoped
public class PdfReaderTool {
    private static final Logger LOGGER = Logger.getLogger(PdfReaderTool.class);
    private final HttpClient httpClient;

    public PdfReaderTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Tool("Extracts all text content from a PDF file. Provide the URL to the PDF document.")
    public String extractTextFromPdf(
            @P("pdfLocation") String pdfLocation) {

        try {
            LOGGER.info("Extracting text from PDF: " + pdfLocation);
            validateUrl(pdfLocation);

            Path tempFile = null;
            try {
                tempFile = downloadPdf(pdfLocation);
                return extractTextFromFile(tempFile.toFile());
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        LOGGER.warn("Could not delete temp file: " + tempFile);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("PDF extraction error for " + pdfLocation + ": " + e.getMessage());
            return "Error: Could not extract text from PDF - " + e.getMessage();
        }
    }

    @Tool("Extracts text from specific pages of a PDF file")
    public String extractTextFromPdfPages(
            @P("pdfLocation") String pdfLocation,
            @P("startPage") int startPage,
            @P("endPage") int endPage) {

        try {
            LOGGER.info("Extracting text from PDF pages " + startPage + "-" + endPage + ": " + pdfLocation);
            validateUrl(pdfLocation);

            Path tempFile = null;
            try {
                tempFile = downloadPdf(pdfLocation);
                return extractTextFromFilePages(tempFile.toFile(), startPage, endPage);
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        LOGGER.warn("Could not delete temp file: " + tempFile);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("PDF page extraction error: " + e.getMessage());
            return "Error: Could not extract text from PDF pages - " + e.getMessage();
        }
    }

    @Tool("Gets metadata and information about a PDF file (number of pages, title, author, etc.)")
    public String getPdfInfo(
            @P("pdfLocation") String pdfLocation) {

        PDDocument document = null;
        Path tempFile = null;

        try {
            LOGGER.info("Getting PDF info for: " + pdfLocation);
            validateUrl(pdfLocation);

            tempFile = downloadPdf(pdfLocation);
            File pdfFile = tempFile.toFile();

            document = PDDocument.load(pdfFile);

            StringBuilder info = new StringBuilder();
            info.append("PDF Information:\n\n");
            info.append("Number of pages: ").append(document.getNumberOfPages()).append("\n");

            var metadata = document.getDocumentInformation();
            if (metadata != null) {
                if (metadata.getTitle() != null) {
                    info.append("Title: ").append(metadata.getTitle()).append("\n");
                }
                if (metadata.getAuthor() != null) {
                    info.append("Author: ").append(metadata.getAuthor()).append("\n");
                }
                if (metadata.getSubject() != null) {
                    info.append("Subject: ").append(metadata.getSubject()).append("\n");
                }
                if (metadata.getCreator() != null) {
                    info.append("Creator: ").append(metadata.getCreator()).append("\n");
                }
                if (metadata.getCreationDate() != null) {
                    info.append("Creation date: ").append(metadata.getCreationDate().getTime()).append("\n");
                }
            }

            LOGGER.debug("PDF info extracted for " + pdfLocation);
            return info.toString();

        } catch (Exception e) {
            LOGGER.error("PDF info extraction error: " + e.getMessage());
            return "Error: Could not get PDF information - " + e.getMessage();
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    LOGGER.warn("Error closing PDF document", e);
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.warn("Could not delete temp file: " + tempFile);
                }
            }
        }
    }

    private String extractTextFromFile(File pdfFile) throws IOException {
        PDDocument document = null;
        try {
            document = PDDocument.load(pdfFile);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            LOGGER.info("Extracted " + text.length() + " characters from PDF with " +
                    document.getNumberOfPages() + " pages");

            // Limit output size
            if (text.length() > 10000) {
                text = text.substring(0, 10000) + "\n\n[Content truncated - showing first 10000 characters]";
            }

            return text.trim();

        } finally {
            if (document != null) {
                document.close();
            }
        }
    }

    private String extractTextFromFilePages(File pdfFile, int startPage, int endPage) throws IOException {
        PDDocument document = null;
        try {
            document = PDDocument.load(pdfFile);

            int totalPages = document.getNumberOfPages();
            if (startPage < 1 || startPage > totalPages) {
                return "Error: Start page " + startPage + " is out of range (1-" + totalPages + ")";
            }
            if (endPage < startPage || endPage > totalPages) {
                endPage = totalPages;
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);

            String text = stripper.getText(document);

            LOGGER.info("Extracted text from pages " + startPage + "-" + endPage +
                    " (" + text.length() + " characters)");

            // Limit output size
            if (text.length() > 10000) {
                text = text.substring(0, 10000) + "\n\n[Content truncated - showing first 10000 characters]";
            }

            return text.trim();

        } finally {
            if (document != null) {
                document.close();
            }
        }
    }

    private Path downloadPdf(String url) throws IOException, InterruptedException {
        LOGGER.debug("Downloading PDF from URL: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)")
                .GET()
                .build();

        Path tempFile = Files.createTempFile("eddi-pdf-", ".pdf");

        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(tempFile));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("HTTP " + response.statusCode() + " when downloading PDF");
        }

        LOGGER.debug("PDF downloaded to temp file: " + tempFile);
        return tempFile;
    }
}
