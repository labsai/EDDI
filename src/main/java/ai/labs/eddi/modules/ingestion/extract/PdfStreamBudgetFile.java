/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * {@link PdfStreamBudget}'s pre-filter on a file that is not held in memory.
 *
 * <p>
 * The same scan, byte for byte, on a {@link FileChannel}: the file is read in
 * windows of {@link #DEFAULT_WINDOW_BYTES} through one reused buffer, forwards
 * while keywords are searched and backwards when a dictionary is looked for,
 * and each compressed stream is handed to the filters as a stream over its
 * range of the file. The heap holds the window, a dictionary (at most 64 KB)
 * and what one stream's filter chain keeps between stages, never the file.
 *
 * <p>
 * The file is read through positional reads, not a memory mapping: a mapped
 * file stays mapped until the collector unmaps it, and on Windows a mapped file
 * cannot be deleted in the {@code finally} that owns it.
 */
public final class PdfStreamBudgetFile {

    /** How much of the file one window of the scan holds. */
    static final int DEFAULT_WINDOW_BYTES = 1024 * 1024;

    private PdfStreamBudgetFile() {
    }

    /**
     * @throws UnreadableDocumentException
     *             when a stream expands past {@code maxStreamBytes}, the scan
     *             outlives {@code deadline}, or the file cannot be read
     */
    public static void requireWithin(Path pdf, long maxStreamBytes, Instant deadline) {
        requireWithin(pdf, maxStreamBytes, deadline, DEFAULT_WINDOW_BYTES);
    }

    /** With a window size of its own: tests make it tiny to cross boundaries. */
    static void requireWithin(Path pdf, long maxStreamBytes, Instant deadline, int windowBytes) {
        try (FileChannel channel = FileChannel.open(pdf, StandardOpenOption.READ)) {
            PdfStreamBudget.scan(new FileSource(channel, windowBytes), maxStreamBytes, deadline);
        } catch (IOException e) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.", e);
        }
    }

    /** Random access to a file through one window-sized buffer. */
    static final class FileSource implements PdfStreamBudget.Source {
        private final FileChannel channel;
        private final long length;
        private final ByteBuffer window;
        /** The file offset of the window's first byte; the window holds none yet. */
        private long windowStart = -1;
        private int windowLength;

        FileSource(FileChannel channel, int windowBytes) throws IOException {
            if (windowBytes < 16) {
                throw new IllegalArgumentException("windowBytes must be at least 16");
            }
            this.channel = channel;
            this.length = channel.size();
            this.window = ByteBuffer.allocate(windowBytes);
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public byte at(long position) throws IOException {
            if (position < windowStart || position >= windowStart + windowLength) {
                fill(position);
            }
            return window.get((int) (position - windowStart));
        }

        /**
         * Loads the window around {@code position}. A miss below the current window
         * comes from a backward search, so that window ends a little past the position
         * — the search compares a keyword's bytes upwards from each candidate, and a
         * window ending exactly at the candidate would be refilled for each of them;
         * any other miss starts the window there.
         */
        private void fill(long position) throws IOException {
            if (position < 0 || position >= length) {
                throw new IndexOutOfBoundsException("Offset " + position + " is outside the file");
            }
            long start = position;
            if (windowStart >= 0 && position < windowStart) {
                int margin = Math.min(64, window.capacity() / 2);
                start = Math.max(0, position - window.capacity() + margin);
            }
            window.clear();
            int read = 0;
            int wanted = (int) Math.min(window.capacity(), length - start);
            while (read < wanted) {
                int n = channel.read(window, start + read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read <= position - start) {
                // Shorter than its size said: the file changed under the scan.
                throw new IOException("The file ended before its reported length");
            }
            windowStart = start;
            windowLength = read;
        }

        @Override
        public InputStream range(long start, long end) {
            return new RangeStream(channel, start, end);
        }
    }

    /**
     * A range of the file as a stream, read through positional reads, so it does
     * not move the channel the window reads from.
     */
    private static final class RangeStream extends InputStream {
        private final FileChannel channel;
        private final long end;
        private long position;

        RangeStream(FileChannel channel, long start, long end) {
            this.channel = channel;
            this.position = start;
            this.end = end;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position >= end) {
                return -1;
            }
            int wanted = (int) Math.min(length, end - position);
            int n = channel.read(ByteBuffer.wrap(bytes, offset, wanted), position);
            if (n < 0) {
                return -1;
            }
            position += n;
            return n;
        }
    }
}
