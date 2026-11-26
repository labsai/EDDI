/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cheap first look at a PDF's compressed streams, on the raw bytes, before
 * PDFBox is given the file.
 *
 * <p>
 * <b>This is a pre-filter, not the bound.</b> The bound is
 * {@link PdfDecodeBudget}, which counts what PDFBox itself decodes and so
 * covers every filter, every repeated reference and every encrypted stream by
 * construction. This exists to refuse the obvious bomb — one stream that
 * expands a thousandfold — without letting the parser allocate anything, and
 * with a message that says so.
 *
 * <p>
 * It finds streams the way PDFBox's parser does: after the {@code stream}
 * keyword it skips any spaces, then one optional line break, and otherwise the
 * data starts right there. For each stream it reads the dictionary's
 * {@code /Filter} and decodes the chain with PDFBox's own filters, counting and
 * dropping what comes out, so ASCIIHex, ASCII85, RunLength and LZW are measured
 * as much as Flate. It passes over what text extraction never decodes and a
 * pre-filter need not: image streams ({@code /Subtype /Image}), image codecs,
 * and encrypted data it cannot read. Each of those is still counted by the
 * budget if PDFBox decodes it.
 */
public final class PdfStreamBudget {

    private static final byte[] STREAM_KEYWORD = {'s', 't', 'r', 'e', 'a', 'm'};
    private static final byte[] ENDSTREAM_KEYWORD = "endstream".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OBJ_KEYWORD = {'o', 'b', 'j'};

    /** How far back from {@code stream} a dictionary is looked for. */
    private static final int MAX_DICTIONARY_BYTES = 64 * 1024;

    private static final Pattern FILTER = Pattern.compile("/Filter\\s*(\\[[^\\]]*\\]|/[^\\s/\\[\\]<>()]+)");
    private static final Pattern NAME = Pattern.compile("/([^\\s/\\[\\]<>()]+)");
    private static final Pattern IMAGE = Pattern.compile("/Subtype\\s*/Image\\b");

    /**
     * Codecs whose output is an image: text extraction never decodes them, and
     * decoding them here would cost what they are skipped to save.
     */
    private static final Set<String> IMAGE_CODECS = Set.of("DCTDecode", "DCT", "JPXDecode", "JBIG2Decode",
            "CCITTFaxDecode", "CCF", "Crypt");

    private static final int BUFFER_SIZE = 16 * 1024;

    private PdfStreamBudget() {
    }

