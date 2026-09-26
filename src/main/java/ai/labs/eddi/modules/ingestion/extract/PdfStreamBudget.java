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
final class PdfStreamBudget {

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
    static void requireWithin(byte[] pdf, long maxStreamBytes, Instant deadline) {
        int from = 0;
        int keyword;
        while ((keyword = indexOf(pdf, STREAM_KEYWORD, from, pdf.length)) >= 0) {
            from = keyword + STREAM_KEYWORD.length;
            if (isEndstream(pdf, keyword)) {
                continue;
            }
            requireBefore(deadline);
            int start = dataStart(pdf, from);
            int end = indexOf(pdf, ENDSTREAM_KEYWORD, start, pdf.length);
            if (end < 0) {
                end = pdf.length;
            }
            List<String> filters = filtersOf(dictionaryBefore(pdf, keyword));
            if (filters == null) {
                continue;
            }
            long expanded = decodedSize(pdf, start, end, filters, maxStreamBytes, deadline);
            if (expanded > maxStreamBytes) {
                throw new UnreadableDocumentException("This PDF contains a compressed stream that expands to more "
                        + "than " + megabytes(maxStreamBytes) + " MB, which is more than one document may take "
                        + "while it is read. If the file is genuine, save a copy with smaller images, or split it.");
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
     * Decodes the chain with PDFBox's own filters, keeping at most {@code cap + 1}
     * bytes between stages and none after the last, and returns how many bytes the
     * last stage produced — or the first stage's size that passed the cap.
     */
    private static long decodedSize(byte[] pdf, int start, int end, List<String> filters, long cap,
                                    Instant deadline) {
        InputStream input = new ByteArrayInputStream(pdf, start, Math.max(0, end - start));
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
    private static boolean isEndstream(byte[] pdf, int keyword) {
        return keyword >= 3 && pdf[keyword - 3] == 'e' && pdf[keyword - 2] == 'n' && pdf[keyword - 1] == 'd';
    }

    /**
     * Where a stream's data begins, exactly as PDFBox's parser decides it: any run
     * of spaces after the keyword, then CRLF, LF or a bare CR if there is one — and
     * otherwise right where the spaces end. Accepting only a line break here let
     * {@code stream \n} hide a stream from the scan while PDFBox decoded it.
     */
    static int dataStart(byte[] pdf, int afterKeyword) {
        int position = afterKeyword;
        while (position < pdf.length && pdf[position] == ' ') {
            position++;
        }
        if (position < pdf.length && pdf[position] == '\r') {
            position++;
            if (position < pdf.length && pdf[position] == '\n') {
                position++;
            }
        } else if (position < pdf.length && pdf[position] == '\n') {
            position++;
        }
        return position;
    }

    /**
     * The dictionary text in front of a {@code stream} keyword: from the object's
     * {@code obj} keyword to the stream. Null when no object header is near enough
     * to trust.
     */
    private static String dictionaryBefore(byte[] pdf, int keyword) {
        int floor = Math.max(0, keyword - MAX_DICTIONARY_BYTES);
        int obj = lastIndexOf(pdf, OBJ_KEYWORD, floor, keyword);
        if (obj < 0) {
            return null;
        }
        return new String(pdf, obj, keyword - obj, StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int to) {
        outer : for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle, int floor, int before) {
        outer : for (int i = before - needle.length; i >= floor; i--) {
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
