/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A generated stream that starts like a PDF ({@code %PDF-1.7}) and continues
 * with bytes derived from a seed and their position — so an upload of any size
 * passes the content check without being held in memory, and its hash can be
 * recomputed. An optional hook runs once, when the stream has handed out
 * {@code hookAfter} bytes: what a concurrent erasure or sweep does while an
 * upload is in flight.
 */
public final class PdfLikeStream extends InputStream {

    private static final byte[] HEADER = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

    /** Something that runs mid-stream; may throw. */
    @FunctionalInterface
    public interface Hook {
        void run() throws Exception;
    }

    private final long seed;
    private final long length;
    private final long hookAfter;
    private final Hook hook;
    private long position;
    private boolean hooked;

    public PdfLikeStream(long seed, long length) {
        this(seed, length, -1, null);
    }

    public PdfLikeStream(long seed, long length, long hookAfter, Hook hook) {
        this.seed = seed;
        this.length = length;
        this.hookAfter = hookAfter;
        this.hook = hook;
    }

    /** The bytes the stream has handed out so far. */
    public long position() {
        return position;
    }

    private int byteAt(long index) {
        if (index < HEADER.length) {
            return HEADER[(int) index] & 0xFF;
        }
        long x = (index + seed) * 0x9E3779B97F4A7C15L;
        return (int) ((x ^ (x >>> 29)) & 0xFF);
    }

    private void maybeHook() throws IOException {
        if (hook != null && !hooked && position >= hookAfter) {
            hooked = true;
            try {
                hook.run();
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @Override
    public int read() throws IOException {
        maybeHook();
        if (position >= length) {
            return -1;
        }
        return byteAt(position++);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        maybeHook();
        if (len == 0) {
            return 0;
        }
        if (position >= length) {
            return -1;
        }
        int n = (int) Math.min(len, length - position);
        if (hook != null && !hooked && hookAfter > position) {
            n = (int) Math.min(n, hookAfter - position);
        }
        for (int i = 0; i < n; i++) {
            b[off + i] = (byte) byteAt(position + i);
        }
        position += n;
        return n;
    }

    /** The SHA-256 of {@code length} bytes of this stream, as lower-case hex. */
    public static String sha256(long seed, long length) {
        try {
            return sha256(new PdfLikeStream(seed, length));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The SHA-256 of what {@code in} hands out to its end, as lower-case hex. */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** {@code count} bytes of the stream from {@code offset}. */
    public static byte[] bytes(long seed, long offset, int count) {
        PdfLikeStream stream = new PdfLikeStream(seed, offset + count);
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) stream.byteAt(offset + i);
        }
        return out;
    }
}
