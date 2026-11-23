/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.ResourceCache;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads a PDF that is a file, one window of pages at a time, without the file
 * or the whole document's object graph on the heap.
 *
 * <p>
 * <b>Why a window re-opens the document.</b> PDFBox reading from a
 * {@link RandomAccessReadBufferedFile} keeps only a buffer of the file in
 * memory, and a {@link MemoryUsageSetting} bounds its stream cache — but
 * neither bounds the COS objects the parser builds as pages are read, nor the
 * document's resource cache, and both live as long as the document is open. So
 * every window opens the document afresh and closes it in {@code finally}; what
 * one window built is garbage before the next starts. The resource cache is
 * replaced by one that keeps nothing, so fonts and images are not retained
 * across pages either. <b>Nothing PDFBox-typed may escape a window</b>: an
 * operation returns plain data — counts, text, records — never a page, a stream
 * or a font.
 *
 * <p>
 * <b>Bounds per window.</b> Each window arms its own {@link PdfDecodeBudget} on
 * the calling thread — the budget is a {@code ThreadLocal}, so the operation
 * must not hand PDFBox work to another thread — and its own deadline, and
 * checks the page tree before the operation can iterate it
 * ({@link PdfStructureExtractor#requireReadablePageTree}). A budget overrun, a
 * deadline, a stack overflow in the parser, an encrypted or unparseable file:
 * each refuses the window with an {@link UnreadableDocumentException} whose
 * message can be shown to a user, and the budget is checked again after the
 * operation however it ended, because PDFBox sometimes swallows the overrun.
 *
 * <p>
 * <b>No brute-force repair of a large file.</b> When a file's cross-reference
 * table is broken, PDFBox's lenient parser rebuilds it by scanning the whole
 * file for objects and holding what it finds — the very cost this class exists
 * to avoid, and once per window. A file larger than
 * {@link Options#lenientParseMaxBytes()} is therefore parsed strictly, and
 * refused with a message when that fails. Smaller files are parsed leniently,
 * as on the upload path.
 */
public final class PdfWindowReader {

    /** Pages per window: {@code eddi.documents.structure.window-pages}. */
    public static final int DEFAULT_WINDOW_PAGES = 50;

    /**
     * One window's deadline:
     * {@code eddi.documents.structure.window-deadline-seconds}.
     */
    public static final Duration DEFAULT_WINDOW_DEADLINE = Duration.ofSeconds(90);

    /**
     * Files above this size are parsed strictly. The default
     * {@code eddi.attachments.max-size-bytes}: a file at or below it may be held in
     * memory on today's path anyway, and is repaired there as it always was.
     */
    public static final long DEFAULT_LENIENT_PARSE_MAX_BYTES = 20L * 1024 * 1024;

    /**
     * Cross-reference entries a strictly parsed file may declare. PDFBox holds each
     * in a map, at roughly a hundred bytes, in every window; half a million is far
     * more than a 5,000-page document needs and stays well inside a 256 MB heap.
     */
    public static final long DEFAULT_MAX_XREF_ENTRIES = 500_000;

    /**
     * Pages a document read in windows may have. Every window walks the whole page
     * tree and so holds every page's dictionary; twice the 5,000 pages large
     * documents are planned for.
     */
    public static final int DEFAULT_MAX_PAGES = 10_000;

    /** Heap the stream cache may use before it spills to the scratch directory. */
    static final long DEFAULT_MAIN_MEMORY_BYTES = 8L * 1024 * 1024;

    static final String TOO_LONG = BoundedTextStripper.TOO_LONG;

    static final String ENCRYPTED = "This PDF is encrypted. Upload a copy without a password.";

    static final String NOT_A_PDF = "This file could not be read as a PDF.";

    static final String BROKEN_INDEX = "This large PDF's internal index (its cross-reference table) is damaged, and "
            + "repairing it would mean reading the whole file into memory. Open it in a PDF viewer, save a new copy, "
            + "and upload that.";

    private static final ResourceCache NO_CACHE = new NoResourceCache();

    private final Options options;
    private final StreamCacheCreateFunction streamCache;

    public PdfWindowReader(Options options) {
        this.options = options;
        this.streamCache = MemoryUsageSetting.setupMixed(options.mainMemoryBytes(),
                options.mainMemoryBytes() + options.scratchBytes())
                .setTempDir(options.scratchDir().toFile()).streamCache;
    }

    public Options options() {
        return options;
    }

    /**
     * What a reader may use.
     *
     * @param scratchDir
     *            where PDFBox may spill its stream cache; an instance's work
     *            directory, never a shared temp root
     * @param mainMemoryBytes
     *            stream cache held in memory before spilling
     * @param scratchBytes
     *            stream cache allowed on disk on top of that
     * @param maxDecodedBytes
     *            the decode budget of <b>one window</b>
     * @param windowDeadline
     *            how long one window may take
     * @param lenientParseMaxBytes
     *            files above this size are parsed strictly
     * @param maxXrefEntries
     *            cross-reference entries a strictly parsed file may declare
     * @param maxPages
     *            pages the document may have
     */
    public record Options(Path scratchDir, long mainMemoryBytes, long scratchBytes, long maxDecodedBytes,
            Duration windowDeadline, long lenientParseMaxBytes, long maxXrefEntries, int maxPages) {

        public Options {
            if (scratchDir == null) {
                throw new IllegalArgumentException("a scratch directory is required");
            }
            if (mainMemoryBytes <= 0 || scratchBytes < 0 || maxDecodedBytes <= 0 || lenientParseMaxBytes < 0
                    || maxXrefEntries <= 0 || maxPages <= 0) {
                throw new IllegalArgumentException("sizes must be positive");
            }
            if (windowDeadline == null || windowDeadline.isNegative() || windowDeadline.isZero()) {
                throw new IllegalArgumentException("windowDeadline must be positive");
            }
        }

        /**
         * The defaults, with a window's decode budget and stream-cache scratch taken
         * from {@code limits} as the upload path takes them.
         */
        public static Options of(Path scratchDir, ExtractionLimits limits, Duration windowDeadline) {
            ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
            return new Options(scratchDir, DEFAULT_MAIN_MEMORY_BYTES, bounds.maxUncompressedBytes(),
                    bounds.maxDecodedBytes(), windowDeadline == null ? DEFAULT_WINDOW_DEADLINE : windowDeadline,
                    DEFAULT_LENIENT_PARSE_MAX_BYTES, DEFAULT_MAX_XREF_ENTRIES, DEFAULT_MAX_PAGES);
        }

        /** The same, with another strict-parse threshold. */
        public Options withLenientParseMaxBytes(long bytes) {
            return new Options(scratchDir, mainMemoryBytes, scratchBytes, maxDecodedBytes, windowDeadline, bytes,
                    maxXrefEntries, maxPages);
        }

        /** The same, with another per-window deadline. */
        public Options withWindowDeadline(Duration deadline) {
            return new Options(scratchDir, mainMemoryBytes, scratchBytes, maxDecodedBytes, deadline,
                    lenientParseMaxBytes, maxXrefEntries, maxPages);
        }

        /** The same, with other caps on cross-reference entries and pages. */
        public Options withCaps(long xrefEntries, int pages) {
            return new Options(scratchDir, mainMemoryBytes, scratchBytes, maxDecodedBytes, windowDeadline,
                    lenientParseMaxBytes, xrefEntries, pages);
        }
    }

    /**
     * The pages one operation is for and when it must be done.
     *
     * @param firstPage
     *            1-based, inclusive
     * @param lastPage
     *            inclusive; {@link Integer#MAX_VALUE} for a whole-document pass
     */
    public record Window(int firstPage, int lastPage, Instant deadline) {

        /** Throws the window's "too long" refusal once the deadline has passed. */
        public void requireBeforeDeadline() {
            requireBefore(deadline);
        }
    }

    /** Work on one open window. Must return plain data, never a PDFBox object. */
    @FunctionalInterface
    public interface WindowOp<T> {
        T apply(PDDocument document, Window window) throws IOException;
    }

    /**
     * Opens {@code pdf}, runs {@code op} on pages {@code firstPage..lastPage}, and
     * closes it, all on the calling thread under this window's budget and deadline.
     * The page range is the operation's to honour; it is checked to be a range, not
     * against the document.
     *
     * @throws UnreadableDocumentException
     *             when the window cannot be read within its bounds
     */
    public <T> T withWindow(Path pdf, int firstPage, int lastPage, WindowOp<T> op) {
        if (firstPage < 1 || lastPage < firstPage) {
            throw new IllegalArgumentException("Not a page range: " + firstPage + ".." + lastPage);
        }
        Window window = new Window(firstPage, lastPage, Instant.now().plus(options.windowDeadline()));
        try (var budget = PdfDecodeBudget.open(options.maxDecodedBytes())) {
            T result;
            try {
                result = open(pdf, window, op);
            } catch (StackOverflowError e) {
                // The one recursion no walk here can run ahead of: the parser's own,
                // while it repairs a broken file. It has unwound by now.
                throw new UnreadableDocumentException("This PDF's structure is nested too deeply to be read.");
            } catch (UnreadableDocumentException e) {
                if (budget.exceeded()) {
                    throw tooMuchToDecode(budget);
                }
                throw e;
            }
            if (budget.exceeded()) {
                throw tooMuchToDecode(budget);
            }
            return result;
        }
    }

    /**
     * A pass that needs the document but reads no page's content — the page tree,
     * the outline — under the same bounds as a window.
     */
    public <T> T withDocument(Path pdf, WindowOp<T> op) {
        return withWindow(pdf, 1, Integer.MAX_VALUE, op);
    }

    private <T> T open(Path pdf, Window window, WindowOp<T> op) {
        requireBefore(window.deadline());
        PDDocument document = parse(pdf);
        try (document) {
            document.setResourceCache(NO_CACHE);
            if (document.isEncrypted()) {
                throw new UnreadableDocumentException(ENCRYPTED);
            }
            PdfStructureExtractor.requireReadablePageTree(document, window.deadline(), options.maxPages());
            return op.apply(document, window);
        } catch (UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnreadableDocumentException(NOT_A_PDF, e);
        }
    }

    private PDDocument parse(Path pdf) {
        boolean lenient;
        RandomAccessRead source;
        try {
            lenient = Files.size(pdf) <= options.lenientParseMaxBytes();
            if (!lenient) {
                if (!hasPdfHeader(pdf)) {
                    throw new UnreadableDocumentException(NOT_A_PDF);
                }
                // Before PDFBox builds its map of every entry, which strict parsing
                // does not bound.
                PdfXrefBound.requireWithin(pdf, options.maxXrefEntries());
            }
            source = new RandomAccessReadBufferedFile(pdf);
        } catch (IOException e) {
            throw new UnreadableDocumentException(NOT_A_PDF, e);
        }
        try {
            // The document owns the source from here and closes it with itself.
            return new PDFParser(source, "", null, null, streamCache).parse(lenient);
        } catch (InvalidPasswordException e) {
            closeQuietly(source, e);
            throw new UnreadableDocumentException(ENCRYPTED, e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(source, e);
            throw new UnreadableDocumentException(lenient ? NOT_A_PDF : BROKEN_INDEX, e);
        } catch (Error e) {
            closeQuietly(source, e);
            throw e;
        }
    }

    /**
     * Whether {@code %PDF-} occurs in the first kilobyte, where readers look for
     * it.
     */
    private static boolean hasPdfHeader(Path pdf) throws IOException {
        byte[] head = new byte[1024];
        int read;
        try (var in = Files.newInputStream(pdf)) {
            read = in.readNBytes(head, 0, head.length);
        }
        return new String(head, 0, read, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    private static void closeQuietly(RandomAccessRead source, Throwable cause) {
        try {
            source.close();
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(TOO_LONG);
        }
    }

    private static UnreadableDocumentException tooMuchToDecode(PdfDecodeBudget.Budget budget) {
        return new UnreadableDocumentException("Part of this PDF expands to more than " + budget.max() / (1024 * 1024)
                + " MB while it is read, which is more than one window of pages may take. If the file is genuine, "
                + "split it, or save a copy without embedded attachments.");
    }

    /**
     * A resource cache that keeps nothing: every font, image and color space is
     * built again where it is used and dropped afterwards. Slower on a document
     * that reuses one font on every page; bounded on one that does not.
     */
    static final class NoResourceCache implements ResourceCache {
        @Override
        public PDFont getFont(COSObject indirect) {
            return null;
        }

        @Override
        public PDColorSpace getColorSpace(COSObject indirect) {
            return null;
        }

        @Override
        public PDExtendedGraphicsState getExtGState(COSObject indirect) {
            return null;
        }

        @Override
        public PDShading getShading(COSObject indirect) {
            return null;
        }

        @Override
        public PDAbstractPattern getPattern(COSObject indirect) {
            return null;
        }

        @Override
        public PDPropertyList getProperties(COSObject indirect) {
            return null;
        }

        @Override
        public PDXObject getXObject(COSObject indirect) {
            return null;
        }

        @Override
        public void put(COSObject indirect, PDFont font) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDColorSpace colorSpace) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDExtendedGraphicsState extGState) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDShading shading) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDAbstractPattern pattern) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDPropertyList propertyList) {
            // keeps nothing
        }

        @Override
        public void put(COSObject indirect, PDXObject xobject) {
            // keeps nothing
        }
    }
}
