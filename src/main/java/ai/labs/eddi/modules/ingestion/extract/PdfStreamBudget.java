/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Measures what a PDF's compressed streams expand to, before PDFBox is let near
 * them.
 *
 * <p>
 * PDFBox decodes a stream whole into memory the moment anything reads it — the
 * cross-reference stream while the file loads, an object stream on the first
 * lookup inside it, a page's content stream when its text is extracted — and it
 * has no limit of its own. Deflate expands about a thousandfold, so a 25 MB
 * upload can describe tens of gigabytes, and the first read of such a stream is
 * an {@link OutOfMemoryError} in whichever thread is running the ingestion: a
 * request thread during upload, the ingestion worker during a run. None of that
 * is reachable from outside the library, so it is checked here, on the raw
 * bytes, where it is.
 *
 * <p>
 * The scan mirrors how PDFBox's own Flate decoder reads a stream — it skips the
 * two-byte zlib header without checking it and inflates the rest raw — so a
 * stream that header-checking would pass over is not one PDFBox would refuse. A
 * stream compressed twice ({@code /Filter [/FlateDecode /FlateDecode]}) is
 * measured at the level that expands most. Nothing is kept: every inflated byte
 * is counted and dropped, so the scan itself costs no more memory than its
 * buffers.
 *
 * <p>
 * The bound is per stream, not per file, because the memory PDFBox takes is per
 * stream: it releases one before it reads the next. The time the whole scan may
 * take is bounded by the caller's deadline instead.
 */
final class PdfStreamBudget {

    private static final byte[] STREAM_KEYWORD = {'s', 't', 'r', 'e', 'a', 'm'};

    /** How deeply compression inside compression is followed. */
    private static final int MAX_NESTING = 3;

    private static final int BUFFER_SIZE = 16 * 1024;

    private PdfStreamBudget() {
    }

    /**
     * @throws UnreadableDocumentException
     *             when a stream expands past {@code maxStreamBytes}, or the scan
     *             outlives {@code deadline}
     */
    static void requireWithin(byte[] pdf, long maxStreamBytes, Instant deadline) {
        int from = 0;
        int keyword;
        while ((keyword = indexOf(pdf, STREAM_KEYWORD, from)) >= 0) {
            from = keyword + STREAM_KEYWORD.length;
            if (isEndstream(pdf, keyword)) {
                continue;
            }
            int start = dataStart(pdf, from);
            if (start < 0) {
                continue;
            }
            requireBefore(deadline);
            long expanded = measure(new ByteArrayInputStream(pdf, start, pdf.length - start), 1, maxStreamBytes,
                    deadline);
            if (expanded > maxStreamBytes) {
                throw new UnreadableDocumentException("This PDF contains a compressed stream that expands to more "
                        + "than " + megabytes(maxStreamBytes) + " MB, which is more than one document may take "
                        + "while it is read. If the file is genuine, save a copy with smaller images, or split it.");
            }
        }
    }

    /**
     * How many bytes this stream inflates to — at the level that inflates most, if
     * it is compressed more than once — stopping just past {@code cap}. Zero for a
     * stream that is not deflate at all.
     */
    private static long measure(InputStream encoded, int level, long cap, Instant deadline) {
        Inflater inflater = new Inflater(true);
        // Never closed: closing would close the stream underneath, which at an inner
        // level is the outer level still being drained. The inflater is ended below.
        CountingStream inflated = new CountingStream(
                new InflaterInputStream(new SkipHeader(encoded), inflater, BUFFER_SIZE), cap);
        long inner = 0;
        try {
            if (level < MAX_NESTING) {
                // Reads from the same stream, so the outer level is counted as the
                // inner one consumes it; whatever the inner one leaves is drained below.
                inner = measure(inflated, level + 1, cap, deadline);
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            while (inflated.count <= cap && inflated.read(buffer) >= 0) {
                requireBefore(deadline);
            }
        } catch (IOException e) {
            // Not deflate, or deflate that ends in garbage — PDFBox stops at the same
            // point. What was inflated up to there still counts.
        } finally {
            inflater.end();
        }
        return Math.max(inflated.count, inner);
    }

    private static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(
                    "This PDF took too long to read. Split it into smaller files and upload those.");
        }
    }

    /** "endstream" contains "stream"; only the opening keyword starts data. */
    private static boolean isEndstream(byte[] pdf, int keyword) {
        return keyword >= 3 && pdf[keyword - 3] == 'e' && pdf[keyword - 2] == 'n' && pdf[keyword - 1] == 'd';
    }

    /**
     * Where the data after a {@code stream} keyword begins: past the end-of-line
     * the specification requires (CRLF or LF, and a bare CR as parsers accept it),
     * or -1 when the keyword is not followed by one and so is not a keyword.
     */
    private static int dataStart(byte[] pdf, int afterKeyword) {
        if (afterKeyword >= pdf.length) {
            return -1;
        }
        if (pdf[afterKeyword] == '\r') {
            return afterKeyword + 1 < pdf.length && pdf[afterKeyword + 1] == '\n' ? afterKeyword + 2 : afterKeyword + 1;
        }
        return pdf[afterKeyword] == '\n' ? afterKeyword + 1 : -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer : for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static long megabytes(long bytes) {
        return Math.max(1, bytes / (1024 * 1024));
    }

    /**
     * Drops the first two bytes, as PDFBox's Flate decoder does with the zlib
     * header it never checks.
     */
    private static final class SkipHeader extends FilterInputStream {
        private boolean skipped;

        SkipHeader(InputStream in) {
            super(in);
        }

        private void skipHeader() throws IOException {
            if (!skipped) {
                skipped = true;
                in.read();
                in.read();
            }
        }

        @Override
        public int read() throws IOException {
            skipHeader();
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            skipHeader();
            return in.read(buffer, offset, length);
        }
    }

    /** Counts what passes through, and stops once it is past the cap. */
    private static final class CountingStream extends FilterInputStream {
        private final long cap;
        private long count;

        CountingStream(InputStream in, long cap) {
            super(in);
            this.cap = cap;
        }

        @Override
        public int read() throws IOException {
            if (count > cap) {
                return -1;
            }
            int read = in.read();
            if (read >= 0) {
                count++;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (count > cap) {
                return -1;
            }
            int read = in.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }
}
