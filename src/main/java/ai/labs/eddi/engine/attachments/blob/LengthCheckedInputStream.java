/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import ai.labs.eddi.engine.attachments.blob.BlobLengthException.Reason;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The length check every {@link BlobBackend#put} applies while it reads: it
 * counts the bytes as they pass and throws {@link BlobLengthException} as soon
 * as the stream runs past the declared length or {@code maxBytes}, or ends
 * before the declared length.
 * <p>
 * An overrun is found by reading past the limit, never by stopping at it:
 * stopping would silently store a truncated object.
 */
public final class LengthCheckedInputStream extends FilterInputStream {

    private final long declaredLength;
    private final long maxBytes;
    private long count;

    /**
     * @param declaredLength
     *            the exact length, or negative when unknown
     * @param maxBytes
     *            the most the stream may deliver
     * @throws BlobLengthException
     *             if the declared length is already above {@code maxBytes}
     * @throws IllegalArgumentException
     *             if {@code maxBytes} is not positive
     */
    public LengthCheckedInputStream(InputStream in, long declaredLength, long maxBytes) throws BlobLengthException {
        super(in);
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (declaredLength > maxBytes) {
            throw new BlobLengthException(Reason.EXCEEDS_MAX_BYTES,
                    "Declared length %d exceeds the limit of %d bytes".formatted(declaredLength, maxBytes));
        }
        this.declaredLength = declaredLength;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b < 0) {
            endOfStream();
        } else {
            advance(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n < 0) {
            endOfStream();
        } else {
            advance(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(n);
        advance(skipped);
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public synchronized void mark(int readlimit) {
        // not supported: a reset would make the count lie
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /** The bytes delivered so far. */
    public long bytesRead() {
        return count;
    }

    private void advance(long n) throws BlobLengthException {
        count += n;
        if (declaredLength >= 0 && count > declaredLength) {
            throw new BlobLengthException(Reason.EXCEEDS_DECLARED_LENGTH,
                    "Stream is longer than its declared length of %d bytes".formatted(declaredLength));
        }
        if (count > maxBytes) {
            throw new BlobLengthException(Reason.EXCEEDS_MAX_BYTES, "Stream exceeds the limit of %d bytes".formatted(maxBytes));
        }
    }

    private void endOfStream() throws BlobLengthException {
        if (declaredLength >= 0 && count < declaredLength) {
            throw new BlobLengthException(Reason.SHORTER_THAN_DECLARED,
                    "Stream ended after %d of its declared %d bytes".formatted(count, declaredLength));
        }
    }
}
