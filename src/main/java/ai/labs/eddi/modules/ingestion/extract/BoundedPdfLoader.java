/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.ICOSParser;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadView;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.XrefTrailerResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opens a PDF the way {@link org.apache.pdfbox.Loader} does, with the one bound
 * PDFBox itself lacks: on the number of cross-reference entries it stores.
 *
 * <p>
 * PDFBox holds every cross-reference entry it reads in a map, and nothing in
 * PDFBox bounds it. A large table of {@code 0000000000 00000 n} lines, or about
 * 150 bytes of cross-reference stream with {@code /W [0 0 0]} and a
 * {@code /Size} of two billion, fills the heap before a page is read: the
 * second exhausts a 256 MB heap in a few seconds. Every entry read from a table
 * or a stream, and every object a lenient rebuild hands back to the resolver,
 * goes through {@link XrefTrailerResolver#setXRef}, so the parser's own
 * resolver is replaced by one that counts the calls and stops the parse when
 * they pass the cap. The one writer that bypasses it, a lenient rebuild adding
 * the objects an object stream lists, puts into the resolved table, which the
 * replacement hands out behind the same cap. That holds whatever the file's
 * syntax, which a separate pre-scan of the cross-reference sections could not.
 *
 * <p>
 * Object streams are counted before their header is read. PDFBox reads the
 * header of an object stream into a map of its own before one of its entries
 * reaches the table, one entry per pair it reads, until it has read {@code /N}
 * pairs or reached {@code /First}: a lenient rebuild does so for every object
 * stream it finds, and an ordinary file the first time it reads an object from
 * one, keeping that stream's objects afterwards. Five million short entries
 * decode to under 50 MB, inside the stream decode limits, yet their map takes
 * hundreds of MB of heap. {@code /N} is the bound the cap can use. So every
 * stream the parser reads from the file adds its {@code /N} to a total for the
 * document when its decoded view is opened, which is where PDFBox's
 * object-stream parser starts, once per stream (keyed by where its data
 * starts), and the document is refused once the total passes the same cap.
 * {@code /N} is read the way PDFBox reads it, so an indirect one counts too.
 *
 * <p>
 * PDFBox catches every exception in a few places, around a font's space width
 * among them, so a refusal raised while an object is first read there would be
 * logged and ignored. Each later object stream is refused too, so memory stays
 * bounded, but the text read so far would be returned as if the file were
 * whole. {@link #requireWithinCap} says whether a refusal happened: the loader
 * calls it after the parse, and every caller after it has read the document.
 *
 * <p>
 * Not covered: a lenient rebuild first collects the objects it finds in maps of
 * its own, and only then passes them to the resolver, so a damaged file made of
 * many tiny {@code N 0 obj} markers is held there before the cap refuses it.
 * That costs a multiple of the file's size, not anything the file merely
 * declares, so the upload size limit bounds it.
 *
 * <p>
 * Every PDF EDDI reads from untrusted bytes is opened here, never with
 * {@code Loader.loadPDF}. Public because the attachment extractor in
 * {@code modules.llm.tools.impl} reads PDFs too.
 */
public final class BoundedPdfLoader {

    private static final Logger LOGGER = Logger.getLogger(BoundedPdfLoader.class);

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
        PDDocument document;
        try {
            document = new BoundedParser(source, streamCache, maxXrefEntries).parse(lenient);
        } catch (XrefLimitExceeded e) {
            closeQuietly(source, e);
            throw tooManyObjects(maxXrefEntries, e);
        } catch (IOException | RuntimeException | Error e) {
            closeQuietly(source, e);
            throw e;
        }
        try {
            requireWithinCap(document);
        } catch (UnreadableDocumentException e) {
            closeQuietly(document, e);
            throw e;
        }
        return document;
    }

    /**
     * Refuses a document whose object streams passed the cap while it was read,
     * even where PDFBox caught the refusal and carried on. Call it after reading a
     * document this class loaded, before using what was read.
     *
     * @throws UnreadableDocumentException
     *             if the document was refused at any point
     */
    public static void requireWithinCap(PDDocument document) {
        if (document.getDocument() instanceof BoundedDocument bounded && bounded.exceeded) {
            throw tooManyObjects(bounded.maxEntries, null);
        }
    }

    private static UnreadableDocumentException tooManyObjects(long maxEntries, Throwable cause) {
        return new UnreadableDocumentException("This PDF declares more than " + maxEntries
                + " objects, more than one document may have. If the file is genuine, split it into smaller "
                + "files and upload those.", cause);
    }

    private static void closeQuietly(Closeable source, Throwable failure) {
        try {
            source.close();
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * A PDFBox parser whose cross-reference map refuses to grow past a cap, and
     * whose document counts the objects its object streams declare.
     */
    private static final class BoundedParser extends PDFParser {
        BoundedParser(RandomAccessRead source, StreamCacheCreateFunction streamCache, long maxEntries)
                throws IOException {
            // No cache function here: the document PDFParser creates with it is
            // replaced at once, and a null function allocates no cache for it.
            super(source, "", null, null, null);
            document = new BoundedDocument(new CapturingCache(streamCache), this, maxEntries);
            xrefTrailerResolver = new CountingResolver(maxEntries);
        }
    }

    /**
     * Remembers the cache it creates, so that {@link BoundedDocument} can give its
     * streams the one {@link COSDocument} keeps privately. Where the given function
     * fails, it falls back to a memory-only cache itself, as {@code COSDocument}
     * would, so that the fallback is the one remembered rather than one only
     * {@code COSDocument} holds.
     */
    private static final class CapturingCache implements StreamCacheCreateFunction {
        private final StreamCacheCreateFunction delegate;
        private RandomAccessStreamCache created;

        CapturingCache(StreamCacheCreateFunction delegate) {
            this.delegate = delegate;
        }

        @Override
        public RandomAccessStreamCache create() throws IOException {
            try {
                created = delegate.create();
            } catch (IOException e) {
                LOGGER.warn("Could not create the PDF stream cache; using a memory-only cache instead", e);
                created = IOUtils.createMemoryOnlyStreamCache().create();
            }
            return created;
        }
    }

    /**
     * The document of a bounded parse. Every stream read from the file is created
     * here, by the main parser and by the one a lenient rebuild uses alike, which
     * lets an object stream's {@code /N} be counted before PDFBox reads the header
     * it sizes.
     */
    private static final class BoundedDocument extends COSDocument {
        private final RandomAccessStreamCache streamCache;
        private final ICOSParser parser;
        private final long maxEntries;
        private final Set<Long> counted = new HashSet<>();
        private long declared;
        private boolean exceeded;

        BoundedDocument(CapturingCache cache, ICOSParser parser, long maxEntries) {
            super(cache.delegate == null ? null : cache, parser);
            this.streamCache = cache.created;
            this.parser = parser;
            this.maxEntries = maxEntries;
        }

        /**
         * {@link COSDocument#createCOSStream(COSDictionary, long, long)}, creating a
         * {@link CountedStream}.
         */
        @Override
        public COSStream createCOSStream(COSDictionary dictionary, long startPosition, long streamLength)
                throws IOException {
            COSStream stream = new CountedStream(streamCache,
                    parser.createRandomAccessReadView(startPosition, streamLength), this, startPosition);
            dictionary.forEach(stream::setItem);
            stream.setKey(dictionary.getKey());
            return stream;
        }

        /**
         * Adds an object stream's {@code /N} to the document's total, once per stream,
         * keyed by where its data starts (a rebuild and a later read of the same object
         * create two instances of it), and refuses the document past the cap. Any
         * stream with a {@code /First} is taken for one: PDFBox reads whatever an entry
         * points at as an object stream, whatever its {@code /Type}, and cannot read
         * one without {@code /First}. Once refused, every object stream is refused,
         * should anything inside PDFBox swallow the first refusal.
         */
        void charge(COSStream stream, long startPosition) {
            if (!stream.containsKey(COSName.FIRST)) {
                return;
            }
            if (exceeded) {
                throw tooManyObjects(maxEntries, null);
            }
            if (!counted.add(startPosition)) {
                return;
            }
            int objects = stream.getInt(COSName.N);
            if (objects > 0) {
                declared += objects;
            }
            if (declared > maxEntries) {
                exceeded = true;
                throw tooManyObjects(maxEntries, null);
            }
        }
    }

    /**
     * A stream read from the file. PDFBox's object-stream parser reads the header
     * from its decoded view, so the stream is counted when that view is opened,
     * before anything is decoded. The refusal is unchecked and not an
     * {@link IOException}, which PDFBox logs and skips around object streams.
     */
    private static final class CountedStream extends COSStream {
        private final BoundedDocument document;
        private final long startPosition;

        CountedStream(RandomAccessStreamCache streamCache, RandomAccessReadView view, BoundedDocument document,
                long startPosition) throws IOException {
            super(streamCache, view);
            this.document = document;
            this.startPosition = startPosition;
        }

        @Override
        public RandomAccessRead createView() throws IOException {
            document.charge(this, startPosition);
            return super.createView();
        }
    }

    /**
     * Counts entries; past the cap it throws instead of storing.
     *
     * <p>
     * It counts calls, which is at least what PDFBox stores: a key already in the
     * current section (a hybrid file lists objects in its table and again in its
     * {@code /XRefStm}) is counted but not stored again, so such a file reaches the
     * cap at fewer objects than a plain one, never more. A lenient rebuild clears
     * every section through {@link #reset()} and refills it from the objects it
     * found, so the count starts again there; otherwise a file whose table broke
     * partway would be counted twice and refused where {@code Loader} repaired it.
     */
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

        @Override
        protected void reset() {
            super.reset();
            entries = 0;
        }

        /**
         * The resolved table, behind a guard on {@code put}. A lenient rebuild that
         * searches for object streams writes the objects they list straight into this
         * map, past {@link #setXRef}, so the cap must hold here too.
         */
        @Override
        public Map<COSObjectKey, Long> getXrefTable() {
            Map<COSObjectKey, Long> table = super.getXrefTable();
            return table == null ? null : new CappedTable(table, maxEntries);
        }
    }

    /**
     * A view of PDFBox's xref map that refuses to store a new key once the map
     * holds {@code maxEntries}. Only {@code put} is guarded; {@code putAll} and the
     * {@code Map} default methods reach it through {@code AbstractMap}, and the
     * rest delegates unchanged, so PDFBox sees the map it expects.
     */
    private static final class CappedTable extends AbstractMap<COSObjectKey, Long> {
        private final Map<COSObjectKey, Long> delegate;
        private final long maxEntries;

        CappedTable(Map<COSObjectKey, Long> delegate, long maxEntries) {
            this.delegate = delegate;
            this.maxEntries = maxEntries;
        }

        @Override
        public Long put(COSObjectKey key, Long value) {
            if (delegate.size() >= maxEntries && !delegate.containsKey(key)) {
                throw new XrefLimitExceeded();
            }
            return delegate.put(key, value);
        }

        @Override
        public Long get(Object key) {
            return delegate.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public Long remove(Object key) {
            return delegate.remove(key);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Set<Entry<COSObjectKey, Long>> entrySet() {
            return delegate.entrySet();
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
