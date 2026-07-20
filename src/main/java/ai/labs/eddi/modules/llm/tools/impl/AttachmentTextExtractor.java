/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Shared text-extraction service for binary attachments. Owns the PDFBox
 * machinery (previously embedded in {@link PdfReaderTool}) and the plain-text
 * decode path, so the {@code PdfReaderTool}, the multimodal attachment
 * forwarder, and the {@code readAttachment} recall tool all extract text
 * through a single, uniformly-capped implementation.
 * <p>
 * All operations work on {@code byte[]} so the caller controls sourcing (URL
 * download, blob-store load, inline base64) and SSRF validation.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class AttachmentTextExtractor {

    private static final Logger LOGGER = Logger.getLogger(AttachmentTextExtractor.class);

    /** Fallback cap when configuration yields a non-positive value. */
    public static final int DEFAULT_MAX_CHARS = 10_000;

    private static final String TRUNCATION_SUFFIX_FMT = "\n\n[Content truncated - showing first %d characters]";

    private final int defaultMaxChars;

    @Inject
    public AttachmentTextExtractor(
            @ConfigProperty(name = "eddi.attachments.extraction.max-chars",
                            defaultValue = "10000") int defaultMaxChars) {
        this.defaultMaxChars = defaultMaxChars > 0 ? defaultMaxChars : DEFAULT_MAX_CHARS;
    }

    /**
     * @return the configured default character cap for extraction
     */
    public int getDefaultMaxChars() {
        return defaultMaxChars;
    }

    /**
     * Whether this service can extract inline text for the given MIME type (PDF or
     * any text-like type — {@code text/*}, JSON, XML, CSV, YAML).
     */
    public boolean canExtractText(String mimeType) {
        return isPdf(mimeType) || isTextLike(mimeType);
    }

    /**
     * Extract text from an attachment using the configured default cap.
     *
     * @see #extractText(byte[], String, int)
     */
    public String extractText(byte[] bytes, String mimeType) throws AttachmentExtractionException {
        return extractText(bytes, mimeType, defaultMaxChars);
    }

    /**
     * Extract text from an attachment, dispatching on MIME type.
     * <ul>
     * <li>{@code application/pdf} → PDFBox full-text extraction</li>
     * <li>text-like ({@code text/*}, JSON, XML, CSV, YAML) → UTF-8 decode</li>
     * </ul>
     * The result is capped to {@code maxChars} characters with a truncation note.
     *
     * @param bytes
     *            the raw attachment bytes
     * @param mimeType
     *            the attachment MIME type
     * @param maxChars
     *            the character cap (non-positive falls back to the default)
     * @return extracted text, capped (never null)
     * @throws AttachmentExtractionException
     *             if the type is unsupported or extraction fails
     */
    public String extractText(byte[] bytes, String mimeType, int maxChars) throws AttachmentExtractionException {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int cap = maxChars > 0 ? maxChars : defaultMaxChars;
        if (isPdf(mimeType)) {
            return extractPdfText(bytes, cap);
        }
        if (isTextLike(mimeType)) {
            return cap(new String(bytes, StandardCharsets.UTF_8), cap);
        }
        throw new AttachmentExtractionException(
                "Unsupported MIME type for text extraction: " + mimeType);
    }

    /**
     * Extract all text from a PDF, capped to the configured default.
     */
    public String extractPdfText(byte[] pdfBytes) throws AttachmentExtractionException {
        return extractPdfText(pdfBytes, defaultMaxChars);
    }

    /**
     * Extract all text from a PDF, capped to {@code maxChars}.
     */
    public String extractPdfText(byte[] pdfBytes, int maxChars) throws AttachmentExtractionException {
        int cap = maxChars > 0 ? maxChars : defaultMaxChars;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            LOGGER.debugf("Extracted %d characters from PDF with %d pages",
                    text.length(), document.getNumberOfPages());
            return cap(text, cap);
        } catch (Exception e) {
            throw new AttachmentExtractionException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Extract text from a page range of a PDF, capped to {@code maxChars}.
     * {@code endPage} beyond the last page is clamped; {@code startPage} out of
     * range throws.
     */
    public String extractPdfText(byte[] pdfBytes, int startPage, int endPage, int maxChars)
            throws AttachmentExtractionException {
        int cap = maxChars > 0 ? maxChars : defaultMaxChars;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            if (startPage < 1 || startPage > totalPages) {
                throw new AttachmentExtractionException(
                        "Start page " + startPage + " is out of range (1-" + totalPages + ")");
            }
            int effectiveEnd = (endPage < startPage || endPage > totalPages) ? totalPages : endPage;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(effectiveEnd);
            String text = stripper.getText(document);
            LOGGER.debugf("Extracted text from PDF pages %d-%d (%d characters)",
                    startPage, effectiveEnd, text.length());
            return cap(text, cap);
        } catch (AttachmentExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new AttachmentExtractionException("Failed to extract PDF pages: " + e.getMessage(), e);
        }
    }

    /**
     * Extract structural metadata from a PDF (page count + document information).
     */
    public PdfInfo extractPdfInfo(byte[] pdfBytes) throws AttachmentExtractionException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            var info = document.getDocumentInformation();
            String title = info != null ? info.getTitle() : null;
            String author = info != null ? info.getAuthor() : null;
            String subject = info != null ? info.getSubject() : null;
            String creator = info != null ? info.getCreator() : null;
            Calendar creationDate = info != null ? info.getCreationDate() : null;
            return new PdfInfo(document.getNumberOfPages(), title, author, subject, creator, creationDate);
        } catch (Exception e) {
            throw new AttachmentExtractionException("Failed to read PDF information: " + e.getMessage(), e);
        }
    }

    private String cap(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() > maxChars) {
            return (text.substring(0, maxChars) + String.format(TRUNCATION_SUFFIX_FMT, maxChars)).trim();
        }
        return text.trim();
    }

    private static boolean isPdf(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("application/pdf");
    }

    private static boolean isTextLike(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String mime = mimeType.toLowerCase(Locale.ROOT);
        // Strip any parameters (e.g. "text/plain; charset=utf-8").
        int semi = mime.indexOf(';');
        if (semi >= 0) {
            mime = mime.substring(0, semi).trim();
        }
        if (mime.startsWith("text/")) {
            return true;
        }
        return switch (mime) {
            case "application/json", "application/xml", "application/csv",
                    "application/yaml", "application/x-yaml", "application/x-ndjson" ->
                true;
            default -> mime.endsWith("+json") || mime.endsWith("+xml");
        };
    }

    /**
     * Structural metadata extracted from a PDF document.
     */
    public record PdfInfo(int numberOfPages, String title, String author,
            String subject, String creator, Calendar creationDate) {
    }

    /**
     * Thrown when text extraction fails or the MIME type is unsupported.
     */
    public static class AttachmentExtractionException extends Exception {
        public AttachmentExtractionException(String message) {
            super(message);
        }

        public AttachmentExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
