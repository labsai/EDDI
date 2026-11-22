/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jboss.logging.Logger;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

/**
 * Builds a page-range PDF out of a stored one — the native slice a
 * document-capable model receives instead of the whole file when only part of
 * it was selected.
 * <p>
 * The pages are copied with PDFBox's {@link Splitter}, which imports each page
 * ({@code PDDocument.importPage}) and repairs what a bare import gets wrong:
 * resources inherited from the page tree, links to pages outside the range,
 * form-field parents that would drag in other pages, and the structure tree of
 * a tagged PDF. Everything a page references is copied with it, so a slice of a
 * document whose pages share large fonts or images can be nearly as big as the
 * document; the output is therefore written through a counting stream that
 * <strong>aborts as soon as it passes {@code maxBytes}</strong>
 * ({@link SliceTooLargeException}), and the caller falls back to the pages'
 * text.
 * <p>
 * The source is read under the same bounds as every other PDF path: the
 * {@link PdfStreamBudget} pre-scan of the raw bytes, then a
 * {@link PdfDecodeBudget} armed on the <em>calling</em> thread for the whole
 * load, copy and save. Nothing here hands work to another thread.
 * <p>
 * The slice is written to a temporary file in {@code workDir} (owner read/write
 * only where the file system supports POSIX permissions). The caller owns the
 * file and deletes it in a {@code finally}; on any failure this class deletes
 * it itself before throwing.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class PdfSliceBuilder {

    private static final Logger LOGGER = Logger.getLogger(PdfSliceBuilder.class);

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final ExtractionLimits limits;

    public PdfSliceBuilder() {
        this(ExtractionLimits.defaults());
    }

    /** For tests: other bounds than the defaults. */
    PdfSliceBuilder(ExtractionLimits limits) {
        this.limits = limits != null ? limits : ExtractionLimits.defaults();
    }

    /**
     * Writes pages {@code firstPage..lastPage} of {@code pdf} into a new PDF file.
     *
     * @param pdf
     *            the whole document
     * @param firstPage
     *            first page, 1-based
     * @param lastPage
     *            last page, inclusive; must not be beyond the document's last page
     * @param maxBytes
     *            the most the slice may be; writing stops as soon as it is passed
     * @param workDir
     *            where the temporary file is created
     * @return the slice file; the caller deletes it
     * @throws SliceTooLargeException
     *             when the slice would be larger than {@code maxBytes}
     * @throws UnreadableDocumentException
     *             when the document is encrypted, corrupt, costs more to read than
     *             the bounds allow, or has fewer pages than {@code lastPage}
     */
    public Path slice(byte[] pdf, int firstPage, int lastPage, long maxBytes, Path workDir) {
        return slice(pdf, firstPage, lastPage, maxBytes, workDir, true);
    }

    /**
     * @param preFilter
     *            whether to run the raw-byte scan first. Off only in tests that
     *            show the decode budget holds on its own
     */
    Path slice(byte[] pdf, int firstPage, int lastPage, long maxBytes, Path workDir, boolean preFilter) {
        if (pdf == null || pdf.length == 0) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.");
        }
        if (firstPage < 1 || lastPage < firstPage) {
            throw new IllegalArgumentException("A slice needs 1 <= firstPage <= lastPage, got " + firstPage + "-" + lastPage);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("A slice needs a positive size limit");
        }
        if (workDir == null) {
            throw new IllegalArgumentException("A slice needs a work directory");
        }
        Instant deadline = Instant.now().plus(limits.maxDuration());
        if (preFilter) {
            PdfStreamBudget.requireWithin(pdf, limits.maxUncompressedBytes(), deadline);
        }
        Path file = createFile(workDir);
        boolean done = false;
        try (var budget = PdfDecodeBudget.open(limits.maxDecodedBytes())) {
            try {
                write(pdf, firstPage, lastPage, maxBytes, file);
            } catch (StackOverflowError e) {
                // As in PdfStructureExtractor: the parser's own recursion over a hostile page
                // tree or a broken cross-reference table. It has unwound by here.
                throw new UnreadableDocumentException("This PDF's structure is nested too deeply to be read.");
            } catch (SliceTooLargeException e) {
                throw e;
            } catch (UnreadableDocumentException e) {
                throw budget.exceeded() ? tooMuchToDecode(budget) : e;
            } catch (IOException | RuntimeException e) {
                OverLimit overLimit = overLimitIn(e);
                if (overLimit != null) {
                    // The writer wrapped the refusal of the counting stream.
                    throw new SliceTooLargeException(overLimit.maxBytes);
                }
                if (budget.exceeded()) {
                    throw tooMuchToDecode(budget);
                }
                throw new UnreadableDocumentException("The pages of this PDF could not be copied.", e);
            }
            // Checked on success too: PDFBox logs and swallows some failures, and a
            // document that passed its budget is refused however it ended.
            if (budget.exceeded()) {
                throw tooMuchToDecode(budget);
            }
            done = true;
            return file;
        } finally {
            if (!done) {
                deleteQuietly(file);
            }
        }
    }

    private void write(byte[] pdf, int firstPage, int lastPage, long maxBytes, Path file) throws IOException {
        // The source stays open until the slice is saved: the imported pages share its
        // objects (fonts, images, content streams) rather than owning copies.
        try (PDDocument source = Loader.loadPDF(pdf, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(limits.maxUncompressedBytes()).streamCache)) {
            if (source.isEncrypted()) {
                throw new UnreadableDocumentException("This PDF is encrypted. Upload a copy without a password.");
            }
            int pageCount = source.getNumberOfPages();
            if (lastPage > pageCount) {
                throw new UnreadableDocumentException(
                        "This PDF has " + pageCount + " page(s); pages " + firstPage + "-" + lastPage + " do not exist.");
            }
            Splitter splitter = new Splitter();
            splitter.setStartPage(firstPage);
            splitter.setEndPage(lastPage);
            splitter.setSplitAtPage(lastPage - firstPage + 1);
            splitter.setStreamCacheCreateFunction(
                    MemoryUsageSetting.setupMainMemoryOnly(limits.maxUncompressedBytes()).streamCache);
            List<PDDocument> parts = splitter.split(source);
            try {
                if (parts.size() != 1 || parts.get(0).getNumberOfPages() != lastPage - firstPage + 1) {
                    throw new UnreadableDocumentException("The pages of this PDF could not be copied.");
                }
                try (OutputStream out = new CappedOutputStream(Files.newOutputStream(file), maxBytes)) {
                    parts.get(0).save(out);
                } catch (OverLimit e) {
                    throw new SliceTooLargeException(e.maxBytes);
                }
            } finally {
                for (PDDocument part : parts) {
                    closeQuietly(part);
                }
            }
        }
    }

    private static Path createFile(Path workDir) {
        try {
            Files.createDirectories(workDir);
            return POSIX
                    ? Files.createTempFile(workDir, "eddi-slice-", ".pdf",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(workDir, "eddi-slice-", ".pdf");
        } catch (IOException e) {
            throw new UnreadableDocumentException("No work file could be created for a PDF slice.", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warnv("A PDF slice work file could not be deleted: {0}", e.getClass().getSimpleName());
        }
    }

    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException e) {
            LOGGER.debugv("Closing a PDF slice failed: {0}", e.getClass().getSimpleName());
        }
    }

    private static OverLimit overLimitIn(Throwable thrown) {
        int depth = 0;
        for (Throwable t = thrown; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof OverLimit overLimit) {
                return overLimit;
            }
        }
        return null;
    }

    private static UnreadableDocumentException tooMuchToDecode(PdfDecodeBudget.Budget budget) {
        return new UnreadableDocumentException("This PDF expands to more than " + budget.max() / (1024 * 1024)
                + " MB while it is read, which is more than one document may take.");
    }

    /** The slice would be larger than the caller allowed; nothing was kept. */
    public static final class SliceTooLargeException extends RuntimeException {

        private final long maxBytes;

        public SliceTooLargeException(long maxBytes) {
            super("The PDF of the selected pages is larger than " + maxBytes + " bytes.");
            this.maxBytes = maxBytes;
        }

        /** The limit that was passed. */
        public long maxBytes() {
            return maxBytes;
        }
    }

    /**
     * Counts what is written and refuses the first byte past the limit. The refusal
     * is an {@link IOException} so PDFBox's writer lets it through; it is turned
     * into {@link SliceTooLargeException} on the way out.
     */
    private static final class CappedOutputStream extends FilterOutputStream {

        private final long maxBytes;
        private long written;

        CappedOutputStream(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            reserve(1);
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(long length) throws IOException {
            if (written + length > maxBytes) {
                throw new OverLimit(maxBytes);
            }
            written += length;
        }
    }

    private static final class OverLimit extends IOException {
        private final long maxBytes;

        OverLimit(long maxBytes) {
            super("over the slice limit");
            this.maxBytes = maxBytes;
        }
    }
}
