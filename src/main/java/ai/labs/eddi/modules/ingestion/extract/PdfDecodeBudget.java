/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.jboss.logging.Logger;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * A hard cap on what PDFBox may decode while one document is read, whatever the
 * filter, however often a stream is referenced, encrypted or not.
 *
 * <p>
 * PDFBox decodes every stream through the {@link Filter} its
 * {@link FilterFactory} hands out, into an in-memory buffer it sizes itself —
 * {@code Filter.decode} does not consult the document's stream cache, so a
 * {@code MemoryUsageSetting} bounds only the scratch space, not this. Nothing
 * outside the library can reach that buffer. So each filter in the factory is
 * replaced, once, by one that routes its output through a counter; the counter
 * is only armed on a thread that has opened a budget, so every other use of
 * PDFBox in the process is untouched.
 *
 * <p>
 * The count is cumulative for the whole document: a stream decoded twenty times
 * — referenced twenty times from one page's {@code /Contents}, which PDFBox
 * decodes all at once — counts twenty times, and every stage of a filter chain
 * counts. Decryption happens before decoding and does not expand. Once the cap
 * is passed every further write fails, and the document is refused afterwards
 * whether or not PDFBox let the failure through, because
 * {@link Budget#exceeded()} stays true.
 *
 * <p>
 * What text extraction never decodes — image data — is never counted, because
 * it is never written.
 */
final class PdfDecodeBudget {

    private static final Logger LOGGER = Logger.getLogger(PdfDecodeBudget.class);

    private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();

    private static volatile boolean installed;

    private PdfDecodeBudget() {
    }

    /**
     * Arms a budget on this thread until the returned handle is closed.
     *
     * @throws UnreadableDocumentException
     *             when the filters could not be wrapped, in which case no PDF is
     *             read at all rather than read without a bound
     */
    static Budget open(long maxDecodedBytes) {
        install();
        var budget = new Budget(maxDecodedBytes);
        CURRENT.set(budget);
        return budget;
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Field field = FilterFactory.class.getDeclaredField("filters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<COSName, Filter> filters = (Map<COSName, Filter>) field.get(FilterFactory.INSTANCE);
            // Values replaced in place, never the map's structure: a reader on another
            // thread sees the old filter or the counted one, and both decode.
            filters.replaceAll((name, filter) -> filter instanceof Counted ? filter : new Counted(filter));
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Could not install the PDF decode budget; PDFs are refused rather than read unbounded", e);
            throw new UnreadableDocumentException("PDF files cannot be read on this server right now.", e);
        }
    }

    /** One document's allowance. */
    static final class Budget implements AutoCloseable {
        private final long max;
        private long used;
        private boolean exceeded;

        private Budget(long max) {
            this.max = max;
        }

        boolean exceeded() {
            return exceeded;
        }

        long max() {
            return max;
        }

        private void spend(int bytes) {
            used += bytes;
            if (used > max) {
                exceeded = true;
            }
            if (exceeded) {
                throw new BudgetExceeded();
            }
        }

        @Override
        public void close() {
            CURRENT.remove();
        }
    }

    /**
     * Thrown into PDFBox when the cap is passed. Unchecked, so the parser's habit
     * of logging an {@code IOException} and carrying on with the next operator does
     * not swallow it; the flag on {@link Budget} is what decides the outcome even
     * where something does.
     */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super("PDF decode budget exceeded", null, false, false);
        }
    }

    /** A filter whose decoded output is counted against the thread's budget. */
    private static final class Counted extends Filter {
        private final Filter delegate;

        Counted(Filter delegate) {
            this.delegate = delegate;
        }

        @Override
        public DecodeResult decode(InputStream encoded, OutputStream decoded, COSDictionary parameters, int index)
                throws IOException {
            return delegate.decode(encoded, counting(decoded), parameters, index);
        }

        @Override
        public DecodeResult decode(InputStream encoded, OutputStream decoded, COSDictionary parameters, int index,
                                   DecodeOptions options)
                throws IOException {
            return delegate.decode(encoded, counting(decoded), parameters, index, options);
        }

        @Override
        protected void encode(InputStream input, OutputStream encoded, COSDictionary parameters) throws IOException {
            delegate.encode(input, encoded, parameters, 0);
        }

        private static OutputStream counting(OutputStream decoded) {
            Budget budget = CURRENT.get();
            return budget == null ? decoded : new CountingOutputStream(decoded, budget);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private final Budget budget;

        CountingOutputStream(OutputStream out, Budget budget) {
            super(out);
            this.budget = budget;
        }

        @Override
        public void write(int b) throws IOException {
            budget.spend(1);
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            budget.spend(length);
            out.write(bytes, offset, length);
        }
    }
}
