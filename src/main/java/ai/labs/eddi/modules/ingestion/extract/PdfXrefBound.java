/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts the entries a PDF's cross-reference sections declare, from the file's
 * tail, before PDFBox is given it.
 *
 * <p>
 * PDFBox holds every cross-reference entry of a document in a map — on the
 * order of a hundred bytes each — and does so again in every window, since a
 * window re-opens the document. Strict parsing stops the whole-file rebuild of
 * a broken table, but not a well-formed table of tens of millions of entries: a
 * large file that is mostly {@code 0000000000 00000 n} lines, or a small
 * cross-reference stream with one-byte fields. The entries PDFBox reads are the
 * ones the sections declare — a table's subsection headers, a stream's
 * {@code /Index} or {@code /Size} — so their sum, over the chain of
 * {@code /Prev} and {@code /XRefStm} offsets, bounds the map.
 *
 * <p>
 * This reads only those headers and dictionaries, through one small buffer, and
 * refuses when the sum passes the limit. A section it cannot make sense of is
 * left to the parser, which reads it strictly; this is a bound on the
 * well-formed case, not a validator.
 */
final class PdfXrefBound {

    /** How far from the end of the file {@code startxref} is looked for. */
    private static final int TAIL_BYTES = 2048;

    /** The longest trailer or stream dictionary read. */
    private static final int MAX_DICTIONARY_BYTES = 64 * 1024;

    /** Sections followed at most, through {@code /Prev} and {@code /XRefStm}. */
    private static final int MAX_SECTIONS = 1_000;

    private static final Pattern START_XREF = Pattern.compile("startxref\\s+(\\d{1,19})");
    private static final Pattern PREV = Pattern.compile("/Prev\\s+(\\d{1,19})");
    private static final Pattern XREF_STM = Pattern.compile("/XRefStm\\s+(\\d{1,19})");
    private static final Pattern INDEX = Pattern.compile("/Index\\s*\\[([^\\]]*)\\]");
    private static final Pattern SIZE = Pattern.compile("/Size\\s+(\\d{1,19})");
    private static final Pattern OBJECT_HEADER = Pattern.compile("\\d+\\s+\\d+\\s+obj\\b");
    private static final Pattern SUBSECTION = Pattern.compile("(\\d{1,19})\\s+(\\d{1,19})");

    private PdfXrefBound() {
    }

    /**
     * @throws UnreadableDocumentException
     *             when the sections declare more than {@code maxEntries} entries
     */
    static void requireWithin(Path pdf, long maxEntries) {
        try (FileChannel channel = FileChannel.open(pdf, StandardOpenOption.READ)) {
            new Walk(channel, maxEntries).run();
        } catch (IOException e) {
            // Unreadable here is unreadable to the parser too; it reports that.
        }
    }

    private static UnreadableDocumentException tooMany(long maxEntries) {
        return new UnreadableDocumentException("This PDF declares more than " + maxEntries + " objects, more than "
                + "one document may have. If the file is genuine, split it into smaller files and upload those.");
    }

    /** One walk over the section chain. */
    private static final class Walk {
        private final FileChannel channel;
        private final long size;
        private final long maxEntries;
        private final Set<Long> visited = new HashSet<>();
        private long entries;

        Walk(FileChannel channel, long maxEntries) throws IOException {
            this.channel = channel;
            this.size = channel.size();
            this.maxEntries = maxEntries;
        }

        void run() throws IOException {
            long tailStart = Math.max(0, size - TAIL_BYTES);
            String tail = text(tailStart, (int) (size - tailStart));
            Matcher start = START_XREF.matcher(tail);
            long offset = -1;
            while (start.find()) {
                offset = Long.parseLong(start.group(1));
            }
            Deque<Long> pending = new ArrayDeque<>();
            if (offset >= 0) {
                pending.push(offset);
            }
            while (!pending.isEmpty() && visited.size() < MAX_SECTIONS) {
                long at = pending.pop();
                if (at < 0 || at >= size || !visited.add(at)) {
                    continue;
                }
                String head = text(at, (int) Math.min(MAX_DICTIONARY_BYTES, size - at));
                String dictionary = head.stripLeading().startsWith("xref") ? table(at, head) : stream(head);
                if (dictionary == null) {
                    continue;
                }
                follow(PREV, dictionary, pending);
                follow(XREF_STM, dictionary, pending);
            }
        }

