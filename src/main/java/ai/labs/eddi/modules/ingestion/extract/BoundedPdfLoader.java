/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.XrefTrailerResolver;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;

/**
 * Opens a PDF the way {@link org.apache.pdfbox.Loader} does, with the one bound
 * PDFBox itself lacks: on the number of cross-reference entries it stores.
 *
 * <p>
 * PDFBox holds every cross-reference entry it reads in a map, and nothing in
 * PDFBox bounds it. A large table of {@code 0000000000 00000 n} lines, or about
 * 150 bytes of cross-reference stream with {@code /W [0 0 0]} and a
 * {@code /Size} of two billion, fills the heap before a page is read: the
 * second exhausts a 256 MB heap in a few seconds. Every entry, from a table or
 * a stream, in strict or lenient parsing, goes through
 * {@link XrefTrailerResolver#setXRef}, so the parser's own resolver is replaced
 * by one that counts the calls and stops the parse when they pass the cap. That
 * is exact whatever the file's syntax, which a separate pre-scan of the
 * cross-reference sections could not be.
 *
 * <p>
 * Every PDF EDDI reads from untrusted bytes is opened here, never with
 * {@code Loader.loadPDF}. Public because the attachment extractor in
 * {@code modules.llm.tools.impl} reads PDFs too.
 */
public final class BoundedPdfLoader {

    /**
     * Cross-reference entries one document may declare. Measured to keep the map
     * well inside a 256 MB heap; a genuine document with this many objects is
     * extraordinarily large.
     */
    public static final long DEFAULT_MAX_XREF_ENTRIES = 500_000;

    private BoundedPdfLoader() {
    }

    /**
     * {@code Loader.loadPDF(bytes, "", null, null, streamCache)}, bounded: lenient
     * parsing, no password.
     *
     * @throws UnreadableDocumentException
     *             if the file declares more than {@link #DEFAULT_MAX_XREF_ENTRIES}
     *             cross-reference entries
     * @throws IOException
     *             as {@code Loader.loadPDF} does, including
     *             {@link org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException}
     */
    public static PDDocument load(byte[] bytes, StreamCacheCreateFunction streamCache) throws IOException {
        return load(new RandomAccessReadBuffer(bytes), streamCache, DEFAULT_MAX_XREF_ENTRIES, true);
    }

    /**
     * Parses {@code source} with at most {@code maxXrefEntries} cross-reference
     * entries. The returned document owns {@code source} and closes it with itself;
     * on any failure {@code source} is closed here.
     *
     * @param lenient
     *            PDFBox's lenient mode, which repairs a broken cross-reference
     *            table by scanning the whole file; {@code Loader} always passes
     *            {@code true}
     * @throws UnreadableDocumentException
     *             if the file declares more than {@code maxXrefEntries} entries
     */
    static PDDocument load(RandomAccessRead source, StreamCacheCreateFunction streamCache, long maxXrefEntries,
                           boolean lenient)
            throws IOException {
        try {
            return new BoundedParser(source, streamCache, maxXrefEntries).parse(lenient);
        } catch (XrefLimitExceeded e) {
            closeQuietly(source, e);
            throw new UnreadableDocumentException("This PDF declares more than " + maxXrefEntries
                    + " objects, more than one document may have. If the file is genuine, split it into smaller "
                    + "files and upload those.", e);
        } catch (IOException | RuntimeException | Error e) {
            closeQuietly(source, e);
            throw e;
        }
    }

    private static void closeQuietly(RandomAccessRead source, Throwable failure) {
        try {
            source.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * A PDFBox parser whose cross-reference map refuses to grow past a cap.
     */
    private static final class BoundedParser extends PDFParser {
        BoundedParser(RandomAccessRead source, StreamCacheCreateFunction streamCache, long maxEntries)
                throws IOException {
            super(source, "", null, null, streamCache);
            xrefTrailerResolver = new CountingResolver(maxEntries);
        }
    }

    /** Counts entries; past the cap it throws instead of storing. */
    private static final class CountingResolver extends XrefTrailerResolver {
        private final long maxEntries;
        private long entries;

        CountingResolver(long maxEntries) {
            this.maxEntries = maxEntries;
        }

        @Override
        public void setXRef(COSObjectKey objKey, long offset) {
            if (++entries > maxEntries) {
                throw new XrefLimitExceeded();
            }
            super.setXRef(objKey, offset);
        }
    }

    /**
     * Thrown out of PDFBox's parser at the cap. Unchecked and not an
     * {@link IllegalArgumentException}, the one exception PDFBox catches around
     * {@code setXRef}, so nothing inside the parser absorbs it.
     */
    private static final class XrefLimitExceeded extends RuntimeException {
        XrefLimitExceeded() {
            super("cross-reference entries over the cap", null, false, false);
        }
    }
}