    /**
     * @throws UnreadableDocumentException
     *             when a stream expands past {@code maxStreamBytes}, or the scan
     *             outlives {@code deadline}
     */
    public static void requireWithin(byte[] pdf, long maxStreamBytes, Instant deadline) {
        try {
            scan(new ArraySource(pdf), maxStreamBytes, deadline);
        } catch (IOException e) {
            // An array source never fails to read.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The scan itself, on any source: an array here, a file read through a bounded
     * buffer in {@link PdfStreamBudgetFile}. Offsets are {@code long} so that a
     * file larger than an array can be scanned.
     *
     * @throws IOException
     *             when the source cannot be read
     */
    static void scan(Source pdf, long maxStreamBytes, Instant deadline) throws IOException {
        long length = pdf.length();
        long from = 0;
        long keyword;
        // The first "endstream" at or after the last stream's data, remembered across
        // streams: -2 until searched, -1 once there are none left. Searching afresh
        // for every opening keyword scanned to the end of the file each time an
        // opening had no terminator, so a file of unterminated "stream"s cost the
        // square of its size.
        long endstream = -2;
        long lastObj = -1;
        long objScannedTo = 0;
        long lastDictionaryObj = -1;
        while ((keyword = indexOf(pdf, STREAM_KEYWORD, from, length)) >= 0) {
            from = keyword + STREAM_KEYWORD.length;
            if (isEndstream(pdf, keyword)) {
                continue;
            }
            requireBefore(deadline);
            long start = dataStart(pdf, from);
            if (endstream != -1 && endstream < start) {
                endstream = indexOf(pdf, ENDSTREAM_KEYWORD, start, length);
            }
            long end = endstream < 0 ? length : endstream;
            // Likewise the latest "obj" in front of the keyword: only the bytes since
            // the previous keyword are new, so only they are searched. Searching up
            // to MAX_DICTIONARY_BYTES back for every keyword cost that much per
            // keyword in a file that has no object headers.
            long found = lastIndexOf(pdf, OBJ_KEYWORD, Math.max(0, objScannedTo - OBJ_KEYWORD.length + 1), keyword);
            if (found >= 0) {
                lastObj = found;
            }
            objScannedTo = keyword;
            if (lastObj >= 0 && lastObj == lastDictionaryObj) {
                // Another "stream" inside the same object: its dictionary was read at the
                // first one, and reading it again copied up to MAX_DICTIONARY_BYTES per
                // keyword — a file that is mostly the word "stream" cost the square of
                // its size.
                continue;
            }
            String dictionary = dictionaryBefore(pdf, lastObj, keyword);
            // Only a complete dictionary settles the object. A "stream" inside one of its
            // strings sees the text up to there, which ends mid-dictionary: it must not
            // stand in for the real keyword that follows, whose /Filter is still unread.
            if (dictionary != null && dictionary.stripTrailing().endsWith(">>")) {
                lastDictionaryObj = lastObj;
            }
            List<String> filters = filtersOf(dictionary);
            if (filters == null) {
                continue;
            }
            long expanded = decodedSize(pdf, start, end, filters, maxStreamBytes, deadline);
            if (expanded > maxStreamBytes) {
                throw new UnreadableDocumentException("This PDF contains a compressed stream that expands to more "
                        + "than " + megabytes(maxStreamBytes) + " MB, which is more than one document may take "
                        + "while it is read. If the file is genuine, save a copy with smaller images, or split it.");
            }
            if (endstream < 0) {
                // This stream had no terminator, so it was measured to the end of the
                // file, and every later keyword lies inside the bytes just measured.
                // Decoding again from each of them cost the rest of the file once per
                // keyword. Whatever a later stream hides, the decode budget still
                // counts when PDFBox reads it — this scan is the pre-filter, not the
                // bound.
                return;
            }
        }
    }

    /**
     * The filter chain to measure, or null for a stream the pre-filter passes over:
     * an image, a stream with no filter (stored as it is, so it cannot expand), or
     * one using a codec text extraction never decodes. A stream whose dictionary
     * cannot be found is measured as Flate, the one filter that matters most.
     */
    static List<String> filtersOf(String dictionary) {
        if (dictionary == null) {
            return List.of("FlateDecode");
        }
        if (IMAGE.matcher(dictionary).find()) {
            return null;
        }
        Matcher filter = FILTER.matcher(dictionary);
        if (!filter.find()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        Matcher name = NAME.matcher(filter.group(1));
        while (name.find()) {
            if (IMAGE_CODECS.contains(name.group(1))) {
                return null;
            }
            names.add(name.group(1));
        }
        return names.isEmpty() ? null : names;
    }

    /**
     * Decodes the chain with PDFBox's own filters, keeping at most {@code cap}
     * bytes between stages and none after the last, and returns how many bytes the
     * last stage produced — or the count of the first stage that passed the cap.
     */
    private static long decodedSize(Source pdf, long start, long end, List<String> filters, long cap,
                                    Instant deadline)
            throws IOException {
        InputStream input = pdf.range(start, Math.max(start, end));
        for (int i = 0; i < filters.size(); i++) {
            Filter filter;
            try {
                filter = FilterFactory.INSTANCE.getFilter(COSName.getPDFName(filters.get(i)));
            } catch (IOException e) {
                // A filter PDFBox does not know is one it cannot decode either.
                return 0;
            }
            boolean last = i == filters.size() - 1;
            CountingSink sink = new CountingSink(cap, deadline, !last);
            try {
                filter.decode(input, sink, new COSDictionary(), 0);
            } catch (IOException | RuntimeException e) {
                if (e instanceof UnreadableDocumentException unreadable) {
                    throw unreadable;
                }
                // Not what the dictionary says, or it ends in garbage — PDFBox stops at
                // the same point. What was decoded up to there still counts.
            }
            if (sink.count > cap || last) {
                return sink.count;
            }
            input = new ByteArrayInputStream(sink.kept.toByteArray());
        }
        return 0;
    }

    private static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(
                    "This PDF took too long to read. Split it into smaller files and upload those.");
        }
    }

    /** "endstream" contains "stream"; only the opening keyword starts data. */
    private static boolean isEndstream(Source pdf, long keyword) throws IOException {
        return keyword >= 3 && pdf.at(keyword - 3) == 'e' && pdf.at(keyword - 2) == 'n' && pdf.at(keyword - 1) == 'd';
    }

    /**
     * Where a stream's data begins, exactly as PDFBox's parser decides it: any run
     * of spaces after the keyword, then CRLF, LF or a bare CR if there is one — and
     * otherwise right where the spaces end. Accepting only a line break here let
     * {@code stream \n} hide a stream from the scan while PDFBox decoded it.
     */
    static int dataStart(byte[] pdf, int afterKeyword) {
        try {
            return (int) dataStart(new ArraySource(pdf), afterKeyword);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long dataStart(Source pdf, long afterKeyword) throws IOException {
        long length = pdf.length();
        long position = afterKeyword;
        while (position < length && pdf.at(position) == ' ') {
            position++;
        }
        if (position < length && pdf.at(position) == '\r') {
            position++;
            if (position < length && pdf.at(position) == '\n') {
                position++;
            }
        } else if (position < length && pdf.at(position) == '\n') {
            position++;
        }
        return position;
    }

    /**
     * The dictionary text in front of a {@code stream} keyword: from the object's
     * {@code obj} keyword to the stream. Null when no object header is near enough
     * to trust.
     *
     * @param obj
     *            where the latest {@code obj} keyword before the stream starts, or
     *            -1 when there is none
     */
    private static String dictionaryBefore(Source pdf, long obj, long keyword) throws IOException {
        if (obj < 0 || obj < keyword - MAX_DICTIONARY_BYTES) {
            return null;
        }
        byte[] text = new byte[(int) (keyword - obj)];
        for (int i = 0; i < text.length; i++) {
            text[i] = pdf.at(obj + i);
        }
        return new String(text, StandardCharsets.ISO_8859_1);
    }

    private static long indexOf(Source haystack, byte[] needle, long from, long to) throws IOException {
        outer : for (long i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack.at(i + j) != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static long lastIndexOf(Source haystack, byte[] needle, long floor, long before) throws IOException {
        outer : for (long i = before - needle.length; i >= floor; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack.at(i + j) != needle[j]) {
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
     * The bytes the scan reads: random access by {@code long} offset, and a range
     * as a stream for the filters to decode.
     */
    interface Source {
        long length() throws IOException;

        /** The byte at {@code position}, which lies in {@code 0..length()-1}. */
        byte at(long position) throws IOException;

        /** The bytes {@code start..end-1}, read when the stream is read. */
        InputStream range(long start, long end) throws IOException;
    }

    /** A whole file in an array: the upload path, which already holds it. */
    private record ArraySource(byte[] bytes) implements Source {
        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public byte at(long position) {
            return bytes[(int) position];
        }

        @Override
        public InputStream range(long start, long end) {
            return new ByteArrayInputStream(bytes, (int) start, (int) (end - start));
        }
    }

    /**
     * Counts what a filter writes and stops it once past the cap; keeps the bytes
     * only when a later stage needs them.
     */
    private static final class CountingSink extends OutputStream {
        private final long cap;
        private final Instant deadline;
        private final ByteArrayOutputStream kept;
        private long count;
        private long sinceDeadlineCheck;

        CountingSink(long cap, Instant deadline, boolean keep) {
            this.cap = cap;
            this.deadline = deadline;
            this.kept = keep ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            count += length;
            sinceDeadlineCheck += length;
            if (sinceDeadlineCheck >= BUFFER_SIZE) {
                sinceDeadlineCheck = 0;
                requireBefore(deadline);
            }
            if (count > cap) {
                throw new IOException("past the budget");
            }
            if (kept != null) {
                kept.write(bytes, offset, length);
            }
        }
    }
}
