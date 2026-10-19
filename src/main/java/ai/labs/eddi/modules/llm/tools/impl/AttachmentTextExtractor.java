/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.ingestion.extract.PdfDecodeBudget;
import ai.labs.eddi.modules.ingestion.extract.PdfStreamBudget;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Fallback cap when configuration yields a non-positive value (~12k tokens).
     * <p>
     * Kept in step with {@code LlmConfiguration.ToolResponseLimits#defaultMaxChars}
     * and {@code eddi.attachments.extraction.max-chars}: an extracted document goes
     * straight into a tool response, so a stricter cap here just clamps the file
     * before the layer that actually guards the context window gets a say.
     */
    public static final int DEFAULT_MAX_CHARS = 50_000;

    private static final String TRUNCATION_SUFFIX_FMT = "\n\n[Content truncated - showing first %d characters]";

    /** Fallback page cap when configuration yields a non-positive value. */
    public static final int DEFAULT_MAX_PAGES = 500;

    /**
     * Fallback per-stream decode cap — the ingestion path's default
     * ({@code ExtractionLimits}). The whole document may decode twice this.
     */
    public static final long DEFAULT_MAX_STREAM_BYTES = 64L * 1024 * 1024;

    /** Wall-clock bound for the raw-byte pre-scan of one PDF. */
    private static final Duration PRE_SCAN_DURATION = Duration.ofSeconds(30);

    private final int defaultMaxChars;
    private final int maxPages;
    private final long maxStreamBytes;

    @Inject
    public AttachmentTextExtractor(
            @ConfigProperty(name = "eddi.attachments.extraction.max-chars",
                            defaultValue = "50000") int defaultMaxChars,
            @ConfigProperty(name = "eddi.attachments.extraction.max-pages",
                            defaultValue = "500") int maxPages) {
        this(defaultMaxChars, maxPages, DEFAULT_MAX_STREAM_BYTES);
    }

    /**
     * @param maxStreamBytes
     *            what one compressed stream may expand to; the whole document may
     *            decode twice this. Non-positive falls back to the default
     */
    public AttachmentTextExtractor(int defaultMaxChars, int maxPages, long maxStreamBytes) {
        this.defaultMaxChars = defaultMaxChars > 0 ? defaultMaxChars : DEFAULT_MAX_CHARS;
        this.maxPages = maxPages > 0 ? maxPages : DEFAULT_MAX_PAGES;
        this.maxStreamBytes = maxStreamBytes > 0 ? maxStreamBytes : DEFAULT_MAX_STREAM_BYTES;
    }

    /**
     * Convenience constructor for tests and callers with no configured page cap.
     */
    public AttachmentTextExtractor(int defaultMaxChars) {
        this(defaultMaxChars, DEFAULT_MAX_PAGES);
    }

    /**
     * Loads a PDF from bytes with a temp-file scratch cache rather than PDFBox's
     * default unlimited in-memory buffering. The scratch cache alone does not bound
     * a "PDF bomb": PDFBox decodes every stream into a buffer it sizes itself, so
     * {@link #withDecodeBudget} wraps every use of a loaded document.
     */
    private static PDDocument loadPdf(byte[] pdfBytes) throws IOException {
        StreamCacheCreateFunction tempFileCache = IOUtils.createTempFileOnlyStreamCache();
        return Loader.loadPDF(pdfBytes, "", null, null, tempFileCache);
    }

    /** One PDF operation, run inside the decode budget. */
    @FunctionalInterface
    private interface PdfOperation<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code operation} under the ingestion path's PDF bounds (security review
     * 2026-10-17, M11): {@link PdfStreamBudget} refuses a stream that expands past
     * the per-stream cap before PDFBox sees the file, and {@link PdfDecodeBudget}
     * counts everything PDFBox decodes while the operation runs and refuses the
     * document once the total passes twice that. A FlateDecode bomb in an upload, a
     * {@code PdfReaderTool} URL or a {@code readAttachment} call used to inflate to
     * gigabytes of temp file and heap.
     */
    private <T> T withDecodeBudget(byte[] pdfBytes, String failure, PdfOperation<T> operation) throws AttachmentExtractionException {
        try {
            PdfStreamBudget.requireWithin(pdfBytes, maxStreamBytes, Instant.now().plus(PRE_SCAN_DURATION));
        } catch (UnreadableDocumentException e) {
            throw new AttachmentExtractionException(failure + ": " + e.getMessage(), e);
        }
        try (var budget = PdfDecodeBudget.open(2 * maxStreamBytes)) {
            T result;
            try {
                result = operation.run();
            } catch (AttachmentExtractionException e) {
                throw budget.exceeded() ? tooMuchToDecode(failure, budget, e) : e;
            } catch (Exception e) {
                throw budget.exceeded()
                        ? tooMuchToDecode(failure, budget, e)
                        : new AttachmentExtractionException(failure + ": " + e.getMessage(), e);
            }
            // Checked on success too: PDFBox logs and swallows some failures, and a
            // document that passed its budget is refused however it ended.
            if (budget.exceeded()) {
                throw tooMuchToDecode(failure, budget, null);
            }
            return result;
        } catch (UnreadableDocumentException e) {
            throw new AttachmentExtractionException(failure + ": " + e.getMessage(), e);
        }
    }

    private static AttachmentExtractionException tooMuchToDecode(String failure, PdfDecodeBudget.Budget budget, Throwable cause) {
        return new AttachmentExtractionException(failure + ": the PDF expands to more than " + budget.max() / (1024 * 1024)
                + " MB while it is read, which is more than one document may take", cause);
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
        return withDecodeBudget(pdfBytes, "Failed to extract text from PDF", () -> extractAllPages(pdfBytes, cap));
    }

    private String extractAllPages(byte[] pdfBytes, int cap) throws IOException {
        try (PDDocument document = loadPdf(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Stop at the page cap: a document with tens of thousands of pages would
            // otherwise have every page extracted into memory before the character
            // cap trims the result. Extracting only up to the cap bounds both.
            int totalPages = document.getNumberOfPages();
            int lastPage = Math.min(totalPages, maxPages);
            stripper.setStartPage(1);
            stripper.setEndPage(lastPage);
            String text = stripper.getText(document);
            LOGGER.debugf("Extracted %d characters from PDF (%d of %d pages)",
                    text.length(), lastPage, totalPages);
            return cap(text, cap);
        }
    }

    /**
     * Extract text from a page range of a PDF, capped to {@code maxChars}.
     * {@code endPage} beyond the last page is clamped; {@code startPage} out of
     * range throws. At most {@code eddi.attachments.extraction.max-pages} pages are
     * read from {@code startPage} on, as for the whole-document variant.
     */
    public String extractPdfText(byte[] pdfBytes, int startPage, int endPage, int maxChars)
            throws AttachmentExtractionException {
        int cap = maxChars > 0 ? maxChars : defaultMaxChars;
        return withDecodeBudget(pdfBytes, "Failed to extract PDF pages", () -> extractPageRange(pdfBytes, startPage, endPage, cap));
    }

    private String extractPageRange(byte[] pdfBytes, int startPage, int endPage, int cap) throws IOException, AttachmentExtractionException {
        try (PDDocument document = loadPdf(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            if (startPage < 1 || startPage > totalPages) {
                throw new AttachmentExtractionException(
                        "Start page " + startPage + " is out of range (1-" + totalPages + ")");
            }
            int effectiveEnd = (endPage < startPage || endPage > totalPages) ? totalPages : endPage;
            // The page cap applies here too: this variant used to read to the last
            // page of a document with any number of them.
            effectiveEnd = (int) Math.min(effectiveEnd, (long) startPage + maxPages - 1);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(effectiveEnd);
            String text = stripper.getText(document);
            LOGGER.debugf("Extracted text from PDF pages %d-%d (%d characters)",
                    startPage, effectiveEnd, text.length());
            return cap(text, cap);
        }
    }

    /**
     * Extract structural metadata from a PDF (page count + document information).
     */
    public PdfInfo extractPdfInfo(byte[] pdfBytes) throws AttachmentExtractionException {
        return withDecodeBudget(pdfBytes, "Failed to read PDF information", () -> readPdfInfo(pdfBytes));
    }

    private static PdfInfo readPdfInfo(byte[] pdfBytes) throws IOException {
        try (PDDocument document = loadPdf(pdfBytes)) {
            var info = document.getDocumentInformation();
            String title = info != null ? info.getTitle() : null;
            String author = info != null ? info.getAuthor() : null;
            String subject = info != null ? info.getSubject() : null;
            String creator = info != null ? info.getCreator() : null;
            Calendar creationDate = info != null ? info.getCreationDate() : null;
            return new PdfInfo(document.getNumberOfPages(), title, author, subject, creator, creationDate);
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
