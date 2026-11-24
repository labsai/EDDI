/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import io.micrometer.core.instrument.MeterRegistry;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The meters of the blob backends, as both attachment stores record them. Tags
 * name the backend and the direction only — never a file, a reference or a
 * user.
 * <ul>
 * <li>{@value #BYTES}{@code {backend, direction=in|out}} — bytes written to and
 * read from a backend.</li>
 * <li>{@value #TOMBSTONES}{@code {backend}} — objects a delete could not remove
 * at once, left to the tombstone sweep.</li>
 * </ul>
 * Every method accepts a {@code null} registry (outside CDI, in tests).
 */
public final class BlobMetrics {

    public static final String BYTES = "eddi.attachment.blob.bytes";
    public static final String TOMBSTONES = "eddi.attachment.blob.tombstones";

    private BlobMetrics() {
    }

    /** Bytes a {@code put} stored. */
    public static void recordWritten(MeterRegistry registry, String backend, long bytes) {
        if (registry != null && bytes > 0) {
            registry.counter(BYTES, "backend", backend, "direction", "in").increment(bytes);
        }
    }

    /** An object left to the sweep because its backend could not delete it. */
    public static void recordTombstone(MeterRegistry registry, String backend) {
        if (registry != null) {
            registry.counter(TOMBSTONES, "backend", backend).increment();
        }
    }

    /**
     * {@code in}, counting the bytes read from it into {@value #BYTES}
     * {@code direction=out} when it is closed.
     */
    public static InputStream countingReads(MeterRegistry registry, String backend, InputStream in) {
        return registry == null ? in : new CountingInputStream(in, registry, backend);
    }

    private static final class CountingInputStream extends FilterInputStream {

        private final MeterRegistry registry;
        private final String backend;
        private long read;
        private boolean closed;

        CountingInputStream(InputStream in, MeterRegistry registry, String backend) {
            super(in);
            this.registry = registry;
            this.backend = backend;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                read++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                read += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            return in.skip(n);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!closed) {
                    closed = true;
                    if (read > 0) {
                        registry.counter(BYTES, "backend", backend, "direction", "out").increment(read);
                    }
                }
            }
        }
    }
}
