/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stream helpers the backends share. */
public final class BlobStreams {

    private BlobStreams() {
    }

    /**
     * Rejects a negative offset or length for {@link BlobBackend#getRange}.
     *
     * @throws IllegalArgumentException
     *             if either is negative
     */
    public static void requireValidRange(long offset, long length) {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must not be negative: " + offset + ", " + length);
        }
    }

    /**
     * A view of {@code in} that ends after {@code limit} bytes; closing it closes
     * {@code in}.
     */
    public static InputStream limit(InputStream in, long limit) {
        return new LimitedInputStream(in, limit);
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readlimit) {
            // not supported
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset not supported");
        }
    }
}