        private static void follow(Pattern key, String dictionary, Deque<Long> pending) {
            Matcher matcher = key.matcher(dictionary);
            if (matcher.find()) {
                pending.push(Long.parseLong(matcher.group(1)));
            }
        }

        /**
         * A classic table: subsection headers, each followed by its entries, up to
         * {@code trailer}. Returns the trailer dictionary's text, or null.
         */
        private String table(long at, String head) throws IOException {
            var lines = new LineReader(channel, at + head.indexOf("xref") + 4, size);
            String line;
            while ((line = lines.next()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("trailer")) {
                    // From a little before the line, so the dictionary is read whole
                    // whatever the line ending; entry lines hold no /Prev to confuse.
                    long trailer = Math.max(at, lines.position() - 512);
                    return text(trailer, (int) Math.min(MAX_DICTIONARY_BYTES, size - trailer));
                }
                Matcher subsection = SUBSECTION.matcher(trimmed);
                if (!subsection.matches()) {
                    return null;
                }
                long count = Long.parseLong(subsection.group(2));
                add(count);
                // Each entry is one line; reading them is bounded by the limit just
                // checked.
                for (long i = 0; i < count; i++) {
                    if (lines.next() == null) {
                        return null;
                    }
                }
            }
            return null;
        }

        /** A cross-reference stream: its dictionary's /Index, else /Size. */
        private String stream(String head) {
            String stripped = head.stripLeading();
            Matcher header = OBJECT_HEADER.matcher(stripped);
            if (!header.lookingAt()) {
                return null;
            }
            int end = stripped.indexOf("stream", header.end());
            String dictionary = end < 0 ? stripped : stripped.substring(0, end);
            if (!dictionary.contains("/XRef")) {
                return null;
            }
            Matcher index = INDEX.matcher(dictionary);
            if (index.find()) {
                String[] numbers = index.group(1).trim().split("\\s+");
                for (int i = 1; i < numbers.length; i += 2) {
                    add(parse(numbers[i]));
                }
            } else {
                Matcher declared = SIZE.matcher(dictionary);
                if (declared.find()) {
                    add(parse(declared.group(1)));
                }
            }
            return dictionary;
        }

        private static long parse(String number) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }

        private void add(long count) {
            entries += Math.max(0, count);
            if (count > maxEntries || entries > maxEntries) {
                throw tooMany(maxEntries);
            }
        }

        private String text(long at, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(0, length));
            while (buffer.hasRemaining() && channel.read(buffer, at + buffer.position()) > 0) {
                // read until full or end of file
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.ISO_8859_1);
        }
    }

    /** Lines of a file from an offset, through a small buffer. */
    private static final class LineReader {
        private final FileChannel channel;
        private final long size;
        private final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        private long position;

        LineReader(FileChannel channel, long start, long size) {
            this.channel = channel;
            this.position = start;
            this.size = size;
            buffer.limit(0);
        }

        long position() {
            return position;
        }

        /** The next line without its end-of-line, or null at the end of the file. */
        String next() throws IOException {
            if (position >= size && !buffer.hasRemaining()) {
                return null;
            }
            var line = new StringBuilder();
            while (true) {
                if (!buffer.hasRemaining()) {
                    buffer.clear();
                    int n = channel.read(buffer, position);
                    buffer.flip();
                    if (n <= 0) {
                        return line.isEmpty() ? null : line.toString();
                    }
                }
                byte b = buffer.get();
                position++;
                if (b == '\n' || b == '\r') {
                    if (b == '\r' && peek() == '\n') {
                        buffer.get();
                        position++;
                    }
                    return line.toString();
                }
                if (line.length() < 256) {
                    line.append((char) (b & 0xFF));
                }
            }
        }

        private int peek() throws IOException {
            if (!buffer.hasRemaining()) {
                buffer.clear();
                int n = channel.read(buffer, position);
                buffer.flip();
                if (n <= 0) {
                    return -1;
                }
            }
            return buffer.get(buffer.position());
        }
    }
}
